package com.eosoclub.ourhome.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

/** [status]: the HTTP status when the server answered with an error, for callers that tell them apart. */
class ApiException(message: String, val status: Int? = null) : Exception(message)
class UnauthorizedException : Exception("Your session has expired. Please sign in again.")

/**
 * Talks to the web app's JSON API. Auth is Better Auth's cookie session, the
 * same one the browser uses, carried by [PersistentCookieJar].
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val cookieJar: PersistentCookieJar,
    /** Signed out by the server; the message says why (null = session expired). */
    private val onUnauthorized: (String?) -> Unit,
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

    /**
     * Updates only the fields passed (non-null); the server checks username/email
     * uniqueness. A new email needs [currentPassword] (password resets go there).
     */
    suspend fun updateProfile(name: String?, username: String?, email: String?, currentPassword: String? = null): ProfileOverview =
        call(
            Request.Builder()
                .url(baseUrl() + "/api/profile")
                .patch(
                    buildJsonObject {
                        name?.let { put("name", it) }
                        username?.let { put("username", it) }
                        email?.let { put("email", it) }
                        currentPassword?.let { put("currentPassword", it) }
                    }.toString().toRequestBody(jsonType),
                )
                .build(),
            ProfileOverview.serializer(),
        )

    /**
     * Saves the about-me fields. Every field is sent, so blank/null clears it;
     * the server checks the emoji is a single one and the birthday ("MM-DD") real.
     */
    suspend fun updateAboutMe(profile: PublicProfile): ProfileOverview =
        call(
            Request.Builder()
                .url(baseUrl() + "/api/profile")
                .patch(
                    buildJsonObject {
                        put("bio", profile.bio)
                        put("avatarEmoji", profile.avatarEmoji)
                        put("profileColor", profile.profileColor)
                        put("birthday", profile.birthday)
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
     * update it replaces the task's checklist - items with an id are kept,
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
    /** A picker value ("room:<id>", "floor:<id>", "") as roomId / floorId (Places.kt). */
    private fun JsonObjectBuilder.putPlace(place: String) {
        put("roomId", place.removePrefix("room:").takeIf { place.startsWith("room:") })
        put("floorId", place.removePrefix("floor:").takeIf { place.startsWith("floor:") })
    }

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
        // Only when the editor knew the rooms; absent = the places are unchanged.
        input.places?.let { values ->
            putJsonArray("roomIds") { values.filter { it.startsWith("room:") }.forEach { add(it.removePrefix("room:")) } }
            putJsonArray("floorIds") { values.filter { it.startsWith("floor:") }.forEach { add(it.removePrefix("floor:")) } }
            // A server from before several places reads only these: the first place.
            putPlace(values.firstOrNull().orEmpty())
        }
        put("assigneeId", input.assigneeId)
        // Always sent: [] clears a rotation (an older server ignores the key).
        putJsonArray("rotationUserIds") { input.rotationUserIds.forEach { add(it) } }
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

    /** Floors and rooms in the household's order; readable by any member. */
    suspend fun places(): Places = call(get("/api/places"), Places.serializer())

    /** Hand-set order of the tasks in one place (needs "Edit others'" on Tasks). */
    suspend fun reorderTasks(taskIds: List<String>) =
        callUnit(post("/api/tasks/reorder", buildJsonObject { putJsonArray("taskIds") { taskIds.forEach { add(it) } } }))

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

    /** One page of the activity log, newest first; [before] = the previous page's `nextBefore`. */
    suspend fun activity(area: String? = null, userId: String? = null, before: String? = null): ActivityPage {
        val query = listOfNotNull(
            area?.let { "area=$it" },
            userId?.let { "userId=$it" },
            before?.let { "before=$it" },
        ).joinToString("&")
        return call(get("/api/activity" + if (query.isEmpty()) "" else "?$query"), ActivityPage.serializer())
    }

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
        /** Where it's kept, as a picker value; null = unchanged (no rooms to pick from). */
        place: String? = null,
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
                    place?.let { putPlace(it) }
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

    /**
     * Binds [tagId] to an existing item, (with [newItemName]) creates the item
     * first, or (with [roomId]) makes it a location tag that opens that room.
     */
    suspend fun nfcSetup(
        tagId: String,
        itemId: String? = null,
        newItemName: String? = null,
        newItemUnit: String? = null,
        newItemQuantity: Double = 0.0,
        newItemLowThreshold: Double = 0.0,
        /** Where a new item is kept, as a picker value ("" / null = not set). */
        newItemPlace: String? = null,
        roomId: String? = null,
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
                    roomId?.let { put("roomId", it) }
                    newItemName?.let { name ->
                        put(
                            "newItem",
                            buildJsonObject {
                                put("name", name)
                                put("unit", newItemUnit)
                                put("quantity", newItemQuantity)
                                put("lowThreshold", newItemLowThreshold)
                                newItemPlace?.takeIf { it.isNotEmpty() }?.let { putPlace(it) }
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

    /** The room a location tag opens. */
    suspend fun nfcTagRoom(tagId: String, roomId: String): NfcLookup =
        call(
            post("/api/nfc/tags/settings", buildJsonObject { put("tagId", tagId); put("roomId", roomId) }),
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

    // --- Members (members:manage; rename and head transfer are head-only) ------

    suspend fun memberAccounts(): List<MemberAccount> =
        call(get("/api/members"), ListSerializer(MemberAccount.serializer()))

    /** Adds someone with a temporary password they must change; a blank [email] gets a placeholder. */
    suspend fun createMember(name: String, username: String, email: String?, role: String, password: String): MemberAccount =
        call(
            post(
                "/api/members",
                buildJsonObject {
                    put("name", name)
                    put("username", username)
                    email?.let { put("email", it) }
                    put("role", role)
                    put("password", password)
                },
            ),
            MemberAccount.serializer(),
        )

    /** Changes only the fields passed (non-null); the server checks username/email are free. */
    suspend fun updateMember(id: String, name: String?, username: String?, email: String?): MemberAccount =
        call(
            post(
                "/api/members/update",
                buildJsonObject {
                    put("id", id)
                    name?.let { put("name", it) }
                    username?.let { put("username", it) }
                    email?.let { put("email", it) }
                },
            ),
            MemberAccount.serializer(),
        )

    suspend fun setMemberRole(id: String, role: String): MemberAccount =
        call(post("/api/members/role", buildJsonObject { put("id", id); put("role", role) }), MemberAccount.serializer())

    /** Sets a new temporary password and signs them out everywhere. */
    suspend fun resetMemberPassword(id: String, password: String): MemberAccount =
        call(
            post("/api/members/password", buildJsonObject { put("id", id); put("password", password) }),
            MemberAccount.serializer(),
        )

    /** Head only: [id] becomes Head of House and the caller a Manager. */
    suspend fun transferHead(id: String): MemberAccount =
        call(post("/api/members/transfer-head", buildJsonObject { put("id", id) }), MemberAccount.serializer())

    suspend fun removeMember(id: String) =
        callUnit(post("/api/members/delete", buildJsonObject { put("id", id) }))

    /** Head only. */
    suspend fun renameHousehold(name: String): HouseholdRef =
        call(post("/api/household/rename", buildJsonObject { put("name", name) }), HouseholdRef.serializer())

    // --- Notifications -------------------------------------------------------

    suspend fun notifications(unreadOnly: Boolean = false): NotificationList =
        call(get("/api/notifications?limit=50&unreadOnly=$unreadOnly"), NotificationList.serializer())

    // --- Instant alerts (FCM) ------------------------------------------------

    /**
     * Tells the server this install's FCM token belongs to the signed-in user.
     * [installId]: the check-in's, so a token Firebase later calls dead tells
     * the server which phone lost OurHome.
     */
    suspend fun registerPushDevice(token: String, installId: String) =
        callUnit(
            post(
                "/api/push/devices",
                buildJsonObject {
                    put("token", token)
                    put("installId", installId)
                },
            ),
        )

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
                onUnauthorized(null)
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

    // --- Location (web lib/location.ts; Location feature) --------------------

    /** Home, stores and other spots; the phone watches each one (geofences). */
    suspend fun mapPlaces(): List<MapPlace> = call(get("/api/map-places"), ListSerializer(MapPlace.serializer()))

    /** The signed-in member's own sharing choice. */
    suspend fun locationConsent(): LocationConsent =
        call(get("/api/location/consent"), LocationConsent.serializer())

    /** Turns the member's own sharing on or off; off also deletes where they were. */
    suspend fun setLocationSharing(on: Boolean): LocationConsent =
        call(
            Request.Builder()
                .url(baseUrl() + "/api/location/consent")
                .patch(buildJsonObject { put("sharing", if (on) "on" else "off") }.toString().toRequestBody(jsonType))
                .build(),
            LocationConsent.serializer(),
        )

    /**
     * This phone arrived at or left a map place. 409 = sharing is off for this
     * member; 404 = the place is gone (see [ApiException.status]).
     */
    suspend fun reportPresence(report: PresenceReport): PresenceResult =
        call(
            post(
                "/api/location/presence",
                buildJsonObject {
                    put("placeId", report.placeId)
                    put("transition", report.transition)
                    put("at", Instant.ofEpochMilli(report.atMillis).toString())
                    report.lat?.let { put("lat", it) }
                    report.lng?.let { put("lng", it) }
                    report.accuracyM?.let { put("accuracyM", it) }
                    report.battery?.let { putBattery(it) }
                },
            ),
            PresenceResult.serializer(),
        )

    /** This phone's battery, hourly while its member shares. 409 = sharing is off. */
    suspend fun reportBattery(battery: BatteryLevel, atMillis: Long) =
        callUnit(
            post(
                "/api/location/battery",
                buildJsonObject {
                    put("pct", battery.pct)
                    put("charging", battery.charging)
                    put("at", Instant.ofEpochMilli(atMillis).toString())
                },
            ),
        )

    private fun JsonObjectBuilder.putBattery(battery: BatteryLevel) {
        put("battery", buildJsonObject {
            put("pct", battery.pct)
            put("charging", battery.charging)
        })
    }

    /** Where the members who share their location are. */
    suspend fun presence(): List<MemberPresence> =
        call(get("/api/location/presence"), ListSerializer(MemberPresence.serializer()))

    /** Allows (or stops allowing) adults to start a live check; off also ends one running now. */
    suspend fun setLiveChecks(on: Boolean): LocationConsent =
        call(
            Request.Builder()
                .url(baseUrl() + "/api/location/consent")
                .patch(buildJsonObject { put("liveChecks", on) }.toString().toRequestBody(jsonType))
                .build(),
            LocationConsent.serializer(),
        )

    /** A check on me, checks I started, my log, who I may check, the household's limits. */
    suspend fun liveChecks(): LiveChecksOverview = call(get("/api/location/checks"), LiveChecksOverview.serializer())

    /** One check (the person checking, or the person checked). */
    suspend fun liveCheck(id: String): LiveCheck = call(get("/api/location/checks/status?id=$id"), LiveCheck.serializer())

    suspend fun startLiveCheck(targetId: String, minutes: Int, intervalSec: Int): LiveCheck =
        call(
            post(
                "/api/location/checks",
                buildJsonObject {
                    put("targetId", targetId)
                    put("minutes", minutes)
                    put("intervalSec", intervalSec)
                },
            ),
            LiveCheck.serializer(),
        )

    /** This phone's position during a check on its member. 409 = the check is over. */
    suspend fun sendLiveCheckFix(
        id: String,
        lat: Double,
        lng: Double,
        accuracyM: Double?,
        atMillis: Long,
        battery: BatteryLevel? = null,
    ): LiveCheck =
        call(
            post(
                "/api/location/checks/fix",
                buildJsonObject {
                    put("id", id)
                    put("lat", lat)
                    put("lng", lng)
                    accuracyM?.let { put("accuracyM", it) }
                    put("at", Instant.ofEpochMilli(atMillis).toString())
                    battery?.let { putBattery(it) }
                },
            ),
            LiveCheck.serializer(),
        )

    suspend fun stopLiveCheck(id: String): LiveCheck =
        call(post("/api/location/checks/stop", buildJsonObject { put("id", id) }), LiveCheck.serializer())

    /** This phone checking in (hourly): which install, and what it may still do. Answers whether it must Stay connected. */
    suspend fun checkIn(
        installId: String,
        appVersion: String,
        deviceName: String,
        locationAlways: Boolean,
        notifications: Boolean,
        usageAccess: Boolean,
        lastPushAtMs: Long?,
        lastPushVia: String?,
        batteryUnrestricted: Boolean,
        standbyBucket: Int,
        stayConnected: Boolean,
        closeGuard: Boolean,
        uninstallGuard: Boolean = closeGuard,
        emergencyUnlockStage: String? = null,
        emergencyUnlockReadyAtMs: Long? = null,
        status: DeviceStatus?,
        shuttingDown: Boolean,
    ): CheckInResult =
        call(
            post(
                "/api/phone/checkin",
                buildJsonObject {
                    put("installId", installId)
                    put("appVersion", appVersion)
                    put("deviceName", deviceName)
                    put("locationAlways", locationAlways)
                    put("notifications", notifications)
                    put("usageAccess", usageAccess)
                    // Left out until the first instant alert arrives (the server wants both or neither).
                    if (lastPushAtMs != null && lastPushVia != null) {
                        put("lastPushAtMs", lastPushAtMs)
                        put("lastPushVia", lastPushVia)
                    }
                    put("batteryUnrestricted", batteryUnrestricted)
                    put("standbyBucket", standbyBucket)
                    put("stayConnected", stayConnected)
                    put("closeGuard", closeGuard)
                    put("uninstallGuard", uninstallGuard)
                    emergencyUnlockStage?.let { put("emergencyUnlockStage", it) }
                    emergencyUnlockReadyAtMs?.let { put("emergencyUnlockReadyAtMs", it) }
                    // Only while an adult looks after this member (DeviceStatus).
                    status?.let { put("status", it.toJson()) }
                    if (shuttingDown) put("shuttingDown", true)
                },
            ),
            CheckInResult.serializer(),
        )

    /** Sends a test notification to my own phones (this one included). */
    suspend fun testPush(): TestPushResult = call(post("/api/push/test", JsonObject(emptyMap())), TestPushResult.serializer())

    /**
     * Stay connected: opens the server's event stream for this install and
     * hands each line to [onLine] until the server or the network ends it.
     * Blocking; run it off the main thread. [onOpen] gets a way to end it from
     * another thread (the service stopping). No read timeout beyond [idleSec]:
     * the server sends a comment every 45 s, so silence that long means the
     * connection is dead. [status]: only while an adult looks after this member.
     */
    fun stream(
        installId: String,
        idleSec: Long,
        status: DeviceStatus?,
        onOpen: (cancel: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
    ) {
        val client = http.newBuilder()
            .readTimeout(idleSec, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        val path = "/api/phone/stream?installId=" + java.net.URLEncoder.encode(installId, "UTF-8") +
            status?.toQuery().orEmpty()
        val request = Request.Builder().url(baseUrl() + path).header("Accept", "text/event-stream").get().build()
        val call = client.newCall(request)
        onOpen(call::cancel)
        call.execute().use { res ->
            if (res.code == 401) {
                onUnauthorized(null)
                throw UnauthorizedException()
            }
            if (!res.isSuccessful) throw ApiException("Stay connected refused (${res.code})", res.code)
            val source = res.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                onLine(line)
            }
        }
    }

    /** Whether my app usage is shared (an adult who looks after me turned it on). */
    suspend fun myUsageSharing(): UsageSharing = call(get("/api/phone/usage"), UsageSharing.serializer())

    /** This phone's app usage for today and yesterday. 409 = it isn't shared. Answers which apps need an icon. */
    suspend fun reportUsage(days: List<UsageDay>): UsageReportResult =
        call(
            post(
                "/api/phone/usage",
                buildJsonObject {
                    putJsonArray("days") {
                        days.forEach { d ->
                            addJsonObject {
                                put("day", d.day)
                                put("totalMinutes", d.totalMinutes)
                                putJsonArray("apps") {
                                    d.apps.forEach { a ->
                                        addJsonObject {
                                            put("app", a.app)
                                            put("label", a.label.take(100))
                                            put("minutes", a.minutes)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            ),
            UsageReportResult.serializer(),
        )

    /** The app icons the server asked for: package name to PNG, base64. 409 = usage isn't shared. */
    suspend fun reportUsageIcons(icons: Map<String, String>) =
        callUnit(
            post(
                "/api/phone/usage/icons",
                buildJsonObject {
                    putJsonArray("icons") {
                        icons.forEach { (app, png) ->
                            addJsonObject {
                                put("app", app)
                                put("png", png)
                            }
                        }
                    }
                },
            ),
        )

    /** Asks a supervised child's phone to send its app usage now; false when the server skipped it. */
    suspend fun refreshUsage(supervisedId: String): Boolean =
        call(
            post("/api/supervision/usage/refresh", buildJsonObject { put("supervisedId", supervisedId) }),
            UsageRefresh.serializer(),
        ).asked

    /** An app's icon (PNG) next to a supervised child's usage, or null when the server has none. */
    suspend fun usageIcon(app: String): ByteArray? = withContext(Dispatchers.IO) {
        val path = "/api/supervision/usage/icon?app=" + java.net.URLEncoder.encode(app, "UTF-8")
        http.newCall(get(path)).execute().use { res ->
            if (res.code == 401) {
                onUnauthorized(null)
                throw UnauthorizedException()
            }
            if (res.isSuccessful) res.body.bytes() else null
        }
    }

    /** Turns sharing a supervised child's app usage on or off (off deletes it). */
    suspend fun setUsageSharing(supervisedId: String, share: Boolean): LookAfter =
        call(
            post(
                "/api/supervision/usage",
                buildJsonObject {
                    put("supervisedId", supervisedId)
                    put("share", share)
                },
            ),
            LookAfter.serializer(),
        )

    /** The children and teens I supervise: I may ring them and require their location sharing. */
    suspend fun lookAfter(): LookAfter = call(get("/api/supervision/mine"), LookAfter.serializer())

    /** Makes a supervised child's location sharing required, or stops requiring it. */
    suspend fun setRequiredSharing(supervisedId: String, required: Boolean): LookAfter =
        call(
            post(
                "/api/supervision/sharing",
                buildJsonObject {
                    put("supervisedId", supervisedId)
                    put("required", required)
                },
            ),
            LookAfter.serializer(),
        )

    /** Rings a supervised child's phone loudly, even on silent. 409 = still ringing, or no phone reached. */
    suspend fun ringPhone(targetId: String) =
        callUnit(post("/api/supervision/ring", buildJsonObject { put("targetId", targetId) }))

    // --- Permissions -------------------------------------------------------

    /** The signed-in user's page access (what they may add/edit/delete). */
    suspend fun myAccess(): MyAccess = call(get("/api/permissions/me"), MyAccess.serializer())

    // --- Settings (head + managers; the server checks settings:manage) -------

    /** Every category of every kind, for Settings. */
    suspend fun allCategories(): List<CategoryAdmin> =
        call(get("/api/categories"), ListSerializer(CategoryAdmin.serializer()))

    /** Creates (null [id]) or changes a category. [color] is "#rrggbb". */
    suspend fun saveCategory(id: String?, name: String, kind: String, color: String, icon: String?) {
        val body = buildJsonObject {
            id?.let { put("id", it) }
            put("name", name)
            put("kind", kind)
            put("color", color)
            put("icon", icon)
        }
        callUnit(post(if (id == null) "/api/categories" else "/api/categories/update", body))
    }

    suspend fun deleteCategory(id: String) =
        callUnit(post("/api/categories/delete", buildJsonObject { put("id", id) }))

    /** Creates (null [id]) or renames a floor; answers with every place. */
    suspend fun saveFloor(id: String?, name: String): Places =
        call(
            post("/api/places/floors", buildJsonObject { id?.let { put("id", it) }; put("name", name) }),
            Places.serializer(),
        )

    /** Creates (null [id]) or changes a room; null [floorId] = not on a floor. */
    suspend fun saveRoom(id: String?, name: String, floorId: String?): Places =
        call(
            post(
                "/api/places/rooms",
                buildJsonObject {
                    id?.let { put("id", it) }
                    put("name", name)
                    put("floorId", floorId)
                },
            ),
            Places.serializer(),
        )

    /** [kind] "floor" | "room"; a floor's rooms are kept, a room's tasks move up a level. */
    suspend fun deletePlace(kind: String, id: String): Places =
        call(post("/api/places/delete", buildJsonObject { put("kind", kind); put("id", id) }), Places.serializer())

    /** The full new order of the floors, or of the rooms on one floor. */
    suspend fun reorderPlaces(kind: String, ids: List<String>): Places =
        call(
            post(
                "/api/places/reorder",
                buildJsonObject {
                    put("kind", kind)
                    putJsonArray("ids") { ids.forEach { add(it) } }
                },
            ),
            Places.serializer(),
        )

    suspend fun pointsSettings(): PointsSettings = call(get("/api/points/settings"), PointsSettings.serializer())

    /** Head only. The server rejects an unknown time zone. */
    suspend fun savePointsSettings(minutesPerPoint: Double, timezone: String, weekStartsOn: Int): PointsSettings =
        call(
            post(
                "/api/points/settings",
                buildJsonObject {
                    put("minutesPerPoint", minutesPerPoint)
                    put("timezone", timezone)
                    put("weekStartsOn", weekStartsOn)
                },
            ),
            PointsSettings.serializer(),
        )

    suspend fun integrations(): List<Integration> =
        call(get("/api/integrations"), ListSerializer(Integration.serializer()))

    /** A new Home Assistant token; [CreatedIntegration.token] is never shown again. */
    suspend fun createIntegration(name: String): CreatedIntegration =
        call(post("/api/integrations", buildJsonObject { put("name", name) }), CreatedIntegration.serializer())

    suspend fun revokeIntegration(id: String) =
        callUnit(post("/api/integrations/delete", buildJsonObject { put("id", id) }))

    suspend fun nfcTags(): List<NfcTag> = call(get("/api/nfc/tags"), ListSerializer(NfcTag.serializer()))

    /**
     * Maps a tag id typed by hand (e.g. a Home Assistant tag) to an item, or
     * (represents "location") to the room it opens.
     */
    suspend fun registerNfcTag(tagId: String, label: String, itemId: String?, roomId: String?, represents: String) =
        callUnit(
            post(
                "/api/nfc/tags",
                buildJsonObject {
                    put("tagId", tagId)
                    put("label", label)
                    itemId?.let { put("itemId", it) }
                    roomId?.let { put("roomId", it) }
                    put("represents", represents)
                },
            ),
        )

    suspend fun deleteNfcTag(id: String) =
        callUnit(post("/api/nfc/tags/delete", buildJsonObject { put("id", id) }))

    suspend fun paperlessStatus(): PaperlessStatus =
        call(get("/api/integrations/paperless"), PaperlessStatus.serializer())

    /** Imports now instead of waiting for the 15-minute sweep. */
    suspend fun syncPaperless(): PaperlessStatus =
        call(post("/api/integrations/paperless/sync", JsonObject(emptyMap())), PaperlessStatus.serializer())

    /**
     * Head only: connect or change the household's own Paperless. A null [token]
     * keeps the saved one. The server tries the connection before saving.
     */
    suspend fun savePaperless(url: String, publicUrl: String, token: String?): PaperlessStatus =
        call(
            putRequest(
                "/api/integrations/paperless",
                buildJsonObject {
                    put("url", url)
                    put("publicUrl", publicUrl)
                    put("token", token)
                },
            ),
            PaperlessStatus.serializer(),
        )

    suspend fun disconnectPaperless(): PaperlessStatus =
        call(post("/api/integrations/paperless/disconnect", JsonObject(emptyMap())), PaperlessStatus.serializer())

    /** Head only: streams the household export (plain JSON, not the envelope) into [out]. */
    suspend fun downloadExport(out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build()
        client.newCall(get("/api/household/export")).execute().use { res ->
            if (res.code == 401) {
                onUnauthorized(null)
                throw UnauthorizedException()
            }
            if (!res.isSuccessful) throw ApiException("Export failed (${res.code})")
            res.body.byteStream().use { it.copyTo(out) }
        }
    }

    // --- Plumbing ------------------------------------------------------------

    private fun get(path: String) = Request.Builder().url(baseUrl() + path).get().build()

    private fun post(path: String, body: JsonElement) =
        Request.Builder().url(baseUrl() + path).post(body.toString().toRequestBody(jsonType)).build()

    private fun putRequest(path: String, body: JsonElement) =
        Request.Builder().url(baseUrl() + path).put(body.toString().toRequestBody(jsonType)).build()

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
                    onUnauthorized(null)
                    throw UnauthorizedException()
                }
                val text = res.body.string()
                val envelope = runCatching {
                    json.decodeFromString(Envelope.serializer(serializer), text)
                }.getOrElse { throw ApiException("Unexpected response from server (${res.code})") }
                if (!envelope.ok) {
                    // The server admin turned this household off: nothing works
                    // until it's back on, so sign out and say why.
                    val code = (envelope.error?.details as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
                    if (res.code == 403 && code == "household_disabled") {
                        onUnauthorized(envelope.error?.message)
                        throw UnauthorizedException()
                    }
                    throw ApiException(envelope.error?.message ?: "Request failed (${res.code})", res.code)
                }
                envelope
            }
        }
}
