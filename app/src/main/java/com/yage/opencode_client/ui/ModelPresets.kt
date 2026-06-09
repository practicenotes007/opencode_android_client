package com.yage.opencode_client.ui

/**
 * Curated model presets for the model selector, matching iOS implementation.
 * Only these models are shown in the dropdown instead of the full API list.
 */
object ModelPresets {
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GLM-5.1", "volcengine-plan", "glm-5.1"),
        AppState.ModelOption("DeepSeek V4 Flash", "volcengine-plan", "deepseek-v4-flash"),
        AppState.ModelOption("DeepSeek V4 Pro", "volcengine-plan", "deepseek-v4-pro"),
    )
}
