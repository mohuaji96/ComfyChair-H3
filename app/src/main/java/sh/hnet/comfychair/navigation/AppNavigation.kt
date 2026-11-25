package sh.hnet.comfychair.navigation

import android.net.Uri

/**
 * Navigation routes of the main activity: the generation screens and the Gallery and Settings tabs.
 */
sealed class MainRoute(val route: String) {
    data object TextToImage : MainRoute("text_to_image")
    data object ImageToImage : MainRoute("image_to_image")
    data object TextToVideo : MainRoute("text_to_video")
    data object ImageToVideo : MainRoute("image_to_video")
    data object VideoToVideo : MainRoute("video_to_video")
    data object H3Workspace : MainRoute("h3_workspace")
    data object TagComposer : MainRoute("tag_composer")
    data object StoryComposer : MainRoute("story_composer")
    data object Gallery : MainRoute("gallery")
    data object Settings : MainRoute("settings")
}

val LocalTabReselects: androidx.compose.runtime.CompositionLocal<kotlinx.coroutines.flow.Flow<MainRoute>>
    get() = sh.hnet.comfychair.ui.navigation.LocalTabReselects

/**
 * Settings pages, opened from the Settings tab's list.
 */
sealed class SettingsRoute(val route: String) {
    // Server
    data object Server : SettingsRoute("server_settings")
    data object Workflows : SettingsRoute("workflows_settings")
    /** A workflow's details, from the Workflows page. */
    data object WorkflowDetail : SettingsRoute("workflow_detail/{workflowId}") {
        const val ARG = "workflowId"
        fun of(workflowId: String): String = "workflow_detail/${Uri.encode(workflowId)}"
    }
    /** The workflows saved on the connected server, to import one; from the Workflows page's add menu. */
    data object ServerImport : SettingsRoute("server_workflow_import")

    // App
    data object Connection : SettingsRoute("connection_settings")
    data object Generation : SettingsRoute("generation_settings")
    data object MediaPrivacy : SettingsRoute("media_privacy_settings")
    data object Language : SettingsRoute("language_settings")
    data object WorkflowEditor : SettingsRoute("workflow_editor_settings")

    // Prompt optimiser & templates
    data object Prompt : SettingsRoute("prompt_settings")
    data object PromptProviders : SettingsRoute("prompt_providers")
    data object PromptProviderEdit : SettingsRoute("prompt_provider_edit/{providerId}") {
        fun of(providerId: String) = "prompt_provider_edit/$providerId"
    }
    data object PromptTemplates : SettingsRoute("prompt_templates")
    data object PromptTemplateDetail : SettingsRoute("prompt_template_detail/{templateId}") {
        fun of(templateId: String) = "prompt_template_detail/$templateId"
    }

    // Data
    data object Backup : SettingsRoute("backup_settings")
    data object Cache : SettingsRoute("cache_settings")
    data object DebugLog : SettingsRoute("debug_log_settings")
    data object Reset : SettingsRoute("reset_settings")

    data object About : SettingsRoute("about_settings")
    data object WhatsNew : SettingsRoute("whats_new_settings")
    data object License : SettingsRoute("license_settings")

    companion object {
        val all: List<SettingsRoute>
            get() = listOf(
                Server, Workflows, WorkflowDetail, ServerImport, Connection, Generation, MediaPrivacy, Language, WorkflowEditor,
                Prompt, PromptProviders, PromptProviderEdit, PromptTemplates, PromptTemplateDetail,
                Backup, Cache, DebugLog, Reset, About, WhatsNew, License
            )
    }
}
