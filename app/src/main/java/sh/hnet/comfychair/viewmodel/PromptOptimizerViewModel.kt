package sh.hnet.comfychair.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ApiProvider
import sh.hnet.comfychair.net.PromptOptimizer
import sh.hnet.comfychair.storage.ApiProviderStore
import sh.hnet.comfychair.storage.OptimizeHistoryStore
import sh.hnet.comfychair.storage.PromptTemplateStore
import sh.hnet.comfychair.task.OptimizeRunner

/**
 * Shared state for the prompt optimiser: provider CRUD plus the one-shot rewrite call.
 *
 * The settings screen and the composer sheet each get their own instance; both read and
 * write through [ApiProviderStore], so a change made in one shows up on the next reload.
 */
class PromptOptimizerViewModel : ViewModel() {

    data class OptimizeUiState(
        val running: Boolean = false,
        /** True while the answer is still arriving; [result] grows in place. */
        val streaming: Boolean = false,
        val result: String = "",
        /** The exact input that produced [result]; used to avoid re-billing an unchanged prompt. */
        val resultInput: String = "",
        val elapsedMs: Long = 0,
        /** Reported by the endpoint when it sends a usage block; zero means it did not. */
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        /** Only meaningful once [hasResult] is true; the tag library is Chinese by default. */
        val english: Boolean = false,
        val error: String? = null,
        /** Raw response or transport detail, shown small under the error. */
        val errorDetail: String? = null,
        /** The model's own one-line report of what it removed, already stripped of its prefix. */
        val note: String = "",
        /** The endpoint refused the thinking flag and the call was repeated without it. */
        val thinkingRejected: Boolean = false
    ) {
        val hasResult: Boolean get() = result.isNotBlank()
    }

    data class TestUiState(
        val running: Boolean = false,
        val ok: Boolean? = null,
        val message: String? = null,
        val detail: String? = null
    )

    val providers = MutableStateFlow<List<ApiProvider>>(emptyList())
    val currentId = MutableStateFlow<String?>(null)
    val optimize = MutableStateFlow(OptimizeUiState())
    val test = MutableStateFlow(TestUiState())
    val keyPresent = MutableStateFlow(false)
    val templateLineCount = MutableStateFlow(0)
    val liteLineCount = MutableStateFlow(0)

    private var appContext: Context? = null
    private var templateLoaded = false

    /** The input and language of the run in flight, so a late report lands on the right state. */
    private var inputForRun: String = ""
    private var englishForRun: Boolean = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!templateLoaded) {
            templateLineCount.value = ApiProviderStore.builtinLineCount(app)
            liteLineCount.value = ApiProviderStore.builtinLineCount(app, ApiProvider.TEMPLATE_LITE)
            templateLoaded = true
        }
        // Always re-read: the settings screen and the composer sheet hold separate instances,
        // and each screen entering composition must see what the other one just saved.
        reload()
        observeRunner()
    }

    fun reload() {
        val ctx = appContext ?: return
        val list = ApiProviderStore.loadOrSeed(ctx)
        providers.value = list
        currentId.value = ApiProviderStore.currentId(ctx)?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
        refreshKeyFlag()
    }

    fun select(id: String) {
        val ctx = appContext ?: return
        currentId.value = id
        ApiProviderStore.setCurrentId(ctx, id)
        refreshKeyFlag()
        test.value = TestUiState()
    }

    /** Returns the provider the composer should use. */
    fun activeProvider(): ApiProvider? {
        val list = providers.value
        return list.firstOrNull { it.id == currentId.value } ?: list.firstOrNull()
    }

    fun addProvider(): ApiProvider? {
        val ctx = appContext ?: return null
        val created = ApiProvider.create(
            id = ApiProviderStore.newId(),
            name = "",
            systemPrompt = ApiProviderStore.builtinTemplate(ctx)
        )
        val list = providers.value + created
        ApiProviderStore.save(ctx, list)
        providers.value = list
        select(created.id)
        return created
    }

    fun upsert(provider: ApiProvider) {
        val ctx = appContext ?: return
        val list = providers.value.toMutableList()
        val index = list.indexOfFirst { it.id == provider.id }
        if (index >= 0) list[index] = provider else list.add(provider)
        ApiProviderStore.save(ctx, list)
        providers.value = list
    }

    /** Copy a provider and its key: the common case is a second endpoint that shares credentials. */
    fun duplicate(id: String): ApiProvider? {
        val ctx = appContext ?: return null
        val source = providers.value.firstOrNull { it.id == id } ?: return null
        val base = ctx.getString(R.string.vm_copy_suffix, source.name.ifBlank { ctx.getString(R.string.po_provider) })
        val taken = providers.value.map { it.name }.toSet()
        var name = base
        var index = 2
        while (name in taken) {
            name = "$base $index"
            index++
        }
        val copy = source.copy(id = ApiProviderStore.newId(), name = name)
        ApiProviderStore.apiKey(ctx, id)?.let { key ->
            if (key.isNotBlank()) ApiProviderStore.setApiKey(ctx, copy.id, key)
        }
        val list = providers.value.toMutableList()
        val at = list.indexOfFirst { it.id == id }
        list.add(if (at >= 0) at + 1 else list.size, copy)
        ApiProviderStore.save(ctx, list)
        providers.value = list
        select(copy.id)
        return copy
    }

    /** Reorder within the list; order is what the picker shows, so it has to persist. */
    fun move(id: String, delta: Int) {
        val ctx = appContext ?: return
        val list = providers.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        ApiProviderStore.save(ctx, list)
        providers.value = list
    }

    fun delete(id: String) {
        val ctx = appContext ?: return
        val list = providers.value.filterNot { it.id == id }
        ApiProviderStore.save(ctx, list)
        ApiProviderStore.removeApiKey(ctx, id)
        if (list.isEmpty()) {
            providers.value = list
            reload()
            return
        }
        providers.value = list
        if (currentId.value == id) select(list.first().id) else refreshKeyFlag()
    }

    fun saveApiKey(id: String, key: String) {
        val ctx = appContext ?: return
        if (key.isBlank()) return
        ApiProviderStore.setApiKey(ctx, id, key)
        refreshKeyFlag()
    }

    fun clearApiKey(id: String) {
        val ctx = appContext ?: return
        ApiProviderStore.removeApiKey(ctx, id)
        refreshKeyFlag()
    }

    fun resetSystemPrompt(id: String) {
        val ctx = appContext ?: return
        val provider = providers.value.firstOrNull { it.id == id } ?: return
        val source = if (provider.templateSource == ApiProvider.TEMPLATE_CUSTOM) {
            ApiProvider.TEMPLATE_FULL
        } else {
            provider.templateSource
        }
        setTemplateSource(id, source)
    }

    /** Switches which template this provider sends; custom keeps whatever was imported. */
    fun setTemplateSource(id: String, source: String) {
        val ctx = appContext ?: return
        val provider = providers.value.firstOrNull { it.id == id } ?: return
        val prompt = when (source) {
            ApiProvider.TEMPLATE_LITE -> ApiProviderStore.builtinTemplate(ctx, source)
            ApiProvider.TEMPLATE_CUSTOM -> provider.systemPrompt
            else -> ApiProviderStore.builtinTemplate(ctx, ApiProvider.TEMPLATE_FULL)
        }
        upsert(provider.copy(templateSource = source, systemPrompt = prompt))
    }

    /** Loads a template from a picked document and marks the provider as custom. */
    fun importTemplate(id: String, text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 100) return false
        val provider = providers.value.firstOrNull { it.id == id } ?: return false
        upsert(provider.copy(templateSource = ApiProvider.TEMPLATE_CUSTOM, systemPrompt = clean))
        return true
    }

    fun clearResult() {
        optimize.value = OptimizeUiState()
    }

    fun clearTest() {
        test.value = TestUiState()
    }

    private fun refreshKeyFlag() {
        val ctx = appContext ?: return
        val id = currentId.value ?: return
        keyPresent.value = ApiProviderStore.hasApiKey(ctx, id)
    }

    /**
     * Resolves the sentence the screen shows. The network layer only hands over resource ids, so
     * the wording follows the active language; a hint, when present, rides on the same line.
     */
    private fun errorLine(context: Context, error: PromptOptimizer.Outcome.Error): String {
        val message = error.message.format(context)
        val hint = error.hint?.format(context)
        return if (hint.isNullOrBlank()) message else "$message · $hint"
    }

    /**
     * Rewrites [input]. Returns the previous result untouched when [input] matches what is
     * already on screen and no explicit retry was asked for.
     */
    fun runOptimize(input: String, force: Boolean = false) {
        val ctx = appContext ?: return
        val provider = activeProvider() ?: run {
            optimize.value = OptimizeUiState(error = ctx.getString(R.string.vm_no_provider))
            return
        }
        if (!provider.isUsable) {
            optimize.value = OptimizeUiState(
                error = ctx.getString(R.string.vm_provider_incomplete),
                english = provider.english
            )
            return
        }
        if (input.isBlank()) {
            optimize.value = OptimizeUiState(error = ctx.getString(R.string.vm_prompt_empty), english = provider.english)
            return
        }
        val state = optimize.value
        if (!force && state.hasResult && !state.running && state.resultInput == input) {
            return
        }

        val key = ApiProviderStore.apiKey(ctx, provider.id).orEmpty()
        inputForRun = input
        englishForRun = provider.english
        optimize.value = OptimizeUiState(
            running = true,
            streaming = true,
            english = provider.english,
            resultInput = input
        )

        val started = OptimizeRunner.start(
            context = ctx,
            provider = provider,
            key = key,
            input = input,
            rules = PromptTemplateStore.active(ctx).body
        )
        if (!started) {
            // Another screen already has a run going; mirror it rather than queueing a second.
            observeRunner()
        }
    }

    /** Stops the run in flight. The endpoint keeps writing for a moment, the answer is dropped. */
    fun cancelOptimize() {
        OptimizeRunner.cancel()
        optimize.value = OptimizeUiState(english = englishForRun)
    }

    private var observing = false

    /**
     * Mirrors the process-wide run into screen state. Two view models exist (the settings page
     * and the composer sheet); both read the same source, so neither can drift.
     */
    fun observeRunner() {
        if (observing) return
        observing = true
        viewModelScope.launch {
            OptimizeRunner.task.collect { task ->
                if (task != null) {
                    optimize.value = optimize.value.copy(
                        running = true,
                        streaming = true,
                        elapsedMs = task.elapsedMs,
                        result = stripPromptTags(task.partial),
                        error = null,
                        errorDetail = null
                    )
                }
            }
        }
        viewModelScope.launch {
            OptimizeRunner.reports.collect { report ->
                when (report) {
                    is OptimizeRunner.Report.Done -> {
                        optimize.value = OptimizeUiState(
                            running = false,
                            result = report.text,
                            note = report.note,
                            resultInput = inputForRun,
                            elapsedMs = report.elapsedMs,
                            promptTokens = report.promptTokens,
                            completionTokens = report.completionTokens,
                            thinkingRejected = report.thinkingRejected,
                            english = englishForRun
                        )
                    }
                    is OptimizeRunner.Report.Failed -> {
                        optimize.value = OptimizeUiState(
                            error = report.message,
                            errorDetail = report.detail,
                            elapsedMs = 0,
                            english = englishForRun
                        )
                    }
                }
            }
        }
    }

    /** Only the body is shown while streaming; the tags arrive around it. */
    private fun stripPromptTags(raw: String): String =
        raw.replace("<prompt>", "").replace("</prompt>", "").trimStart()

    /** [draftKey] is the text currently in the key field, so an unsaved key can be tried. */
    fun runTest(provider: ApiProvider, draftKey: String?) {
        val ctx = appContext ?: return
        if (!provider.isUsable) {
            test.value = TestUiState(ok = false, message = ctx.getString(R.string.vm_fill_url_model))
            return
        }
        val key = draftKey?.takeIf { it.isNotBlank() }
            ?: ApiProviderStore.apiKey(ctx, provider.id).orEmpty()

        test.value = TestUiState(running = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PromptOptimizer.test(provider, key)
            }
            when (outcome) {
                is PromptOptimizer.Outcome.Ok -> {
                    test.value = TestUiState(
                        ok = true,
                        message = "OK · ${outcome.elapsedMs}ms · ${provider.model}"
                    )
                    upsert(provider.copy(lastOkAt = System.currentTimeMillis()))
                }
                is PromptOptimizer.Outcome.Error -> {
                    test.value = TestUiState(
                        ok = false,
                        message = errorLine(ctx, outcome),
                        detail = outcome.detail
                    )
                }
            }
        }
    }
}
