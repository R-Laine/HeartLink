# HeartLink plan

Ideas for what to build next, grouped into phases. Effort is a rough guide: **S** is about an afternoon, **M** a day or two, **L** several days.

Before starting any of this, finish the steps under **Release checklist** that apply to the build currently being tested.

## Phase 1: Make the watch the main way to use HeartLink

| # | Item | Effort | Notes |
|---|------|--------|-------|
| 1 | **Watch face complication and tile** | M | Shows the partner's heart rate, status and local time on the watch face (complication) and in a swipeable tile, without opening the app. Uses `androidx.wear.watchface:watchface-complications-data-source` and `androidx.wear.tiles`, reading the same Firebase data. Needs a cached "latest partner state" on the watch so these load instantly; ask for updates when the partner's data changes. |
| 2 | **"Thinking of you" tap** | S | One tap on the complication or tile sends a heartbeat. The buzz path (`HeartRepository.sendBuzz`) already exists, so this is a tap action that calls it. Depends on 1. |
| 3 | **Home-screen widget (phone)** | M | Partner's heart, status, local time and a Send heartbeat button. Build with Jetpack Glance. Update it from `HeartLinkService` when the partner's state changes. |
| 4 | **Reply from the watch** | S | Add quick replies and voice input to message notifications, so a reply goes back as a HeartLink message. Use `RemoteInput` on the notification and handle the reply in a small receiver that calls `sendMessage`. |

## Phase 2: Fill the gaps in what's already built

| # | Item | Effort | Notes |
|---|------|--------|-------|
| 5 | **Watch reports its own online status** | M | Today only the phone holds the live connection, so a dead phone shows the person as "no connection" even when their LTE or Wi-Fi watch still works. Add a short presence update from the watch (`presence/{role}/watchSeen`) during passive heart-rate updates, and treat a recent one as reachable in `partnerStatus`. |
| 6 | **Morning replay for quiet hours** | S | Quiet hours are done (see **Done**). Still to do: heartbeats that arrive during quiet hours are only shown as a silent notification; optionally queue them and play the vibration when quiet hours end. |
| 7 | **Haptic settings** | S | Vibration strength and number of beats, stored per device. `Vibration.heartbeatWaveform` already takes a beat count; add an amplitude scale. |

## Phase 3: Being together across distance

| # | Item | Effort | Notes |
|---|------|--------|-------|
| 9 | **Manual check-ins** | S | Automatic "Home safe 🏠" on arriving home is done (see **Done**). Still to do: one-tap check-ins such as "Landed ✈️", "At work" or "Leaving now", from the app, the watch and the notification. The storage (`presence/{role}/checkIn`, `HeartRepository.checkIn`) and the display on the partner's card already exist, so this is a picker and buttons. |
| 10 | **Heartbeat sync** | M | While both of you are looking at HeartLink, show the two hearts side by side and celebrate when the rates stay within about 3 bpm for 10 seconds. The `watching` flags already tell each side when the other is looking. |
| 11 | **Countdown on widget and tile** | S | The countdown itself is done (see **Done**). Add it to the widget (3) and tile (1) when those are built. |
| 12 | **Overlap finder** | M | Using both time zones (already stored) and recent sleep times, suggest a good time to call, for example "19:00–22:30 yours". Needs a few days of sleep history, so it depends on 13 or a small sleep log. |
| 13 | **Shared daily moment** | S | At an agreed time each day, both watches vibrate a heartbeat together. Schedule with `AlarmManager` on each phone and relay to the watch as with buzzes. |

## Phase 4: History and keepsakes (opt-in)

| # | Item | Effort | Notes |
|---|------|--------|-------|
| 14 | **Heart-rate history** | L | A simple daily chart for both people. The database currently keeps only the latest reading, so this needs a history store, for example one summary per 5 minutes at `couples/{id}/history/{role}/{day}`, plus a retention limit and security rules. Both people must opt in. |
| 15 | **"Your heart when you thought of me"** | S | When a heartbeat is sent, record the sender's bpm and time in a small log the receiver can scroll through. Depends on the privacy decisions made for 14. |

## Phase 5: Polish and release

| # | Item | Effort | Notes |
|---|------|--------|-------|
| 16 | **App icon, splash screen and theming** | S | Adaptive launcher icon, a monochrome themed icon for Android 13 and later, and a splash screen. Proper Material 3 colour and type for the phone app. |
| 17 | **Release signing** | S | Create an upload key and configure `signingConfigs` for both apps with the same key, which the watch needs to link through the phone. Keep the keystore and passwords out of git. |
| 18 | **Google Play release** | M | Internal testing track first. Needs the Health Connect declaration form, a privacy policy URL, the data safety form, and a justification for the `connectedDevice` background service type. |

## Done

| Item | What was built |
|------|----------------|
| **Morning and goodnight moments** | A notification when the partner wakes up or falls asleep ("Laine is awake ☀️"), from their watch's sleep detection. Settings → Moments turns it off. Silent during your quiet hours. |
| **Arriving home** (automatic half of check-ins) | Settings → "Tell Laine when I get home": stand at home and tap Set. A geofence around home (150 m, location stays on the phone) posts "Home safe 🏠" on your card for 3 hours and messages your partner. At most once per 30 minutes. Needs "Allow all the time" location access. |
| **Countdown** | Settings → "Together again" sets the date (either person can change it). Shows "Together again in 23 days 💞" on both phones and watches. |
| **Quiet hours** | On by default, 23:00–07:00, set in Settings. Heartbeats, messages and moments arrive silently (no vibration or sound) during quiet hours or while your watch says you're asleep. "Not while I'm using my phone" (on by default) lets things through when you're online. Your partner sees "🌙 Quiet hours" on your card. |

## Release checklist (for the current build)

- [ ] Publish `firebase/database.rules.json` in the Firebase console.
- [ ] Run the rules tests: `firebase emulators:start --only database`, then `npm install && npm test` in `firebase/rules-test/`.
- [ ] Check that Anonymous sign-in is enabled in Firebase Authentication.
- [ ] Re-pair both people after installing (the data layout changed).
- [ ] On real devices, check:
  - [ ] the watch links itself through the phone
  - [ ] live readings start on the watch while the partner is looking (Android may block this from the background)
  - [ ] status changes to "no connection" within a minute or two of switching a phone off
  - [ ] status changes to away after an hour without using the phone
  - [ ] asleep shows after the watch detects sleep
  - [ ] messages and heartbeats arrive with HeartLink closed
  - [ ] battery levels update on both cards
  - [ ] quiet hours: a heartbeat sent at night arrives silently; it vibrates normally while you're using your phone
  - [ ] arriving home posts "Home safe 🏠" (needs "Allow all the time" location)
  - [ ] wake/sleep notifications appear after the watch detects a change

## Suggested order

1. Release checklist
2. Phase 1, items 1 and 2 (complication, tile, "thinking of you" tap)
3. Phase 2, item 5 (watch online status), then 9 (manual check-ins)
4. Whatever feels most worth having from Phases 3 to 5
