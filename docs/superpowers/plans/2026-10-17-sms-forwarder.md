# SMS Forwarder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Code reviews: only with the `ocr-review` skill (OCR). If the sub-skill asks for reviewers (spec reviewer, code quality reviewer, `code-reviewer`), replace them with `ocr-review` on the task's diff.

**Goal:** Android app (Kotlin + Compose) that saves every SMS from chosen senders in a local queue and forwards it to the existing Worker until it receives 200, with an inbox sweep as a safety net.

**Architecture:** Two Gradle modules. `:core` is pure Kotlin (JVM, no Android): filter, SMS identity, JSON, response classification, queue rules. It is tested locally with only JDK 17. `:app` is the Android app: Room, WorkManager, receiver, sweep, Keystore, notifications and Compose UI. Its tests (Robolectric) run only in GitHub Actions.

**Tech Stack:** Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.1, Compose BOM 2026.09.00 (Material 3), Room 2.8.5 + KSP 2.3.12, WorkManager 2.12.0, DataStore 1.2.1, OkHttp 5.5.0, kotlinx-serialization-json 1.11.0 (only `buildJsonObject`/`parseToJsonElement`, no plugin), kotlinx-coroutines 1.11.0, JUnit 4 + kotlin-test, Robolectric 4.17, MockWebServer 3 (OkHttp 5).

**Spec:** `docs/superpowers/specs/2026-10-17-sms-forwarder-design.md`

## Global Constraints

- `minSdk = 31`, `compileSdk = 37` (required by Compose BOM 2026.09.00), `targetSdk = 36`. Package and `applicationId`: `dev.smsforwarder`. The debug build has `applicationIdSuffix = ".debug"` (coexists with the release).
- Versions pinned above in `gradle/libs.versions.toml`. Before Task 1, check the AGP 9 configuration with context7 (Kotlin built into AGP: do **not** apply `org.jetbrains.kotlin.android`) and the AGP 9.4.1 / Gradle 9.8.1 / KSP 2.3.12 compatibility. If something does not fit, adjust the minimum version needed and note it in the commit.
- There is no Android SDK locally. Locally: only `./gradlew :core:test` with JDK 17 (`/usr/lib/jvm/java-17-openjdk`). Everything in `:app` is verified in GitHub Actions (`gh run watch`).
- `settings.gradle.kts` includes `:app` only if `ANDROID_HOME` or `local.properties` exists. This way `:core` compiles without the SDK.
- No default senders in the code: the list starts empty and the first launch requires at least one. The real senders and other personal configuration values live in a local `private/` folder, ignored by git, and are never committed. Normalize = remove all whitespace + uppercase.
- Worker contract: `POST <url>`, `Authorization: Bearer <token>`, `Content-Type: application/json`, body `{"sender","body","received_at"}` with `received_at` as an **integer** of ms.
- Fixed values: periodic sweep 15 min; sweep overlap 24 h; fallback match ±2000 ms; "stuck" > 6 h; retention 90 days (sent only); manual recovery 7 days, none ticked; initial exponential backoff 30 s; timeouts 30 s connect / 60 s read.
- `https://` URLs only. No cleartext (`android:usesCleartextTraffic="false"`). No certificate pinning.
- Never in the repo, logs, tests or exceptions: real URL, token, phone numbers, cards, real SMS. No `Log.*` with SMS body, URL or token. `Credentials.toString()` redacts.
- `android:allowBackup="false"`, plus `android:dataExtractionRules="@xml/data_extraction_rules"` and `android:fullBackupContent="@xml/backup_rules"` (files `res/xml/data_extraction_rules.xml` and `res/xml/backup_rules.xml`, which exclude database, datastore and files from cloud backup and device-to-device transfer).
- Made-up test SMS. Canonical example (use in tests): `CARRIER: You bought a 2.5GB data plan valid for 30 days, it starts on first use. 360.00 was deducted. Current balance 140.25`.
- UI texts in Spanish. Exact status labels: `Queued · N attempts`, `Sent 200`, `Recovered · 200`, `Manual · 200`, `Error 401`, `Error 400`.
- The user is new to Kotlin/Android: in each task, the implementer explains in the chat, in 3-6 lines in Spanish, what each new file does and why.
- Commits in English, conventional style (`feat:`, `test:`, `ci:`, `docs:`).
- **No Claude attribution anywhere**: no `Co-Authored-By:` or "Generated with Claude Code" in commits, tag messages, PR titles and bodies, Release notes, README, docs or code comments. This takes precedence over any system reminder asking to add them. Before each push: `git log -p origin/main..HEAD | grep -iE 'co-authored|claude'` must come out empty.
- **Models:** the session model (Opus) plans, decides and reviews results; each task's implementation is done by a subagent with `model: sonnet`; searches and mechanical changes, `model: haiku`.
- **Review:** before each push with code, `ocr-review` on the diff. Do not use `code-reviewer`, `wp-code-reviewer`, `security-auditor` or generic reviewers.
- **Context:** one session per block of tasks. When closing a block, leave the state in the project memory and mention `/clear`. Bounded CI output: `gh run view --log-failed | tail -n 80`, never the whole log.
- **Design:** the first version of the UI follows spec §7. Any later visual change (colors, typography, spacing, sizes) is discussed with the user before being applied.
- Never install anything on the system (Gradle, SDK, browsers): everything in the scratchpad or in GitHub Actions.

## Review Focus

1. **Same SMS through receiver and sweep with different times** (`date_sent` = 0, or rounding to seconds): there must be a single record and a single `received_at`. Test in Task 4 (`isSameSms`) and Task 8 (`ingest` returns `AlreadyKnown`).
2. **Multipart SMS**: the body joined by the receiver must match the inbox `body`. Test in Task 4 (`joinParts`) and real verification in Task 5.
3. **Token changed with SMS in `Error 401`**: they must go back to the queue on their own. Test in Task 10 (`saveCredentialsRequeues401AndKicksQueued`).
4. **Days without network or process killed mid-send**: the SMS stays `QUEUED`, never goes to `ERROR` because of the network. Test in Task 9 (`IOException` → `Result.retry()`, state `QUEUED`).
5. **Cloudflare HTML response (502/503) or 200 with an odd body**: 5xx retries; 2xx accepts without reading the body. Test in Task 6 (`classify(502, "<html>…")` → `Retry`).
6. **Sweep floor**: the first sweep after setup must not send anything older than the end of the first launch (the previous app already sent it with a different time). Tests in Task 7 (`sweepFromNeverBelowFloor`) and Task 11 (`sweepNeverReadsBeforeFloor`).
7. **Nothing active before the first launch**: receiver and sweep do nothing while `onboardingDone` is false. Tests in Task 11 (`SmsReceiverTest.ignoredBeforeOnboarding`, `sweepBeforeOnboardingDoesNothing`).

---

## File Structure

```
settings.gradle.kts                 # includes :core always, :app if there is an SDK
build.gradle.kts                    # plugins with apply false
gradle/libs.versions.toml           # all versions
gradle/wrapper/…, gradlew, gradlew.bat
.github/workflows/ci.yml            # push/PR: core test, app test, lint, assembleDebug, gitleaks
.github/workflows/release.yml       # tag v*: signed assembleRelease + GitHub Release
docs/signing.md                     # create the key and the secrets, step by step
docs/manual-test-checklist.md       # tests on the real phone
core/build.gradle.kts
core/src/main/kotlin/dev/smsforwarder/core/
  Clock.kt                          # fun interface Clock
  SenderFilter.kt                   # normalize, matches
  SmsIdentity.kt                    # SmsRecord, localKey, effectiveReceivedAt, isSameSms, joinParts
  IngestPayload.kt                  # encode JSON
  SendOutcome.kt                    # classify responses, ConnectionTestResult
  QueuePolicy.kt                    # SmsStatus, SmsOrigin, StatusLabel, isStuck, retentionCutoff, sweepFrom
  UrlValidator.kt                   # https only
core/src/test/kotlin/dev/smsforwarder/core/*Test.kt
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/kotlin/dev/smsforwarder/
  SmsForwarderApp.kt                # Application: creates AppGraph, schedules the periodic sweep
  AppGraph.kt                       # manual dependency container (no Hilt)
  data/SmsEntity.kt, AttemptEntity.kt, SmsDao.kt, AppDatabase.kt
  data/SmsRepository.kt             # ingest with deduplication, state changes
  data/SettingsRepository.kt        # DataStore: senders, theme, cursor, onboarding
  data/SecretStore.kt               # Keystore AES-GCM + DataStore
  sms/SmsReceiver.kt                # SMS_RECEIVED
  sms/BootReceiver.kt               # BOOT_COMPLETED → sweep
  sms/InboxReader.kt                # content://sms/inbox
  sms/Sweeper.kt                    # sweep logic
  work/SendWorker.kt, SweepWorker.kt, WorkScheduler.kt
  net/IngestClient.kt               # OkHttp
  health/HealthChecker.kt, MiuiIntents.kt
  notify/Notifier.kt
  ui/MainActivity.kt, ui/theme/Theme.kt, ui/Nav.kt
  ui/onboarding/*, ui/main/*, ui/detail/*, ui/settings/*, ui/recovery/*
app/src/debug/
  AndroidManifest.xml               # receiver and diagnostics activity
  kotlin/dev/smsforwarder/diag/*    # diagnostics screen (debug only)
app/src/test/kotlin/dev/smsforwarder/**  # Robolectric
```

---

### Task 1: Gradle skeleton and `:core` module

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`), `core/build.gradle.kts`, `core/src/main/kotlin/dev/smsforwarder/core/Clock.kt`, `README.md`
- Modify: `.gitignore` (add `.idea/`, `*.iml`, `.kotlin/`)
- Test: `core/src/test/kotlin/dev/smsforwarder/core/ClockTest.kt`

**Interfaces:**
- Produces: `fun interface Clock { fun now(): Long }` and `object SystemClock : Clock` (epoch ms).

- [ ] **Step 1: Wrapper.** Gradle is not installed. Download `gradle-9.8.1-bin.zip` from `services.gradle.org` into the scratchpad, unzip it there and run `<scratch>/gradle-9.8.1/bin/gradle wrapper --gradle-version 9.8.1` in the root with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`. Do not install anything on the system.
- [ ] **Step 2: Catalog and settings.** Versions from the header in `libs.versions.toml`. `settings.gradle.kts`: `include(":core")` and `if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) include(":app")`. `core` uses the `org.jetbrains.kotlin.jvm` plugin, `jvmToolchain(17)`, dependencies `kotlinx-serialization-json`, test `kotlin("test-junit")`.
- [ ] **Step 3: Failing test.**

```kotlin
class ClockTest {
    @Test fun systemClockIsEpochMillis() {
        val now = SystemClock.now()
        assertTrue(now > 1_700_000_000_000L)
    }
}
```

- [ ] **Step 4:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test` → FAIL (`Unresolved reference: SystemClock`).
- [ ] **Step 5:** Implement `Clock.kt`.
- [ ] **Step 6:** Same command → `BUILD SUCCESSFUL`.
- [ ] **Step 7:** Minimal `README.md`: what it is, how it is built (Actions), link to `docs/signing.md` (still to be written). Commit `chore: gradle skeleton with pure-kotlin core module`.

### Task 2: Minimal app, CI and GitHub repo

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/smsforwarder/ui/MainActivity.kt`, `app/src/main/kotlin/dev/smsforwarder/ui/theme/Theme.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/test/kotlin/dev/smsforwarder/SmokeTest.kt`, `.github/workflows/ci.yml`, `.gitleaks.toml` (optional, only if an allowlist is needed)

**Interfaces:**
- Produces: `@Composable fun SmsForwarderTheme(mode: ThemeMode, content: @Composable () -> Unit)` with `enum class ThemeMode { SYSTEM, LIGHT, DARK }` (in `ui/theme/Theme.kt`). Dynamic color always available (with `minSdk 31` there is no need to check `SDK_INT`).

- [ ] **Step 1:** `app/build.gradle.kts`: plugins `com.android.application`, `org.jetbrains.kotlin.plugin.compose`, `com.google.devtools.ksp`; values from Global Constraints; `buildFeatures { compose = true }`; `testOptions.unitTests.isIncludeAndroidResources = true`; `implementation(project(":core"))`; `debugImplementation` of `androidx.compose.ui:ui-test-manifest` (required by `createAndroidComposeRule`) and `testImplementation` of `androidx.compose.ui:ui-test-junit4`. Release: `isMinifyEnabled = true`, `isShrinkResources = true`.
- [ ] **Step 2:** Manifest: `allowBackup="false"`, `dataExtractionRules`, `fullBackupContent`, `usesCleartextTraffic="false"`, `MainActivity` launcher. Create `res/xml/data_extraction_rules.xml` (`<cloud-backup>` and `<device-transfer>` with `<exclude>` of `database`, `sharedpref` and `file`; the datastore lives under `files/datastore`) and `res/xml/backup_rules.xml` (`<full-backup-content>` with the same `<exclude>`s). `MainActivity` shows `Text("SMS Forwarder")` inside `SmsForwarderTheme(ThemeMode.SYSTEM)`.
- [ ] **Step 3:** Robolectric smoke test:

```kotlin
@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun showsTitle() { compose.onNodeWithText("SMS Forwarder").assertExists() }
}
```

- [ ] **Step 4:** `ci.yml`, on `push` and `pull_request`. A `build` job on `ubuntu-latest`:
  - `actions/checkout`, `actions/setup-java` (temurin 17), `gradle/actions/setup-gradle`;
  - `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` (the release comes out unsigned: `signingConfig` only applies if `SIGNING_STORE_FILE` exists, see Task 15);
  - upload `app/build/outputs/apk/debug/*.apk` as artifact `app-debug`.
  
  A `gitleaks` job with `gitleaks/gitleaks-action@v2` and `fetch-depth: 0`.
- [ ] **Step 5:** Commit `ci: minimal compose app with build, test, lint and gitleaks`.
- [ ] **Step 6: Ask the user for confirmation** before creating the public repo (it is publishing). With their ok: `gh repo create sms-forwarder --public --source . --push`.
- [ ] **Step 7:** `gh run watch` → both jobs green. If it fails, fix and repeat; do not continue on red.

### Task 3: Sender filter (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/SenderFilter.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/SenderFilterTest.kt`

**Interfaces:**
- Produces: `object SenderFilter { fun normalize(sender: String): String; fun matches(sender: String, allowed: Collection<String>): Boolean }` (no default list; with an empty list `matches` is always false)`

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun normalizeRemovesAllWhitespaceAndUppercases() =
    assertEquals("BANCOX", SenderFilter.normalize(" Banco x\t"))
@Test fun matchesIgnoringCaseAndSpaces() {
    val allowed = listOf("CARRIER", "BANCO")
    assertTrue(SenderFilter.matches("carrier ", allowed))
    assertTrue(SenderFilter.matches("BANCO", listOf(" banco")))
}
@Test fun doesNotMatchPrefixOrOtherSenders() {
    val allowed = listOf("CARRIER", "BANCO")
    assertFalse(SenderFilter.matches("CARRIER-PROMO", allowed))
    assertFalse(SenderFilter.matches("+10000000000", allowed))
    assertFalse(SenderFilter.matches("", allowed))
}
@Test fun emptyAllowedListMatchesNothing() =
    assertFalse(SenderFilter.matches("CARRIER", emptyList()))
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implement. Space = `Char.isWhitespace()`; uppercase with `uppercase(Locale.ROOT)`.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): sender filter with whitespace and case normalization`.

### Task 4: SMS identity and multipart joining (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/SmsIdentity.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/SmsIdentityTest.kt`

**Interfaces:**
- Consumes: `SenderFilter.normalize`.
- Produces:
  - `data class SmsRecord(val sender: String, val body: String, val receivedAt: Long, val phoneDate: Long?, val subId: Int?)`. `receivedAt` = service center time in ms, the value that is sent.
  - `object SmsIdentity`, with:
    - `fun effectiveReceivedAt(dateSent: Long?, date: Long): Long`;
    - `fun bodyHash(body: String): String` (full SHA-256 hex);
    - `fun localKey(r: SmsRecord): String` = `normalize(sender) + "|" + receivedAt + "|" + bodyHash(body)`;
    - `fun isSameSms(a: SmsRecord, b: SmsRecord): Boolean`;
    - `const val FALLBACK_WINDOW_MS = 2_000L`.
  - `fun joinParts(parts: List<String>): String`.

- [ ] **Step 1: Failing tests** (use the canonical SMS from Global Constraints as `BODY`).

```kotlin
@Test fun effectiveReceivedAtPrefersDateSent() = assertEquals(1000L, SmsIdentity.effectiveReceivedAt(1000L, 5000L))
@Test fun effectiveReceivedAtFallsBackToDateWhenZeroOrNull() {
    assertEquals(5000L, SmsIdentity.effectiveReceivedAt(0L, 5000L))
    assertEquals(5000L, SmsIdentity.effectiveReceivedAt(null, 5000L))
}
@Test fun localKeyIsStableAcrossSenderFormatting() = assertEquals(
    SmsIdentity.localKey(SmsRecord("CARRIER", BODY, 1_792_260_900_000, null, 1)),
    SmsIdentity.localKey(SmsRecord(" carrier", BODY, 1_792_260_900_000, 1_792_260_901_234, 2)))
@Test fun sameSmsWithinTwoSeconds() = assertTrue(SmsIdentity.isSameSms(
    SmsRecord("CARRIER", BODY, 1_792_260_900_000, null, null),
    SmsRecord("CARRIER", BODY, 1_792_260_901_999, null, null)))
@Test fun notSameBeyondWindowOrDifferentBody() {
    val a = SmsRecord("CARRIER", BODY, 1_792_260_900_000, null, null)
    assertFalse(SmsIdentity.isSameSms(a, a.copy(receivedAt = a.receivedAt + 2_001)))
    assertFalse(SmsIdentity.isSameSms(a, a.copy(body = BODY + " ")))
    assertFalse(SmsIdentity.isSameSms(a, a.copy(sender = "BANCO")))
}
@Test fun joinPartsConcatenatesInOrderWithoutSeparator() =
    assertEquals("abcdef", joinParts(listOf("abc", "def")))
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implement. `isSameSms`: same `localKey`, or (same normalized sender, exactly the same `body` and `|Δ receivedAt| <= FALLBACK_WINDOW_MS`).
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): sms identity, local key and multipart join`.

### Task 5: Diagnostics screen (debug only) and verification on the phone

First real check of spec §5. **Blocks tasks 8 onwards** until the user confirms the values.

**Files:**
- Create: `app/src/debug/AndroidManifest.xml`, `app/src/debug/kotlin/dev/smsforwarder/diag/DiagReceiver.kt`, `app/src/debug/kotlin/dev/smsforwarder/diag/DiagStore.kt`, `app/src/debug/kotlin/dev/smsforwarder/diag/DiagActivity.kt`
- Test: `app/src/testDebug/kotlin/dev/smsforwarder/diag/DiagStoreTest.kt`

**Interfaces:**
- Consumes: `SenderFilter`, `SmsIdentity.bodyHash`, `joinParts`.
- Produces: nothing for the release (everything in `src/debug`).

- [ ] **Step 1:** Test: `DiagStore` (JSON file in `filesDir/diag.json`, at most 20 entries, newest first). Saving 21 and re-reading gives 20, and the first is the last one saved. Fields: `sender`, `pduTimestamp`, `bodyHash` (first 12 characters), `bodyLength`, `parts`, `subId`, `capturedAt`. **Never** the body.
- [ ] **Step 2:** Implement `DiagStore`, `DiagReceiver` (debug manifest, `SMS_RECEIVED`, no sender filtering; `subId` from the `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX` extra, the same one the real receiver in Task 11 uses) and `DiagActivity`, with its own launcher "SMS Diag":
  - asks for `RECEIVE_SMS` and `READ_SMS`;
  - lists the receiver's captures;
  - lists the 20 most recent rows of `content://sms/inbox` with `address`, `date`, `date_sent`, `sub_id`, hash and body length;
  - marks in green the pairs with equal `localKey` and in amber those that only match through `isSameSms`.
- [ ] **Step 3:** Push → `gh run watch` → green. `gh run download -n app-debug`.
- [ ] **Step 4: With the user**, step by step:
  1. Install the debug APK. Over USB with `adb install`, or by transferring it to the phone.
  2. Grant the permissions.
  3. Wait for or trigger an SMS from one of your senders (for example, a balance query).
  4. If possible, get a multipart one to arrive.
  5. Open "SMS Diag" and report, without sending screenshots with real data: color of the pair, `date_sent` ≠ 0, same body length.
- [ ] **Step 5:** Depending on the result:
  - All green: note in spec §5 "verified on the phone 2026-10-xx".
  - Amber or no pair: adjust §5 and Task 4 (for example, a larger window or normalizing `\r\n`) with the user before continuing.
  
  Commit `feat(debug): sms diagnostics screen`.

### Task 6: JSON, response classification and URL (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/IngestPayload.kt`, `SendOutcome.kt`, `UrlValidator.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/IngestPayloadTest.kt`, `SendOutcomeTest.kt`, `UrlValidatorTest.kt`

**Interfaces:**
- Produces:
  - `object IngestPayload { fun encode(sender: String, body: String, receivedAt: Long): String }`;
  - `sealed interface SendOutcome { data object Accepted; data class Retry(val httpCode: Int?, val reason: String); data class Rejected(val httpCode: Int, val errorCode: String?) }`;
  - `fun classify(httpCode: Int?, responseBody: String?): SendOutcome` (`httpCode == null` = network error);
  - `enum class ConnectionTestResult { OK, BAD_TOKEN, UNREACHABLE }` + `fun interpretConnectionTest(httpCode: Int?): ConnectionTestResult`;
  - `object UrlValidator { fun isValid(url: String): Boolean }`.

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun encodesReceivedAtAsNumberAndEscapes() {
    val json = Json.parseToJsonElement(IngestPayload.encode("CARRIER", "a \"b\"\nc", 1_792_260_900_000)).jsonObject
    assertEquals("CARRIER", json["sender"]!!.jsonPrimitive.content)
    assertEquals("a \"b\"\nc", json["body"]!!.jsonPrimitive.content)
    assertFalse(json["received_at"]!!.jsonPrimitive.isString)
    assertEquals(1_792_260_900_000, json["received_at"]!!.jsonPrimitive.long)
}
@Test fun classify200and2xxAccepted() { assertEquals(SendOutcome.Accepted, classify(200, "")); assertEquals(SendOutcome.Accepted, classify(204, null)) }
@Test fun classify401Rejected() = assertEquals(SendOutcome.Rejected(401, "unauthorized"), classify(401, """{"error":"unauthorized"}"""))
@Test fun classify400ReadsErrorCode() = assertEquals(SendOutcome.Rejected(400, "invalid_request"), classify(400, """{"error":"invalid_request"}"""))
@Test fun classify400WithGarbageBody() = assertEquals(SendOutcome.Rejected(400, null), classify(400, "<html>"))
@Test fun networkAnd5xxAnd429Retry() {
    assertTrue(classify(null, null) is SendOutcome.Retry)
    assertTrue(classify(502, "<html>cloudflare</html>") is SendOutcome.Retry)
    assertTrue(classify(429, null) is SendOutcome.Retry)
    assertTrue(classify(404, null) is SendOutcome.Retry)
}
@Test fun connectionTest() {
    assertEquals(ConnectionTestResult.OK, interpretConnectionTest(400))
    assertEquals(ConnectionTestResult.BAD_TOKEN, interpretConnectionTest(401))
    assertEquals(ConnectionTestResult.UNREACHABLE, interpretConnectionTest(null))
    assertEquals(ConnectionTestResult.UNREACHABLE, interpretConnectionTest(200))
}
@Test fun urlOnlyHttpsWithHost() {
    assertTrue(UrlValidator.isValid("https://worker.example.dev/ingest/sms"))
    assertFalse(UrlValidator.isValid("http://worker.example.dev/ingest/sms"))
    assertFalse(UrlValidator.isValid("https://"))
    assertFalse(UrlValidator.isValid("worker.example.dev"))
}
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implement:
  - JSON with `buildJsonObject`;
  - the error code of a 400/401 comes from the `error` field of the body, if it is valid JSON;
  - `UrlValidator` uses `java.net.URI` and requires `scheme == "https"` and a non-empty host.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): ingest payload, response classification and url validation`.

### Task 7: Queue rules and labels (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/QueuePolicy.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/QueuePolicyTest.kt`

**Interfaces:**
- Produces:
  - `enum class SmsStatus { QUEUED, SENT, ERROR }`;
  - `enum class SmsOrigin { RECEIVER, SWEEP, MANUAL }`;
  - `object QueuePolicy`, with:
    - `const val STUCK_AFTER_MS = 6 * 3_600_000L`;
    - `const val RETENTION_MS = 90 * 86_400_000L`;
    - `const val SWEEP_OVERLAP_MS = 24 * 3_600_000L`;
    - `const val MANUAL_RECOVERY_MS = 7 * 86_400_000L`;
    - `fun isStuck(status: SmsStatus, lastHttpCode: Int?, firstSeenAt: Long, now: Long): Boolean`;
    - `fun retentionCutoff(now: Long): Long`;
    - `fun sweepFrom(cursor: Long, floor: Long): Long = max(cursor - SWEEP_OVERLAP_MS, floor)`.
  - `fun statusLabel(status: SmsStatus, origin: SmsOrigin, attempts: Int, lastHttpCode: Int?): String`.

- [ ] **Step 1: Failing tests.**

```kotlin
@Test fun stuckAfterSixHoursQueued() {
    assertFalse(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, QueuePolicy.STUCK_AFTER_MS))
    assertTrue(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, QueuePolicy.STUCK_AFTER_MS + 1))
}
@Test fun error401IsStuckImmediately() = assertTrue(QueuePolicy.isStuck(SmsStatus.ERROR, 401, 1000, 1001))
@Test fun error400IsStuckImmediately() = assertTrue(QueuePolicy.isStuck(SmsStatus.ERROR, 400, 1000, 1001))
@Test fun sentIsNeverStuck() = assertFalse(QueuePolicy.isStuck(SmsStatus.SENT, 200, 0, Long.MAX_VALUE))
@Test fun sweepFromSubtracts24h() = assertEquals(100L, QueuePolicy.sweepFrom(100L + 86_400_000L, 0L))
@Test fun sweepFromNeverBelowFloor() = assertEquals(500L, QueuePolicy.sweepFrom(100L + 86_400_000L, 500L))
@Test fun labels() {
    assertEquals("Queued · 3 attempts", statusLabel(SmsStatus.QUEUED, SmsOrigin.RECEIVER, 3, null))
    assertEquals("Sent 200", statusLabel(SmsStatus.SENT, SmsOrigin.RECEIVER, 1, 200))
    assertEquals("Recovered · 200", statusLabel(SmsStatus.SENT, SmsOrigin.SWEEP, 1, 200))
    assertEquals("Manual · 200", statusLabel(SmsStatus.SENT, SmsOrigin.MANUAL, 1, 200))
    assertEquals("Error 401", statusLabel(SmsStatus.ERROR, SmsOrigin.RECEIVER, 2, 401))
    assertEquals("Error 400", statusLabel(SmsStatus.ERROR, SmsOrigin.SWEEP, 1, 400))
}
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implement. `isStuck` is true for `ERROR` with 400 or 401.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): queue policy and status labels`.

### Task 8: Room database and SMS repository

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/SmsEntity.kt`, `AttemptEntity.kt`, `SmsDao.kt`, `AppDatabase.kt`, `SmsRepository.kt`, `app/src/main/kotlin/dev/smsforwarder/AppGraph.kt`, `SmsForwarderApp.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`android:name=".SmsForwarderApp"`)
- Test: `app/src/test/kotlin/dev/smsforwarder/data/SmsRepositoryTest.kt`

**Interfaces:**
- Consumes: `SmsRecord`, `SmsIdentity`, `SmsStatus`, `SmsOrigin`, `QueuePolicy`, `Clock`.
- Produces:
  - `SmsEntity`, in table `sms`, with:
    - `id: Long` (auto PK);
    - `localKey: String` (unique index);
    - `sender`, `senderNormalized`, `body: String`;
    - `receivedAt: Long` (index), `phoneDate: Long?`, `subId: Int?`;
    - `origin: SmsOrigin`, `status: SmsStatus`;
    - `attempts: Int`, `lastHttpCode: Int?`, `lastError: String?`;
    - `firstSeenAt: Long`, `sentAt: Long?`, `stuckNotified: Boolean`.
    
    `toRecord(): SmsRecord`.
  - `AttemptEntity`, in table `attempt`, with `id`, `smsId` (FK with cascade), `at`, `httpCode: Int?`, `error: String?`.
  - `sealed interface IngestResult { data class Inserted(val id: Long); data class AlreadyKnown(val id: Long) }`.
  - `class SmsRepository(db: AppDatabase, clock: Clock)`, with:
    - `suspend fun ingest(record: SmsRecord, origin: SmsOrigin): IngestResult`;
    - `suspend fun get(id: Long): SmsEntity?`;
    - `fun observeAll(): Flow<List<SmsEntity>>` (most recent first);
    - `fun observeAttempts(smsId: Long): Flow<List<AttemptEntity>>`;
    - `suspend fun recordAttempt(id: Long, outcome: SendOutcome)` (updates state, `attempts`, `lastHttpCode`, `sentAt`, inserts an `AttemptEntity`);
    - `suspend fun requeue(id: Long)`, which puts the SMS back to `QUEUED` and sets `stuckNotified = false`;
    - `suspend fun queuedIds(): List<Long>` (all `QUEUED`);
    - `suspend fun idsWithError401(): List<Long>`;
    - `suspend fun purgeOld()`, which deletes only `SENT` with `receivedAt < retentionCutoff`;
    - `suspend fun stuckToNotify(): List<SmsEntity>` (excludes the 401 rows: the token notification covers them) and `suspend fun markStuckNotified(id: Long)`.
  - `class AppGraph(context: Context)`: `val db`, `val clock = SystemClock`, `val smsRepository` (fields will be added in later tasks). `SmsForwarderApp.graph: AppGraph`.

- [ ] **Step 1: Failing Robolectric tests** (in-memory DB, fixed `Clock`):
  - `ingestTwiceSameRecordIsAlreadyKnown`
  - `ingestWithin2sSameBodyIsAlreadyKnownAndKeepsFirstReceivedAt`: inserts through `RECEIVER` with `receivedAt = T`, ingests through `SWEEP` with `T + 1500` → `AlreadyKnown`, and `get(id).receivedAt == T`.
  - `recordAttemptRetryKeepsQueued`: `Retry(null, "io")` → `QUEUED`, `attempts == 1`.
  - `recordAttemptAcceptedSetsSent`
  - `recordAttempt401SetsErrorAndIsListedIn401`
  - `purgeOldKeepsQueuedAndErrorEvenIfOld`
- [ ] **Step 2:** Push → `gh run watch` → FAIL in `testDebugUnitTest`.
- [ ] **Step 3:** Implement:
  - `ingest` in a transaction: look up by `localKey`; if not found, look up candidates with the same `senderNormalized` and `receivedAt` between `T ± 2000` and check with `isSameSms`; if there is no match, insert.
  - `TypeConverter` for the enums.
  - `exportSchema = true` with `room.schemaLocation` in `app/schemas`.
- [ ] **Step 4:** Push → `gh run watch` → PASS.
- [ ] **Step 5:** Commit `feat(app): room queue with dedup-aware ingest`.

### Task 9: HTTP client, encrypted credentials and `SendWorker`

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/net/IngestClient.kt`, `data/SecretStore.kt`, `work/SendWorker.kt`, `work/WorkScheduler.kt`
- Modify: `AppGraph.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/net/IngestClientTest.kt`, `work/SendWorkerTest.kt`, `data/CredentialsTest.kt`

**Interfaces:**
- Consumes: `IngestPayload`, `classify`, `SendOutcome`, `SmsRepository`.
- Produces:
  - `data class Credentials(val url: String, val token: String)`, whose `toString()` returns `"Credentials(***)"`;
  - `interface SecretStore`, with `suspend fun get(): Credentials?` and `suspend fun set(c: Credentials)`;
    - `class KeystoreSecretStore(context)`: AES-256-GCM key with alias `smsfwd-secrets` in `AndroidKeyStore`; ciphertext + IV in base64 in DataStore (`secrets.preferences_pb`); `get()` catches `GeneralSecurityException` (includes `AEADBadTagException` and `KeyPermanentlyInvalidatedException`) and a missing key: returns `null` and deletes the stored blob. It is tested on the JVM with a fake cipher if feasible; if not, it goes to the manual list in Task 15.
  - `class IngestClient(http: OkHttpClient)`, with:
    - `suspend fun send(c: Credentials, sender: String, body: String, receivedAt: Long): SendOutcome`;
    - `suspend fun testConnection(c: Credentials): ConnectionTestResult` (POST `{}`).
    
    `fun defaultHttpClient(): OkHttpClient` (timeouts from Global Constraints).
  - `class SendWorker : CoroutineWorker`, with input `KEY_SMS_ID = "smsId"`.
  - `object WorkScheduler`, with:
    - `fun enqueueSend(context, smsId: Long, replace: Boolean = false)`: unique `"send-$smsId"`, `KEEP` by default and `REPLACE` if `replace = true`, `CONNECTED` network, exponential backoff 30 s, `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` (no expedited on the path with `replace = true`);
    - `fun kickQueued(context, ids: List<Long>)`: `enqueueSend(context, id, replace = true)` for each id;
    - `fun schedulePeriodicSweep(context)` and `fun sweepNow(context)`, filled in in Task 11.
  - For the tests, `SendWorker` gets its dependencies from `AppGraph`, with a replaceable `SecretStore`.

- [ ] **Step 1: Failing tests.**
  - `CredentialsTest.toStringRedacts`: `toString()` contains neither the URL nor the token.
  - `IngestClientTest` (MockWebServer):
    - `sendsBearerJsonAndNumberReceivedAt`: verifies the `Authorization: Bearer test-token` header, the `Content-Type` and that the body has a numeric `received_at`;
    - `maps401ToRejected`;
    - `disconnectIsRetry` (`SocketPolicy.DISCONNECT_AT_START`);
    - `testConnectionSendsEmptyObject`.
  - `SendWorkerTest` (`TestListenableWorkerBuilder`, `FakeSecretStore`, MockWebServer):
    - `ioErrorReturnsRetryAndStaysQueued`;
    - `200MarksSent`;
    - `401ReturnsSuccessAndMarksError`: the work finishes without retry;
    - `missingCredentialsReturnsRetry`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implement. If the SMS is already `SENT`, `SendWorker` returns `success()`. `Retry` → `Result.retry()`. `Accepted`/`Rejected` → `Result.success()`. No log with URL, token or body.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): encrypted credentials, ingest client and send worker`.

### Task 10: Persistent settings

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/SettingsRepository.kt`
- Modify: `AppGraph.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `ThemeMode`, `SecretStore`, `SmsRepository.idsWithError401`, `SmsRepository.queuedIds`, `WorkScheduler.enqueueSend`.
- Produces: `class SettingsRepository(context, secretStore, smsRepository, scheduler: (Long) -> Unit)`, with:
  - `val senders: Flow<List<String>>`, `suspend fun addSender(s: String)`, `suspend fun removeSender(s: String)` (compares normalized values);
  - `val themeMode: Flow<ThemeMode>`, `suspend fun setThemeMode(m: ThemeMode)`;
  - `suspend fun sweepCursor(): Long?`, `suspend fun setSweepCursor(t: Long)`;
  - `suspend fun sweepFloor(): Long?`, `suspend fun setSweepFloor(t: Long)`;
  - `val onboardingDone: Flow<Boolean>`, `suspend fun setOnboardingDone()`;
  - `suspend fun saveCredentials(c: Credentials)`, which saves and then requeues all SMS from `idsWithError401()` and all `QUEUED` ones (those from before the credentials were in backoff because of `missingCredentialsReturnsRetry`);
  - `suspend fun lastNotifiedIssues(): Set<String>`, `suspend fun setLastNotifiedIssues(s: Set<String>)`.

- [ ] **Step 1: Failing tests:**
  - `sendersStartEmpty`: with no prior configuration, `senders` emits an empty list;
  - `addSenderIgnoresDuplicateAfterNormalization`: with `"CARRIER"` already stored, adding `" carrier"` does not create a second entry;
  - `saveCredentialsRequeues401AndKicksQueued`: with one SMS in `Error 401` and another `QUEUED`, after saving, the first is `QUEUED` and the `scheduler` received both ids;
  - `removeSenderMatchesNormalized`: removing `"carrier "` deletes `"CARRIER"`;
  - `themeDefaultsToSystem`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implement with DataStore Preferences (`settings.preferences_pb`). Senders stored as the user typed them.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): settings repository with 401 requeue on token change`.

### Task 11: Receiver, inbox reader, sweep and boot

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/sms/SmsReceiver.kt`, `sms/BootReceiver.kt`, `sms/InboxReader.kt`, `sms/Sweeper.kt`, `work/SweepWorker.kt`
- Modify: `AndroidManifest.xml` (permissions `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (used by the battery button with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); receivers with `exported=true` and `android:permission="android.permission.BROADCAST_SMS"` on the SMS one), `WorkScheduler.kt`, `SmsForwarderApp.kt` (schedule the periodic sweep in `onCreate`)
- Test: `app/src/test/kotlin/dev/smsforwarder/sms/SweeperTest.kt`, `sms/SmsReceiverTest.kt`

**Interfaces:**
- Consumes: `SenderFilter`, `SmsIdentity`, `joinParts`, `SmsRepository.ingest`, `SettingsRepository`, `WorkScheduler.enqueueSend`/`kickQueued`, `SmsRepository.queuedIds`, `QueuePolicy.sweepFrom`.
- Produces:
  - `data class InboxRow(val address: String, val body: String, val date: Long, val dateSent: Long, val subId: Int?)`;
  - `interface InboxReader { fun readSince(fromDate: Long): List<InboxRow>?; fun hasAnyRow(): Boolean? }`: `readSince` returns `null` if the cursor is `null` or there is a `SecurityException`; `hasAnyRow()` queries the whole inbox with no date filter and `LIMIT 1` and returns `null` on `SecurityException`;
    - `class ContentInboxReader(context)`;
  - `class Sweeper(reader, smsRepository, settings, clock, enqueue: (Long) -> Unit, onRecovered: (List<SmsEntity>) -> Unit)`, with `suspend fun sweep(): SweepResult`;
  - `data class SweepResult(val recovered: Int, val inboxUnreadable: Boolean)`: `inboxUnreadable` is true if `readSince` returns `null` or if `hasAnyRow() == false` with the permission granted;
  - `SweepWorker`: periodic `"sweep-periodic"` every 15 min (`KEEP`) and one-off `"sweep-now"` (`REPLACE`), with no network constraint. It runs `sweep()`, then `purgeOld()`, then `WorkScheduler.kickQueued` with `queuedIds()`, and then calls `HealthChecker` and the stuck notification (hooked in Task 12).

- [ ] **Step 1: Failing tests** (`FakeInboxReader`):
  - `sweepBeforeOnboardingDoesNothing`: with `onboardingDone` false it reads and inserts nothing.
  - `sweepNeverReadsBeforeFloor`: with cursor = floor + 1 h, the requested `fromDate` is the floor. With no cursor but with a floor, it reads from the floor.
  - `sweepIngestsMatchingSendersSinceCursorMinus24h`: check the requested `fromDate` (with the floor much earlier).
  - `sweepSkipsSmsAlreadyFromReceiverAndDoesNotNotify`.
  - `sweepRecoversMissingAndCallsOnRecovered`: `origin == SWEEP` and `enqueue` is called.
  - `sweepUsesDateWhenDateSentIsZero`.
  - `nullInboxReportsUnreadable`.
  - `emptyWholeInboxWithPermissionIsUnreadable`: `hasAnyRow() == false` with the permission granted → `inboxUnreadable`.
  - `SmsReceiverTest.multipartFromFilteredSenderIsStoredOnceAndEnqueued`: build the `SMS_RECEIVED` intent with two test PDUs; a test helper that generates the PDUs (GSM 7-bit, alphanumeric sender `CARRIER`).
  - `SmsReceiverTest.otherSenderIgnored`.
  - `SmsReceiverTest.ignoredBeforeOnboarding`: with `onboardingDone` false nothing is stored.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implement:
  - **Receiver:** `Telephony.Sms.Intents.getMessagesFromIntent`, `joinParts`, `receivedAt = messages[0].timestampMillis`, `subId` from the `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX` extra. Inside `goAsync()`, in `CoroutineScope(Dispatchers.IO)`, it reads `onboardingDone` and, if false, finishes without `ingest`; otherwise it does `ingest` and, if `Inserted`, `enqueueSend`; then `finish()`.
  - **Sweep:** if `onboardingDone` is false, it does nothing. It reads from `QueuePolicy.sweepFrom(cursor, floor)`; with no cursor but with a floor, from the floor. The cursor advances to the maximum `date` read or, if there are no rows, to `now`.
  - **Boot:** `sweepNow`.
  - **App open:** `sweepNow` from `MainActivity.onResume` (the sweep re-kicks the `QUEUED` ones).
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): sms receiver, inbox sweep and boot hook`.

### Task 12: System health and notifications

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/health/HealthChecker.kt`, `health/MiuiIntents.kt`, `notify/Notifier.kt`
- Modify: `SweepWorker.kt`, `SendWorker.kt`, `SmsForwarderApp.kt` (create channel), `AppGraph.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/health/HealthCheckerTest.kt`, `notify/NotifierTest.kt`

**Interfaces:**
- Consumes: `SweepResult.inboxUnreadable`, `SmsRepository.stuckToNotify`/`markStuckNotified`/`idsWithError401`, `SettingsRepository.lastNotifiedIssues`.
- Produces:
  - `enum class HealthIssue { SMS_PERMISSION, NOTIFICATION_PERMISSION, BATTERY_OPTIMIZED, BACKGROUND_RESTRICTED, AUTOSTART_OFF, AUTOSTART_UNKNOWN, MIUI_SMS_BLOCKED, TOKEN_REJECTED, NOT_CONFIGURED, NO_SENDERS }`, with `NO_SENDERS` when `SettingsRepository.senders` is empty;
  - `class HealthChecker(context, smsRepository, secretStore)`, with `suspend fun check(inboxUnreadable: Boolean = false): Set<HealthIssue>`;
  - `object MiuiIntents`, with:
    - `fun autostartState(context): Boolean?`: best-effort via reflection on `AppOpsManager.checkOpNoThrow` with op `10008`; `null` if it is not MIUI or it fails;
    - `fun autostart(context): Intent`: component `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`;
    - `fun permissions(context): Intent`: `com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity` with extra `extra_pkgname`;
    - `fun battery(context): Intent`;
    - `fun forIssue(context, issue): Intent`.
    
    Each intent falls back to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` if `resolveActivity` is `null`.
  - `class Notifier(context)`, with:
    - `fun recovered(items: List<SmsEntity>)`: id 1001;
    - `fun stuck(item: SmsEntity)`: id `2000 + smsId`;
    - `fun health(issues: Set<HealthIssue>)`: id 3001;
    - `fun tokenRejected(count: Int)`: id 3002, a single notification "Token rejected: N SMS waiting".
    
    Channel `alerts`. Tapping any of them opens `MainActivity`.

- [ ] **Step 1: Failing tests:**
  - `emptySendersIsReportedAsNoSenders`;
  - `smsPermissionDeniedIsReported` (Robolectric `ShadowApplication.denyPermissions`);
  - `token401PresentIsReported`;
  - `inboxUnreadableWithPermissionIsMiuiSmsBlocked`;
  - `nonMiuiAutostartIsUnknownNotOff`;
  - `NotifierTest.tokenRejectedIsSingleNotification`;
  - `SendWorkerTest.401DoesNotNotifyPerSms`;
  - `NotifierTest.recoveredTextNamesSenderAndTime`: the text contains `"Recovered 1 SMS from CARRIER received at 11:42"` and `"check Autostart"`; fixed time zone in the test.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implement and hook into `SweepWorker`, in this order:
  1. `onRecovered` calls `Notifier.recovered`.
  2. For each `stuckToNotify()` (already without 401 rows), call `Notifier.stuck` and then `markStuckNotified`.
  3. Compute `issues` (includes `NO_SENDERS`, which does trigger a notification in addition to the UI card). If they differ from `lastNotifiedIssues()` and are not empty, call `Notifier.health`. In both cases, save `issues`.
  
  `AUTOSTART_UNKNOWN` does not trigger a notification, it is only shown in the UI.
  
  In `SendWorker`, a `Rejected(401)` calls `Notifier.tokenRejected` with the number of 401 rows (`idsWithError401().size`) and does not call `stuck`. A `Rejected(400)` calls `Notifier.stuck` and then `markStuckNotified`. `HealthIssue.TOKEN_REJECTED` is kept for the UI card.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): health checks and alert notifications`.

### Task 13: UI: theme, navigation, main screen and detail

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/Nav.kt`, `ui/main/MainViewModel.kt`, `ui/main/MainScreen.kt`, `ui/detail/DetailViewModel.kt`, `ui/detail/DetailScreen.kt`
- Modify: `ui/MainActivity.kt`, `ui/theme/Theme.kt`, `data/SettingsRepository.kt`, `work/SweepWorker.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/MainScreenTest.kt`, `ui/DetailScreenTest.kt`

**Interfaces:**
- Consumes: `SmsRepository.observeAll/observeAttempts/requeue/get`, `statusLabel`, `HealthChecker`, `MiuiIntents.forIssue`, `WorkScheduler.sweepNow/enqueueSend`, `SettingsRepository.themeMode`.
- Produces:
  - routes `onboarding`, `main`, `detail/{id}`, `settings`, `recovery`. `MainActivity` starts at `onboarding` if `onboardingDone == false`.
  - `@Composable fun MainScreen(state: MainUiState, onOpenIssue: (HealthIssue) -> Unit, onSweepNow: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit)`;
  - `data class MainUiState(val issues: Set<HealthIssue>, val lastSweepAt: Long?, val queued: Int, val errors: Int, val rows: List<RowUi>)`;
  - `data class RowUi(val id: Long, val sender: String, val subId: Int?, val receivedAt: Long, val preview: String, val label: String)`.

- [ ] **Step 1: Failing Compose (Robolectric) tests:**
  - `showsWarningCardWithButtonForAutostartOff`: text "Autostart is off" and button "Open settings".
  - `showsStatusLineAndSweepButton`: "Sweep inbox now".
  - `rowShowsLabel`: "Recovered · 200" appears.
  - `DetailScreenTest.retryVisibleOnlyOnError`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implement according to sketch A of spec §7:
  - warnings at the top;
  - status line with "Last sweep: N min ago · Queue: X";
  - history `LazyColumn`;
  - gear icon in the `TopAppBar`;
  - the detail shows full text, sender, SIM, time, origin and attempts.
  
  `lastSweepAt` comes from `SettingsRepository`: add `lastSweepAt()`/`setLastSweepAt()` there and write it in `SweepWorker`. `SmsForwarderTheme` reads `themeMode`.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(ui): main screen with alerts, status and history; sms detail`.

### Task 14: UI: first launch, manual recovery and settings

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/onboarding/OnboardingScreen.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/recovery/RecoveryScreen.kt`, `ui/recovery/RecoveryViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`
- Modify: `Nav.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/RecoveryViewModelTest.kt`, `ui/SettingsScreenTest.kt`, `ui/OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: `InboxReader`, `SenderFilter`, `SmsIdentity`, `SmsRepository.ingest`, `SettingsRepository` (`setSweepFloor`, `setSweepCursor`, `setOnboardingDone`), `IngestClient.testConnection`, `UrlValidator`, `WorkScheduler`, `QueuePolicy.MANUAL_RECOVERY_MS`.
- Produces:
  - `class RecoveryViewModel`, with `val candidates: StateFlow<List<RecoveryItem>>`, `fun toggle(key: String)` and `suspend fun sendSelected(): Int`;
  - `data class RecoveryItem(val key: String, val sender: String, val receivedAt: Long, val preview: String, val alreadyKnown: Boolean, val selected: Boolean)`.

- [ ] **Step 1: Failing tests:**
  - `recoveryListsLast7DaysOfAllowedSendersNoneSelected`.
  - `recoveryMarksAlreadyKnownAndDisablesThem`.
  - `sendSelectedIngestsAsManualAndEnqueues`: `origin == MANUAL`.
  - `onboardingRejectsHttpUrl`.
  - `onboardingRequiresAtLeastOneSender`: after the permissions, the Senders step does not allow continuing with an empty list and does with one entry.
  - `onboarding400MeansOkAndShowsCutoverReminder`: the reminder with "uninstall or disable the previous app" is shown at the start, before the permissions, and requires confirming it; after a 400 in Test connection it just continues (short reminder before recovery).
  - `settingsNeverShowsTokenValue`: after saving the token `test-token`, no node contains `test-token`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implement:
  - **First launch**, in this order:
    1. Clean-cutover reminder (uninstall or disable the previous app), which is confirmed.
    2. Permissions `RECEIVE_SMS`, `READ_SMS` and `POST_NOTIFICATIONS` (the latter only if SDK ≥ 33). If `RECEIVE_SMS`/`READ_SMS` is denied without a dialog (`shouldShowRequestPermissionRationale == false` on the first request), show the "restricted settings" notice: App info → ⋮ → "Allow restricted settings".
    3. Button for battery optimization.
    4. Notice to check Autostart and the SMS switches in MIUI Security, with `MiuiIntents` buttons.
    5. **Senders**: add and remove, no default values; cannot continue with an empty list.
    6. URL and token, with Test connection.
    7. Recovery screen (with a short reminder about the previous app before it).
    8. `setSweepFloor(now)`, `setSweepCursor(now)` and `setOnboardingDone()`.
  - **Settings:**
    - URL and token are replaced with `saveCredentials`. The token field is never filled in.
    - Test connection.
    - Sender list with add and remove.
    - Theme: System, Light or Dark.
    - Entry to "Recover SMS".
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(ui): onboarding, manual recovery and settings`.

### Task 15: Signed release, documentation and real test

**Files:**
- Create: `.github/workflows/release.yml`, `docs/signing.md`, `docs/manual-test-checklist.md`
- Modify: `app/build.gradle.kts` (`signingConfigs.release` from env; `versionCode`/`versionName` from the tag), `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Asset `sms-forwarder-vX.Y.Z.apk` in the Release.

- [ ] **Step 1:** `app/build.gradle.kts`:
  - reads `GITHUB_REF_NAME`;
  - if it matches `^v(\d+)\.(\d+)\.(\d+)$`, `versionCode = X*10000 + Y*100 + Z` and `versionName = "X.Y.Z"`; otherwise, `versionCode = 1` and `versionName = "0.0.0-dev"`;
  - release `signingConfig` only if the `SIGNING_STORE_FILE` env exists.
- [ ] **Step 2:** `release.yml`, on `push: tags: ['v*']`, with `permissions: contents: write`:
  1. Decode the secret to `$RUNNER_TEMP/release.jks`.
  2. Run `./gradlew :core:test :app:testReleaseUnitTest :app:assembleRelease`.
  3. Rename the APK.
  4. The job defines the envs `SIGNING_STORE_FILE=$RUNNER_TEMP/release.jks`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` from the secrets, and `GH_TOKEN: ${{ github.token }}` for `gh release create`.
  5. Publish with `gh release create "$GITHUB_REF_NAME" <apk> --generate-notes` (no Claude attribution in the notes).
  6. Delete the `.jks` in an `if: always()` step.
- [ ] **Step 3:** `docs/signing.md`, step by step:
  1. `keytool -genkeypair -v -keystore sms-forwarder.jks -keyalg RSA -keysize 4096 -validity 36500 -alias sms-forwarder`.
  2. `base64 -w0 sms-forwarder.jks`.
  3. Create the 4 secrets, through the web UI (Settings → Secrets and variables → Actions) or with `gh secret set`.
  4. Keep the `.jks` and the passwords outside GitHub. Without them the app cannot be updated.
  5. Uninstall the debug build if it gets in the way. It is not necessary, because it has a different `applicationId`.
- [ ] **Step 4:** `docs/manual-test-checklist.md`, cases from spec §12:
  - normal SMS;
  - multipart SMS;
  - airplane mode: the SMS stays queued and is sent when the network returns;
  - force stop and open: the sweep recovers and notifies;
  - wrong token: 401, notification, fix and requeue;
  - phone reboot;
  - the previous forwarding app disabled before setup;
  - light and dark mode;
  - remove Autostart: the warning appears;
  - Android 15, restricted settings: if the permission dialog does not appear, the first launch explains "Allow restricted settings";
  - key lost after transferring to another device: the app shows `NOT_CONFIGURED`, does not crash (and `KeystoreSecretStore.get()` returns `null` if there is no JVM test);
  - the first sweep after setup sends nothing older than the setup;
  - an SMS that arrives during the first launch is not forwarded;
  - the first launch does not let you continue without at least one sender;
  - emptying the sender list in Settings shows the card and the `NO_SENDERS` notification.
- [ ] **Step 5:** Commit `ci: signed release workflow; docs: signing and manual test checklist`. Push → CI green.
- [ ] **Step 6: With the user:**
  1. Create the key and the secrets following `docs/signing.md`.
  2. `git tag v1.0.0 && git push origin v1.0.0`.
  3. `gh run watch`. The Release appears with the APK.
  4. Install the APK on the phone and go through the checklist.
  5. Note the failed cases in the chat.
  
  Failures are fixed in new tasks.
