# CLAUDE.md

Guidance for Claude Code in this repo. [README.md](README.md) has what the app does, the
database layout and setup steps; [PLAN.md](PLAN.md) tracks what's done and what's left. This file
covers how to work here and the non-obvious things that have caused bugs.

## Build and test (Windows)

Run Gradle through `cmd` with stdin closed, or it can hang:

```powershell
cmd /c ".\gradlew.bat :shared:testDebugUnitTest -q < nul"   # unit tests (shared only)
cmd /c ".\build-apks.bat < nul"                              # release APKs -> dist\
```

- `build-apks.bat` builds R8-shrunk release APKs signed with the **debug** key, as
  `dist\HeartLink_Phone.apk` and `dist\HeartLink_Watch.apk`. After changes, run the tests and
  the build, then tell the user to install both APKs.
- Release builds are what the user installs, so R8 bugs only show up there (see below).
- No device is usually attached; `adb` is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
  Say clearly when something is untested on a device.
- Unit tests live in `shared/src/test/.../HeartLinkUiTest.kt`. Put logic that can be tested
  (status rules, encodings, timing maths) in `:shared` as plain functions and test it there.

## Rules for changes

- Don't commit or push unless asked. `google-services.json` is gitignored and must stay out of git.
- **Database rules:** any new field under `/couples/...` needs a rule in
  `firebase/database.rules.json`, since every level ends in `"$other": {".validate": false}`.
  Unknown fields are rejected and the whole write fails. Remind the user to republish the rules in
  the Firebase console after changing them.
- Timestamps in rules must satisfy `<= now + 60000`, so client-clock times are fine but future
  times aren't.
- Keep [PLAN.md](PLAN.md) updated for user-visible features.

## Architecture in brief

- `:shared` – `HeartRepository` (all Firebase access), `Presence.kt` (status and sleep rules),
  `HeartLinkUi.kt` (colours, heartbeat animation curve), `Session.kt` (`WearPaths` message paths),
  `WatchReport.kt`, `Battery.kt`, `Moments.kt` (quiet hours).
- `:phone` – `service/HeartLinkService` is the always-on foreground service: presence, activity,
  inbox, buzz playback, relaying to the watch, watch sync, status notification.
  `wear/WatchPairingService` receives watch messages (pairing, sync replies).
- `:watch` – `MainActivity` (permissions, live measuring), `PassiveHeartService` (background heart
  rate and sleep from Health Services), `LiveHeartService` (short live measuring when the partner
  looks), `PhoneRelayService` (messages from the phone), `SleepReport` (reliable sleep sending via
  WorkManager), `BackgroundSetup` (passive registration and the boot receiver).
- Both apps share applicationId `com.heartlink.app` and must share a signing key, or the Data Layer
  won't connect them.

## Pitfalls already hit

- **R8 and protobuf:** Health Services looks up protobuf-lite fields by name. Both
  `proguard-rules.pro` files keep `GeneratedMessageLite` subclasses; without that the watch
  crashes in release builds only.
- **Anonymous sign-in race:** concurrent `signInAnonymously` calls create several users.
  `ensureSignedIn` holds a `Mutex`.
- **Refused reads:** one PERMISSION_DENIED isn't proof a device was unlinked. `valueFlow` flags
  `accessDenied` and then re-listens with backoff. Callers confirm with `isStillLinked` before
  forgetting a link. Never let a listener stop for good: that froze data on screen.
- **`valueFlow` never throws** to collectors (an exception in a screen closes the app).
- **Offline writes don't fail, they wait.** Use `HeartRepository.trySend` for anything with UI
  feedback (Sent / Queued / Failed). Queued writes live in memory only and are lost if the process
  dies.
- **Buzz timestamps are the sender's clock**, so a buzz delivered late isn't played as new. The
  receiver shows one more than 2 minutes old as a silent notification.
- **Health Services passive listeners** are dropped on reboot (and possibly on update).
  `BootReceiver` and every app open call `BackgroundSetup.register`.
- **Wear OS 6** uses `android.permission.health.READ_HEART_RATE` instead of `BODY_SENSORS`. Check
  with `BackgroundSetup.hasHeartRate`, never `BODY_SENSORS` alone.
- **Sleep changes are only reported once** by Health Services. `SleepReport` keeps the latest
  change until the database confirms it (WorkManager retries; the phone can also relay it via
  `WearPaths.SYNC_REQUEST`/`SYNC_REPLY`, then `SLEEP_RELAYED`).
- **Phone "activity"** means screen on *and* unlocked (`inUse()`): the screen also lights up for
  notifications and lift-to-wake, which made sleeping people show as online.
- **Starting the watch's `LiveHeartService`:** a service started with `startForegroundService` must
  call `startForeground` before stopping, or the app crashes. `LiveHeartService.request` checks the
  link and heart-rate permission *before* starting it.
- **Finding the watch:** the phone uses the `heartlink_watch` capability (`WatchNodes`), declared
  in `watch/res/values/wear.xml` and kept from resource shrinking by `watch/res/raw/keep.xml`.
  Without the keep file, release builds drop it and the phone never sees the watch.
- **Client timestamps compared across devices** (buzzes) use `HeartRepository.serverNow()`.
- `WearableListenerService.onMessageReceived` runs on a background thread, so `runBlocking`
  there is fine. Don't block the main thread elsewhere.

## Status and sleep model

`partnerStatus` in `Presence.kt` is the single source of truth for online / inactive / away /
asleep / no connection, used by both apps and the notification. In order:

1. **No connection:** reachability.
2. **Asleep:** from the sleep model. Its internal "woke up" state (stirring at night) shows as
   inactive.
3. **Online / inactive / away:** from the activity model.

**Activity** is decided in one place too: `ActivityModel.decide` (`shared/ActivityModel.kt`), from
`ActivitySignal`s.

- **Direct use:** phone unlocked, the watch app, or plugging in or unplugging a cable (wireless pads
  are ignored; they reconnect by themselves). These mean online, then inactive, then away.
- **Calls** (`AudioManager` mode) are not direct use, because people stay in "sleep calls" (e.g.
  on Discord) all night:
  - A call keeps someone online only if they also used a device directly in the last hour;
    otherwise inactive.
  - It never counts as being awake for the sleep model, or as "using my phone" for quiet hours.
  - It's shared as `callAt` every 5 minutes, never as `lastActive`.
- **Moving:** Android activity transitions in `phone/activity/Motion.kt`, using the Sleep detection
  permission. Being on the move keeps someone at inactive instead of away. It never makes them
  online and isn't use.
- **Where they're recorded:** the person's phone keeps them in `ActivityLog` and shares
  `lastActive` (any engaged signal) and `movingAt` (every 10 minutes while moving) and `callAt`. Readers run the
  same model on those.
- **Feeding sleep:** the sleep model's evidence of use is `ActivityModel.engagedRuns`. Heart-rate
  readings count for neither model.

Sleep is decided in one place: `SleepModel.decide` (`shared/SleepModel.kt`), a pure state machine
that replays everything since the watch said asleep. Its sources:

- the watch's `asleep`/`asleepAt`, which starts a sleep (its "awake" is final);
- phone and watch use, as `UseRun`s;
- finished sleeps from Health Connect and Android's Sleep API;
- Sleep API confidence readings.

Naps and nights use the same rules (no time-of-day logic). If you change thresholds in
`StatusRules` or `SleepModel`, update the tests.

- **Who decides:** each person's own phone sees every source, runs the model in
  `HeartLinkService.decideSleep`, and publishes the verdict to `presence/{role}/sleep` when it
  changes. Its use log is kept on disk (`phone/sleep/UseLog.kt`).
- **Who reads it:** everyone else calls `Presence.sleepDecision`. It uses the published verdict
  while its `basis` matches the watch's current `asleepAt`. Otherwise it runs the same model on what
  the presence shows (`asleepAt`, `wokeAt`..`lastActive`).
- **The woke-up state lapses on its own:** it carries `lastUse` and turns back into asleep without needing
  another write.
- **Learning:** each phone tunes the use thresholds to its person (`SleepTuning`, `SleepLearning`).
  `phone/sleep/NightJournal.kt` saves each night's evidence and, once Health Connect or the
  Sleep API has recorded the night (plus 3 hours), replays it to score the model's first "awake"
  against the real wake time. Settings move a step at a time within fixed limits. Everything stays
  on the phone (`noBackupFilesDir` and prefs) and is cleared on unlinking or with Settings → Reset.
- **Don't add separate fields** that encode sleep conclusions (the old `awakeAt` did, and the logic
  spread out). Add a new source as evidence to `SleepEvidence` instead.

## Battery

Background loops use coroutine `delay` (they don't wake a sleeping CPU). The status notification
only ticks while the screen is on, and the heart animation only runs while there's a beat to show.
Don't add frequent background network calls; prefer piggybacking on existing events (passive
heart-rate batches, screen on, reconnects).

## Tooling notes

- Bash heredocs with large embedded code sometimes fail. For big multi-file patches, write a
  Python script to the scratchpad and run it with `python -I`.
- Use they/them for the user.
