package com.eosoclub.ourhome.data

import kotlinx.serialization.Serializable

// Set up a child (ui/AddChildScreen, web components/members/add-child-wizard.tsx):
// the rules of the wizard, of a supervisor's view of how far a child's phone
// got, and of a setup link opening this app, kept apart from the screens so
// they're tested.

/**
 * POST /api/members/setup-link (web SetupLinkDTO): a link, shown as a QR code,
 * that opens a page on the child's phone to download the app without signing
 * in. [qr] rows: '1' dark, '0' light, no quiet zone.
 */
@Serializable
data class SetupLink(val url: String, val expiresAt: String, val qr: SetupLinkQr)

@Serializable
data class SetupLinkQr(val size: Int, val rows: List<String>)

/**
 * What a setup link's Open Our Home button (ourhome://setup?username=&server=)
 * fills in on the sign-in screen. [server]: only when this app has none yet;
 * a link never changes a server already set, so a link from elsewhere can't
 * send a password to another server. [note]: why the link's server wasn't used.
 */
data class SetupPrefill(val username: String, val server: String?, val note: String?)

/** Reads a setup link's username and server against [currentServer]; null when the username isn't one. */
fun setupPrefill(username: String?, server: String?, currentServer: String): SetupPrefill? {
    val name = username?.trim().orEmpty()
    if (name.isEmpty() || usernameProblem(name) != null) return null
    val linkServer = server?.trim()?.trimEnd('/')
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val current = currentServer.trim().trimEnd('/')
    return when {
        linkServer == null -> SetupPrefill(name, null, null)
        current.isEmpty() -> SetupPrefill(name, linkServer, null)
        linkServer.equals(current, ignoreCase = true) -> SetupPrefill(name, null, null)
        else -> SetupPrefill(
            name,
            null,
            "This link is for $linkServer, but this app signs in to $current. Change the server below only if " +
                "the adult setting up your phone says so.",
        )
    }
}

/** The wizard's steps, in order. Nothing is added until [Phone]'s button. */
enum class ChildSetupStep(val label: String) {
    Account("Account"),
    Supervisors("Supervisors"),
    Phone("Their phone"),
    Install("Install"),
    Progress("Progress"),
}

/**
 * What a supervising adult chose for the new child's phone, sent with the
 * account (web createMemberSchema setup). The server takes it only from one
 * of their supervisors.
 */
data class ChildSetupChoices(
    val locationRequired: Boolean = false,
    val shareAppUsage: Boolean = false,
    val rules: PhoneRules = PhoneRules(),
)

/** The step holding the first field the server named, to go back to; null: none of them. */
fun stepForFieldErrors(fields: Set<String>): ChildSetupStep? = when {
    "supervisorIds" in fields -> ChildSetupStep.Supervisors
    "setup" in fields -> ChildSetupStep.Phone
    fields.isNotEmpty() -> ChildSetupStep.Account
    else -> null
}

/** Every step done, from their own password to the last required step. False from an older server. */
fun LookedAfter.setupComplete(): Boolean {
    val setup = setup ?: return false
    return setup.passwordChosen && phone != null && setup.steps.all { it.done == true }
}

/** A step's name as their phone shows it; the server's key for one this app doesn't know. */
fun setupStepTitle(key: String): String = SetupStep.entries.firstOrNull { it.key == key }?.title ?: key

/**
 * Under a step that isn't done: their app is too old to report it, or Prevent
 * uninstall, which needs the parent PIN. Null otherwise.
 */
fun setupStepNote(step: LookedAfterSetupStep, hasPhone: Boolean): String? = when {
    step.done == null && hasPhone -> "Their app is too old to say. Update OurHome on their phone."
    step.step == SetupStep.PreventUninstall.key && step.done != true -> "A parent does this one: it needs the parent PIN."
    else -> null
}

/** "Set up this phone: 3 of 5 done", for a folded row; null from an older server. */
fun LookedAfter.setupSummary(): String? {
    val steps = setup?.steps ?: return null
    return when {
        setupComplete() -> "Set up this phone: all ${steps.size} done"
        phone != null -> "Set up this phone: ${steps.count { it.done == true }} of ${steps.size} done"
        else -> "Set up this phone: not started"
    }
}

/** "Alex", "Alex or Sam", "Alex, Sam or Jo". */
fun joinNames(names: List<String>, last: String): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " $last " + names.last()
}
