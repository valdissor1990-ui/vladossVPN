package com.vpnproxy.core.services

/** Broad category used only for grouping in the UI. */
enum class ServiceCategory {
    VIDEO,
    MESSENGER,
    SOCIAL,
    AI,
    SEARCH,
    DEV
}

/**
 * A control endpoint for one user-facing service.
 *
 * Only neutral, unauthenticated HTTPS endpoints are allowed. The URL is never
 * written to logs or exported: results carry [id] only.
 */
data class ServiceTarget(
    val id: String,
    val title: String,
    val category: ServiceCategory,
    val url: String
)

/**
 * Built-in catalogue of control endpoints.
 *
 * Endpoints are deliberately simple text resources instead of branded APIs:
 * they do not require credentials, do not expose user data and are unlikely to
 * be rate-limited. A failed control request is not proof that a service is
 * blocked; the UI must say "control request failed", not "service blocked".
 */
object ServiceCatalog {
    val defaultTargets: List<ServiceTarget> = listOf(
        ServiceTarget("youtube", "YouTube", ServiceCategory.VIDEO, "https://www.youtube.com/robots.txt"),
        ServiceTarget("telegram", "Telegram", ServiceCategory.MESSENGER, "https://telegram.org/robots.txt"),
        ServiceTarget("whatsapp", "WhatsApp", ServiceCategory.MESSENGER, "https://www.whatsapp.com/robots.txt"),
        ServiceTarget("instagram", "Instagram", ServiceCategory.SOCIAL, "https://www.instagram.com/robots.txt"),
        ServiceTarget("chatgpt", "ChatGPT", ServiceCategory.AI, "https://chatgpt.com/robots.txt"),
        ServiceTarget("google", "Google", ServiceCategory.SEARCH, "https://www.google.com/generate_204"),
        ServiceTarget("github", "GitHub", ServiceCategory.DEV, "https://github.com/robots.txt")
    )

    fun byId(id: String): ServiceTarget? = defaultTargets.firstOrNull { it.id == id }
}
