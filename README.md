# Simbox

Read SMS from all your Android phones on one phone. Each phone relays the SMS it receives (with the SIM slot) to Firebase, encrypted with a key that only your paired phones have. The main phone shows every SIM's messages in one inbox and notifies you about new ones.

## Install

Download `Simbox-x.y.z.apk` from [Releases](../../releases) and install it on every phone.

1. **Main phone:** open Simbox, name the phone, tap **This is my main phone**, and allow access.
2. **Each other phone:** open Simbox, name it, tap **Link to my main phone**, then scan the QR code shown when you tap **+** on the main phone.
3. **On every phone:**
   - If Android blocks the SMS permission, open App info, tap the three-dot menu, choose **Allow restricted settings**, then allow SMS and Phone.
   - Allow **Background running** when Simbox asks.
   - On Xiaomi, Oppo, Vivo, Realme and Samsung, also turn on **Autostart** and set Battery to **No restrictions** in App info.

When a phone is linked, it uploads every SMS already in its inbox. New messages arrive within seconds. The main phone loads older messages as you scroll.

## Backend

Firebase project `simbox-6657c`, using Firestore in `asia-south1`. The security rules are in [firestore.rules](firestore.rules). Message bodies, senders and phone info are AES-256-GCM encrypted on the device. Firestore only stores ciphertext plus timestamps.

## Build

```
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```
