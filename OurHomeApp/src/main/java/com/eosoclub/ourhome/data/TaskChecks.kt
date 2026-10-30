package com.eosoclub.ourhome.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// Task photo proof (vault "Task Photo Proof"; web taskService / taskSubmissionService):
// a child someone looks after sends a task for a check, an adult who looks
// after them approves it (points paid) or sends it back. The texts are the
// spec's, the same as the website's. Pure, so it is unit-tested (TaskChecksTest).

/** What the screens say about checks. */
object CheckText {
    fun sendTitle(task: String) = "Send “$task” for a check"
    const val SEND_INTRO = "An adult will look at it before the points are paid."
    const val TAKE_PHOTO = "Take a photo"
    const val TAKE_AGAIN = "Take again"
    const val REMOVE_PHOTO = "Remove photo"
    const val NEEDS_PHOTO = "This task needs a photo."
    const val NO_CAMERA = "Can't take a photo? Say why in the note."
    const val WITHOUT_PHOTO = "Send without a photo"
    const val NOTE_OPTIONAL = "Note (optional)"
    const val NOTE = "Note"
    const val SEND = "Send for a check"
    const val CANCEL = "Cancel"
    const val SEND_FAILED_PHOTO_KEPT = "Couldn't send it. Your photo is kept, try again."
    const val SEND_FAILED = "Couldn't send it. Try again."
    const val TOO_BIG = "That’s too big to send."
    const val SENT = "Sent for a check"
    const val WAITING = "Waiting for an OK"
    const val TAKE_BACK = "Take back"
    const val CAMERA_REFUSED = "Our Home needs the camera for this. Ask a parent to allow it."
    const val TO_CHECK = "To check"
    const val APPROVE = "Approve"
    const val SEND_BACK = "Send back"
    const val WHAT_NEEDS_FIXING = "What needs fixing?"
    const val REDO_STEPS = "Steps to do again (optional)"
    const val REDO_STEPS_HELP = "Steps you leave alone stay done and keep their points."
    const val STEPS_SEND_BACK = "This sends back all of these. No points are paid for them."
    const val COUNTED_AT_CYCLE_END = "Counted as done when the day ended"
    const val BACK_IN_TO_CHECK = "Back in To check"
    const val UNDONE = "Undone. The points were taken back."
    const val EDIT_WHILE_WAITING = "Waiting for an OK, so only the name, notes and places can change."
    /** The server's own words when the child who sent it opens the editor (taskReviewService WAITING_FOR_OK). */
    const val EDIT_WHILE_MINE_WAITS = "This is waiting for an adult’s OK. Take it back to change it."
    /** People you look after: the card holding [REVIEW_TASKS] (web look-after-view.tsx). */
    const val TASK_CHECKS_CARD = "Task checks"
    fun taskChecksSummary(on: Boolean) = if (on) "On" else "Off"
    const val REVIEW_TASKS = "Check their tasks before points"
    const val REVIEW_TASKS_HELP =
        "When they finish a task, it waits for one of you to look. Points are paid when you approve it."
    const val PHOTO_PROOF = "Ask for a photo"
    const val PHOTO_PROOF_HELP =
        "Children someone looks after take a photo when they finish. An adult who looks after them checks it before points are paid."

    fun sentBy(name: String?) = "${name ?: "Someone"} sent this for a check"
    /** The web's words (task-card.tsx); the Members page is where the head picks who looks after whom. */
    fun headHint(name: String?) =
        "To check it yourself, add yourself as someone who looks after ${name ?: "them"} on the Members page."
    fun waitingPoints(points: Double) = "${pointsDelta(points)} waiting for an OK"
    fun stepsTicked(ticked: Int, total: Int) = "$ticked of $total steps ticked"
    fun total(points: Double) = "${pointsDelta(points)} in all"
    fun sendBackTitle(task: String, child: String?) = "Send “$task” back to ${child ?: "them"}"
    fun takeBack(points: Double) = "This takes back the ${pointsDelta(points)}. The task stays done for that day."
    fun approved(task: String, child: String?) = "Approved “$task” for ${child ?: "them"}"
    fun sentBack(task: String, child: String?) = "Sent “$task” back to ${child ?: "them"}"
    fun tookBack(task: String, child: String?) = "Took back ${child?.let { "$it's" } ?: "their"} points for “$task”"
}

/**
 * The machine codes on the server's check refusals (409, `details.code`; web
 * taskReviewService). The app acts on these, never on the message text, and
 * still shows the server's message.
 */
object CheckCode {
    /** An old app without `checks` (this one always sends it). */
    const val UPDATE_APP = "update_app"
    /** Checks were turned on: send it for a check instead. */
    const val NEEDED = "check_needed"
    /** Checks were turned off while the dialog was open: complete it again. */
    const val NOT_NEEDED = "check_not_needed"
    /** The task already waits for an OK. */
    const val WAITING = "check_waiting"
    /** Two sends of the same task landed at once; the other one won. */
    const val ALREADY_WAITING = "check_already_waiting"
    /** Someone already decided (or took back) this check. */
    const val DECIDED = "check_decided"
    /** 10 things already wait for an OK. */
    const val CAP = "check_cap"

    /** Refusals that mean the task changed under the user: show the message and fetch it again. */
    val TASK_CHANGED = setOf(UPDATE_APP, NOT_NEEDED, WAITING, ALREADY_WAITING, DECIDED, CAP)
}

/** The server's code for a refusal ([CheckCode]), or null. */
val Throwable.checkCode: String? get() = (this as? ApiException)?.code

/** "+1 pt", "+5 pts", "+1.5 pts". */
fun pointsDelta(points: Double): String {
    val centi = points.centi()
    return "+${formatPoints(centi)} ${if (centi == 100) "pt" else "pts"}"
}

private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val dayClock = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
private val dateClock = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

/** "4:12 PM" today, else "Tue 4:12 PM". */
fun checkTime(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val at = Instant.parse(iso).atZone(zone)
    return at.format(if (at.toLocalDate() == today) clock else dayClock)
}

/** "Sent 4:12 PM". */
fun sentLine(iso: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)) =
    "Sent ${checkTime(iso, zone, today)}"

/** "Decide by Thu Oct 30, 12:00 AM or it counts as done" (the cycle end, with its date so midnight is clear). */
fun decideByLine(iso: String, zone: ZoneId = ZoneId.systemDefault()) =
    "Decide by ${Instant.parse(iso).atZone(zone).format(dateClock)} or it counts as done"

/** "Riley · Steps on “Dishes”" for step points, "Riley · “Dishes”" for a task. */
fun checkHeader(item: TaskSubmission): String {
    val who = item.child.name ?: "Someone"
    return if (item.kind == "steps") "$who · Steps on “${item.task.title}”" else "$who · “${item.task.title}”"
}

/**
 * One line per thing a "steps" check pays: a step ticked again, or the steps
 * ticked before someone else finished the task (one line per completion).
 */
fun entryLines(
    entries: List<CheckEntry>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<String> {
    val lines = mutableListOf<String>()
    val done = mutableSetOf<String>()
    for (entry in entries) {
        val completion = entry.completionId
        if (completion == null) {
            val again = if (entry.awardKind == "step_repeat") " again" else ""
            lines += "Ticked “${entry.title}”$again · ${checkTime(entry.checkedAt, zone, today)} (${pointsDelta(entry.points)})"
            continue
        }
        if (!done.add(completion)) continue
        val group = entries.filter { it.completionId == completion }
        val latest = group.maxBy { Instant.parse(it.checkedAt) }
        val steps = if (group.size == 1) "1 step" else "${group.size} steps"
        val who = group.firstNotNullOfOrNull { it.completedBy?.name } ?: "someone"
        lines += "Ticked $steps before $who finished it · ${checkTime(latest.checkedAt, zone, today)}" +
            " (${pointsDelta(group.sumOf { it.points })})"
    }
    return lines
}

/**
 * The check to take back when [userId] sent [task]'s waiting check; null
 * otherwise. The server also gives the id to the adults who look after the
 * sender, but only the sender may take it back.
 */
fun takeBackId(task: Task, userId: String): String? =
    task.review.submissionId?.takeIf { task.waitingForOk && task.review.submittedBy?.id == userId }

/**
 * The steps Send back offers to do again (web to-check.tsx redoChoices): only
 * on a task check, not one counted as done when its day ended, and only the
 * steps that were ticked when it was sent (an unticked step has nothing to redo).
 */
fun redoChoices(item: TaskSubmission): List<CheckStep> =
    if (item.kind == "task" && !item.countedAtCycleEnd) item.steps.filter { it.ticked } else emptyList()

/**
 * The head's hint on a waiting task they can't decide (web task-card.tsx
 * headHint): only adults who look after the child decide, and the head is not
 * one automatically. The server gives the check's id only to the sender and
 * those adults, so no id means this head doesn't look after them.
 */
fun headHint(task: Task, role: String?, userId: String): String? {
    if (role != "head" || !task.waitingForOk) return null
    if (task.review.submittedBy?.id == userId || task.review.submissionId != null) return null
    return CheckText.headHint(task.review.submittedBy?.name)
}

/**
 * Whether "Ask for a photo" shows in the task editor: hidden from a teen or
 * child someone looks after (the server refuses their change). [supervised]
 * null = not known yet, so it stays hidden from teens and children.
 */
fun canSetPhotoProof(role: String?, supervised: Boolean?): Boolean = !(canBeSupervised(role) && supervised != false)

/** Undo's toast: "back_to_check" puts it back in To check. */
fun undoOutcome(undo: String?): String = if (undo == "back_to_check") CheckText.BACK_IN_TO_CHECK else CheckText.UNDONE

/**
 * What a failed send for a check does to the open dialog: what to say, whether
 * the dialog closes (the task changed: refresh it), and whether the photo is
 * dropped (the server couldn't read it: take another). The photo is kept on
 * anything a retry can fix.
 */
data class SendFailure(val message: String, val close: Boolean, val dropPhoto: Boolean)

fun sendFailure(e: Throwable, hadPhoto: Boolean): SendFailure {
    val api = e as? ApiException
    val status = api?.status
    val kept = if (hadPhoto) CheckText.SEND_FAILED_PHOTO_KEPT else CheckText.SEND_FAILED
    return when {
        e is UnauthorizedException -> SendFailure(e.message ?: kept, close = true, dropPhoto = true)
        api == null -> SendFailure(kept, close = false, dropPhoto = false)
        // "That photo couldn't be read. Take it again."
        status == 422 && hadPhoto && "photo" in api.fieldErrors -> SendFailure(api.message ?: kept, close = false, dropPhoto = true)
        // The task changed meanwhile (checks turned off, already waiting, two sends at once, the cap): start again from the list.
        api.code in CheckCode.TASK_CHANGED -> SendFailure(api.message ?: kept, close = true, dropPhoto = true)
        // Uncoded refusals that also mean it changed ("Someone else just completed this task.", not found, not allowed).
        status == 403 || status == 404 || status == 409 -> SendFailure(api.message ?: kept, close = true, dropPhoto = true)
        status == 413 -> SendFailure(api.message ?: CheckText.TOO_BIG, close = false, dropPhoto = false)
        // A proxy in front of the server refused the size before it was read (no envelope).
        status == null && api.message?.endsWith("(413)") == true -> SendFailure(CheckText.TOO_BIG, close = false, dropPhoto = false)
        status != null && status < 500 -> SendFailure(api.message ?: kept, close = false, dropPhoto = false)
        else -> SendFailure(kept, close = false, dropPhoto = false)
    }
}

// --- The photo: rotation baked into the pixels, then scaled down -------------

/** The longest side a check photo is sent at. */
const val CHECK_PHOTO_MAX_EDGE = 1600

/** JPEG quality for a check photo (0-100). */
const val CHECK_PHOTO_QUALITY = 80

/** The server takes at most 1.5 MiB decoded (web PHOTO_BASE64_MAX). */
const val CHECK_PHOTO_MAX_BYTES = 1_572_864

data class PhotoSize(val width: Int, val height: Int)

/** [degrees] as 0, 90, 180 or 270 (CameraX only reports those; anything else snaps to the nearest). */
fun normalizeRotation(degrees: Int): Int = ((((degrees % 360) + 360) % 360 + 45) / 90 % 4) * 90

/**
 * The photo's pixels (row by row, [width] x [height]) turned clockwise by
 * [degrees] (CameraX's rotationDegrees is clockwise); the result is
 * [rotatedSize] wide and high. This is the turn the app bakes into a check
 * photo, after scaling it down (ui/TaskCamera encodeCheckPhoto). The pixel at (x, y) lands at:
 * 90: (height-1-y, x); 180: (width-1-x, height-1-y); 270: (y, width-1-x).
 */
fun rotatePixels(pixels: IntArray, width: Int, height: Int, degrees: Int): IntArray {
    require(pixels.size == width * height) { "pixels must be width x height" }
    val turns = normalizeRotation(degrees)
    if (turns == 0) return pixels.copyOf()
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val to = when (turns) {
                // Turned 90 or 270, a row of the result is [height] pixels long.
                90 -> x * height + (height - 1 - y)
                180 -> (height - 1 - y) * width + (width - 1 - x)
                else -> (width - 1 - x) * height + y
            }
            out[to] = pixels[row + x]
        }
    }
    return out
}

/** The size after turning by [degrees] (90 and 270 swap the sides). */
fun rotatedSize(width: Int, height: Int, degrees: Int): PhotoSize =
    if (normalizeRotation(degrees) % 180 == 0) PhotoSize(width, height) else PhotoSize(height, width)

/** How much the turned photo shrinks so its long edge fits [maxEdge]; never enlarged. */
fun photoScale(width: Int, height: Int, maxEdge: Int = CHECK_PHOTO_MAX_EDGE): Float {
    val long = max(width, height)
    return if (long <= maxEdge) 1f else maxEdge.toFloat() / long
}

/** The size sent: turned by [degrees] first, then scaled to fit [maxEdge]. */
fun checkPhotoSize(width: Int, height: Int, degrees: Int, maxEdge: Int = CHECK_PHOTO_MAX_EDGE): PhotoSize {
    val turned = rotatedSize(width, height, degrees)
    val scale = photoScale(turned.width, turned.height, maxEdge)
    return PhotoSize(
        (turned.width * scale).roundToInt().coerceIn(1, maxEdge),
        (turned.height * scale).roundToInt().coerceIn(1, maxEdge),
    )
}

/**
 * The size to scale the captured (not yet turned) photo to, so that turning
 * it by [degrees] afterwards gives exactly [checkPhotoSize]. Scaling first
 * keeps the full-size frame out of the turn (memory); the long edge stays the
 * long edge, so the picture is the same as turning first.
 */
fun scaledBeforeTurnSize(width: Int, height: Int, degrees: Int, maxEdge: Int = CHECK_PHOTO_MAX_EDGE): PhotoSize {
    val sent = checkPhotoSize(width, height, degrees, maxEdge)
    // Turning back by the same quarter turn swaps the sides back for 90 and 270.
    return rotatedSize(sent.width, sent.height, degrees)
}

/**
 * The JPEG qualities to try in order: [CHECK_PHOTO_QUALITY] first, lower ones
 * only when a busy photo would still be over the server's limit.
 */
val CHECK_PHOTO_QUALITIES = listOf(CHECK_PHOTO_QUALITY, 70, 60, 50)
