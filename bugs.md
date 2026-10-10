# Bugs found (review pass, 2026-10-09 night)

## Status after the fix pass (2026-10-10 night)

Everything below is **fixed (uncommitted)** unless listed here. The tests
added with the fixes:
- `recurrenceService.test.ts`: next occurrence, DST, local weekdays,
  every-other-week
- `format.test.ts`: overdue and due-today labels
- `taskReadyService.test.ts`
- `HouseholdRemindersTest`

| Item | Status |
| --- | --- |
| W4 | Fixed for task assignee and categories, and shopping/inventory categories. Bill assignees were already checked. |
| W8 | **Fixed, including the evening label flip.** Every page now gets the household time zone (`HouseholdZoneProvider` in the app layout), and every client-side date label uses it, so the server render and the browser always produce the same text. That covers tasks, bills, bill detail, requests, calendar (which day an event falls on, the Today highlight, the default week, the starting month), profile, settings and Paperless. "5m ago" labels use `<RelativeTime>`, which refreshes every minute. A production build passes. |
| W12, W13 | Fixed by rewriting the recurrence engine to use the household's local time, the start date and every-N weeks/months. |
| W21 ("HTTPS only" is server-wide) | **Resolved by multi-household Phase 1 (2026-10-10).** It's now a server-wide setting that only the server admin can change (Server page), which is correct, since it's checked before anyone signs in. |
| Build warning (new note) | `next build` warns that `src/server/settings.ts` (via `instrumentation.ts`) makes Turbopack trace the whole project. Not user-facing and not new tonight, but worth tidying with a `turbopackIgnore` comment on its file read. |
| Build-time DB query (new, fixed) | `/login` and `/setup` queried the database while Next prerendered them during `next build`. Both now mark themselves per-request first, so the build never touches the database. |
| W27 | Fixed as part of the bell change: requests, events and settings now link. |
| A7 | Fixed: scan hand-offs to MainActivity now carry a per-install secret. |
| Not reviewed | The calendar, members, settings, auth and command-palette screens on the web. The reviewer ran out of time there. |

---

**How this was done:** a read-only code review of the website (OurHomeWeb) and
the app, split into four areas: task/points services, other web services, web
UI, and the Android app. **Nothing here is fixed.** The only code changes
tonight are the two features in `what-i-did.md`.

I didn't click through the live site or app. The emulator came up signed out,
and I avoid touching live data. So everything below comes from reading code.

- **✔ verified:** I re-read the code myself and confirmed it.
- **reported:** a reviewer traced it, but I didn't re-check it line by line.

Web paths are under `OurHomeWeb/src/`; app paths are under
`OurHomeApp/src/main/java/com/eosoclub/ourhome/`.

---

## 🔴 High

### W1. Completing a daily/monthly task before its due time doesn't advance it, so it can be paid again ✔ verified
- **Where:** `server/services/taskService.ts` completeTask → `computeNextRunAt(rule, now)`; `recurrenceService.ts` `nextAligned` / `nextByMonthday`.
- **What:** the next date is computed from *now*. Picked dates are stored at 12:00 local, so completing a daily task at 09:00 gives "today 12:00" again. The task stays pending with its steps unchecked.
- **Effect:** completing it again pays all its points again, until noon.
- **Note:** this also passes a **rotating** task's turn on each completion.
- **Fix:** compute from `max(now, existing.dueDate)`.

### W2. Paying a recurring bill before its due date doesn't advance it ✔ verified
- **Where:** `server/services/billService.ts` (~line 364), same root cause as W1.
- **What:** a monthly bill due the 15th, paid on the 9th, stays due the 15th and unpaid.
- **Effect:** reminders keep firing, and it invites a double payment.
- **Fix:** same as W1, ideally inside `computeNextRunAt`'s callers.

### W3. Role changes and removals take up to 5 minutes to apply ✔ verified
- **Where:** `server/auth/auth.ts` (`cookieCache maxAge 5*60`); `server/api/http.ts` checks `ctx.user.role` from `getSession` (cached).
- **What:**
  - A demoted manager keeps `members:manage` for up to 5 minutes, so they can still reset kids' passwords.
  - A removed member keeps API access for up to 5 minutes, even though their session rows were deleted.
  - After a headship transfer, the old head can transfer it back during that window.
- **Fix:**
  - Re-read role and householdId from the DB in `withAuth` (it already re-reads the access grid), or
  - call `getSession({ query: { disableCookieCache: true } })` for permission checks, or
  - revoke sessions on role change.

### W4. Task assignee and category from another household are accepted ✔ verified
- **Where:** `taskService.ts` createTask/updateTask. Also `categoryId` in `shoppingService.ts` and `inventoryService.ts` (reported).
- **What:**
  - Ids are only checked to be cuids. A task can be assigned to a user in another household, who then gets the overdue bell, email and push.
  - A foreign category leaks its name and colour.
  - Requests and inventory already validate assignees; tasks don't.
  - The new rotation list *is* validated, but a plain `assigneeId` isn't.
- **Impact:** only matters for multi-household installs. Your server is one household.

### W5. A cycle rollover un-archives archived tasks ✔ verified
- **Where:** `taskService.ts` `rollOne` always writes `status: 'pending'`, and archiving doesn't clear `cycleEndsAt`.
- **What:** an archived cycling task comes back at the next cycle boundary, logs "missed" rows, and sends overdue reminders.
- **Fix:** skip archived tasks in `rollTaskCycles`, or clear the cycle fields when a task is archived.

### A1. App: the previous user's screens and permissions carry over after sign-out ✔ verified
- **Where:** `ui/HomeScreen.kt` `viewModel { … }`, with every ViewModel scoped to the activity. Sign-out doesn't recreate the activity.
- **What:** on a shared phone, the head signs out and a teen signs in. `AccessViewModel` was built with the head's role, so the teen sees head controls until `/api/permissions/me` answers. If that call fails, they keep them. Other tabs show the head's data until they refresh.
- **Impact:** the server still enforces permissions, so this is display only. It's still bad on a family phone.
- **Fix:** key the HomeScreen subtree's `ViewModelStoreOwner` on user id, or clear the store on sign-out.

## 🟠 Medium

### A2. App: sign-out leaves some alerts in the shade ✔ verified
- **Where:** `notifications/RequestReminders.kt` `cancel()` clears only ids 1001 and 1005.
- **What:**
  - Bug-report (1002), deadline (1003) and app-update (1004) notifications stay after sign-out, including when the session expires.
  - The "already alerted" memory carries over to the next account.

### W6. Web: dialogs steal focus while typing (e.g. Report a bug) ✔ verified
- **Where:** `components/ui/dialog.tsx:90`. The focus effect depends on `[open, onClose]`, and callers pass a new inline `onClose` on every render.
- **What:** in Report a bug, typing in Details re-renders the dialog and focus jumps back to the title field. Payment and bill dialogs do the same whenever the parent re-renders.
- **Fix:** keep `onClose` in a ref and depend on `open` only.

### W7. Web: bills and tasks show "Overdue" from noon on their due day ✔ verified
- **Where:** `lib/format.ts` `isOverdue` compares with `Date.now()`, but picked dates are stored at 12:00.
- **What:** from noon, a bill moves to the Overdue section with a red badge while its text still says "Due today". Requests do this correctly with `startOfToday()`.

### W8. Web dashboard dates use the server's timezone (UTC) — reported
- **Where:** `app/(app)/dashboard/page.tsx` (~lines 190, 312–333). This is a server component calling `toLocaleString` without `timeZone`, and the container has no `TZ` set.
- **What:**
  - A 7 pm Central event shows as 12:00 AM the next day.
  - After 7 pm, "Due today" and "Overdue" labels are off by a day.
- **Fix:** `data.timezone` is already available there.
- **Related:** client components that call `formatDueDate` during server rendering can hit hydration mismatches in the evening (medium confidence).

### W9. An editor can reopen a completed task and get paid twice — reported
- **Where:** `updateTask` accepts `status` freely.
- **What:** complete a task (paid), set it back to pending, then complete it again. All steps are still ticked, so it pays again.

### W10. Turning cycles off on a "done this cycle" task leaves it completed forever — reported (consistent with what I read)
- **Where:** `updateTask` clears the cycle fields but not `status: 'completed'`, so the task never reopens.

### W11. Undo can overwrite edits made after the completion — reported
- **What:** if the head changes the due date or recurrence within 10 minutes of someone completing, and that person then presses Undo, the snapshot puts the old values back.
- **Note:** this also covers the new rotation assignee, which is restored the same way.

### W12. Weekday recurrence uses UTC days and ignores the rule's timezone — reported
- **Where:** `recurrenceService.ts` `nextByWeekday` uses `getUTCDay()`.
- **What:** a Monday 19:00 Central task is Tuesday in UTC. Daily rules step a fixed 24 hours, so they drift by an hour across DST.

### W13. Recurring calendar events appear before their start, and "every N weeks" acts as weekly — reported
- **Where:** `calendarService.ts` with `nextByWeekday` / `nextByMonthday`, which ignore `anchorDate` and `interval`.

### W14. Stock adjustments can be lost under concurrency — reported
- **Where:** `inventoryService.ts` adjust does read-modify-write with no atomic increment.
- **What:** two phones scanning at once, or Home Assistant plus the app, can lose one ±1.

### W15. Forced password change can be skipped — reported
- **Where:** `POST /api/profile/clear-password-flag` clears the flag without proof the password changed.
- **What:** a member given a temporary password can keep it.

### W16. Editing a bill's amount doesn't recompute paid/unpaid — reported
- **What:** a $100 bill paid in full, then edited to $120, stays "paid".

### W17. Step reset race can double-pay or wipe a fresh tick — reported
- **Where:** `resetDueSubtasks` reads, then runs `updateMany` without re-checking `doneAt`.
- **Fix:** add `doneAt` to the update's where clause.

### W18. Unticking and re-ticking a step that was paid at once loses its points — reported
- **Where:** `setStepDone` voids the award and clears `autoResetAt`, so the re-tick pays nothing.

### A3. App: a slow NFC lookup can reopen the scan sheet or show the wrong tag — reported
- **Where:** `ui/ScanSheet.kt` `open()` doesn't check that the result is for the tag still being shown.
- **What:** scanning A then B can show A's item, and ±1 then changes A.

### A4. App: network calls run inside `MutableStateFlow.update {}` — reported
- **Where:** Tasks, Inventory, Requests, Shopping, Bills, Dashboard, Profile and Scan history.
- **What:** `update` re-runs its lambda when state changes, so a refresh re-sends the GET each time you tick something during it. Cancellation is also caught as an error.

## 🟡 Low / minor

- **W19 One household's sweep failure blocks the others** (reported). `reminderSweep.ts` has no try/catch per household.
- **W20 Contact-form messages are global** (reported). Every household's managers can read every submission. Head-only, or an install admin, would be safer.
- **W21 "HTTPS only" is server-wide** (reported). `accessService.httpAllowed()` uses `findFirst` across all households.
- **W22 Email change needs no password or verification** (reported). A stolen session can change the email, then use forgot-password.
- **W23 Bill editor has no "Every N days" option** (reported). An `interval` bill opens showing "Does not repeat" but saves interval.
- **W24 Web inventory ± flickers on fast taps** (reported). There's no in-flight guard like the app's.
- **W25 Shopping undo-delete** (reported). It re-creates the item unpurchased, and fails for users who can delete but not add.
- **W26 Rename list with an unchanged name does nothing silently** (reported).
- **W27 Notifications for requests/events have no link** (reported, low confidence). `subjectHref` lacks those cases.
- **W28 Moving one step by `position` can leave duplicate positions** (reported). Only older clients hit this.
- **W29 Ticking steps on archived tasks queues points that never pay** (reported).
- **W30 Two simultaneous completes return a 500** instead of "already completed" (reported). Only one pays, which is correct.
- **W31 Duplicate step ids in an editor save mis-assign points** (reported). Only crafted requests hit this.
- **A5 Task editor checklist rows have no Compose `key`** (reported, `TaskEditor.kt` checklist loop). Focused number text can follow the wrong step after a move or remove.
- **A6 Capped "already alerted" sets drop random entries** (reported, `DeadlineReminders.kt`, `BugReportAlerts.kt`). `takeLast` runs over a HashSet, so a past alert can fire again.
- **A7 MainActivity accepts `scanned_tag` / `open_tab` extras from any app** (reported). The phone must be unlocked, so this is not a lock-screen issue.
- **A8 A failed step toggle reverts to the original value** even if a later tap changed it (reported, `TasksScreen.kt`).

## Checked and fine
- **Lock-screen rule (the app):**
  - every notifier goes through `lockScreenSafe`
  - every data-changing action sets `setAuthenticationRequired(true)`
  - no `showWhenLocked` / `turnScreenOn`
  - `QuickScanReceiver` isn't exported
- App request bodies match the web's zod schemas, and `defaultAccess` matches `BUILTIN_ROLE_ACCESS`.
- Member management guards hold: a manager can't promote themselves, demote the head or delete the head (apart from W3's 5-minute window). Better Auth's `role` / `householdId` fields are `input: false`.
- Task/points routes check the right permissions and are scoped to the household. Guests only complete their own tasks.
- The link-preview fetch is SSRF-guarded, Turnstile fails closed, and setup is limited to the home network.

## Suggested order
1. Fix W1 and W2 together (same root cause, and W1 pays points twice).
2. Then W3.
3. Then A1 and A2 (shared phone).
4. Then W5–W7.

Most of the rest is small.
