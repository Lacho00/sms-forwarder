# v2 delivery 4 (2.0.0): export, import, diagnostics and About. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The user exports their destinations and rules to a versioned JSON file (without secrets by default), imports it on another phone or on the same one without losing anything they already have, and exports from About a text diagnostic that contains no personal data. Closes v2 with a pass over hostile cases and the 2.0.0 release.

**Architecture:** The file format, its validation, the import plan (renaming of duplicates) and the diagnostic report are pure and live in `:core`. In `:app`, `ConfigTransfer` exports and imports against Room and the secret store in a single transaction; `EngineLog` keeps the latest engine events in its own file (no Room); `DiagnosticsCollector` gathers counters, permissions and events for the report. The UI adds Export and Import to the ⋮ menu with the Storage Access Framework (`CreateDocument`/`OpenDocument`, no new permissions) and a diagnostics button in About. Before everything, a debug-only receiver seeds destinations and rules so `scripts/e2e-emulator.sh` can test sending on the emulator without touching the UI.

**Tech Stack:** Kotlin, Compose Material 3, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization (`JsonElement` only, no plugin), Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), delivery 4 (§3, §5 ⋮ menu, §8 exhausted regex "noted in the diagnostics", §13, §14, §16, §17). Previous plan: [delivery 3](2026-11-03-v2-delivery-3-tabs.md); ledgers with the deferred items: `.superpowers/sdd/2026-11-03-v2-delivery-3-tabs/progress.md` and `.superpowers/sdd/2026-11-02-v2-delivery-2-model/progress.md`.

**Branch:** `v2-delivery-4` from `main` @ `80161d7` (1.3.0 published). Worktree `/srv/http/sms-forwarder-wt/v2-d4`; the main clone stays on `main`. The closing OCR review compares against `80161d7`.

## Decisions of this plan

Written without the user present (they asked for autonomous work). Everything the spec does not fix is **(my decision)** and is reviewed when the delivery closes.

- **(my decision) Event log in a file, not Room.** `EngineLog` keeps the last 500 lines in `noBackupFilesDir/engine-events.log`. Reason: events are disposable and a 3→4 migration in the release that closes v2 adds risk with no benefit. **No schema migration**: Room stays at version 3.
- **(my decision) File format:** JSON object with `"format": "sms-forwarder/config"`, `"version": 1`, `"app"` (the version that wrote it, informational), `"secrets"` (informational), `"destinations"` and `"rules"`. Each destination carries a `"key"` (`"d<id>"`) and rules point at those keys. Reason: ids are useless across phones.
- **(my decision) Format version:** `version` greater than the known one → invalid file ("created by a newer version"); unknown fields are ignored; an unknown enum value invalidates the file. Reason: forward compatible without guessing values.
- **(my decision) Without secrets** = without the URL (always encrypted, §12) and without the values of secret headers; the names of those headers are exported. On import, a destination without a URL is created with nothing in the secret store: it counts as "without credentials", `HealthIssue.NOT_CONFIGURED` warns if an active rule uses it, and its row in Destinations marks it. Its secret headers are discarded and the summary names them so the user can add them again. Reason: there is nowhere to store a secret header without a value without touching the store or the schema.
- **(my decision) The `secrets` field does not rule:** each destination decides for itself (URL present or not). Reason: a hand-edited file cannot leave the app half-configured.
- **(my decision) Export dialog:** checkbox "Include URL, tokens and secret headers" unchecked on open, with a warning when checked; Export and Cancel buttons. Reason: "no" by default without the main button changing meaning.
- **(my decision) ⋮ menu:** three entries, Export, Import and About, in that order. Reason: the spec groups "Export/Import"; two entries avoid an intermediate screen.
- **(my decision) Renaming:** suffix ` (2)`, ` (3)`… comparing names trimmed and case-insensitively, against the live ones and against those already planned from the same file. Also for rules, although the spec only asks for it for destinations. Reason: two rules with the same name are just as confusing.
- **(my decision) File limits:** 1 MiB, 100 destinations, 500 rules, 50 headers per destination; leading UTF-8 BOM accepted. Reason: a hostile file cannot block the app.
- **(my decision) Import keeps `enabled`** of destinations and rules as they come. Reason: it is a copy; whoever exported something paused wants it paused.
- **(my decision) Import is atomic with the Keystore too:** rows, links and secrets are written inside a Room transaction (like `DestinationRepository.update`); if storing a secret fails, the transaction is rolled back and the secrets already written for those ids are deleted. Reason: "an invalid file changes nothing" extends to a half-done failure.
- **(my decision) Exporting without secrets also reads the store** (for the names of secret headers); a transient Keystore error aborts the export with its message and writes nothing. The text is built before opening the file picker and discarded on writing or canceling; it never goes to saved state or the log.
- **(my decision) Completing a destination without credentials gets it going:** `DestinationRepository.update` also returns the SMS with a `QUEUED` delivery to that destination when it previously had no secrets, and the caller schedules them with `kickSends`. Reason: without this they wait out WorkManager's backoff (up to hours).
- **(my decision) Diagnostics:** destinations by id and template type, never by name (the name is written by the user and may contain anything). Errors grouped as `http_<code>`, `network`, `config_<reason>`, `destination_deleted` or `other`: the `lastError` that comes from the server (`errorCodeOf`) is never copied. Last 50 events. Manufacturer and model are included (they matter on MIUI).
- **(my decision) File names:** `sms-forwarder-config-<YYYY-MM-DD>.json` and `sms-forwarder-diagnostic-<YYYY-MM-DD>.txt`. MIME types on open: `application/json`, `text/*`, `application/octet-stream` (some file managers label JSON that way).
- **(my decision) About:** back arrow (`Icons.AutoMirrored.Filled.ArrowBack`, the one in the editors) instead of the "Back" text button, and the same in Detail and Recovery so all full-screen views go back the same way; icon cropped to a circle (`CircleShape`) instead of the square. "Export diagnostics" button is an `OutlinedButton`, like those in Detail.
- **(my decision) Seed receiver in debug only**, exported and protected with `android.permission.DUMP` (held by `adb shell`, not by a normal app). Reason: `am broadcast` from the script without opening the surface to other apps.
- **(my decision) The e2e script** uses `https://httpbin.org/anything` (200) and `https://httpbin.org/status/403` by default, changeable through environment variables; it refuses to run if the serial is not `emulator-*`.

### Pending visual OK

Applied with components and styles the app already uses; the user reviews them in the T9 screenshots and any change is a small commit:

1. ⋮ menu with Export, Import and About (`DropdownMenuItem`, text only).
2. Export dialog: `AlertDialog` with a `Checkbox` + text row; warning on check with `MaterialTheme.colorScheme.error` (like the warning for a rule without destinations).
3. Import summary, invalid file and result: `AlertDialog` with scrollable text.
4. Destination row without credentials: "No credentials" label in the same place and style as "Paused".
5. Back arrow in About, Detail and Recovery.
6. About icon cropped to a circle.
7. "Export diagnostics" as an `OutlinedButton` with a `bodyMedium` result line below.

## Global Constraints

- v1 rules that still apply: **https only**; **no foreground service** or fixed notification; **no `fallbackToDestructiveMigration`**; **backups disabled**; no real SMS texts, real senders, phone numbers or the author's project names in code, tests, strings or docs (examples: `AAA`, `TEST`, `https://h.example`).
- Room schema: stays at version 3. If a task needed to touch it, it carries an explicit 3→4 migration, a committed `4.json` and a test in `MigrationTest`; CI fails on an uncommitted schema.
- **No new permissions** in the release manifest (SAF does not ask for them). The release manifest contains neither `SeedReceiver` nor `DiagReceiver`.
- All new visible text goes in `values/strings.xml` (English) and `values-es/strings.xml`; `:app` tests compare with `context.getString(R.string.x)`, never with literals.
- `minSdk = 31`. Manual navigation (`Nav.kt`), no `navigation-compose`. ViewModels with `ViewModelProvider.Factory` created in `MainActivity`.
- JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. `:app` runs locally (`./gradlew :app:testDebugUnitTest --tests ...`, with network) and CI is the reference. Robolectric `sdk=35`, `TestApp`; `WorkManagerTestInitHelper` before launching activities.
- The URL, the token, secret headers, the SMS body and sender, the exported text and drafts are never written to the log, to `toString()` or to the diagnostic report.
- Templates and substitution with no change in behavior (`Templates.render`); the migrated destination sends byte for byte the same as 1.3.0.
- Always requeue with `WorkScheduler.kickSends` (it never replaces a `RUNNING` job).
- **Design:** whatever the spec does not fix is done with Material 3 components and styles already present, and goes to "Pending visual OK"; no hand-picked colors or measurements.
- Each task leaves `:core:test`, the build, lint and `:app` tests green (CI).
- Code review: OCR only (`ocr delegate rule`). No Claude attribution lines in commits or PRs. Push to `origin v2-delivery-4` authorized; never `main` or tags (the user does those).

## Review Focus

1. Importing a valid file when the Keystore fails while storing the second secret: no new destination, rule or secret remains (T3, `secretFailureRollsBackEverything`).
2. The diagnostics with an SMS whose body and sender are recognizable, a destination with a token in the URL, a secret header and a server `lastError` with a URL inside: none of them appears in the text (T5, `reportLeaksNothing`).
3. Names that clash only by case or whitespace (`"Main "` vs `"main"`), and duplicates inside the file itself: all end up distinct (T2, `renamesCaseAndSpaceInsensitive`, `renamesDuplicatesInsideFile`).
4. Hostile file: JSON nested 10,000 levels, more than 1 MiB, changed types, unknown enum, a rule that points to a key that does not exist: `Invalid` without an exception and without touching the database (T2, `hostileFilesAreInvalid`; T3, `invalidFileChangesNothing`).
5. Imported destination without credentials with an active rule: the SMS stays queued unsent, `NOT_CONFIGURED` warns, and saving the URL in the editor schedules it right away (T3, `completingCredentialsKicksQueued`).

---

### Task 1: Debug broadcast seeding and `scripts/e2e-emulator.sh`

What was deferred from delivery 3 (`task-10-fix-brief.md`): test sending end to end on the emulator without driving the UI.

**Files:**
- Create: `app/src/debug/kotlin/dev/smsforwarder/diag/SeedCommands.kt`, `app/src/debug/kotlin/dev/smsforwarder/diag/SeedReceiver.kt`, `scripts/e2e-emulator.sh`
- Modify: `app/src/debug/AndroidManifest.xml`, `README.md` (one line in the development section)
- Test: `app/src/testDebug/kotlin/dev/smsforwarder/diag/SeedCommandsTest.kt`

**Interfaces:**
- Consumes: `DestinationRepository.create`, `RuleRepository.create`, `DestinationPresets.standardJson/ntfy/custom`, `RuleDraft`, `SettingsRepository.setSweepFloor/setOnboardingDone` (the same as `OnboardingViewModel.finish`).
- Produces:
  - `data class SeedResult(val ok: Boolean, val data: String)`; `internal class SeedCommands(graph: AppGraph) { suspend fun run(extras: Map<String, String>): SeedResult }`.
  - Operations (`op`): `done` (onboarding finished, floor = now) → `"ok"`; `destination` (`template` = `standard|ntfy|custom`, `name`, `url`, `token` only for `standard`, `body` and `json` = `true|false` only for `custom`, `enabled` = `true|false`, default `true`) → `"id=<n>"`; `rule` (`name`, `sender`, `mode` = `exact|contains|regex`, default `exact`, `body_mode` = `contains|not_contains|regex`, `body`, `sim` = `any|1|2`, `destinations` = comma-separated ids) → `"id=<n>"`; `status` → `"sms queued=<a> sent=<b> error=<c> deliveries queued=<d> sent=<e> error=<f> canceled=<g>"`. Error: `ok = false`, `data = "error=<NAMES>"` (those of `DestinationError`/`RuleError`, or `bad_op`, `bad_arg`).
  - `SeedReceiver`: action `dev.smsforwarder.debug.SEED`, `goAsync()`, 9 s timeout, `setResult(if (ok) 0 else 1, data, null)`. Never writes the URL, token or body to the log.
- [ ] **Step 1: Failing tests** in `SeedCommandsTest` (Robolectric, `FakeDestinationSecretStore` in `graph.destinationSecretStore`):
  - `seedsStandardDestinationAndRule`: `destination` with `https://h.example/x` and token `t` → `id=1`; `rule` `sender=AAA destinations=1` → `id=…`; `activeRules()` contains the rule toward 1; `secrets(1)?.url == "https://h.example/x"`.
  - `httpUrlRejected`: `url=http://h.example` → `ok == false`, `data` contains `URL_NOT_HTTPS`, no destination.
  - `invalidRegexRejected`: `mode=regex sender=(` → `data` contains `SENDER_REGEX_INVALID`.
  - `unknownOpFails`: `op=x` → `error=bad_op`.
  - `statusCountsOnly`: after ingesting an SMS with body `SECRET-BODY` and sender `AAA`, `status` contains neither `SECRET-BODY` nor `AAA` and says `sms queued=1`.
  - `doneMarksOnboarding`: `onboardingDone.first() == true` y `sweepFloor() != null`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*SeedCommandsTest'`; expected: FAIL (does not compile).
- [ ] **Step 3: Implement.** `SeedReceiver` turns the extras into a `Map<String, String>` and delegates to `SeedCommands`; debug manifest: `<receiver android:name=".diag.SeedReceiver" android:exported="true" android:permission="android.permission.DUMP">` with the action's `intent-filter`. `status` counters use existing count queries or new `@Query`s in the DAOs (T5 reuses them: `SmsDao.countsByStatus(): List<StatusCount>` and `DeliveryDao.countsByStatus(): List<DeliveryStatusCount>`, only SMS without `deletedAt`).
- [ ] **Step 4: Write `scripts/e2e-emulator.sh`** (`bash`, `set -euo pipefail`): `ANDROID_SERIAL` defaults to `emulator-5554` and exits 2 if it does not start with `emulator-`; `E2E_OK_URL` (`https://httpbin.org/anything`) and `E2E_FAIL_URL` (`https://httpbin.org/status/403`); `--no-install` skips `./gradlew :app:installDebug`. Steps: `pm clear dev.smsforwarder.debug`; `pm grant` of `RECEIVE_SMS`, `READ_SMS`, `POST_NOTIFICATIONS`; `am broadcast -a dev.smsforwarder.debug.SEED -n dev.smsforwarder.debug/dev.smsforwarder.diag.SeedReceiver --es op ...` for `done`, a working `custom` destination, one that fails, and a `TEST` rule toward both; `adb emu sms send TEST "e2e <epoch>"`; poll `op=status` every 3 s up to 90 s, waiting for `sms` in error with `deliveries sent=1 error=1`; print `PASS`/`FAIL` and exit 0/1. The `custom` destinations are seeded with `body` `{"b":"{body}"}` and `json=true`.
- [ ] **Step 5: Run** the test (PASS); `shellcheck scripts/e2e-emulator.sh` if installed; `./gradlew :app:processReleaseMainManifest` and `grep -rc 'SeedReceiver\|DiagReceiver' $(find app/build/intermediates -path '*release*' -name AndroidManifest.xml)` → all 0.
- [ ] **Step 6: On the emulator:** `android-emu`, `scripts/e2e-emulator.sh`; expected `PASS`; `android-emu-stop` when done.
- [ ] **Step 7: Commit** `test(e2e): debug seed receiver and emulator script`.

### Task 2: File format, validation and import plan in `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/ConfigFile.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/ConfigFileTest.kt`

**Interfaces:**
- Consumes: `DestinationDraft`, `HeaderDraft`, `RuleDraft`, `validateDestination`, `validateRule`, enums from `Templates.kt` and `Rules.kt`.
- Produces:
  - `const val CONFIG_FORMAT = "sms-forwarder/config"`, `CONFIG_VERSION = 1`, `CONFIG_MAX_BYTES = 1_048_576`, `CONFIG_MAX_DESTINATIONS = 100`, `CONFIG_MAX_RULES = 500`, `CONFIG_MAX_HEADERS = 50`.
  - `data class ConfigDestination(val key: String, val draft: DestinationDraft)` (`draft.url == null` = without credentials; secret header with `value == null` = without value).
  - `data class ConfigRule(val draft: RuleDraft, val destinationKeys: List<String>)` (`draft.destinationIds` empty).
  - `data class ConfigFile(val destinations: List<ConfigDestination>, val rules: List<ConfigRule>, val includesSecrets: Boolean)` with a `toString()` without URL or header values.
  - `enum class ConfigError { TOO_LARGE, NOT_JSON, WRONG_FORMAT, NEWER_VERSION, BAD_VALUE, TOO_MANY, DUPLICATE_KEY, UNKNOWN_DESTINATION, INVALID_DESTINATION, INVALID_RULE }`.
  - `sealed interface ConfigParse { data class Ok(val file: ConfigFile); data class Invalid(val error: ConfigError, val item: String?) }` (`item` = name of the offending destination or rule if it could be read).
  - `object ConfigCodec { fun encode(file: ConfigFile, appVersion: String): String; fun decode(text: String): ConfigParse }`.
  - `data class PlannedDestination(val key: String, val draft: DestinationDraft, val originalName: String, val withoutCredentials: Boolean, val droppedSecretHeaders: List<String>)`; `data class PlannedRule(val draft: RuleDraft, val originalName: String, val destinationKeys: List<String>)`; `data class ImportPlan(val destinations: List<PlannedDestination>, val rules: List<PlannedRule>)` with `val renamed: List<Pair<String, String>>` and `val withoutCredentials: List<String>`.
  - `fun planImport(file: ConfigFile, takenDestinationNames: Collection<String>, takenRuleNames: Collection<String>): ImportPlan`; `fun uniqueName(name: String, taken: Set<String>): String`.
- [ ] **Step 1: Failing tests** in `ConfigFileTest`:
  - `roundTripWithSecrets`: a standard JSON, a Telegram and an ntfy with two rules → `decode(encode(f))` gives `Ok` equal to `f`.
  - `withoutSecretsHasNoUrlNorSecretValues`: `encode` of a file with `url = null` and secret header `value = null` → the text contains neither `https://h.example` nor `Bearer`, but does contain `"Authorization"`; `"secrets":false`.
  - `versionAndFormatChecked`: `"version":2` → `NEWER_VERSION`; no `format` or a different one → `WRONG_FORMAT`; an extra unknown field → `Ok`.
  - `everythingValidated`: URL `http://h.example` → `INVALID_DESTINATION` with `item` = its name; `{foo}` in the body → `INVALID_DESTINATION`; regex `"("` → `INVALID_RULE`; public header with null `value` → `BAD_VALUE`; `"method":"PATCH"` → `BAD_VALUE`.
  - `rulesPointAtKeys`: a rule with a nonexistent key → `UNKNOWN_DESTINATION`; two destinations with the same key → `DUPLICATE_KEY`; a rule without destinations → `Ok` (kept inactive, spec §8).
  - `hostileFilesAreInvalid`: 10,000 `[` → `NOT_JSON` without an exception (also catch `StackOverflowError`); text of `CONFIG_MAX_BYTES + 1` bytes → `TOO_LARGE` without parsing; 101 destinations → `TOO_MANY`; `"destinations":"x"` → `BAD_VALUE`; `"enabled":"yes"` → `BAD_VALUE`; empty string → `NOT_JSON`; leading BOM → `Ok`.
  - `renamesCaseAndSpaceInsensitive`: taken `["main"]`, file with `"Main "` → `"Main (2)"`; taken `["Main", "Main (2)"]` → `"Main (3)"`; `renamed == [("Main ", "Main (2)")]`.
  - `renamesDuplicatesInsideFile`: two destinations `"A"` in the file, nothing taken → `"A"` and `"A (2)"`; same with rules.
  - `withoutCredentialsDropsSecretHeaders`: a destination without a URL with secret header `X-Key` → `withoutCredentials`, `droppedSecretHeaders == ["X-Key"]`, the draft no longer carries it; public ones stay.
  - `fileToStringHidesSecrets`.
- [ ] **Step 2: Run** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test --tests '*ConfigFileTest'`; expected: FAIL.
- [ ] **Step 3: Implement `ConfigFile.kt`** with `Json.parseToJsonElement` and manual reading of `JsonObject` (no serialization plugin). Shape of each destination: `key`, `name`, `enabled`, `template`, `method`, `url` (string or `null`), `headers` (`[{"name","value","secret"}]`), `body`, `bodyIsJson`, `successPolicy`; of each rule: `name`, `enabled`, `senderMode`, `senderPattern`, `bodyMode`, `bodyPattern`, `sim`, `destinations` (keys). Enums by `name`. Validation: `validateDestination(d, urlStored = d.url == null)`; `validateRule(r)` ignoring `NO_DESTINATIONS`. `encode` with `prettyPrint`, keys in a fixed order. `uniqueName` over `trim()` and `lowercase()`.
- [ ] **Step 4: Run** the same command; expected: PASS.
- [ ] **Step 5: Commit** `feat(core): versioned config file with validation and import plan`.

### Task 3: Export and import against the database (`ConfigTransfer`)

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/ConfigTransfer.kt`
- Modify: `data/DestinationRepository.kt` (insert without secrets inside an outer transaction; `update` returns the `QUEUED` ones if there were no secrets before; `observeLiveWithConfigured`), `data/DestinationDao.kt`, `data/RuleDao.kt`, `AppGraph.kt`
- Test: new `app/src/test/kotlin/dev/smsforwarder/data/ConfigTransferTest.kt`, `DestinationRepositoryTest.kt`

**Interfaces:**
- Consumes: T2 (`ConfigCodec`, `ConfigFile`, `planImport`, `ImportPlan`); `DestinationRepository`, `RuleRepository`, `DestinationSecretStore`, `TransientSecretException`.
- Produces:
  - `sealed interface ImportPreview { data class Ready(val file: ConfigFile, val plan: ImportPlan); data class Invalid(val error: ConfigError, val item: String?) }`.
  - `data class ImportResult(val destinations: Int, val rules: Int, val withoutCredentials: Int)`.
  - `class ConfigTransfer(db: AppDatabase, destinations: DestinationRepository, rules: RuleRepository, secretStore: DestinationSecretStore) { suspend fun export(includeSecrets: Boolean, appVersion: String): String; suspend fun preview(text: String): ImportPreview; suspend fun apply(file: ConfigFile): ImportResult }`; `AppGraph.configTransfer`.
  - `data class DestinationWithConfig(val entity: DestinationEntity, val configured: Boolean)`; `DestinationRepository.observeLiveWithConfigured(): Flow<List<DestinationWithConfig>>` (used by T6).
- [ ] **Step 1: Failing tests** in `ConfigTransferTest` (in-memory Room + `FakeDestinationSecretStore`):
  - `exportWithoutSecrets`: a standard JSON with a token and a rule → `decode` gives a destination with `url == null`, a secret `Authorization` header without value, a rule with its key; the text contains neither the URL nor the token.
  - `exportWithSecrets`: URL and token present; deleted destinations and their links left out.
  - `importAddsNeverReplaces`: with an existing `"Main"` destination, importing a file with `"Main"` → two destinations (`"Main"`, `"Main (2)"`); the original intact (row and secrets); the imported rule points at the new one.
  - `invalidFileChangesNothing`: `preview` of a file with an invalid regex → `Invalid(INVALID_RULE, …)`; counts of destinations, rules, links and secrets unchanged.
  - `importWithoutSecretsIsUnconfigured`: after `apply`, `secrets(id) == null`, `unconfiguredInUse()` contains it, `ImportResult.withoutCredentials == 1`.
  - `secretFailureRollsBackEverything`: a fake store that throws on the second `set` → `apply` rethrows; no new destination, rule or secret (not even the first).
  - `exportFailsOnTransientKeystore`: `get` throws `TransientSecretException` → `export` rethrows.
  - `applyRenamesAgainstCurrentNames`: a destination `"A"` created between `preview` and `apply` → the imported one ends up `"A (2)"`.
  - `DestinationRepositoryTest.completingCredentialsKicksQueued`: a destination without secrets with a `QUEUED` delivery → `update` with a URL returns that SMS.
  - `DestinationRepositoryTest.observeLiveWithConfiguredFlagsMissingSecrets`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*ConfigTransferTest' --tests '*DestinationRepositoryTest'`; expected: FAIL.
- [ ] **Step 3: Implement.** `export`: live destinations (`DestinationRepository.editable` will not do, it hides secrets: read entity + `secretStore.get`), key `"d<id>"`, rules from `RuleRepository.observeAll().first()`. `apply`: inside `db.withTransaction`, current live names → `planImport` → insert rows → links and rules (same trimming as `RuleRepository.create`) → `secretStore.set` for those with a URL; on any exception, in `NonCancellable` `secretStore.remove` for each inserted id and rethrow. `update`: if `stored == null` before and there is a URL now, adds `deliveries.smsIdsQueuedFor(id)` to the result.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(data): export and transactional import of destinations and rules`.

### Task 4: Engine event log

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/EngineEvent.kt`, `app/src/main/kotlin/dev/smsforwarder/diag/EngineLog.kt`
- Modify: `AppGraph.kt`, `sms/SmsReceiver.kt`, `sms/Sweeper.kt`, `ui/recovery/RecoveryViewModel.kt`, `work/SendWorker.kt`, `data/LegacyImport.kt`, `data/ConfigTransfer.kt`
- Test: `core/.../EngineEventTest.kt`, `app/src/test/kotlin/dev/smsforwarder/diag/EngineLogTest.kt`, `SendWorkerTest.kt`, `SmsReceiverTest.kt`

**Interfaces:**
- Produces:
  - `enum class EngineEventType { RECEIVED, SWEEP, SEND, RULE_TIMEOUT, SAFE_MODE, IMPORT, EXPORT }`.
  - `data class EngineEvent(val at: Long, val type: EngineEventType, val smsId: Long? = null, val destinationId: Long? = null, val ruleId: Long? = null, val httpCode: Int? = null, val code: String? = null, val count: Int? = null)`.
  - `fun EngineEvent.toLine(): String` (`<at> <TYPE> sms=… dest=… rule=… http=… code=… n=…`, null fields left out); `fun parseEngineEvent(line: String): EngineEvent?`; `fun safeCode(s: String?): String?` (`[a-z0-9_]{1,40}` or `"other"`; `null` → `null`).
  - `class EngineLog(file: File, capacity: Int = 500) { suspend fun add(e: EngineEvent); suspend fun recent(limit: Int): List<EngineEvent> }`: `add` never throws except on cancellation; `AppGraph.engineLog` (file `noBackupFilesDir/engine-events.log`).
  - Codes: `RECEIVED` `inserted|known|no_destination|no_rule`; `SWEEP` `ok|unreadable` with `n` = recovered; `SEND` `accepted|retry|rejected|invalid|no_secrets` with `http`; `IMPORT`/`EXPORT` `with_secrets|no_secrets` with `n` = destinations; `SAFE_MODE` `legacy_import`.
- [ ] **Step 1: Failing tests:**
  - `EngineEventTest.lineRoundTrip`, `unknownLineIsNull`, `safeCodeRejectsFreeText` (`"https://h.example x"` → `"other"`, `"config_template"` → unchanged).
  - `EngineLogTest.keepsLastCapacity` (capacity 5, 12 events → `recent(10)` returns the last 5 in order), `survivesCorruptLine`, `addNeverThrowsOnIoError` (read-only directory), `concurrentAddsKeepAll` (100 `add` in parallel with capacity 500 → 100 lines).
  - `SendWorkerTest.logsOutcomePerDelivery`: an SMS with one 200 delivery and one 403 → two `SEND` events with `dest` and `http`, with no body or URL in the file.
  - `SmsReceiverTest.regexTimeoutLogged`: a rule with a catastrophic regex → `RULE_TIMEOUT` event with its id (spec §8).
- [ ] **Step 2: Run** `:core:test` and the affected `:app` classes; expected: FAIL.
- [ ] **Step 3: Implement.** `EngineLog` with `Mutex`; adds a line and, on exceeding `2 × capacity`, rewrites the last `capacity` to a temp file and renames it. The `Log.w(TAG, "rule $it timed out")` calls stay and also go to the log. In `SendWorker`, the event is written after `recordAttempt`; a log failure does not change the job's result. `ConfigTransfer` receives `engineLog: EngineLog? = null` as the last parameter (the T3 tests stay the same) and records `IMPORT`/`EXPORT` when it finishes successfully.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(diag): engine event log`.

### Task 5: Diagnostic report

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Diagnostic.kt`, `app/src/main/kotlin/dev/smsforwarder/diag/DiagnosticsCollector.kt`
- Modify: `data/DeliveryDao.kt` (error count by code), `AppGraph.kt`
- Test: `core/.../DiagnosticTest.kt`, `app/src/test/kotlin/dev/smsforwarder/diag/DiagnosticsCollectorTest.kt`

**Interfaces:**
- Consumes: T1 (`SmsDao.countsByStatus`, `DeliveryDao.countsByStatus`), T4 (`EngineLog.recent`, `EngineEvent.toLine`), `HealthChecker.check`, `MiuiIntents.autostartState`, `DestinationRepository.observeLiveWithConfigured` (T3).
- Produces:
  - `data class DestinationDiag(val id: Long, val template: TemplateKind, val enabled: Boolean, val configured: Boolean, val counts: Map<DeliveryStatus, Int>)`.
  - `data class DiagnosticInput(val generatedAt: Long, val appVersion: String, val versionCode: Long, val androidRelease: String, val sdkInt: Int, val device: String, val miui: Boolean, val autostart: Boolean?, val issues: List<String>, val forwardingEnabled: Boolean, val sms: Map<SmsStatus, Int>, val destinations: List<DestinationDiag>, val errors: Map<String, Int>, val rules: Int, val activeRules: Int, val events: List<EngineEvent>)`.
  - `fun errorKey(httpCode: Int?, lastError: String?): String` (`http_<n>` if there is a code; otherwise `network`, `destination_deleted`, `config_<a-z_>` as is; the rest `other`).
  - `fun renderDiagnostic(i: DiagnosticInput): String` (sections `App`, `Device`, `Health`, `Queue`, `Destinations`, `Errors`, `Rules`, `Events`; times in ISO UTC).
  - `data class ErrorCodeCount(val lastHttpCode: Int?, val lastError: String?, val n: Int)`, `DeliveryDao.errorCodes(): List<ErrorCodeCount>` (`ERROR` deliveries of SMS without `deletedAt`).
  - `class DiagnosticsCollector(context: Context, graph: AppGraph) { suspend fun collect(): DiagnosticInput; suspend fun report(): String }`; `AppGraph.diagnostics`.
- [ ] **Step 1: Failing tests:**
  - `DiagnosticTest.errorKeys`: `(403, "invalid_token")` → `http_403`; `(null, "network")` → `network`; `(null, "config_template")` → igual; `(null, "leak https://h.example")` → `other`.
  - `DiagnosticTest.rendersAllSections` (every section header present, counters and events).
  - `DiagnosticsCollectorTest.reportLeaksNothing`: an SMS with body `BODY-7f3a` and sender `AAA-7f3a`, a custom destination with URL `https://h.example/tok-7f3a` and secret header `X-Key: key-7f3a`, an `ERROR` delivery with `lastError = "srv https://h.example/tok-7f3a"` and one event of each type → `report()` contains neither `7f3a` nor `h.example`; it does contain `http_` or `other`, `queued`, the version and `SEND`.
  - `DiagnosticsCollectorTest.countsMatchDatabase`.
- [ ] **Step 2: Run**; expected: FAIL.
- [ ] **Step 3: Implement.** Version with `PackageManager.getPackageInfo(...).versionName/longVersionCode`; `device = "${Build.MANUFACTURER} ${Build.MODEL}"`; `issues` = names from `HealthChecker.check()`; events `recent(50)`. Nothing in the report comes from `SmsEntity.body`, `sender`, `senderNormalized`, destination or rule names, or the secret store (only `configured`).
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(diag): diagnostic report without personal data`.

### Task 6: Export and Import from the ⋮ menu

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/transfer/TransferViewModel.kt`, `TransferDialogs.kt`, `app/src/main/kotlin/dev/smsforwarder/ui/DocumentIo.kt`
- Modify: `ui/AppShell.kt`, `ui/Nav.kt`, `ui/MainActivity.kt`, `ui/destinations/DestinationsViewModel.kt`, `ui/destinations/DestinationsScreen.kt`, `res/values*/strings.xml`
- Test: new `TransferViewModelTest.kt`, `TransferDialogsTest.kt`; `AppShellTest.kt`, `DestinationsViewModelTest.kt`, `DestinationsScreenTest.kt`

**Interfaces:**
- Consumes: T3 (`ConfigTransfer`, `ImportPreview`, `ImportResult`, `observeLiveWithConfigured`), T2 (`ConfigError`, `ImportPlan`), T4 (`EngineLog` is not touched here: `ConfigTransfer` records it).
- Produces:
  - `interface DocumentIo { suspend fun write(uri: Uri, text: String); suspend fun read(uri: Uri, maxBytes: Int): String? }` (`null` = larger than `maxBytes`); `class ContentResolverDocumentIo(resolver: ContentResolver) : DocumentIo`. Reused by T7.
  - `sealed interface TransferUi { Idle; AskExport(val includeSecrets: Boolean); PickExportFile(val fileName: String); PickImportFile; Waiting; ImportSummary(val plan: ImportPlan); ImportInvalid(val error: ConfigError, val item: String?); Done(val message: TransferMessage) }` with a `toString()` without secrets.
  - `sealed interface TransferMessage { data object Exported; data class Imported(val result: ImportResult); data object ReadFailed; data object WriteFailed; data object SecretsUnavailable }`.
  - `class TransferViewModel(transfer: ConfigTransfer, io: DocumentIo, appVersion: String, today: () -> LocalDate)`: `state: StateFlow<TransferUi>`, `startExport()`, `setIncludeSecrets(Boolean)`, `confirmExport()`, `pickerLaunched()`, `onExportFile(uri: Uri?)`, `startImport()`, `onImportFile(uri: Uri?)`, `confirmImport()`, `dismiss()` + `Factory`.
  - `AppShell(..., onExport: () -> Unit, onImport: () -> Unit, onAbout: () -> Unit, ...)`.
  - `DestinationRowUi.configured: Boolean`.
- [ ] **Step 1: Failing tests:**
  - `TransferViewModelTest.exportDefaultsToNoSecrets`: `startExport()` → `AskExport(false)`; `confirmExport()` → `PickExportFile("sms-forwarder-config-2026-11-03.json")`; `onExportFile(uri)` → `DocumentIo` receives a text without the stored URL; `Done(Exported)`.
  - `cancelPickerWritesNothing`: `onExportFile(null)` → `Idle`, no write.
  - `pickerLaunchedOnce`: after `pickerLaunched()` the state is `Waiting` (Nav only launches the picker on `Pick*`).
  - `tooLargeFileRejected`: `read` returns `null` → `ImportInvalid(TOO_LARGE, null)`.
  - `invalidFileChangesNothing`: a file with `http://` → `ImportInvalid(INVALID_DESTINATION, name)`; database unchanged.
  - `summaryThenApply`: `onImportFile` → `ImportSummary` with renamed items and those without credentials; `confirmImport()` → `Done(Imported(...))` and new rows.
  - `stateToStringHidesSecrets`: with `includeSecrets = true`, no state `toString()` contains the URL or the token.
  - `AppShellTest.menuHasExportImportAbout`: the ⋮ shows `menu_export`, `menu_import`, `about_title` in that order and each calls its callback.
  - `TransferDialogsTest.exportCheckboxStartsOff` (checkbox `Off`; checking it shows `export_secrets_warning`), `summaryShowsCountsRenamesAndMissingCredentials` (`getQuantityString(R.plurals.import_summary_destinations, 2, 2)`, `getString(R.string.import_renamed, "Main", "Main (2)")`, `import_without_credentials`), `invalidShowsReason` (`getString(R.string.import_error_newer_version)`).
  - `DestinationsViewModelTest.rowFlagsMissingCredentials`; `DestinationsScreenTest.withoutCredentialsLabel` (`destination_without_credentials`).
- [ ] **Step 2: Run** the affected classes; expected: FAIL.
- [ ] **Step 3: Implement.** In `Nav.kt`, `rememberLauncherForActivityResult(CreateDocument("application/json"))` and `OpenDocument()` (`arrayOf("application/json", "text/*", "application/octet-stream")`), launched from a `LaunchedEffect(state)` when the state is `PickExportFile`/`PickImportFile`, followed by `pickerLaunched()`. `TransferDialogs(state, vm)` is drawn over any tab. The exported text lives in a private ViewModel field, outside `TransferUi`, and is cleared on write, cancel or `dismiss()`. `ContentResolverDocumentIo.read` cuts at `maxBytes + 1` and reads UTF-8. New strings (en/es) for the menu, export dialog, summary, each `ConfigError`, each `TransferMessage` and the row label. Proposed copy: `export_secrets_label` "Include URL, tokens and secret headers" / "Incluir URL, tokens y cabeceras secretas"; `export_secrets_warning` "Anyone with this file can send to your destinations" / "Quien tenga este fichero podrá enviar a tus destinos"; `import_without_credentials` "No URL or token: add them in Destinations" / "Sin URL ni token: añádelos en Destinos"; `destination_without_credentials` "No credentials" / "Sin credenciales".
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(ui): export and import from the overflow menu`.

### Task 7: About with diagnostics, back arrow and round icon

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/about/AboutViewModel.kt`
- Modify: `ui/about/AboutScreen.kt`, `ui/detail/DetailScreen.kt`, `ui/recovery/RecoveryScreen.kt`, `ui/Nav.kt`, `ui/MainActivity.kt`, `res/values*/strings.xml`
- Test: `AboutScreenTest.kt`, `DetailScreenTest.kt`, new `AboutViewModelTest.kt` (and the tests that clicked `R.string.back` by text)

**Interfaces:**
- Consumes: T5 (`DiagnosticsCollector.report`), T6 (`DocumentIo`).
- Produces:
  - `enum class DiagnosticMessage { SAVED, FAILED }`; `data class AboutState(val busy: Boolean, val message: DiagnosticMessage?, val pickFile: String?)`.
  - `class AboutViewModel(diagnostics: DiagnosticsCollector, io: DocumentIo, today: () -> LocalDate) { val state: StateFlow<AboutState>; fun startExport(); fun pickerLaunched(); fun onFile(uri: Uri?) }` + `Factory`.
  - `AboutScreen(versionName: String, state: AboutState, onOpenLink: (String) -> Unit, onExportDiagnostic: () -> Unit, onBack: () -> Unit)`.
- [ ] **Step 1: Failing tests:**
  - `AboutViewModelTest.exportWritesReport`: `startExport()` → `pickFile == "sms-forwarder-diagnostic-2026-11-03.txt"`; `onFile(uri)` → `DocumentIo` receives `report()`; `message == SAVED`; `onFile(null)` → nothing written, `message == null`; write failure → `FAILED`.
  - `AboutScreenTest.exportDiagnosticButton` (`about_export_diagnostic` calls the callback; with `SAVED`, `about_diagnostic_saved` is visible), `backIsArrow` (`onNodeWithContentDescription(getString(R.string.back))` calls `onBack`; no node with text `back`), `iconIsShown` (the other current tests keep passing).
  - `DetailScreenTest`/`RecoveryScreen` (if it has a test): going back by content description.
- [ ] **Step 2: Run**; expected: FAIL.
- [ ] **Step 3: Implement.** `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }` in the three screens, as in the editors. Icon: `Modifier.clip(CircleShape).background(colorResource(R.color.ic_launcher_background))`. Picker with `CreateDocument("text/plain")` in `Nav.kt`, same as in T6. New strings (en/es): `about_export_diagnostic` "Export diagnostics" / "Exportar diagnóstico", `about_diagnostic_hint` "Text file without SMS text, senders, URLs or tokens" / "Fichero de texto sin texto de SMS, remitentes, URLs ni tokens", `about_diagnostic_saved`, `about_diagnostic_failed`.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(about): diagnostic export, back arrow and round icon`.

### Task 8: Pass over hostile cases in `:core` (spec §16)

**Files:**
- Create: `core/src/test/kotlin/dev/smsforwarder/core/HostileInputsTest.kt`
- Modify: the `:core` code that fails (only if it fails)

**Interfaces:**
- Consumes: `Templates.render`, `DestinationPresets`, `RuleEngine`, `validateDestination`, `ConfigCodec` (T2).
- Produces: nothing new; if a case fails, the fix goes in this task with its test.
- [ ] **Step 1: Read** `TemplatesTest`, `RulesTest`, `DraftsTest`, `UrlValidatorTest`, `ConfigFileTest` and note in the report which §16 cases are already covered; do not duplicate them.
- [ ] **Step 2: New tests** in `HostileInputsTest`, bodies `"a\"b"`, `"l1\nl2\r\n"`, `"back\\slash"`, `"{body}{sender}"`, `"  "`, `"\u0000"`, `"ñ€😀"`:
  - `jsonPresetsStayValid`: with standard JSON, Telegram and a custom JSON, the rendered body parses and the text field is exactly the original (Telegram: `"<sender>: <body>"`).
  - `ntfyTextBodyIsLiteral`: the body is `"TEST: " + body`, without expanding `{body}` twice.
  - `urlVariableCannotChangeHost`: `https://h.example/x?b={body}` with body `"x@evil.example/#"` → `URI.host == "h.example"`.
  - `headerVariableCannotInject`: `X-S: {body}` with `"a\r\nX-Injected: 1"` → no CR/LF and ASCII only.
  - `catastrophicRegexInImportedRule`: a file with rule `(a+)+\\1$` imports (valid pattern) and `RuleEngine` reports it as exhausted with `"a".repeat(30) + "b"`.
  - `httpVariants`: `HTTP://h.example`, `http://h.example`, `https://`, `ftp://h.example` are neither saved nor imported.
- [ ] **Step 3: Run** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`; fix whatever fails with the minimal change.
- [ ] **Step 4: Commit** `test(core): hostile inputs across templates, rules and import`.

### Task 9: Closing delivery 2.0.0

**Files:**
- Modify: `docs/manual-test-checklist.md` (section `## v2.0.0`), `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 status, §13, §14 and this plan's decisions), `README.md` (export/import and diagnostics in the feature list)

- [ ] **Step 1: Screenshots** on the emulator (`android-emu`, debug APK seeded with `scripts/e2e-emulator.sh --no-install` or by hand): ⋮ menu, export dialog with the checkbox checked and unchecked, import summary, invalid file, Destinations with a destination without credentials, About, light and dark; 1000 px JPEG; if there are more than three, comparison with a sonnet subagent. They are saved in the ledger for the "Pending visual OK" review. `android-emu-stop` when done.
- [ ] **Step 2: OCR review** of the whole branch against `80161d7` (`ocr delegate rule`, model depending on the diff; opus expected because of data and secrets). Fix High and Medium, each with its own test.
- [ ] **Step 3: Manual checklist** `## v2.0.0` in `docs/manual-test-checklist.md`:
      export without secrets and open the file (no URL or token); export with secrets (warning visible);
      import on the same phone (everything duplicated with a suffix, nothing replaced); import without secrets
      ("No credentials" row, `NOT_CONFIGURED` warning, adding the URL sends what was waiting);
      import an invalid file (http, broken regex, version 2, any text file): message and
      nothing changes; export diagnostics and search it for a body, a sender and the URL (they do not appear);
      About with arrow, round icon and link; ⋮ with Export, Import and About on all
      tabs; update from 1.3.0 to 2.0.0 without losing destinations, rules or history; no new permissions.
- [ ] **Step 4: Spec:** §3 status (1.2.0 and 1.3.0 published; 2.0.0 implemented in `v2-delivery-4`), §13 with the decisions on format, secrets, renaming and event log, §14 with the diagnostics in About.
- [ ] **Step 5: Commit** `docs: 2.0.0 checks and spec status`; push to `origin v2-delivery-4`; CI green.
- [ ] **Step 6: Release (done by the user; the agent does not push `main` or tags):**
      `git -C /srv/http/sms-forwarder checkout main && git merge --ff-only origin/v2-delivery-4 && git push origin main && git tag v2.0.0 && git push origin v2.0.0`.
      The `release.yml` workflow signs and publishes the APK.
- [ ] **Step 7: Update on the emulator with locally signed release APKs** (after the tag or, if the user asks earlier, with the branch): 1.3.0 from the GitHub release (`gh release download v1.3.0`), configured through the UI with a standard JSON destination, a `TEST` rule and some sent SMS; 2.0.0 built with `GITHUB_REF_NAME=v2.0.0` and the signing key from `~/sms-forwarder-keys` (variables only inside the same command, as in delivery 3), `versionCode` 20000, same certificate SHA-256 (`apksigner verify --print-certs`), `adb -s emulator-5554 install -r`; walk through the `## v2.0.0` list (the file to import is uploaded with `adb push` to `/sdcard/Download`). Report per item in the ledger; failures become new tasks. `android-emu-stop` when done. Installing on the real phone waits for the user.
