package com.vpnproxy.core.dualpath

/**
 * Suggested AI / agent client package names for the dual-path picker UI.
 * Nothing is selected by default — the user chooses apps on the dual-path screen.
 * Telegram is intentionally excluded.
 */
object AiAppCatalog {

    data class AiApp(
        val packageName: String,
        val label: String
    )

    /** Optional quick-pick hints in the dual-path screen (not auto-enabled). */
    val SUGGESTED: List<AiApp> = listOf(
        AiApp("com.openai.chatgpt", "ChatGPT"),
        AiApp("com.openai.voice", "ChatGPT Voice"),
        AiApp("com.anthropic.claude", "Claude"),
        AiApp("com.anthropic.claude.ai", "Claude AI"),
        AiApp("com.google.android.apps.bard", "Gemini"),
        AiApp("com.microsoft.copilot", "Microsoft Copilot"),
        AiApp("com.microsoft.bing", "Microsoft Bing"),
        AiApp("ai.perplexity.app.android", "Perplexity"),
        AiApp("com.perplexity.app", "Perplexity (alt)"),
        AiApp("ai.x.grok", "Grok"),
        AiApp("com.xai.grok", "Grok (alt)"),
        AiApp("ai.character.app", "Character.AI"),
        AiApp("com.poe.android", "Poe"),
        AiApp("com.deepseek.chat", "DeepSeek"),
        AiApp("com.qianwen.android", "Qwen")
    )

    val SUGGESTED_PACKAGES: Set<String> = SUGGESTED.map { it.packageName }.toSet()
}
