package com.vpnproxy.core.dualpath

/**
 * Known AI / agent client package names.
 * Only packages installed on the device are applied to sing-box process_name rules.
 */
object AiAppCatalog {

    data class AiApp(
        val packageName: String,
        val label: String
    )

    /** Default set requested for dual-path (all major AI clients). */
    val PRESETS: List<AiApp> = listOf(
        // OpenAI
        AiApp("com.openai.chatgpt", "ChatGPT"),
        AiApp("com.openai.voice", "ChatGPT Voice"),
        // Anthropic
        AiApp("com.anthropic.claude", "Claude"),
        AiApp("com.anthropic.claude.ai", "Claude AI"),
        // Google Gemini / Bard
        AiApp("com.google.android.apps.bard", "Gemini"),
        AiApp("com.google.android.apps.googleassistant", "Google Assistant"),
        // Microsoft Copilot
        AiApp("com.microsoft.copilot", "Microsoft Copilot"),
        AiApp("com.microsoft.bing", "Microsoft Bing"),
        AiApp("com.microsoft.office.officehubrow", "Microsoft 365"),
        // Perplexity
        AiApp("ai.perplexity.app.android", "Perplexity"),
        AiApp("com.perplexity.app", "Perplexity (alt)"),
        // Grok / xAI
        AiApp("ai.x.grok", "Grok"),
        AiApp("com.xai.grok", "Grok (alt)"),
        AiApp("com.twitter.android", "X (Grok in-app)"),
        // Character.AI
        AiApp("ai.character.app", "Character.AI"),
        AiApp("com.character.chatgpt", "Character.AI (legacy)"),
        // Poe
        AiApp("com.quora.android", "Poe / Quora"),
        AiApp("com.poe.android", "Poe"),
        // Midjourney / Discord (often used with AI bots)
        AiApp("com.discord", "Discord"),
        // Notion AI
        AiApp("notion.id", "Notion"),
        // DeepSeek
        AiApp("com.deepseek.chat", "DeepSeek"),
        // Qwen / Alibaba
        AiApp("com.alibaba.android.rimet", "DingTalk / Qwen surface"),
        AiApp("com.qianwen.android", "Qwen"),
        // Cursor / coding agents (if present on Android)
        AiApp("com.todesktop.230313mzl4w4u92", "Cursor"),
        // Telegram (bots / Mini Apps often need foreign exit)
        AiApp("org.telegram.messenger", "Telegram"),
        AiApp("org.telegram.messenger.web", "Telegram Web"),
        AiApp("org.thunderdog.challegram", "Telegram X"),
        // Meta AI surfaces
        AiApp("com.facebook.orca", "Messenger"),
        AiApp("com.instagram.android", "Instagram"),
        AiApp("com.whatsapp", "WhatsApp")
    )

    val DEFAULT_PACKAGES: Set<String> = PRESETS.map { it.packageName }.toSet()
}
