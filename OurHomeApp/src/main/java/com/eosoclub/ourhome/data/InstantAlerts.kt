package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * POST /api/push/test: how many phones the test went to (Firebase accepted it
 * plus connected phones), and why none when it's 0.
 */
@Serializable
data class TestPushResult(
    val reached: Int = 0,
    val firebase: Boolean = false,
    val connected: Boolean = false,
    val tooSoon: Boolean = false,
) {
    /** What Profile says after the button. */
    val message: String
        get() = when {
            tooSoon -> "A test was just sent. Wait a few seconds before sending another."
            reached > 0 -> "Sent. A test notification should arrive in a few seconds."
            !firebase -> "This server has no Firebase set up, and Stay connected isn't connected on this phone, " +
                "so instant alerts can't reach it."
            else -> "Couldn't reach this phone. Open OurHome once with internet so it registers again, then try again."
        }
}

/**
 * POST /api/phone/checkin's answer. [stayConnectedRequired]: an adult looks
 * after this member, so the phone must Stay connected (false from an older
 * server, which doesn't say). [blockedApps]: the apps their supervisors
 * blocked (none from an older server).
 */
@Serializable
data class CheckInResult(
    val stayConnectedRequired: Boolean = false,
    val blockedApps: List<BlockedAppRule> = emptyList(),
)

/**
 * Server-sent events, a line at a time (Stay connected's stream). Collects
 * `event:` and `data:` lines and hands each finished event (a blank line ends
 * one) to [onEvent]; comments (`:`, the server's keep-alive) and other fields
 * are skipped.
 */
class SseParser(private val onEvent: (event: String?, data: String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()

    fun line(line: String) {
        when {
            line.isEmpty() -> {
                if (event != null || data.isNotEmpty()) onEvent(event, data.toString())
                event = null
                data.clear()
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
            else -> Unit
        }
    }
}

private val streamJson = Json { ignoreUnknownKeys = true }

/**
 * One stream message's fields as strings, the same shape as a Firebase
 * message's data; null when it isn't a JSON object.
 */
fun parseStreamMessage(json: String): Map<String, String>? {
    val obj = runCatching { streamJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
    return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
}
