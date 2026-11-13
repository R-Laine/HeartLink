# HeartLink

HeartLink lets long-distance couples feel each other's heartbeat. A Wear OS watch measures your heart rate, and both your phone and your watch show your heartbeat and your partner's, live.

## How couples link up

1. One partner opens the phone app, taps **Start a new link** and enters their name. The app shows a one-time invite code (for example `K7QM2X`, valid for 7 days) with a **Share invite** button.
2. The other partner taps **I have an invite code**, enters it and their name. The code is used up and disappears; from then on nothing needs a code.
3. Each watch links itself: open HeartLink on the watch and it gets the link from its phone automatically. **Use a code instead** is a fallback.

**Add device** (phone) creates a one-time code that works for 15 minutes, for linking a new or reinstalled phone to either person. **Unlink** removes only that device's access.

## Staying in touch

- **Status:** your partner's card shows **online**, **inactive** (no phone or watch use for 10 minutes), **away** (none for over an hour), **asleep** (from their watch's sleep detection) or **no connection** (their phone has lost its connection, for example a flat battery, and their watch isn't sending readings).
- **Local time:** your partner's card shows the time where they are, and the difference from yours.
- **Battery:** your partner's phone and watch battery (for example `📱 64% · ⌚ 40%⚡`). It turns pink at 15% or below and shows its age when more than an hour old, so a dead battery explains a "no connection" status.
- **♥ Send heartbeat:** your partner's watch vibrates a lub-dub at your last measured heart rate, or their phone if no watch is connected. Available from the app, the watch and the always-on notification.
- **Message:** sends a short note, which arrives as a notification and is mirrored to their watch.
- **Quiet hours:** on by default (23:00–07:00, change in Settings). Heartbeats and messages arrive silently during them, or while your watch says you're asleep, unless you're actively using your phone. Your partner sees "🌙 Quiet hours" on your card.
- **Wake and sleep:** a gentle notification when your partner wakes up or falls asleep.
- **Arriving home:** set your home in Settings and your partner gets "Home safe 🏠" when you arrive. The location stays on your phone.
- **Countdown:** set the next date you'll be together, and both of you see "Together again in 23 days 💞".
- **Live when it matters:** while either of you has HeartLink open, the other's watch measures live for about 90 seconds at a time.

All of this runs without a server. The phone keeps a background connection, which needs an always-on notification. **Settings** turns the connection off or chooses whether the notification shows your partner's heartbeat, status and local time. If HeartLink keeps disconnecting, allow it unrestricted battery use (Settings → Battery settings).

## No HeartLink watch? Health Connect fallback

Someone with a Fitbit, Galaxy Watch (without the HeartLink watch app), Garmin, Oura or similar can tap **No HeartLink watch? Share from Health Connect** on the phone. HeartLink then shares the newest heart rate that the device's own app saved to Health Connect: every minute while HeartLink is open, and about every 15 minutes in the background if background access is allowed.

These readings are only as fresh as the other app's last sync, so they show as "14 min ago · Health Connect" rather than a live, beating heart. A Health Connect reading never overwrites a newer live reading from the watch.

## Modules

| Module    | What it is                                                                      |
|-----------|---------------------------------------------------------------------------------|
| `:watch`  | Wear OS app. Measures heart rate (live, in the background, and on demand while the partner is looking), reports sleep, plays received heartbeats, and links itself through the phone. |
| `:phone`  | Phone app. Linking, both hearts, partner status and local time, messages and heartbeats, the background connection (presence, inbox, relaying to the watch), and the Health Connect fallback. |
| `:shared` | Code used by both: Firebase repository, sessions and invites, status rules, vibration patterns, formatting helpers. |

Both apps use the application ID `com.heartlink.app`, so Wear OS treats them as one app.

## Data

Firebase Realtime Database, with anonymous sign-in:

```
/couples/{coupleId}/devices/{uid}        devices allowed in (the only way to read or write a couple)
/couples/{coupleId}/members/{a|b}/name   set once
/couples/{coupleId}/beats/{a|b}          { bpm, ts, source }
/couples/{coupleId}/presence/{a|b}       { connected, lastActive, asleep, tz, … }
/couples/{coupleId}/messages/{a|b}/{id}  inbox, deleted once shown
/couples/{coupleId}/buzz/{a|b}           latest "feel my heartbeat"
/couples/{coupleId}/watching/{a|b}       when the partner last looked
/invites/{code}                          one-time invites, deleted when used
```

The couple ID is a long random key that is never shown. Security rules are in [`firebase/database.rules.json`](firebase/database.rules.json); publish them in the Firebase console under **Realtime Database → Rules**. Only a couple's own devices can read it, invites work once and expire, each person can only write their own heartbeat and status, and messages and heartbeats can only be sent to the partner.

To check the rules locally, start the database emulator (`firebase emulators:start --only database`), then run `npm install && npm test` in `firebase/rules-test/`.

## Setup

1. Open the project folder in Android Studio.
2. Put `google-services.json` from the Firebase console (project `heartlink-3b45b`, app `com.heartlink.app`) in both `phone/` and `watch/`. It is gitignored.
3. Phone and watch builds must be signed with the same key (debug builds on one computer are), or the watch can't link through the phone.
4. In the Firebase console, check that **Authentication → Sign-in method → Anonymous** is enabled, and publish the database rules.
5. Run the `phone` or `watch` configuration, or run `build-apks.bat` to get `dist\HeartLink_Phone.apk` and `dist\HeartLink_Watch.apk`.

```sh
./gradlew assembleDebug          # both apps
./gradlew :shared:testDebugUnitTest
```
