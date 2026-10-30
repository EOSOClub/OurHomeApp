package com.eosoclub.ourhome.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

class TaskChecksTest {
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 10, 29)

    // --- Photo: turned first, then scaled to 1600 on the long edge ----------

    @Test
    fun portraitShotFromALandscapeSensorTurnsThenScales() {
        // A 4000 x 3000 sensor frame held upright reports 90 degrees.
        assertEquals(PhotoSize(3000, 4000), rotatedSize(4000, 3000, 90))
        assertEquals(PhotoSize(1200, 1600), checkPhotoSize(4000, 3000, 90))
        assertEquals(PhotoSize(1200, 1600), checkPhotoSize(4000, 3000, 270))
    }

    @Test
    fun landscapeAndUpsideDownKeepTheirSides() {
        assertEquals(PhotoSize(1600, 1200), checkPhotoSize(4000, 3000, 0))
        assertEquals(PhotoSize(1600, 1200), checkPhotoSize(4000, 3000, 180))
    }

    @Test
    fun smallPhotosAreNeverEnlarged() {
        assertEquals(1f, photoScale(1200, 900))
        assertEquals(PhotoSize(900, 1200), checkPhotoSize(1200, 900, 90))
        assertEquals(PhotoSize(1600, 1200), checkPhotoSize(1600, 1200, 0))
    }

    @Test
    fun oddRatiosRoundAndStayWithinTheLimit() {
        // 2048 x 1536 (the capture size asked for): 1600 x 1200 exactly.
        assertEquals(PhotoSize(1600, 1200), checkPhotoSize(2048, 1536, 0))
        // 1920 x 1080 turned: 1080 x 1920 -> 900 x 1600.
        assertEquals(PhotoSize(900, 1600), checkPhotoSize(1920, 1080, 90))
        // A very thin strip keeps at least 1 px.
        assertEquals(PhotoSize(1600, 1), checkPhotoSize(10000, 2, 0))
    }

    @Test
    fun rotationIsNormalised() {
        assertEquals(90, normalizeRotation(-270))
        assertEquals(0, normalizeRotation(360))
        assertEquals(PhotoSize(3000, 4000), rotatedSize(4000, 3000, -90))
        // Only quarter turns exist; anything else snaps to the nearest.
        assertEquals(90, normalizeRotation(80))
        assertEquals(0, normalizeRotation(10))
    }

    // --- The turn itself, pixel by pixel ------------------------------------
    // A 3 x 2 photo, each pixel its own number, row by row:
    //   1 2 3
    //   4 5 6
    private val photo = intArrayOf(1, 2, 3, 4, 5, 6)

    @Test
    fun turning90ClockwisePutsTheBottomLeftAtTheTopLeft() {
        // 2 x 3:  4 1 / 5 2 / 6 3
        assertEquals(listOf(4, 1, 5, 2, 6, 3), rotatePixels(photo, 3, 2, 90).toList())
    }

    @Test
    fun turning180FlipsBothWays() {
        // 3 x 2:  6 5 4 / 3 2 1
        assertEquals(listOf(6, 5, 4, 3, 2, 1), rotatePixels(photo, 3, 2, 180).toList())
    }

    @Test
    fun turning270ClockwisePutsTheTopRightAtTheTopLeft() {
        // 2 x 3:  3 6 / 2 5 / 1 4
        assertEquals(listOf(3, 6, 2, 5, 1, 4), rotatePixels(photo, 3, 2, 270).toList())
        assertEquals(rotatePixels(photo, 3, 2, 270).toList(), rotatePixels(photo, 3, 2, -90).toList())
    }

    @Test
    fun noTurnKeepsThePixelsAndFourQuarterTurnsComeBack() {
        assertEquals(photo.toList(), rotatePixels(photo, 3, 2, 0).toList())
        var turned = photo
        var w = 3
        var h = 2
        repeat(4) {
            turned = rotatePixels(turned, w, h, 90)
            w = h.also { h = w }
        }
        assertEquals(photo.toList(), turned.toList())
        // Turning keeps every pixel, just moved.
        assertEquals(photo.sorted(), rotatePixels(photo, 3, 2, 90).sorted())
    }

    @Test
    fun aTurnedPortraitIsAsWideAsTheSourceWasHigh() {
        // 4 x 1 strip turned 90: a 1 x 4 column, top to bottom = left to right.
        assertEquals(listOf(1, 2, 3, 4), rotatePixels(intArrayOf(1, 2, 3, 4), 4, 1, 90).toList())
        assertEquals(PhotoSize(1, 4), rotatedSize(4, 1, 90))
    }

    // --- Scale first, then turn (what the app does, for memory) == turn first, then scale (the spec) ---

    /** Nearest-pixel scaling that keeps the four corners on the corners (a stand-in for Bitmap.scale). */
    private fun scaleNearest(pixels: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray =
        IntArray(nw * nh) { i ->
            val x = i % nw
            val y = i / nw
            val sx = if (nw == 1) 0 else Math.round(x * (w - 1).toDouble() / (nw - 1)).toInt()
            val sy = if (nh == 1) 0 else Math.round(y * (h - 1).toDouble() / (nh - 1)).toInt()
            pixels[sy * w + sx]
        }

    private fun corners(pixels: IntArray, w: Int, h: Int) =
        listOf(pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1])

    @Test
    fun scalingFirstGivesTheSameSizeAsTurningFirst() {
        for (degrees in listOf(90, 180, 270)) {
            for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 2048 to 1536, 1920 to 1080, 1200 to 900, 4032 to 1816)) {
                val small = scaledBeforeTurnSize(w, h, degrees)
                // Long edge already within 1600 before the turn.
                assertTrue(maxOf(small.width, small.height) <= CHECK_PHOTO_MAX_EDGE)
                assertEquals("$w x $h at $degrees", checkPhotoSize(w, h, degrees), rotatedSize(small.width, small.height, degrees))
            }
        }
    }

    @Test
    fun scalingFirstPutsEveryCornerWhereTurningFirstDoes() {
        // 6 x 4 frame with marked corners, sent with a long edge of 3.
        val w = 6
        val h = 4
        val frame = IntArray(w * h) { 0 }.also {
            it[0] = 1 // top left
            it[w - 1] = 2 // top right
            it[(h - 1) * w] = 3 // bottom left
            it[h * w - 1] = 4 // bottom right
        }
        for (degrees in listOf(90, 180, 270)) {
            // The spec's order: turn, then scale.
            val turned = rotatedSize(w, h, degrees)
            val sent = checkPhotoSize(w, h, degrees, maxEdge = 3)
            val specWay = scaleNearest(rotatePixels(frame, w, h, degrees), turned.width, turned.height, sent.width, sent.height)
            // The app's order: scale, then turn.
            val small = scaledBeforeTurnSize(w, h, degrees, maxEdge = 3)
            val appWay = rotatePixels(scaleNearest(frame, w, h, small.width, small.height), small.width, small.height, degrees)
            assertEquals(sent, rotatedSize(small.width, small.height, degrees))
            assertEquals("corners at $degrees", corners(specWay, sent.width, sent.height), corners(appWay, sent.width, sent.height))
        }
        // And the corners really moved: at 90 the bottom left (3) is the new top left.
        val small = scaledBeforeTurnSize(w, h, 90, maxEdge = 3)
        val appWay = rotatePixels(scaleNearest(frame, w, h, small.width, small.height), small.width, small.height, 90)
        assertEquals(listOf(3, 1, 4, 2), corners(appWay, 2, 3))
    }

    @Test
    fun qualityStartsAt80AndTheLimitMatchesTheServer() {
        assertEquals(80, CHECK_PHOTO_QUALITIES.first())
        assertEquals(1600, CHECK_PHOTO_MAX_EDGE)
        // 1.5 MiB decoded = the server's 2,097,152 base64 characters (plus a data: prefix).
        assertEquals(1_572_864, CHECK_PHOTO_MAX_BYTES)
        assertEquals(2_097_152, java.util.Base64.getEncoder().encodeToString(ByteArray(CHECK_PHOTO_MAX_BYTES)).length)
    }

    // --- Texts and labels ----------------------------------------------------

    @Test
    fun pointsSayPtForOne() {
        assertEquals("+1 pt", pointsDelta(1.0))
        assertEquals("+5 pts", pointsDelta(5.0))
        assertEquals("+1.5 pts", pointsDelta(1.5))
        assertEquals("+5 pts waiting for an OK", CheckText.waitingPoints(5.0))
        assertEquals("+4 pts in all", CheckText.total(4.0))
    }

    @Test
    fun specTexts() {
        assertEquals("Send “Dishes” for a check", CheckText.sendTitle("Dishes"))
        assertEquals("Send “Dishes” back to Riley", CheckText.sendBackTitle("Dishes", "Riley"))
        assertEquals("Approved “Dishes” for Riley", CheckText.approved("Dishes", "Riley"))
        assertEquals("Riley sent this for a check", CheckText.sentBy("Riley"))
        assertEquals("3 of 4 steps ticked", CheckText.stepsTicked(3, 4))
        assertEquals("This takes back the +5 pts. The task stays done for that day.", CheckText.takeBack(5.0))
        assertEquals("Back in To check", undoOutcome("back_to_check"))
        assertEquals("Undone. The points were taken back.", undoOutcome("undone"))
        assertEquals("Undone. The points were taken back.", undoOutcome(null))
    }

    @Test
    fun noLongDashesInAnyText() {
        val texts = listOf(
            CheckText.SEND_INTRO, CheckText.NEEDS_PHOTO, CheckText.NO_CAMERA, CheckText.SEND_FAILED_PHOTO_KEPT,
            CheckText.CAMERA_REFUSED, CheckText.REDO_STEPS_HELP, CheckText.STEPS_SEND_BACK, CheckText.EDIT_WHILE_WAITING,
            CheckText.REVIEW_TASKS_HELP, CheckText.PHOTO_PROOF_HELP, CheckText.COUNTED_AT_CYCLE_END, CheckText.UNDONE,
        )
        // En dash and em dash, by code so this file has neither.
        texts.forEach { assertFalse(it, it.contains(Char(0x2013)) || it.contains(Char(0x2014))) }
    }

    @Test
    fun timesShowTheDayUnlessToday() {
        // 21:12 UTC = 4:12 PM in Chicago (CDT).
        assertEquals("4:12 PM", checkTime("2026-10-29T21:12:00Z", zone, today))
        assertEquals("Sent 4:12 PM", sentLine("2026-10-29T21:12:00Z", zone, today))
        assertEquals("Tue 4:12 PM", checkTime("2026-10-27T21:12:00Z", zone, today))
        assertEquals("Decide by Fri Oct 30, 12:00 AM or it counts as done", decideByLine("2026-10-30T05:00:00Z", zone))
    }

    private fun check(
        kind: String = "task",
        status: String = "waiting",
        entries: List<CheckEntry> = emptyList(),
        actions: List<String> = emptyList(),
        taskId: String = "t1",
    ) = TaskSubmission(
        id = "s-$kind-$taskId",
        kind = kind,
        status = status,
        task = CheckTask(taskId, "Dishes"),
        child = UserRef("kid", "Riley"),
        submittedAt = "2026-10-29T21:12:00Z",
        entries = entries,
        actions = actions,
    )

    @Test
    fun headersNameTheChildAndTheTask() {
        assertEquals("Riley · “Dishes”", checkHeader(check()))
        assertEquals("Riley · Steps on “Dishes”", checkHeader(check(kind = "steps")))
    }

    @Test
    fun entryLinesGroupStepsByTheCompletionThatFollowed() {
        val entries = listOf(
            CheckEntry("a", "Wipe the counter", 1.0, "2026-10-27T21:12:00Z", "step_repeat"),
            CheckEntry("b", "Dry", 1.0, "2026-10-28T23:00:00Z", "step", "c7", UserRef("sam", "Sam")),
            CheckEntry("c", "Put away", 2.0, "2026-10-28T23:30:00Z", "step", "c7", UserRef("sam", "Sam")),
        )
        assertEquals(
            listOf(
                "Ticked “Wipe the counter” again · Tue 4:12 PM (+1 pt)",
                "Ticked 2 steps before Sam finished it · Wed 6:30 PM (+3 pts)",
            ),
            entryLines(entries, zone, today),
        )
    }

    @Test
    fun aFirstTickPaidAtOnceHasNoAgain() {
        val entries = listOf(CheckEntry("a", "Sweep", 2.0, "2026-10-29T21:12:00Z", "step"))
        assertEquals(listOf("Ticked “Sweep” · 4:12 PM (+2 pts)"), entryLines(entries, zone, today))
    }

    @Test
    fun aStepTickedAgainSaysItWaits() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val step = json.decodeFromString(Subtask.serializer(), """{"id":"a","title":"Wipe","done":true,"waitingForOk":true}""")
        assertTrue(step.waitingForOk)
        // An older server sends no field: nothing waits.
        assertFalse(json.decodeFromString(Subtask.serializer(), """{"id":"a","title":"Wipe"}""").waitingForOk)
    }

    @Test
    fun aTaskDtoCarriesItsReviewAndAStepsItemParses() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val task = json.decodeFromString(
            Task.serializer(),
            """{"id":"t1","title":"Dishes","type":"one_time","priority":"medium","status":"awaiting_review","photoProof":true,
               "review":{"waiting":true,"submittedBy":{"id":"kid","name":"Riley"},"submittedAt":"2026-10-29T13:00:00.000Z","decideBy":null},
               "completionId":null,"submissionId":"s1"}""",
        )
        assertTrue(task.waitingForOk)
        assertTrue(task.photoProof)
        assertEquals("s1", task.submissionId)
        assertEquals("Riley", task.review.submittedBy?.name)
        val item = json.decodeFromString(
            TaskSubmission.serializer(),
            """{"id":"s2","kind":"steps","status":"waiting","autoReason":null,"task":{"id":"t1","title":"Dishes"},
               "child":{"id":"kid","name":"Riley"},"submittedAt":"2026-10-29T13:00:00.000Z","note":null,"hasPhoto":false,
               "points":3,"steps":[],"stepsTicked":0,"decideBy":null,
               "entries":[{"subtaskId":"b","title":"Dry","points":3,"checkedAt":"2026-10-29T13:00:00.000Z","awardKind":"step",
                           "completionId":"c7","completedBy":{"id":"sam","name":"Sam"}}],
               "actions":["approve","send_back"]}""",
        )
        assertTrue(item.canDecide)
        assertFalse(item.canWithdraw)
        assertEquals("Sam", item.entries.single().completedBy?.name)
    }

    private fun task(review: TaskReview, status: String = "awaiting_review") =
        Task(id = "t1", title = "Dishes", type = "one_time", priority = "medium", status = status, review = review)

    @Test
    fun takeBackUsesTheIdOnTheTaskForItsSenderOnly() {
        val waiting = TaskReview(waiting = true, submittedBy = UserRef("kid", "Riley"), submissionId = "s1")
        assertEquals("s1", takeBackId(task(waiting), "kid"))
        // An adult who looks after Riley gets the id too, but can't take it back.
        assertNull(takeBackId(task(waiting), "mum"))
        // Nothing waits, or a server without the field.
        assertNull(takeBackId(task(TaskReview(submissionId = null), status = "pending"), "kid"))
        assertNull(takeBackId(task(waiting.copy(submissionId = null)), "kid"))
    }

    @Test
    fun sendBackOffersOnlyTheStepsTickedWhenItWasSent() {
        // "Set the table": Plates and Cutlery ticked when sent, Glasses not.
        val steps = listOf(CheckStep("p", "Plates", ticked = true), CheckStep("g", "Glasses", ticked = false), CheckStep("c", "Cutlery", ticked = true))
        val sent = check().copy(steps = steps, stepsTicked = 2)
        assertEquals(listOf("Plates", "Cutlery"), redoChoices(sent).map { it.title })
        // Nothing ticked: no list at all.
        assertTrue(redoChoices(sent.copy(steps = steps.map { it.copy(ticked = false) })).isEmpty())
        // A steps item, or one counted as done when its day ended: never a list.
        assertTrue(redoChoices(sent.copy(kind = "steps")).isEmpty())
        assertTrue(redoChoices(sent.copy(status = "auto_approved", autoReason = "cycle_end")).isEmpty())
    }

    @Test
    fun theTaskChecksCardIsNamedAsOnTheWeb() {
        assertEquals("Task checks", CheckText.TASK_CHECKS_CARD)
        assertEquals("On", CheckText.taskChecksSummary(true))
        assertEquals("Off", CheckText.taskChecksSummary(false))
    }

    @Test
    fun theHeadIsToldHowToCheckOnlyWhenTheyCant() {
        val waiting = TaskReview(waiting = true, submittedBy = UserRef("kid", "Riley"), submissionId = null)
        assertEquals(
            "To check it yourself, add yourself as someone who looks after Riley on the Members page.",
            headHint(task(waiting), "head", "boss"),
        )
        // The head looks after Riley: the check's id comes with the task, so no hint.
        assertNull(headHint(task(waiting.copy(submissionId = "s1")), "head", "boss"))
        // Not the head, nothing waiting, or the head's own check.
        assertNull(headHint(task(waiting), "adult", "mum"))
        assertNull(headHint(task(TaskReview(), status = "pending"), "head", "boss"))
        assertNull(headHint(task(waiting), "head", "kid"))
        assertEquals("To check it yourself, add yourself as someone who looks after them on the Members page.", CheckText.headHint(null))
    }

    @Test
    fun reviewFieldsParseAndDefaultForOlderServers() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val now = json.decodeFromString(
            TaskReview.serializer(),
            """{"waiting":false,"submittedBy":null,"submittedAt":null,"decideBy":null,"checkForMe":true,"submissionId":null}""",
        )
        assertTrue(now.checkForMe)
        assertNull(now.submissionId)
        val old = json.decodeFromString(TaskReview.serializer(), """{"waiting":true}""")
        assertFalse(old.checkForMe)
        assertNull(old.submissionId)
        // A task from a server before checks: no review at all.
        val bare = json.decodeFromString(Task.serializer(), """{"id":"t","title":"T","type":"one_time","priority":"low","status":"pending"}""")
        assertFalse(bare.review.checkForMe)
    }

    // --- Refusal codes (details.code), never the message text -----------------

    @Test
    fun theCodeIsReadFromTheErrorDetails() {
        val json = kotlinx.serialization.json.Json
        assertEquals("check_needed", codeOf(json.parseToJsonElement("""{"code":"check_needed"}""")))
        assertEquals("check_cap", codeOf(json.parseToJsonElement("""{"code":"check_cap","fieldErrors":{"x":["y"]}}""")))
        assertEquals("household_disabled", codeOf(json.parseToJsonElement("""{"code":"household_disabled"}""")))
        assertNull(codeOf(null))
        assertNull(codeOf(json.parseToJsonElement("""{"fieldErrors":{"photo":["z"]}}""")))
        assertNull(codeOf(json.parseToJsonElement("""{"code":{"nested":true}}""")))
    }

    @Test
    fun everyTaskChangedCodeClosesTheDialogWhateverTheWords() {
        listOf(
            CheckCode.UPDATE_APP, CheckCode.NOT_NEEDED, CheckCode.WAITING,
            CheckCode.ALREADY_WAITING, CheckCode.DECIDED, CheckCode.CAP,
        ).forEach { code ->
            // Words the app has never seen: only the code decides, and the words are shown as sent.
            val e = ApiException("Server words for $code", 409, code = code)
            assertEquals(code, e.checkCode)
            assertEquals(SendFailure("Server words for $code", close = true, dropPhoto = true), sendFailure(e, hadPhoto = true))
        }
        // "Needs a check" is not "changed": Complete opens the dialog for it instead.
        assertFalse(CheckCode.NEEDED in CheckCode.TASK_CHANGED)
        assertNull(java.io.IOException("x").checkCode)
    }

    @Test
    fun anUncodedConflictStillClosesTheDialog() {
        val f = sendFailure(ApiException("Someone else just completed this task.", 409), hadPhoto = true)
        assertEquals("Someone else just completed this task.", f.message)
        assertTrue(f.close)
    }

    @Test
    fun photoSwitchHiddenFromSupervisedChildren() {
        assertTrue(canSetPhotoProof("adult", null))
        assertTrue(canSetPhotoProof("head", true))
        assertTrue(canSetPhotoProof("teen", false))
        assertFalse(canSetPhotoProof("child", true))
        // Not known yet: hidden from teens and children.
        assertFalse(canSetPhotoProof("teen", null))
    }

    // --- A failed send ------------------------------------------------------

    @Test
    fun networkFailureKeepsThePhotoAndTheDialog() {
        assertEquals(SendFailure(CheckText.SEND_FAILED_PHOTO_KEPT, close = false, dropPhoto = false), sendFailure(IOException("timeout"), hadPhoto = true))
        assertEquals(CheckText.SEND_FAILED, sendFailure(IOException("timeout"), hadPhoto = false).message)
        assertEquals(CheckText.SEND_FAILED_PHOTO_KEPT, sendFailure(ApiException("Something went wrong", 500), hadPhoto = true).message)
    }

    @Test
    fun anUnreadablePhotoAsksForANewOne() {
        val e = ApiException("That photo couldn’t be read. Take it again.", 422, mapOf("photo" to "That photo couldn’t be read. Take it again."))
        val f = sendFailure(e, hadPhoto = true)
        assertEquals("That photo couldn’t be read. Take it again.", f.message)
        assertFalse(f.close)
        assertTrue(f.dropPhoto)
    }

    @Test
    fun aChangedTaskClosesTheDialogWithTheServersWords() {
        val e = ApiException("This task doesn’t need a check any more. Tap Complete again.", 409, code = CheckCode.NOT_NEEDED)
        val f = sendFailure(e, hadPhoto = true)
        assertEquals("This task doesn’t need a check any more. Tap Complete again.", f.message)
        assertTrue(f.close)
    }

    @Test
    fun twoSendsAtOnceCloseTheLoserWithTheServersWords() {
        val f = sendFailure(ApiException("This is already waiting for an OK.", 409, code = CheckCode.ALREADY_WAITING), hadPhoto = true)
        assertEquals("This is already waiting for an OK.", f.message)
        assertTrue(f.close)
    }

    @Test
    fun rateLimitAndSizeKeepThePhoto() {
        val limited = sendFailure(ApiException("That’s a lot of checks at once. Wait a minute, then try again.", 429), hadPhoto = true)
        assertEquals("That’s a lot of checks at once. Wait a minute, then try again.", limited.message)
        assertFalse(limited.close || limited.dropPhoto)
        assertEquals(CheckText.TOO_BIG, sendFailure(ApiException("Unexpected response from server (413)"), hadPhoto = true).message)
        assertEquals("That’s too big to send.", sendFailure(ApiException("That’s too big to send.", 413), hadPhoto = true).message)
    }

    @Test
    fun aMissingNoteIsSaidInTheDialog() {
        val e = ApiException("This task needs a photo. Can’t take a photo? Say why in the note.", 422, mapOf("photo" to "x"))
        val f = sendFailure(e, hadPhoto = false)
        assertFalse(f.close || f.dropPhoto)
        assertEquals("This task needs a photo. Can’t take a photo? Say why in the note.", f.message)
    }
}
