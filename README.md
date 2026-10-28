# Simbox

Read SMS from all your Android phones on one phone. Sender phones relay the SMS they receive (with the SIM slot), encrypted with a key that only your paired phones have. The main phone keeps every message locally and shows all your SIMs in one inbox.

## Features

- One inbox for every SIM, as **conversations** or **all messages** with sticky dates and jump-to-date
- Full history stored on the main phone, with instant search across messages, numbers and contact names
- Smart filters: Unread, Codes, Money (with +/- amounts), Personal, Offers, Starred, Archived, Muted
- Swipe right to mark read or unread, swipe left to archive (with undo). Press and hold for copy, share, star, mute and details
- Notifications grouped by phone, with **Copy code** and **Mark read** buttons
- Alerts when a phone goes offline, runs low on battery, or has a SIM change
- Home-screen widget with the latest one-time codes
- App lock (fingerprint or screen lock), hidden lock-screen previews, auto-delete of the cloud copy
- Sender setup checklist with a one-tap connection test, and a missed-message safety scan every 15 minutes
- Built-in update check against GitHub releases

## Install

Download the latest `Simbox-x.y.z.apk` from [Releases](../../releases) and install it on every phone. Updates install over the old version.

1. **Main phone:** open Simbox, name it, and tap **This is my main phone**. It only reads; it doesn't send its own SMS.
2. **Each other phone:** open Simbox, name it, tap **Send this phone's messages**, then scan the code from **+** on the main phone.
3. On each sender phone, work through the **Setup checklist**, then tap **Send** under Test connection.

If Android blocks the SMS permission, open App info, tap the three-dot menu, choose **Allow restricted settings**, then allow SMS and Phone.

## Backend

Firebase project `simbox-6657c`, using Firestore in `asia-south1`. The security rules are in [firestore.rules](firestore.rules). Message bodies, senders, phone info and contacts are AES-256-GCM encrypted on the device. Firestore only stores ciphertext plus timestamps.

## Build

```
gradlew.bat assembleRelease
gradlew.bat testDebugUnitTest
```

Release builds are signed with `~/.android/simbox-release.jks` (falls back to the debug key if it's missing).
