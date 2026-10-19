package com.eosoclub.ourhome.data

import java.net.URI
import java.net.URLDecoder

/**
 * How a shopping item's link is shown, as on the web (lib/amazon.ts and the
 * item row): where it opens (an Amazon product link tidied to /dp/<id>), its
 * label ("View on Amazon", or the site's address), and Amazon's product id.
 */
data class ProductLink(val href: String, val label: String, val asin: String?)

// Amazon's product id (ASIN): 10 letters and digits, under one of these paths.
private val ASIN_PATHS = listOf("/dp/", "/gp/product/", "/gp/aw/d/", "/dp/product/", "/product/")
    .map { Regex(Regex.escape(it) + "([A-Z0-9]{10})", RegexOption.IGNORE_CASE) }
private val ASIN = Regex("^[A-Z0-9]{10}$", RegexOption.IGNORE_CASE)

private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

private fun hostOf(url: String): String? = parse(url)?.host?.lowercase()

/** Full Amazon storefront links and amzn.to / a.co short links. */
fun isAmazonUrl(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return Regex("(^|\\.)amazon\\.").containsMatchIn(host) ||
        Regex("(^|\\.)amzn\\.to$").containsMatchIn(host) ||
        host == "a.co"
}

/** The ASIN in an Amazon link, or null (a short link only has it after a redirect). */
fun extractAsin(url: String): String? {
    val uri = parse(url) ?: return null
    val queryAsin = uri.rawQuery?.split('&')
        ?.firstOrNull { it.substringBefore('=') == "asin" }
        ?.substringAfter('=', "")
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    if (queryAsin != null && ASIN.matches(queryAsin)) return queryAsin.uppercase()
    val path = uri.rawPath ?: return null
    return ASIN_PATHS.firstNotNullOfOrNull { it.find(path)?.groupValues?.get(1)?.uppercase() }
}

fun productLink(raw: String): ProductLink {
    val url = raw.trim()
    val asin = extractAsin(url)
    val host = hostOf(url)
    val href = if (asin != null && host != null) "https://$host/dp/$asin" else url
    val label = if (isAmazonUrl(url)) "View on Amazon" else hostOf(href)?.removePrefix("www.") ?: href
    return ProductLink(href, label, asin)
}
