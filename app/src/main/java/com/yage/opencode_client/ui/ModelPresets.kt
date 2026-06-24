package com.yage.opencode_client.ui

/**
 * Curated model presets for the model selector, matching iOS implementation.
 * Only these models are shown in the dropdown instead of the full API list.
 */
object ModelPresets {
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GLM-5.1(火山)", "volcengine-plan", "glm-5.1"),
        AppState.ModelOption("DSv4Flash(火山)", "volcengine-plan", "deepseek-v4-flash"),
        AppState.ModelOption("DSv4Pro(火山)", "volcengine-plan", "deepseek-v4-pro"),
        AppState.ModelOption("DSv4Flash(官方)", "deepseek", "deepseek-v4-flash"),
        AppState.ModelOption("DSv4Pro(官方)", "deepseek", "deepseek-v4-pro"),
    )
}
