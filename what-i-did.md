# What I did overnight

## Round 2 (night of 2026-10-10): your follow-ups + bug fixes

Still **nothing committed, pushed or deployed**. Both repos build and pass
their checks:
- **Web:** typecheck clean, 251/251 tests, eslint clean.
- **App:** `assembleDebug` + unit tests pass, and the merged manifest is still
  free of showWhenLocked/turnScreenOn.

Not clicked through live; please try the items under "Worth a manual test"
after you deploy.

### Your three asks
1. **"Your turn" notifications.** A new notification type, `task_ready`, goes
   to a task's assignee when the task becomes theirs to do:
   - someone creates it for them, or reassigns it to them
   - a rotation passes it to them
   - a new cycle opens
   - without cycles, someone else completed it and the next occurrence is open

   Each one lands in their bell and on their phone (push, then the app's
   household-reminder notification, still lock-screen safe).
   - There's at most one per task. Completing, archiving or deleting the task
     removes it.
   - You aren't notified about something you did yourself: assigning yourself,
     or completing a task and getting it straight back.
   - **Decision:** not emailed, because daily tasks would flood inboxes. Easy
     to add.
2. **Pronouns removed** from profiles everywhere: database field, API, website
   and app.
3. **The bell goes to the thing and then clears.**
   - **Website:** the notifications page now shows **unread** by default. Clicking
     one marks it read and opens what it's about. Tasks open on the task itself
     (`/tasks?task=<id>` expands and highlights it); bills, stock, requests,
     calendar and settings links also work. Notifications with no page clear
     when clicked. The "Read" and "All" filters keep the history.
   - **App:** the bell lists unread only. Tapping one marks it read and opens
     its tab. The app doesn't scroll to the exact task within the tab yet.

### Bugs fixed (details and anything left in `bugs.md`)
Fixed W1–W3, W5–W20, W22–W31 and A1–A8.

Decisions I made along the way:
- **Overdue rule:** a due date picked without a time (stored at 12:00) is due
  all day, and is overdue from the next day. A task given an explicit due time
  is overdue once that time passes. Pages, the dashboard counts and bell
  reminders now all agree.
- **Recurrence follows your household time zone** (Settings → Task points). A
  noon due date stays noon across daylight saving, and "Monday" means your
  Monday. This changes when existing evening-time weekly tasks land. They now
  land on the right local day, which was the bug.
- **Completed stays completed in the editor:** an edit can't mark a task
  completed (use Complete, which pays) or reopen a completed one (use Undo,
  which takes the points back).
- **Changing your own email now asks for your current password**, on web and
  app, because password resets go to that address.
- **Contact-form messages are now Head-only** (managers no longer see them).
- **Undo is refused** if someone edited the task after it was completed, so it
  can't silently overwrite their edit.
- **Archiving a task that runs in cycles pauses its cycles.** Un-archiving
  starts a fresh cycle, with no backlog of "missed" rows.

### Worth a manual test after deploying
- Assign a task to someone else, and check they get a "Your turn"
  notification. Then complete a rotating task and check the next person gets
  one.
- Click a bell notification for a task: it should open, highlight the task,
  and drop off the unread list.
- Report a bug on the website and type in Details. Focus should stay put now.
- Sign out on the phone and sign in as someone else. Nothing from the first
  account should show, and no old alerts should stay in the shade.
- Change your email: it should ask for your password.

---

# Round 1 (2026-10-09)

Two features, built on both the website (OurHomeWeb) and the app (OurHomeApp).
**Nothing is committed or pushed**, and the server isn't redeployed. Until you
push the web repo and redeploy, the app hides the new bits; older servers just
don't send them.

Checks run: web `typecheck` clean, `vitest` 236/236 passing (including the new
`taskRotation.test.ts` and `profile.test.ts`), `eslint` clean on changed files,
`db:generate` done. App `assembleDebug` + `testDebugUnitTest` pass (new
`ProfileStyleTest`), and the merged manifest still has no
showWhenLocked/turnScreenOn.
**Not tested end to end.** The new endpoints aren't live, and the emulator came
up signed out (I don't have the password). I didn't run the web app against the
DB either, because the dev settings might point at live data. Please click
through both features once after deploying.

---

## 1. Rotating assignees ("Take turns")

**How it works**
- A recurring task can have an ordered list of people. The task form's
  recurring section has a new **Take turns** picker on both web and app: tap
  people in turn order, and the number on each chip is their turn.
- With two or more people picked, the Assignee field becomes **Whose turn now**
  and only lists people in the rotation.
- **No cycles:** completing the task passes it to the next person, along with
  the next due date.
- **With cycles:** every cycle that ends is one turn, **whether it was done or
  missed**. If several cycles passed while the server was down, it moves that
  many turns.
- **Undo** of a completion puts the assignee back.
- Task cards show "Alice's turn · next Bob" (the web shows the full order on
  hover).
- People who leave the household drop out of the rotation automatically.

**Files**
- Web:
  - `prisma` `Task.rotationUserIds` (comma-separated ids, per the schema's no-list rule)
  - `src/lib/taskRotation.ts` + test
  - `taskService.ts` (create/update/complete/roll/undo; the DTO gains `rotation` + `nextAssignee`)
  - `validation/task.ts`, `types.ts`, `create-task-form.tsx`, `task-card.tsx`
  - `pointsService.ts` (snapshot `assigneeId`)
  - `docs/ARCHITECTURE.md`
- App:
  - `Models.kt` (`Task.rotation`, `nextAssignee`, `TaskInput.rotationUserIds`)
  - `ApiClient.putTaskFields`
  - `TaskEditor.kt` (`RotationSection`)
  - `TasksScreen.kt` card meta

**Decisions you may want to change**
1. **A missed cycle still passes the turn.** I treated a cycle as "this
   week's slot". The alternative is "you stay on it until you do it". That's a
   one-line change in `rollOne` (count only completed windows).
2. **The next person comes after the *assignee*, not after whoever completed
   it.** If Bob does Alice's turn, it still goes to whoever follows Alice.
3. **Rotation only on recurring tasks.** A one-time task never "moves on", so
   the picker is hidden for it, and switching a task to one-time clears its
   rotation.
4. **No assignment notification.** Tasks never had "you've been assigned"
   alerts, so nobody is pinged when their turn starts. They just see it in
   Needs you / their task list. This is worth adding if you want it
   ("It's your turn: Bins").
5. **Covering a turn on an older app build:** if an old app build changes the
   assignee by hand, the server accepts it, and the next turn then restarts at
   the top of the list. Current web and app always send the list, so this only
   affects old installs.
6. **There's an existing bug to fix first** (`bugs.md` W1): completing a
   daily task before noon doesn't advance it, so it can be completed again.
   With a rotation, each of those completions also passes the turn.
7. Points are unchanged: whoever checks steps or completes the task gets the
   points, whoever's turn it was.

## 2. Profile customisation ("About me")

**What's there**
- On **Profile** (web and app), a new **About me** card:
  - avatar emoji (16 quick picks, or type any single emoji; "Use initials" clears it)
  - avatar colour (9 colours)
  - pronouns
  - birthday (month + day, **no year**)
  - "About me" text, up to 500 characters
- A **Household** card lists everyone's avatar, role, pronouns, about-me and
  birthday, so the family can see each other.
- The web profile header shows your avatar.

**Server**
- `User.bio/pronouns/avatarEmoji/profileColor/birthday`.
- `PATCH /api/profile` accepts them: blank clears a field; the emoji must be
  exactly one; colours come from a fixed list; the birthday must be a real
  MM-DD.
- `GET /api/household/members` now also returns role + these fields. It was
  already readable by every member, and emails are still not included.
- Files:
  - Web: `lib/profile.ts` + test, `profileService.ts`, `validation/user.ts`, `components/profile/{about-me-card,household-card,profile-avatar}.tsx`
  - App: `data/ProfileStyle.kt` + test, `ui/ProfileAvatar.kt`, `ProfileScreen.kt`, `Models.kt`, `ApiClient.updateAboutMe`

**Decisions you may want to change**
1. **Everyone sees everyone's about-me, including guests.** Nothing in it is
   secret by design, but a guest also sees birthdays. Easy to limit
   (e.g. hide the Household card for guests).
2. **Kids can write whatever they want in their bio.** There's no head
   moderation or "edit someone else's profile". The head can still rename
   people in Members.
3. **Birthday has no year** on purpose (no ages stored). Not used anywhere
   else yet. A "🎂 Birthday today" on the dashboard and a reminder are the
   obvious next step.
4. **Avatars only appear on Profile so far.** Showing them next to names on
   tasks, the dashboard and the leaderboard would be a natural follow-up; I
   kept the scope tight.
5. I didn't add a profile photo upload. Emoji + colour avoids storing images.

## Other notes
- The web `GET /api/household/members` response grew (the app parses it with
  `ignoreUnknownKeys`, and the app's Member model has defaults), so old app
  builds keep working.
- I didn't update `docs/permissions.md`: no permission changed.
- Bugs found in the review pass are in `bugs.md` next to this file.
- Both `.md` files are untracked in this **public** repo. Delete them or keep
  them out of commits when you're done (they mention nothing private, but
  they're notes, not docs).
