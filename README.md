# Rishat File Share — Wi-Fi Direct + same-Wi-Fi WebRTC

Android-only Kotlin project with a minimal futuristic UI and two peer-to-peer transfer modes.

## Mode 1: Wi-Fi Direct (offline)
- Android Wi-Fi Direct peer discovery and connection.
- Direct TCP socket between phones; file data streams in 64 KiB blocks.
- The client retries the local TCP connection for up to about 30 seconds, with TCP keep-alive and no-delay enabled.
- Does not require a router or internet connection.
- No cloud upload, file server, STUN, TURN, or relay is used.

## Mode 2: WebRTC DataChannel (same Wi-Fi)
- Intended for two phones connected to the same local Wi-Fi network.
- Sender creates an SDP Offer QR; receiver scans it and displays an SDP Answer QR; sender scans the answer.
- Once the WebRTC DataChannel is open, files are sent directly as binary chunks.
- No file-upload server or cloud signaling is used. No STUN/TURN server is configured; this mode relies on reachable local ICE host candidates.
- SDP payloads can be too large for QR codes, and some devices/networks may not establish a connection. This mode is experimental; use Wi-Fi Direct if it fails.

## Wi-Fi Direct usage
1. Install the app on both Android phones. Both devices must support Wi-Fi Direct.
2. Turn on Wi-Fi on both phones; internet/router connection is not required.
3. Open the app on both phones and grant Nearby Wi-Fi permission (or Location permission on older Android).
4. Tap **আশেপাশের ফোন খুঁজুন** on one phone, wait for the other phone to appear, then tap its device row.
5. Wait until direct Wi-Fi Direct connection is ready.
6. Tap **ফাইল নির্বাচন করে পাঠান**, choose a file, and wait for completion.
7. The receiving phone shows the received file; tap **ফোনে সংরক্ষণ করুন** to choose its destination.

## Same-Wi-Fi WebRTC usage
1. Connect both phones to the same Wi-Fi network.
2. On the sender phone, tap **একই Wi-Fi-তে WebRTC দিয়ে ফাইল পাঠান**, then **ফাইল পাঠান (Send)** and choose a file. The app creates an Offer QR.
3. On the receiver phone, open the same WebRTC screen and tap **ফাইল গ্রহণ করুন (Receive)**, then **অফার QR স্ক্যান করুন**.
4. Receiver displays an Answer QR. On the sender, tap **উত্তর QR স্ক্যান করুন** and scan it.
5. Wait for DataChannel state `OPEN`, then tap **নির্বাচিত ফাইল পাঠান**.
6. Receiver chooses a destination when prompted.

## Important notes
- The app does not use a remote file server for either mode.
- Wi-Fi Direct does not require internet. WebRTC mode is intended for the same Wi-Fi and depends on local host ICE candidates.
- Android manufacturers and OS versions differ. Wi-Fi Direct support is device-dependent.
- This project has not been compiled or tested on physical devices in this environment. Build with Android Studio or GitHub Actions and test on two real phones.
- No app can guarantee that every P2P connection succeeds on every phone/network: Wi-Fi Direct support, Android permission settings, manufacturer behavior, radio interference, and OS restrictions can still prevent connection. If a connection fails, keep Wi-Fi enabled on both phones, keep the app open, disconnect, and rediscover devices.
- The receiver temporarily stores incoming files in app cache before the user chooses a destination. Very large transfers require sufficient free storage.
- Wi-Fi Direct mode has no resume/retry, checksum, pairing authentication, or additional application-layer encryption. WebRTC QR mode is experimental and QR payloads may be too large.
- No fixed app-level file-size cap is set in Wi-Fi Direct mode, but actual limits are available storage, OS/provider behavior, and connection stability. WebRTC DataChannel may have message/buffer limits depending on the WebRTC build and device.
- Preserve Bengali source text as UTF-8/Unicode.

## Build
Open in Android Studio with JDK 17 and Android SDK 35, then build `app` > `assembleDebug`. GitHub Actions workflow uploads a debug APK artifact.


## Build dependency note
- WebRTC dependency uses `io.github.webrtc-sdk:android:150.7871.01`, which is published on Maven Central; the old `org.webrtc:google-webrtc:1.0.32006` coordinate was hosted on a retired Bintray repository and fails to resolve from Google Maven/Maven Central.
