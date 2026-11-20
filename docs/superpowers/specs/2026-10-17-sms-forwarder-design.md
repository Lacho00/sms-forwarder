# SMS Forwarder — design

Date: 2026-10-17. Status: draft for approval. Revised 2026-10-23 (sweep floor, nothing active before first launch, minSdk 31, 400 notifies).

## 1. What it is and why

A custom, single-user Android app that forwards SMS from specific senders (the ones the user
configures; the list starts empty) to the existing Cloudflare Worker. Those SMS feed a personal
finance manager (another repo).

It replaces the previous forwarding app, which loses messages. On 2026-10-17 an SMS from the mobile
carrier was lost: MIUI killed the forwarding app and did not let it start when the SMS arrived. The
Worker and the receiver were fine.

**Goal number one: never lose an SMS.** Everything else is subordinate to this.

## 2. Goals, non-goals and anti-goals

Goals:
- Every SMS from a sender on the list ends up accepted by the Worker (HTTP 200), even if it takes hours.
- If something prevents that (permissions, MIUI, token), the user finds out through a notification.
- If the receiver did not run, an inbox sweep recovers the SMS.

Non-goals:
- It is not a messaging app and does not replace Google Messages.
- It does not interpret the SMS content (the Worker does that).
- It is not integrated into a future APK of the finance manager. Standalone app (see §10).
- It does not check for updates of itself.

Anti-goals (failure even if it "works"):
- Discarding an SMS because of a network failure or a transient error.
- Notifications for every successful send (noise that ends up ignored).
- The token, the Worker URL, phone numbers, cards or real SMS appearing in the repo, in logs or in tests.
- A permanent notification in the status bar: **the user rejected a foreground service**.

## 3. Constraints

- Phone: Xiaomi with MIUI, Android 14, dual SIM. Google Play Services available. Messaging app:
  Google Messages. The important SMS arrive on SIM 1.
- Slow, intermittent mobile network, sometimes through a VPN.
- Kotlin + Jetpack Compose, Android only, `minSdk 31`. With 31, WorkManager expedited work is an
  Android 12+ job, never a foreground service (on API 30 it would be one, with a notification, and
  would require `getForegroundInfo()`).
- Risk: Android 15 restricts SMS permissions for apps installed from outside the store ("restricted
  settings"). If the permission dialog does not appear, the user must go to App info → ⋮ → "Allow
  restricted settings". The first launch explains this when the permission is denied without a dialog.
- The user is new to Kotlin/Android: the plan and the code explain things step by step.
- Android Studio and the SDK are not installed locally: everything is built in GitHub Actions.
- **Public** repo on GitHub.
- The Worker contract is not changed (§4).

## 4. Worker contract (fixed)

```
POST https://<host>/ingest/sms
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json

{"sender": "<1-64 chars>", "body": "<1-2000 chars>", "received_at": <ISO 8601 with offset or Z | ms since epoch, 13 digits>}
```

- `200`: accepted (also if it is a duplicate).
- `400 invalid_json` / `invalid_request`: malformed data.
- `401 unauthorized`: wrong token.
- The Worker deduplicates by `hash(sender + body + received_at)`. Resending the same SMS with the
  same `received_at` is safe.
- The Worker itself ignores the carrier's promos and stores as "unknown" whatever it does not understand.

## 5. How an SMS reaches the app

Three independent paths, all ending in the same queue. None is active until the first launch
finishes: while `onboardingDone` is false, the receiver discards SMS and the sweep does nothing.
Reason: an SMS arriving while the previous app is still active would be entered twice.

1. **`SMS_RECEIVED` receiver** declared in the manifest. On receipt, it joins the parts of a
   multipart SMS, applies the sender filter, saves to the local database (inside `goAsync()`) and
   enqueues a send job. It sends nothing over the network from the receiver.
2. **Periodic sweep** with WorkManager, every 15 minutes (Android's minimum), with no network requirement.
3. **One-off sweep** when the app is opened, when "Sweep inbox now" is pressed and after `BOOT_COMPLETED`.

**Verified on the phone 2026-10-29** (Android 14, MIUI): for one-part and two-part SMS, the
receiver and the inbox agree on sender, hash and body length, and `pduTimestamp` equals
`date_sent` (identical `localKey`). `date` arrives several seconds after `date_sent`, so
`date_sent` wins. `joinParts` reproduces the inbox body, line breaks included. An app that was
never opened does not receive `SMS_RECEIVED`: the first launch must start it.

### Sweep

- Reads `content://sms/inbox` (`READ_SMS` permission) from a saved cursor, with **24 hours of
  overlap** backwards on every sweep. The overlap covers skewed clocks and SMS that Google
  Messages writes late.
- **Sweep floor (`sweepFloor`)**: at the end of the first launch that instant is saved and never
  moves. Every sweep reads from `max(cursor − 24 h, sweepFloor)`. Without the floor, the first
  sweep after setup would send the previous 24 h of SMS that the previous app already sent with a
  different time. SMS older than the floor are only sent through manual recovery.
- It filters by sender, and for each SMS that does not already exist in the local database it
  inserts it with origin "sweep" and enqueues its send.
- If it inserts any that the receiver did not capture, it fires the "SMS recovered" notification (§8).
- **Nothing is automatically resent backwards.** The sweep cursor starts at the moment of
  installation (or initial setup). Earlier SMS are only sent through manual recovery (below).
- **Manual recovery** (opened at the end of the first launch, which repeats after a reinstall
  because data is not copied, and from Settings; it does not open by itself just because the
  database is empty, since it would show on every launch if the user ticks nothing): the app
  lists the SMS from the senders on the list from the last 7 days, **none ticked**, and the user
  ticks which ones to send. The ticked ones enter the queue with origin "manual".

Why: the previous app sent `received_at` = the phone's time, not the service center's, so the
Worker does not recognize as duplicates the SMS it already received from that app. In the finance
manager, bank payments are deduplicated by transaction number and are not duplicated, but each
carrier movement uses the raw SMS row as its reference: a resend with a different time **duplicates
the expense**. That is why any resend of something that may already have arrived by another path is
decided by the user.

Clean cutover: the previous app is uninstalled or disabled before setting up this one. If both run
at the same time, every carrier SMS is entered twice. The first launch reminds the user of this,
before anything else.

### SMS identity (critical)

Receiver and sweep must produce **exactly** the same `sender`, `body` and `received_at` for the
same SMS. Otherwise the Worker would store it twice and the app would not know it already has it.

- `received_at` = service center timestamp, in milliseconds: in the receiver
  `SmsMessage.getTimestampMillis()`, in the sweep the `date_sent` column. Sent as a 13-digit integer.
- `body` = parts concatenated in order (receiver) / `body` column (sweep).
- `sender` = originating address as it arrives (not normalized; normalization is only used for
  filtering and for the local key).
- Local deduplication key: `normalized sender + received_at + hash(body)`, with a unique index in
  the database.
- **Whoever sees an SMS first fixes its identity.** The local database stores `date_sent` and
  `date` (or the receiver's equivalent). If the sweep finds an SMS that matches one already
  stored, it does not create another or send another `received_at`: the stored value is the one
  always sent.
- Sweep match against what is stored: first the exact key; otherwise a fallback on same
  normalized sender, same body and `received_at` within ±2 s. If `date_sent` is 0 or missing,
  `date` is used for that SMS.
- **First task of the plan: check on the real phone.** A diagnostics screen, in debug builds only,
  shows on the phone itself the raw values (sender, `date`, `date_sent`, PDU timestamp, body hash)
  from receiver and inbox for the latest SMS. None of that goes to the repo or to logs. What it
  shows confirms or adjusts this section before the queue is built.

### Dual SIM

SMS from both SIMs are forwarded if the sender matches; the filter is the sender, not the SIM.
The SIM it arrived on is stored (subscription extra in the receiver, `sub_id` in the inbox) to
show it in the history.

### Sender filter

Editable list in Settings. **It starts empty: there are no default senders in the code**; the
user enters them on first launch (§7). If the list ends up empty, the app forwards nothing and
warns about it (`HealthIssue.NO_SENDERS`, a card on the main screen and a notification). Comparison
with both sides normalized: no spaces (in any position) and uppercase. Exact match after
normalizing.

Removing a sender from the list does not cancel its SMS that are already queued: they are sent
anyway. Adding a sender does not recover anything older than the sweep floor; its older SMS only
come in through manual recovery.

## 6. Queue and sending

- Local database (Room). Every SMS is saved **before** any send attempt.
- One `CoroutineWorker` per SMS (unique work by id), `NetworkType.CONNECTED` constraint,
  exponential backoff. The receiver requests it as expedited work when allowed.
- Backoff can reach WorkManager's 5 h cap on an intermittent network. When the app is opened, on
  every sweep (periodic or manual) and after saving credentials, every `QUEUED` SMS is enqueued
  again with `REPLACE` to try right away (always with the network constraint). This forced retry
  does not use expedited work; expedited is reserved for the receiver path.
- HTTPS only: the app rejects `http://` URLs and does not allow cleartext traffic. No certificate
  pinning (the VPN and possible Cloudflare certificate changes would make it fragile).
- Generous HTTP timeouts (around 30 s connect, 60 s read) because of the slow network.
- Response handling:
  - `200`: state "Sent".
  - Network error, timeout, `5xx`, `429`: retry with backoff, no attempt limit. Never discarded.
  - `401`: state "Error 401", no automatic retry, immediate notification. When a new token is
    saved in Settings, all SMS in "Error 401" go back to the queue automatically.
  - `400`: state "Error 400" with the error code from the body, no automatic retry (retrying does
    not fix it), immediate notification (the user must know). Manual "Retry" button.
  - Other codes: treated as transient.
- Every attempt is logged (time, HTTP code or error type) for the detail view.
- If an SMS goes **more than 6 hours** without receiving 200, an "SMS stuck" notification with the last error.

## 7. Screens

Material 3 with Compose. **Light and dark** mode: follows the system by default, with a selector
in Settings (System / Light / Dark). Dynamic color when the phone's Android supports it.

Chosen structure (option A of the sketch): **one main screen + Settings behind a gear icon.**

Main screen, top to bottom:
1. Active warnings (if any): Autostart, battery, permissions, token rejected, no senders. Each with
   a button that opens the corresponding settings screen.
2. Status line: time of the last sweep, number of SMS queued and in error. "Sweep inbox now" button.
3. History, most recent on top. Each row: sender, SIM, date and time, start of the text and a
   status label: `Queued · N attempts`, `Sent 200`, `Recovered · 200` (arrived through the
   sweep), `Error 401`, `Error 400`.

SMS detail (on tapping a row): full text, sender, SIM, time, origin (receiver or sweep), list of
attempts with their result. "Retry" button if it is in error.

Settings:
- Worker URL and token. The URL is visible while typing; the token is hidden and **never shown
  again**, only replaced.
- "Test connection" button: sends the Worker a POST with body `{}`. `400` = URL and token correct
  (the Worker stores nothing). `401` = wrong token. Any other result = network or URL error.
- Sender list: add and remove.
- Theme: System / Light / Dark.

First launch: first of all, the clean-cutover reminder (uninstall or disable the previous app),
which the user must confirm. Then permissions (`RECEIVE_SMS`, `READ_SMS`, and
`POST_NOTIFICATIONS` if the Android version requires it), then **Senders** (at least one must be
added to continue), then URL and token, then "Test connection". If it goes well: a short reminder
about the previous app and the manual recovery screen (§5). History label for these: `Manual · 200`.

Retention: the history is deleted automatically after 90 days. SMS that are queued or in error are never deleted.

## 8. Alerts (notifications)

Silence when everything is fine. Only these are notified:

1. **SMS recovered**: the sweep found one that the receiver did not see. Text along the lines of
   "Recovered 1 SMS from CARRIER received at 11:42. The receiver did not run: check Autostart."
2. **SMS stuck**: more than 6 h without 200, or a `401`/`400` (immediate). A rejected token
   produces ONE single notification "Token rejected: N SMS waiting" (fixed id), not one per SMS. A
   `400` produces one per SMS (it is rare). Requeueing resets the "already notified" mark.
3. **Permissions lost**: periodic check (in the same sweep job) of SMS permissions, battery
   optimization (`PowerManager.isIgnoringBatteryOptimizations`), background restriction
   (`ActivityManager.isBackgroundRestricted`) and MIUI Autostart.

MIUI also has its own SMS switches in the Security app ("Read SMS", "Notification SMS"), separate
from the Android permission, which can block the receiver or the inbox even when Android says
"granted". They are handled like Autostart: best-effort detection (with `READ_SMS` granted, a
query of the whole inbox with no date filter and `LIMIT 1` that returns 0 rows is flagged
`MIUI_SMS_BLOCKED`; also if it fails with the permission granted), a warning with a button, and
the first launch tells the user to check them.

MIUI Autostart has no public API. Best-effort detection; if it cannot be determined, the main
screen shows it as "cannot be checked" with the button to the MIUI screen, and it is not notified
in a loop. The buttons open the corresponding MIUI activity if it exists and, if not, the app's settings.

Accepted limit: without a foreground service and with Autostart removed, no app is guaranteed to
run on MIUI. The real guarantee is that the SMS remains in the inbox and the next sweep that runs
(at the very least when the app is opened) recovers it.

## 9. Secrets and privacy

- URL and token are entered once in the app and stored encrypted with an AES-GCM key in the
  Android Keystore. Do not use `EncryptedSharedPreferences` (deprecated).
- Never in the repo, nor in logs, nor in tests, nor in exception messages. The SMS body is not
  logged to logcat either.
- Android backups disabled for the database and the settings (`allowBackup=false` or exclusion
  rules). `allowBackup=false` does not prevent Android 12+ device-to-device transfer: add
  `android:dataExtractionRules` excluding the database, datastore and files in both cloud backup
  and transfer, and `fullBackupContent` excluding them as well.
- If the Keystore key is missing or invalid (transfer, reset), the decryption failure is treated
  as "not configured" (`NOT_CONFIGURED` warning), never as an app crash.
- Real senders and personal configuration values live in a local `private/` folder, ignored by
  git, and are never committed.
- The repo is public, so:
  - gitleaks in CI on every push and PR;
  - `.gitignore` with `*.jks`, `*.keystore`, `local.properties`, `.superpowers/`, `.codebase-memory/`;
  - made-up test SMS in a realistic format, for example: "CARRIER: Se ha comprado un plan de
    datos de 2.5GB valido por 30 dias, se activa con el primer uso. Se desconto 360.00. Saldo
    actual 140.25". No real phone numbers or cards.

## 10. Relationship with the finance manager

**Standalone** app. The Worker is the integration point: the finance manager receives the SMS
through `/ingest/sms` regardless of where they come from. A future native SMS reader in its own
APK is no longer needed; that is noted in that repo, not in this one.

Two Gradle modules (a change made while planning): `:core`, pure Kotlin with no Android (filter,
SMS identity, JSON, response classification, queue rules), which is tested locally with only the
JDK; and `:app`, with everything Android (receiver, sweep, Room, WorkManager, UI). There is no
Android SDK locally, so without this split every test would have to go through GitHub Actions.

## 11. Build and release

- GitHub Actions:
  - On every push and PR: build, JVM unit tests, lint, gitleaks and an unsigned `assembleRelease`,
    so that an R8/minify breakage shows up before tagging.
  - When a `vX.Y.Z` tag is pushed: signed release APK, published in a GitHub Release.
- Signing: a single key. The user generates it on their PC with `keytool` (the plan gives the
  commands step by step), converts it to base64 and stores it in the repo secrets along with
  passwords and alias. They must keep a copy outside GitHub: without it the app cannot be updated
  without uninstalling (and uninstalling loses the queue).
- `versionCode` derived from the tag (`vX.Y.Z` → `X*10000 + Y*100 + Z`) so that every version
  installs over the previous one.
- Secrets are uploaded through the GitHub web UI (Settings → Secrets); the plan also gives the
  `gh secret set` command as an alternative.
- Installation: the phone will ask to allow "install unknown apps" for the browser and Play
  Protect may warn about an unverified app; both are accepted once. Download the APK from the
  Release on the phone and install it over the previous one.

## 12. Testing

- JVM unit tests (in CI): sender normalization and filter, multipart joining, building the local
  key and the JSON, HTTP response classification (200/400/401/5xx/network), queue and sweep logic
  (cursor, overlap, deduplication), "stuck" detection.
- No emulator tests in CI.
- Manual checklist on the real phone, included in the repo: normal SMS, multipart, no network and
  recovery, app force-stopped and sweep on open, wrong token, phone reboot, receiver/sweep
  match (§5).

## 13. Decisions I made (to review)

- Manual recovery of 7 days, none ticked (approved by the user: no automatic backward resend).
- 24 h overlap on every sweep.
- Both SIMs, filter by sender; SIM stored for display.
- "Stuck" threshold: 6 h. `401` and `400` notify instantly (a rejected token, in a single grouped
  notification).
- `400` with no automatic retry; `401` is requeued when the token changes.
- Sweep floor fixed when the first launch finishes: the sweep never reads before it.
- Nothing active (receiver or sweep) until the first launch finishes, and the clean-cutover
  reminder comes first.
- `minSdk 31` (the phone is already on Android 14).
- History retention: 90 days.
- "Test connection" with body `{}`.
- Theme: follows the system by default, with a selector; dynamic color if available.
- No update check in the app.
- Two Gradle modules (pure `:core` + `:app`), so the logic can be tested locally without the SDK.
- No default senders in the code: the list starts empty, the first launch requires at least one
  and `HealthIssue.NO_SENDERS` warns if it ends up empty. The real ones live in `private/`
  (git-ignored).

## 14. What is left to the builder

- Package name (generic, no personal data), dependency versions, HTTP library.
- Concrete Room schema and migrations.
- How to detect Autostart on MIUI and which exact intents open each settings screen.
- Fine visual design within Material 3.
- Structure of the Actions workflow and Gradle cache.
