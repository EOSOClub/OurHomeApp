package com.eosoclub.ourhome.data

/**
 * Set up this phone (ui/PhoneSetupScreen): the steps a supervised child's
 * phone needs, in the order they have to be done. The order matters:
 * - Location asks "While using" before "Allow all the time" (Android's rule).
 * - Keep Our Home running comes last: once it's on, App info, Device admin
 *   and Accessibility pages close on this phone, so nothing that needs them
 *   (Allow restricted settings is in App info) can be done after it.
 * - The web filter before both guards. (The guard only closes Private DNS
 *   once it's set to the filter, so a filter chosen later can still be set.)
 * [key]: the web's name for it (lib/phoneHealth.ts PHONE_SETUP_STEPS), as a
 * supervisor's setup progress names it.
 */
enum class SetupStep(val key: String, val title: String) {
    Notifications("notifications", "Allow notifications"),
    Location("location", "Allow location all the time"),
    Battery("batteryUnrestricted", "Allow unrestricted battery"),
    UsageAccess("usageAccess", "Allow usage access"),
    WebFilter("webFilter", "Turn on the web filter"),
    PreventUninstall("preventUninstall", "Prevent uninstall"),
    KeepRunning("keepRunning", "Keep Our Home running"),
}

/** What this phone has on right now, as the checklist reads it. */
data class PhoneSetupChecks(
    val notifications: Boolean,
    /** Precise location allowed, and allowed all the time. */
    val locationAlways: Boolean,
    val batteryUnrestricted: Boolean,
    val usageAccess: Boolean,
    /** Device admin active with a parent PIN set. */
    val preventUninstall: Boolean,
    val keepRunning: Boolean,
    /** Private DNS is set to the chosen web filter's hostname (false while none is chosen). */
    val webFilter: Boolean = false,
)

data class SetupStepState(val step: SetupStep, val done: Boolean)

/** The steps [required] asks of this phone, in order, each with whether it's done. */
fun setupSteps(required: PhoneSetupRequired, checks: PhoneSetupChecks): List<SetupStepState> = buildList {
    if (required.notifications) add(SetupStepState(SetupStep.Notifications, checks.notifications))
    if (required.location) add(SetupStepState(SetupStep.Location, checks.locationAlways))
    if (required.batteryUnrestricted) add(SetupStepState(SetupStep.Battery, checks.batteryUnrestricted))
    if (required.usageAccess) add(SetupStepState(SetupStep.UsageAccess, checks.usageAccess))
    if (required.webFilter) add(SetupStepState(SetupStep.WebFilter, checks.webFilter))
    if (required.preventUninstall) add(SetupStepState(SetupStep.PreventUninstall, checks.preventUninstall))
    if (required.keepRunning) add(SetupStepState(SetupStep.KeepRunning, checks.keepRunning))
}

/**
 * Whether Android may refuse [step] as a restricted setting, so its card says
 * how to allow it (App info, ⋮, Allow restricted settings; the menu item only
 * appears after Android has refused once). Only for an installation Android
 * restricts ([restrictedInstall]: from a file, Android 13 or later):
 * Accessibility from Android 13, Usage access and Device admin from 15.
 */
fun mayBeRestricted(step: SetupStep, restrictedInstall: Boolean, sdk: Int): Boolean = restrictedInstall && when (step) {
    SetupStep.KeepRunning -> sdk >= 33
    SetupStep.UsageAccess, SetupStep.PreventUninstall -> sdk >= 35
    else -> false
}
