package com.eosoclub.ourhome.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)
class UnauthorizedException : Exception("Your session has expired. Please sign in again.")

/**
 * Talks to the web app's JSON API. Auth is Better Auth's cookie session, the
 * same one the browser uses, carried by [PersistentCookieJar].
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val cookieJar: PersistentCookieJar,
    private val onUnauthorized: () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Better Auth's CSRF guard checks Origin on cookie-bearing POSTs; a
        // native client has none, so present the site's own origin.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Origin", baseUrl()).build())
        }
        .build()

    // --- Auth (Better Auth: bare JSON, not the app envelope) -----------------

    suspend fun signIn(username: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("rememberMe", true)
        }
        http.newCall(post("/api/auth/sign-in/username", body)).execute().use { res ->
            if (!res.isSuccessful) {
                val text = res.body.string()
                val err = runCatching { json.decodeFromString(AuthError.serializer(), text) }.getOrNull()
                throw ApiException(
                    when {
                        res.code == 429 -> "Too many attempts. Wait a few seconds and try again."
                        err?.message != null -> err.message
                        else -> "Sign-in failed (${res.code})"
                    },
                )
            }
        }
    }

    /** The current session's user, or null when signed out. */
    // disableCookieCache: read the user from the database, not Better Auth's
    // 5-minute session cookie cache, so a password or name changed on the
    // website shows here at once (the "temporary password" flag stayed stale).
    suspend fun getSession(): SessionUser? = withContext(Dispatchers.IO) {
        http.newCall(get("/api/auth/get-session?disableCookieCache=true")).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw ApiException("Could not reach the server (${res.code})")
            if (text.isBlank() || text == "null") null
            else json.decodeFromString(SessionResponse.serializer(), text).user
        }
    }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(post("/api/auth/sign-out", JsonObject(emptyMap()))).execute().close()
            }
        }
        cookieJar.clear()
    }

    // --- Profile -------------------------------------------------------------

    suspend fun profile(): ProfileOverview = call(get("/api/profile"), ProfileOverview.serializer())

    /** Updates only the fields passed (non-null); the server checks username/email uniqueness. */
    suspend fun updateProfile(name: String?, username: String?, email: String?): ProfileOverview =
        call(
            Request.Builder()
                .url(baseUrl() + "/api/profile")
                .patch(
                    buildJsonObject {
                        name?.let { put("name", it) }
                        username?.let { put("username", it) }
                        email?.let { put("email", it) }
                    }.toString().toRequestBody(jsonType),
                )
                .build(),
            ProfileOverview.serializer(),
        )

    /**
     * Better Auth's change-password (bare JSON errors, not the app envelope).
     * With [revokeOtherSessions] the server rotates this device's session
     * cookie, which the cookie jar picks up.
     */
    suspend fun changePassword(current: String, new: String, revokeOtherSessions: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("currentPassword", current)
                put("newPassword", new)
                put("revokeOtherSessions", revokeOtherSessions)
            }
            http.newCall(post("/api/auth/change-password", body)).execute().use { res ->
                if (!res.isSuccessful) {
                    val err = runCatching { json.decodeFromString(AuthError.serializer(), res.body.string()) }.getOrNull()
                    throw ApiException(
                        when {
                            res.code == 429 -> "Too many attempts. Wait a few seconds and try again."
                            err?.code == "INVALID_PASSWORD" -> "Your current password is incorrect."
                            err?.message != null -> err.message
                            else -> "Couldn't change your password (${res.code})"
                        },
                    )
                }
            }
        }

    /** Lifts the "must change password" flag set on admin-provisioned accounts. */
    suspend fun clearPasswordFlag() = callUnit(post("/api/profile/clear-password-flag", JsonObject(emptyMap())))

    // --- Tasks ---------------------------------------------------------------

    suspend fun tasks(): List<Task> =
        call(get("/api/tasks"), ListSerializer(Task.serializer()))

    /** Creates a task with its whole checklist in one call, like the web's create form. */
    suspend fun createTask(input: TaskInput, steps: List<StepInput>): Task =
        call(
            post(
                "/api/tasks",
                buildJsonObject {
                    putTaskFields(input, forUpdate = false)
                    if (steps.isNotEmpty()) putSteps(steps)
                },
            ),
            Task.serializer(),
        )

    /**
     * The checklist as the editor left it (TaskPoints.kt values included): on
     * update it replaces the task's checklist — items with an id are kept,
     * new ones created, missing ones deleted, order = list order.
     */
    private fun JsonObjectBuilder.putSteps(steps: List<StepInput>) {
        putJsonArray("subtasks") {
            steps.forEach { s ->
                addJsonObject {
                    if (s.id != null) put("id", s.id)
                    put("title", s.title)
                    put("done", false)
                    put("resetIntervalDays", s.resetIntervalDays)
                    put("minutes", s.values.minutes)
                    put("points", s.values.pointsCenti.asPoints())
                    put("minutesCustom", s.values.minutesCustom)
                    put("pointsFollowTime", s.values.pointsFollowTime)
                }
            }
        }
    }

    /**
     * The fields the create and update endpoints share. Update takes null
     * `notes`/`recurrence` to clear them; create only accepts them absent.
     */
    private fun JsonObjectBuilder.putTaskFields(input: TaskInput, forUpdate: Boolean) {
        put("title", input.title)
        if (input.notes != null || forUpdate) put("notes", input.notes)
        put("type", input.type)
        put("priority", input.priority)
        put("dueDate", input.dueDate?.toString())
        put("estimatedMinutes", input.estimatedMinutes)
        put("points", input.points)
        put("pointsFollowTime", input.pointsFollowTime)
        put("categoryId", input.categoryId)
        put("assigneeId", input.assigneeId)
        val r = input.recurrence
        if (r == null) {
            if (forUpdate) put("recurrence", JsonNull)
        } else {
            putJsonObject("recurrence") {
                put("kind", r.kind)
                put("interval", r.interval)
                put("timezone", "UTC")
                put("until", r.until?.toString())
                if (r.kind == "weekly" && r.byWeekday.isNotEmpty()) {
                    putJsonArray("byWeekday") { r.byWeekday.forEach { add(it) } }
                }
                if (r.kind == "monthly" && r.byMonthday.isNotEmpty()) {
                    putJsonArray("byMonthday") { r.byMonthday.forEach { add(it) } }
                }
                put("rollover", r.rollover)
                if (r.rollover && r.kind == "weekly") putJsonArray("cycleWeekdays") { r.cycleWeekdays.forEach { add(it) } }
                if (r.rollover && r.kind == "monthly") putJsonArray("cycleMonthdays") { r.cycleMonthdays.forEach { add(it) } }
            }
        }
    }

    /** Completes a task and pays its points; the result carries `completionId` for Undo. */
    suspend fun completeTask(taskId: String): Task =
        call(post("/api/tasks/complete", buildJsonObject { put("taskId", taskId) }), Task.serializer())

    /** Undo a completion (completer within 10 min, head any time); its points are voided. */
    suspend fun undoCompletion(completionId: String): Task =
        call(post("/api/tasks/completions/undo", buildJsonObject { put("completionId", completionId) }), Task.serializer())

    /** A task's history, newest first, incl. missed cycles and whether the caller may undo. */
    suspend fun taskCompletions(taskId: String): List<TaskCompletion> =
        call(get("/api/tasks/completions?taskId=$taskId"), ListSerializer(TaskCompletion.serializer()))

    /**
     * Saves a task's editable fields and its whole checklist in one call.
     * Every field is sent, so a null clears it (notes, due date, estimate,
     * category, assignee, recurrence), as the web's edit form does.
     */
    suspend fun updateTask(taskId: String, input: TaskInput, steps: List<StepInput>): Task =
        call(
            post(
                "/api/tasks/update",
                buildJsonObject {
                    put("taskId", taskId)
                    putTaskFields(input, forUpdate = true)
                    putSteps(steps)
                },
            ),
            Task.serializer(),
        )

    // --- Points ----------------------------------------------------------------

    /** Totals for the day/week/month/year containing [date] ("YYYY-MM-DD"; null = today). */
    suspend fun pointsSummary(period: String, date: String? = null): PointsSummary =
        call(
            get("/api/points/summary?period=$period" + (date?.let { "&date=$it" } ?: "")),
            PointsSummary.serializer(),
        )

    /** The ledger for a period, newest first (voided entries included). */
    suspend fun pointAwards(period: String, date: String? = null, userId: String? = null): List<PointAward> =
        call(
            get(
                "/api/points/awards?period=$period" +
                    (date?.let { "&date=$it" } ?: "") +
                    (userId?.let { "&userId=$it" } ?: ""),
            ),
            ListSerializer(PointAward.serializer()),
        )

    /** Head only: strike an entry from the totals, with a reason. */
    suspend fun voidAward(awardId: String, reason: String) =
        callUnit(post("/api/points/awards/void", buildJsonObject { put("awardId", awardId); put("reason", reason) }))

    suspend fun deleteTask(taskId: String) =
        callUnit(post("/api/tasks/delete", buildJsonObject { put("taskId", taskId) }))

    /** Appends a checklist item; returns the whole updated task. */
    suspend fun addSubtask(taskId: String, title: String, resetIntervalDays: Int?): Task =
        call(
            post(
                "/api/subtasks",
                buildJsonObject {
                    put("taskId", taskId)
                    put("title", title)
                    put("resetIntervalDays", resetIntervalDays)
                },
            ),
            Task.serializer(),
        )

    /** Saves a checklist item's title and auto-uncheck cadence (null clears it). */
    suspend fun updateSubtask(subtaskId: String, title: String, resetIntervalDays: Int?) =
        callUnit(
            post(
                "/api/subtasks/update",
                buildJsonObject {
                    put("subtaskId", subtaskId)
                    put("title", title)
                    put("resetIntervalDays", resetIntervalDays)
                },
            ),
        )

    suspend fun deleteSubtask(subtaskId: String) =
        callUnit(post("/api/subtasks/delete", buildJsonObject { put("subtaskId", subtaskId) }))

    /** Sets the checklist order to [subtaskIds] (every item of the task). */
    suspend fun reorderSubtasks(taskId: String, subtaskIds: List<String>) =
        callUnit(
            post(
                "/api/subtasks/reorder",
                buildJsonObject {
                    put("taskId", taskId)
                    putJsonArray("subtaskIds") { subtaskIds.forEach { add(it) } }
                },
            ),
        )

    /** Categories of one kind ("task", …); readable by any member. */
    suspend fun categories(kind: String): List<CategoryRef> =
        call(get("/api/categories?kind=$kind"), ListSerializer(CategoryRef.serializer()))

    /** Checks/unchecks one checklist step; returns the whole updated task. */
    suspend fun setSubtaskDone(subtaskId: String, done: Boolean): Task =
        call(
            post("/api/subtasks/update", buildJsonObject { put("subtaskId", subtaskId); put("done", done) }),
            Task.serializer(),
        )

    // --- Shopping ------------------------------------------------------------

    suspend fun shoppingLists(): List<ShoppingList> =
        call(get("/api/shopping/lists"), ListSerializer(ShoppingList.serializer()))

    suspend fun addShoppingItem(listId: String, name: String, quantity: Int): ShoppingItem =
        call(
            post(
                "/api/shopping/items",
                buildJsonObject { put("listId", listId); put("name", name); put("quantity", quantity) },
            ),
            ShoppingItem.serializer(),
        )

    suspend fun setPurchased(itemId: String, purchased: Boolean): ShoppingItem =
        call(
            post(
                "/api/shopping/items/purchase",
                buildJsonObject { put("itemId", itemId); put("purchased", purchased) },
            ),
            ShoppingItem.serializer(),
        )

    suspend fun updateShoppingItem(
        itemId: String,
        name: String,
        quantity: Int,
        priority: String,
        notes: String?,
        recurring: Boolean,
    ): ShoppingItem =
        call(
            post(
                "/api/shopping/items/update",
                buildJsonObject {
                    put("itemId", itemId)
                    put("name", name)
                    put("quantity", quantity)
                    put("priority", priority)
                    put("notes", notes)
                    put("recurring", recurring)
                },
            ),
            ShoppingItem.serializer(),
        )

    suspend fun deleteShoppingItem(itemId: String) =
        callUnit(post("/api/shopping/items/delete", buildJsonObject { put("itemId", itemId) }))

    suspend fun clearBought(listId: String) =
        callUnit(post("/api/shopping/lists/clear", buildJsonObject { put("listId", listId) }))

    /** [kind]: one of the web's SHOPPING_LIST_KINDS (grocery, supplies, …). */
    suspend fun createShoppingList(name: String, kind: String): ShoppingList =
        call(
            post("/api/shopping/lists", buildJsonObject { put("name", name); put("kind", kind) }),
            ShoppingList.serializer(),
        )

    suspend fun renameShoppingList(listId: String, name: String): ShoppingList =
        call(
            post("/api/shopping/lists/update", buildJsonObject { put("listId", listId); put("name", name) }),
            ShoppingList.serializer(),
        )

    suspend fun deleteShoppingList(listId: String) =
        callUnit(post("/api/shopping/lists/delete", buildJsonObject { put("listId", listId) }))

    // --- Dashboard -----------------------------------------------------------

    suspend fun dashboard(): Dashboard = call(get("/api/dashboard"), Dashboard.serializer())

    // --- Inventory -----------------------------------------------------------

    suspend fun inventory(): List<InventoryItem> =
        call(get("/api/inventory/items"), ListSerializer(InventoryItem.serializer()))

    suspend fun adjustInventory(itemId: String, delta: Double): InventoryItem =
        call(
            post("/api/inventory/items/adjust", buildJsonObject { put("itemId", itemId); put("delta", delta) }),
            InventoryItem.serializer(),
        )

    suspend fun updateInventoryItem(
        itemId: String,
        name: String,
        unit: String?,
        quantity: Double,
        lowThreshold: Double,
        reorderIntervalDays: Int?,
    ): InventoryItem =
        call(
            post(
                "/api/inventory/items/update",
                buildJsonObject {
                    put("itemId", itemId)
                    put("name", name)
                    put("unit", unit)
                    put("quantity", quantity)
                    put("lowThreshold", lowThreshold)
                    put("reorderIntervalDays", reorderIntervalDays)
                },
            ),
            InventoryItem.serializer(),
        )

    suspend fun deleteInventoryItem(itemId: String) =
        callUnit(post("/api/inventory/items/delete", buildJsonObject { put("itemId", itemId) }))

    // --- NFC tags ------------------------------------------------------------

    suspend fun nfcLookup(tagId: String): NfcLookup =
        call(get("/api/nfc/lookup?tagId=${java.net.URLEncoder.encode(tagId, "UTF-8")}"), NfcLookup.serializer())

    /** Applies a scan to the tag's item as the signed-in user; returns the updated item. */
    suspend fun nfcScan(tagId: String, amount: Double): InventoryItem =
        call(
            post("/api/nfc/scans", buildJsonObject { put("tagId", tagId); put("amount", amount) }),
            InventoryItem.serializer(),
        )

    /** Binds [tagId] to an existing item, or (with [newItemName]) creates the item first. */
    suspend fun nfcSetup(
        tagId: String,
        itemId: String? = null,
        newItemName: String? = null,
        newItemUnit: String? = null,
        newItemQuantity: Double = 0.0,
        newItemLowThreshold: Double = 0.0,
        scanAction: String = "open",
        shoppingListId: String? = null,
    ): NfcLookup =
        call(
            post(
                "/api/nfc/setup",
                buildJsonObject {
                    put("tagId", tagId)
                    put("scanAction", scanAction)
                    put("shoppingListId", shoppingListId)
                    itemId?.let { put("itemId", it) }
                    newItemName?.let { name ->
                        put(
                            "newItem",
                            buildJsonObject {
                                put("name", name)
                                put("unit", newItemUnit)
                                put("quantity", newItemQuantity)
                                put("lowThreshold", newItemLowThreshold)
                            },
                        )
                    }
                },
            ),
            NfcLookup.serializer(),
        )

    /** How the app treats a scan of [tagId] with the app closed, and its shopping list. */
    suspend fun nfcTagSettings(tagId: String, scanAction: String, shoppingListId: String?): NfcLookup =
        call(
            post(
                "/api/nfc/tags/settings",
                buildJsonObject {
                    put("tagId", tagId)
                    put("scanAction", scanAction)
                    put("shoppingListId", shoppingListId)
                },
            ),
            NfcLookup.serializer(),
        )

    /** Puts the tag's item on its shopping list (no duplicate if it's already there). */
    suspend fun nfcAddToShopping(tagId: String): NfcShoppingResult =
        call(post("/api/nfc/shopping", buildJsonObject { put("tagId", tagId) }), NfcShoppingResult.serializer())

    suspend fun nfcScans(limit: Int = 50): List<NfcScan> =
        call(get("/api/nfc/scans?limit=$limit"), ListSerializer(NfcScan.serializer()))

    // --- Bills ---------------------------------------------------------------

    suspend fun updateBill(
        id: String,
        name: String,
        amount: Double,
        dueDate: Instant?,
        autoPay: Boolean,
        notes: String?,
    ) = callUnit(
        post(
            "/api/bills/update",
            buildJsonObject {
                put("id", id)
                put("name", name)
                put("amount", amount)
                put("dueDate", dueDate?.toString())
                put("autoPay", autoPay)
                put("notes", notes)
            },
        ),
    )

    suspend fun deleteBill(id: String) =
        callUnit(post("/api/bills/delete", buildJsonObject { put("id", id) }))

    suspend fun bills(): List<Bill> = call(get("/api/bills"), ListSerializer(Bill.serializer()))

    /** Records a payment; with no [amount] the server applies the remaining balance. */
    suspend fun payBill(id: String, amount: Double? = null): Bill =
        call(
            post("/api/bills/pay", buildJsonObject { put("id", id); amount?.let { put("amount", it) } }),
            Bill.serializer(),
        )

    // --- Requests ------------------------------------------------------------

    suspend fun requests(): List<HouseholdRequest> =
        call(get("/api/requests"), ListSerializer(HouseholdRequest.serializer()))

    /** Creates a media request, or with [id] replaces the caller's own. */
    suspend fun saveMediaRequest(id: String?, mediaType: String, title: String, year: Int, season: Int?) =
        saveRequest(id) {
            put("category", "media")
            put("mediaType", mediaType)
            put("title", title)
            put("year", year)
            put("season", season)
        }

    /** Creates a maintenance request for [assigneeId], or with [id] replaces the caller's own. */
    suspend fun saveMaintenanceRequest(id: String?, title: String, details: String?, assigneeId: String) =
        saveRequest(id) {
            put("category", "maintenance")
            put("title", title)
            put("details", details)
            put("assigneeId", assigneeId)
        }

    private suspend fun saveRequest(id: String?, fields: JsonObjectBuilder.() -> Unit): HouseholdRequest =
        call(
            post(
                if (id == null) "/api/requests" else "/api/requests/update",
                buildJsonObject {
                    id?.let { put("id", it) }
                    fields()
                },
            ),
            HouseholdRequest.serializer(),
        )

    /**
     * Accepts a request: maintenance needs the assignee's done-by [dueAt] (or
     * moves it); media is accepted by the head with no date.
     */
    suspend fun acceptRequest(id: String, dueAt: Instant? = null): HouseholdRequest =
        call(
            post("/api/requests/accept", buildJsonObject { put("id", id); dueAt?.let { put("dueAt", it.toString()) } }),
            HouseholdRequest.serializer(),
        )

    suspend fun completeRequest(id: String): HouseholdRequest =
        call(post("/api/requests/complete", buildJsonObject { put("id", id) }), HouseholdRequest.serializer())

    /** Id + name of everyone in the household; readable by any member. */
    suspend fun householdMembers(): List<Member> =
        call(get("/api/household/members"), ListSerializer(Member.serializer()))

    suspend fun deleteRequest(id: String) =
        callUnit(post("/api/requests/delete", buildJsonObject { put("id", id) }))

    // --- Notifications -------------------------------------------------------

    suspend fun notifications(unreadOnly: Boolean = false): NotificationList =
        call(get("/api/notifications?limit=50&unreadOnly=$unreadOnly"), NotificationList.serializer())

    // --- Instant alerts (FCM) ------------------------------------------------

    /** Tells the server this install's FCM token belongs to the signed-in user. */
    suspend fun registerPushDevice(token: String) =
        callUnit(post("/api/push/devices", buildJsonObject { put("token", token) }))

    /** Stops pushes to this install; called just before signing out. */
    suspend fun unregisterPushDevice(token: String) =
        callUnit(post("/api/push/devices/delete", buildJsonObject { put("token", token) }))

    // --- Bug reports ---------------------------------------------------------

    /** Files a bug report; the server notifies the head and emails support. */
    suspend fun submitBugReport(title: String, description: String, context: String?, appVersion: String) =
        callUnit(
            post(
                "/api/bug-reports",
                buildJsonObject {
                    put("title", title)
                    put("description", description)
                    put("source", "android")
                    put("context", context)
                    put("appVersion", appVersion)
                },
            ),
        )

    suspend fun markNotificationRead(id: String) =
        callUnit(post("/api/notifications/read", buildJsonObject { put("id", id) }))

    suspend fun markAllNotificationsRead() =
        callUnit(post("/api/notifications/read", buildJsonObject { put("all", true) }))

    // --- App updates ---------------------------------------------------------

    /** The Android app this server offers, or null when it builds none. */
    suspend fun appRelease(): AppRelease? = call(get("/api/app/info"), AppInfo.serializer()).release

    /**
     * Downloads the offered APK into [dest] with this session (the download is
     * for signed-in members only), reporting progress as 0..1.
     */
    suspend fun downloadApp(dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        // A ~30 MB file: allow more than the API's read timeout between chunks.
        val client = http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
        client.newCall(get("/api/app/download")).execute().use { res ->
            if (res.code == 401) {
                onUnauthorized()
                throw UnauthorizedException()
            }
            if (!res.isSuccessful) {
                throw ApiException(if (res.code == 404) "No app update is on offer right now." else "Download failed (${res.code})")
            }
            val total = res.body.contentLength()
            res.body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    // --- Permissions -------------------------------------------------------

    /** The signed-in user's page access (what they may add/edit/delete). */
    suspend fun myAccess(): MyAccess = call(get("/api/permissions/me"), MyAccess.serializer())

    // --- Plumbing ------------------------------------------------------------

    private fun get(path: String) = Request.Builder().url(baseUrl() + path).get().build()

    private fun post(path: String, body: JsonElement) =
        Request.Builder().url(baseUrl() + path).post(body.toString().toRequestBody(jsonType)).build()

    /** Executes an app-API call and returns the envelope's `data`. */
    private suspend fun <T> call(request: Request, serializer: KSerializer<T>): T =
        execute(request, serializer).data ?: throw ApiException("Empty response from server")

    /** Executes an app-API call where only success matters, not the payload. */
    private suspend fun callUnit(request: Request) {
        execute(request, JsonElement.serializer())
    }

    private suspend fun <T> execute(request: Request, serializer: KSerializer<T>): Envelope<T> =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { res ->
                if (res.code == 401) {
                    onUnauthorized()
                    throw UnauthorizedException()
                }
                val text = res.body.string()
                val envelope = runCatching {
                    json.decodeFromString(Envelope.serializer(serializer), text)
                }.getOrElse { throw ApiException("Unexpected response from server (${res.code})") }
                if (!envelope.ok) {
                    throw ApiException(envelope.error?.message ?: "Request failed (${res.code})")
                }
                envelope
            }
        }
}
