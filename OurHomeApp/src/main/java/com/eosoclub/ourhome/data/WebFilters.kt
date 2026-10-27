package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

/**
 * The web filters a supervising adult can choose for a child's phone (web
 * lib/webFilter.ts WEB_FILTERS). The phone uses one through Android's Private
 * DNS set to its [hostname], so websites are filtered in every browser and app.
 * [key]: the web's name for it.
 */
enum class WebFilterChoice(val key: String, val label: String, val hostname: String, val blurb: String) {
    Family(
        "family",
        "Family filter",
        "family-filter-dns.cleanbrowsing.org",
        "Blocks adult sites and the proxy and VPN sites used to get around it, and keeps Google, Bing and YouTube " +
            "on safe search. From CleanBrowsing.",
    ),
    Adult(
        "adult",
        "Adult sites only",
        "family.cloudflare-dns.com",
        "Blocks adult sites and harmful ones (malware). From Cloudflare.",
    ),
    ;

    companion object {
        fun of(key: String?): WebFilterChoice? = entries.firstOrNull { it.key == key }
    }
}

/** What the phone says while Private DNS isn't set to a hostname (off or Automatic). */
const val PRIVATE_DNS_OFF = "off"

/** A hostname as compared: lower case, without a trailing dot. */
fun normalizeHost(host: String): String = host.trim().lowercase().removeSuffix(".")

/** Whether Private DNS as read ([privateDns]: a hostname, [PRIVATE_DNS_OFF], or null when unknown) is [hostname]. */
fun usesFilter(privateDns: String?, hostname: String): Boolean =
    privateDns != null && normalizeHost(privateDns) == normalizeHost(hostname)

/**
 * The web filter on a supervised child's phone (web LookAfterDTO webFilter).
 * [filter]: the one chosen ([WebFilterChoice.key]), null when none. [on]:
 * whether their phone uses it; null when none is chosen, no phone checked in
 * yet, or their app is too old to say.
 */
@Serializable
data class LookedAfterWebFilter(val filter: String? = null, val on: Boolean? = null) {
    val choice: WebFilterChoice? get() = WebFilterChoice.of(filter)

    /** The folded card's line. */
    val summary: String
        get() {
            val label = choice?.label ?: return "Off"
            return when (on) {
                true -> "$label · on"
                false -> "$label · not on their phone"
                null -> "$label · waiting for their phone"
            }
        }
}

/** GET /api/phone/setup's web filter: what to enter in Private DNS. */
@Serializable
data class PhoneSetupWebFilter(val filter: String, val label: String, val hostname: String)
