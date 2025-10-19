# Market Signal Monitor – Weekend Hack Edition

A lightweight Android client built as a weekend experiment to connect with the [PHP REST API – Hack Edition](https://github.com/Eken134/my_Webpage).  
The app periodically polls the `/get_latest` endpoint and sends push notifications when new signals are detected.

---

### Features
- Background service polling `/get_latest` every few seconds
- Basic Auth support
- Push notifications for BUY / SELL signals
- Broadcast updates to UI
- Minimal footprint and low network usage

---

### Purpose
Part two of a spontaneous **“Saturday hack”** — built to test Android background services, REST polling, and push notifications.

---

### Technical overview
- Language: **Kotlin**
- Android: **Android 15** (API **35**) — `compileSdk = 35`, `targetSdk = 35`
- Runtime model: **Foreground Service** (`startForeground`) for reliable polling with a persistent notification
- Foreground service type: **dataSync**
- HTTP client: **OkHttp** (with Basic Auth header)
- JSON parsing: **org.json.JSONObject**
- Notifications: **NotificationManager / PendingIntent** (own channel)
- Update broadcast: Local **broadcast** to UI on new signal
- Rate limiting: Cooldown between identical signals to avoid spam

---

#### Notes on foreground polling (Android 14/15)
- Uses a **foreground service** to keep polling stable under Doze/App Standby.
- Persistent notification is required by Android 14/15 for long-running work.
- Suggested manifest: `android:foregroundServiceType="dataSync"`.
