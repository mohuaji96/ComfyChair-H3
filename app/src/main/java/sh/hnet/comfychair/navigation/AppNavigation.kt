package sh.hnet.comfychair.navigation

/**
 * Navigation routes for the main container (generation screens).
 * FAB: Gallery
 */
sealed class MainRoute(val route: String) {
    data object TextToImage : MainRoute("text_to_image")
    data object ImageToImage : MainRoute("image_to_image")
    data object TextToVideo : MainRoute("text_to_video")
    data object ImageToVideo : MainRoute("image_to_video")
    /** Independent H3 workspace (T2V / I2V / FL2V / R2V). */
    data object H3Workspace : MainRoute("h3_workspace")
    /** Standalone random tag combiner: builds a prompt line, never touches a workflow. */
    data object TagComposer : MainRoute("tag_composer")

    data object StoryComposer : MainRoute("story_composer")
    data object Gallery : MainRoute("gallery")
}

/**
 * Navigation routes for the settings container.
 * FAB: Back to Generation
 */
sealed class SettingsRoute(val route: String) {
    data object Workflows : SettingsRoute("workflows_settings")
    data object Application : SettingsRoute("application_settings")
    data object Server : SettingsRoute("server_settings")
    data object Prompt : SettingsRoute("prompt_settings")

    /** Sub-pages of the optimiser: choosing a provider, editing it, and the template library. */
    data object PromptProviders : SettingsRoute("prompt_providers")
    data object PromptProviderEdit : SettingsRoute("prompt_provider_edit/{providerId}") {
        fun of(providerId: String) = "prompt_provider_edit/$providerId"
    }
    data object PromptTemplates : SettingsRoute("prompt_templates")
    data object PromptTemplateDetail : SettingsRoute("prompt_template_detail/{templateId}") {
        fun of(templateId: String) = "prompt_template_detail/$templateId"
    }

    data object About : SettingsRoute("about_settings")
}

/**
 * Emits when a tab is tapped again while already selected: the page scrolls back to its top.
 */
val LocalTabReselects = androidx.compose.runtime.staticCompositionLocalOf<kotlinx.coroutines.flow.Flow<MainRoute>> {
    kotlinx.coroutines.flow.emptyFlow()
}

