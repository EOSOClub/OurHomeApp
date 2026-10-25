package com.eosoclub.ourhome.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.eosoclub.ourhome.R
import com.eosoclub.ourhome.data.AppLimitRule
import com.eosoclub.ourhome.data.AppScheduleRule
import com.eosoclub.ourhome.data.ExtraTimeDay
import com.eosoclub.ourhome.data.LimitsReached
import com.eosoclub.ourhome.data.PointsRule
import com.eosoclub.ourhome.data.extraOn
import com.eosoclub.ourhome.data.withExtra
import com.eosoclub.ourhome.data.limitFor
import com.eosoclub.ourhome.data.limitPlan
import com.eosoclub.ourhome.data.limitReason
import com.eosoclub.ourhome.data.limitTitle
import com.eosoclub.ourhome.data.limitWarning
import com.eosoclub.ourhome.data.scheduleFor
import com.eosoclub.ourhome.data.schedulePlan
import com.eosoclub.ourhome.data.scheduleReason
import com.eosoclub.ourhome.data.scheduleTitle
import com.eosoclub.ourhome.data.scheduleWarning
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Daily limits and schedules a supervising adult set on this phone (web
 * appLimitService, appScheduleService). Both come with each check-in's answer
 * ([PhoneCheckIn]) and are kept here, so they work offline too. "Keep Our
 * Home running" ([CloseGuardService]) says when an app opens; this looks at
 * the app in the active window and covers it with the blocked screen while a
 * schedule is on (unless the schedule still allows it), or once its daily
 * limit is used up, until midnight. Limits count today's minutes with Usage
 * access ([UsageReport.todayMillis]); schedules need nothing but the clock.
 * The child is warned 5 minutes before a limit runs out or a schedule starts,
 * and it looks again right when a warning, an end or a start is due, so an app
 * left open is covered on time. Each limit used up is said in the next
 * check-in ([reachedToday]), so the adults are told. Extra minutes for today
 * (bought with points or given by an adult) make every limit that much longer;
 * while a point can buy time, the screen for a used-up limit offers it. Only
 * while an adult looks after this member; never the apps this phone always
 * keeps open.
 */
object AppLimiter {
    private const val TAG = "AppLimiter"
    private const val PREFS = "app_limiter"
    private const val KEY_LIMITS = "limits"
    private const val KEY_SCHEDULES = "schedules"
    private const val KEY_EXTRA = "extra"
    private const val KEY_POINTS_RULE = "points_rule"
    private const val KEY_DAY = "day"
    private const val KEY_WARNED = "warned"
    private const val KEY_REACHED = "reached"
    private const val CHANNEL_ID = "time_limits"
    private const val NOTIFICATION_ID = 1012

    /**
     * Window changes within this long are looked at once, after it: by then the
     * opening app's window is the active one (at its first change it often isn't yet).
     */
    private const val SETTLE_MS = 300L

    /** While Android's own screens cover the app (the shade, the lock screen), look again this often. */
    private const val SYSTEM_UI_RECHECK_MS = 15_000L
    private const val SYSTEM_UI = "com.android.systemui"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(AppLimitRule.serializer())
    private val scheduleSerializer = ListSerializer(AppScheduleRule.serializer())
    private val extraSerializer = ListSerializer(ExtraTimeDay.serializer())

    @Volatile
    private var cached: List<AppLimitRule>? = null

    @Volatile
    private var cachedSchedules: List<AppScheduleRule>? = null

    @Volatile
    private var cachedExtra: List<ExtraTimeDay>? = null

    /** Counting reads a day of Usage access events: off the main thread, one at a time. */
    private val worker: Handler by lazy { Handler(HandlerThread("app-limits").apply { start() }.looper) }
    private val recheck = Runnable { appContext?.let { run(it) } }

    @Volatile
    private var settling = false
    private val settled = Runnable {
        settling = false
        appContext?.let { run(it) }
    }

    @Volatile
    private var appContext: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The limits as last heard from the server. */
    fun limits(context: Context): List<AppLimitRule> = cached ?: run {
        val stored = prefs(context).getString(KEY_LIMITS, null)
        val list = stored?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
        cached = list
        list
    }

    /** The schedules as last heard from the server. */
    fun schedules(context: Context): List<AppScheduleRule> = cachedSchedules ?: run {
        val stored = prefs(context).getString(KEY_SCHEDULES, null)
        val list = stored?.let { runCatching { json.decodeFromString(scheduleSerializer, it) }.getOrNull() }.orEmpty()
        cachedSchedules = list
        list
    }

    /** Extra minutes on the limits by day, as last heard from the server. */
    private fun extra(context: Context): List<ExtraTimeDay> = cachedExtra ?: run {
        val stored = prefs(context).getString(KEY_EXTRA, null)
        val list = stored?.let { runCatching { json.decodeFromString(extraSerializer, it) }.getOrNull() }.orEmpty()
        cachedExtra = list
        list
    }

    /** What a point buys, as last heard; null when they can't buy time. */
    fun pointsRule(context: Context): PointsRule? =
        prefs(context).getString(KEY_POINTS_RULE, null)?.let { runCatching { json.decodeFromString(PointsRule.serializer(), it) }.getOrNull() }

    /**
     * The extra minutes and points rule from a check-in's answer. More minutes
     * are acted on at once: a covered app may have time again.
     */
    fun saveExtra(context: Context, extra: List<ExtraTimeDay>, rule: PointsRule?) {
        val ruleJson = rule?.let { json.encodeToString(PointsRule.serializer(), it) }
        if (extra == extra(context) && ruleJson == prefs(context).getString(KEY_POINTS_RULE, null)) return
        prefs(context).edit {
            putString(KEY_EXTRA, json.encodeToString(extraSerializer, extra))
            if (ruleJson == null) remove(KEY_POINTS_RULE) else putString(KEY_POINTS_RULE, ruleJson)
        }
        cachedExtra = extra
        Log.i(TAG, "extra time: ${extra.sumOf { it.minutes }} min over ${extra.size} day(s)")
        check(context)
    }

    /** The limits from a check-in's answer. A change is acted on at once (a tighter limit may be up already). */
    fun save(context: Context, limits: List<AppLimitRule>) {
        if (limits == limits(context)) return
        prefs(context).edit { putString(KEY_LIMITS, json.encodeToString(serializer, limits)) }
        cached = limits
        Log.i(TAG, "${limits.size} daily limit(s)")
        check(context)
    }

    /** The schedules from a check-in's answer. A change is acted on at once (one may be on already). */
    fun saveSchedules(context: Context, schedules: List<AppScheduleRule>) {
        if (schedules == schedules(context)) return
        prefs(context).edit { putString(KEY_SCHEDULES, json.encodeToString(scheduleSerializer, schedules)) }
        cachedSchedules = schedules
        Log.i(TAG, "${schedules.size} schedule(s)")
        check(context)
    }

    /** Signed out: no limits, schedules, warnings or reports stay for whoever signs in next. */
    fun signedOut(context: Context) {
        prefs(context).edit { clear() }
        cached = emptyList()
        cachedSchedules = emptyList()
        cachedExtra = emptyList()
        worker.removeCallbacks(recheck)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * A window changed ([app], from Keep Our Home running): checks the app in
     * front against its limits and the schedules, once for a burst of changes.
     */
    fun opened(context: Context, app: String?) {
        if (app == null || (limits(context).isEmpty() && schedules(context).isEmpty())) return
        appContext = context.applicationContext
        if (settling) return
        settling = true
        worker.postDelayed(settled, SETTLE_MS)
    }

    /** Looks at the app on screen now (the limits or schedules changed, or the screen came on). */
    fun check(context: Context) {
        val app = context.applicationContext
        appContext = app
        worker.post { run(app) }
    }

    /** The limits this phone covered an app for today, for the check-in; null when none. */
    fun reachedToday(context: Context): LimitsReached? {
        val prefs = prefs(context)
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DAY, null) != today) return null
        val apps = prefs.getStringSet(KEY_REACHED, null)?.toList().orEmpty()
        return if (apps.isEmpty()) null else LimitsReached(today, apps.sorted())
    }

    private fun run(context: Context) {
        worker.removeCallbacks(recheck)
        val limits = limits(context)
        val schedules = schedules(context)
        if ((limits.isEmpty() && schedules.isEmpty()) || !StayConnected.isRequired(context)) return
        // The screen is off: the next unlock looks again (CloseGuardService).
        if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        val service = CloseGuard.service ?: return
        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        val is24 = DateFormat.is24HourFormat(context)
        val lookAgain = mutableListOf<Long>()
        try {
            // Warned before a schedule starts, whatever is on screen; looked at again when it starts.
            if (schedules.isNotEmpty()) {
                val plan = schedulePlan(now, schedules, todaySet(context, today, KEY_WARNED))
                plan.warn?.let { start ->
                    addToday(context, today, KEY_WARNED, start.warnKey)
                    warn(context, scheduleWarning(start.rule, is24))
                }
                plan.checkAgainInMs?.let(lookAgain::add)
            }
            // The active window, not the one that just changed: a keyboard isn't the app being used.
            val app = service.activeApp() ?: return
            if (app == SYSTEM_UI) {
                lookAgain += SYSTEM_UI_RECHECK_MS
                return
            }
            // Only apps someone can open, as their usage counts them (not a system dialog).
            if (context.packageManager.getLaunchIntentForPackage(app) == null) return
            val alwaysAllowed = AppBlocker.alwaysAllowed(context)
            scheduleFor(app, now, schedules, alwaysAllowed)?.let { state ->
                Log.i(TAG, "a schedule is on; covering the app")
                service.coverLimit(app, scheduleTitle(state.rule, is24), scheduleReason(state.rule, is24))
                return
            }
            if (limits.isEmpty() || !UsageReport.hasAccess(context)) return
            val used = runCatching { UsageReport.todayMillis(context) }.getOrElse {
                Log.w(TAG, "couldn't count today's app time", it)
                return
            }
            // Today's extra minutes make every limit longer.
            val extra = extraOn(extra(context), today)
            val state = limitFor(app, used, withExtra(limits, extra), alwaysAllowed) ?: return
            val rule = limits.first { it.app == state.rule.app }
            // Warned per limit and length (extra time included): a longer limit later in the day warns again.
            val warnKey = "${state.rule.app}@${state.rule.minutes}"
            val plan = limitPlan(state, warned = warnKey in todaySet(context, today, KEY_WARNED))
            if (plan.cover) {
                Log.i(TAG, "a daily limit is used up; covering the app")
                service.coverLimit(app, limitTitle(rule), limitReason(rule, extra), moreTime = pointsRule(context) != null)
                // The first time today: the adults are told with a check-in.
                if (addToday(context, today, KEY_REACHED, state.rule.app)) PhoneCheckIn.sendNow(context)
            }
            if (plan.warn) {
                addToday(context, today, KEY_WARNED, warnKey)
                warn(context, limitWarning(state.rule))
            }
            plan.checkAgainInMs?.let(lookAgain::add)
        } finally {
            // A second past the moment, so the time is surely gone (or the schedule surely on).
            lookAgain.minOrNull()?.let { worker.postDelayed(recheck, it + 1_000L) }
        }
    }

    /** Today's warned or reached entries; another day's are forgotten. */
    private fun todaySet(context: Context, today: String, key: String): Set<String> {
        val prefs = prefs(context)
        if (prefs.getString(KEY_DAY, null) != today) return emptySet()
        return prefs.getStringSet(key, null).orEmpty()
    }

    /** Adds [entry] to today's [key] set; false when it was there already. */
    private fun addToday(context: Context, today: String, key: String, entry: String): Boolean {
        val prefs = prefs(context)
        val fresh = prefs.getString(KEY_DAY, null) != today
        val set = if (fresh) emptySet() else prefs.getStringSet(key, null).orEmpty()
        if (entry in set) return false
        prefs.edit {
            if (fresh) {
                remove(KEY_WARNED)
                remove(KEY_REACHED)
                putString(KEY_DAY, today)
            }
            putStringSet(key, set + entry)
        }
        return true
    }

    /** "5 minutes left on YouTube today" or "Bedtime starts at 9:00 PM" ([warning]: title and text), as a heads-up notification. */
    private fun warn(context: Context, warning: Pair<String, String>) {
        if (!RequestReminders.canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Time limits", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A warning 5 minutes before a daily limit runs out or a schedule starts"
            },
        )
        val (title, text) = warning
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .lockScreenSafe(context, CHANNEL_ID, "Time limit")
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission revoked", e)
        }
    }
}
