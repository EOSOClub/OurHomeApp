# What I did overnight (2026-10-09)

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
