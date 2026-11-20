# v2 delivery 2 (1.2.0): destinations, rules, deliveries and migration. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app stores destinations, rules and one delivery per SMS + destination pair, migrates the 1.1.x data with no user intervention, and sends with a generic engine, without changing anything the user sees or anything the Worker receives.

**Architecture:** The pure policies (rules, templates, escaping, success and error policy, aggregate status) live in `:core` and are tested on the JVM. In `:app`, Room moves to version 3 with the tables `destination`, `rule`, `rule_destination` and `delivery`. The migration is done in two phases: the Room SQL (atomic) creates the schema and the Main destination with one delivery per SMS, and an app import (idempotent) copies the token to the encrypted store and turns the senders into rules. The Settings screen keeps editing "the connection" and "the senders", which are now the main destination and its exact rules.

**Tech Stack:** Kotlin, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization, Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), delivery 2 (§8, §9, §11, §12, §16). Previous plan: [delivery 1](2026-11-02-v2-delivery-1-shell.md).

## Decisions of this plan (recorded in the spec in T1)

- **Success of the migrated destination: any 2xx** (user decision, 2026-11-02). Spec §11 said "only 200"; a 201/204 would be retried endlessly and would duplicate sends.
- **Main is always created** when a destination is needed: in the SQL migration (even with no credentials) and, in a fresh install, when adding the first sender or saving credentials. Without secrets, the destination counts as "not configured" (`NOT_CONFIGURED`), like v1 without credentials. Reason: rules and deliveries need a destination to point to.
- **The unit of work is still the SMS**: `SendWorker` with `KEY_SMS_ID` and unique name `send-<smsId>`, which sends that SMS's pending deliveries in parallel. This way the jobs already queued by 1.1.x remain valid after updating, and a destination that is down does not hold back the others (each delivery is recorded on its own; those already sent are not repeated).
- **`sms.status`, `attempts`, `lastHttpCode`, `lastError`, `sentAt` and `stuckNotified` are kept as an aggregate** of their deliveries, recomputed in the same transaction as each attempt. Home, detail, purge, stuck notice and health keep reading the SMS row.
- **The URL and the secret headers of each destination are stored encrypted** (not only the secret ones): v1 already hid the stored URL.
- `{sim}` is the slot (1 or 2) computed with `SubscriptionManager.getSlotIndex(subId) + 1`; empty if unknown. The SIM condition of a rule uses that same value.

## Global Constraints

- v1 rules that still apply: https only; no foreground service; no `fallbackToDestructiveMigration`; backups disabled; no SMS texts, real senders or the author's project names in code, tests, strings or docs.
- The Worker must not notice the change: for the migrated destination, the body sent is byte for byte that of `IngestPayload.encode(sender, body, receivedAt)`, with the same `Authorization: Bearer <token>` header and `Content-Type: application/json; charset=utf-8`, POST method.
- `localKey`, per-SMS deduplication, the sweep, the sweep floor and the MIUI notices do not change.
- `minSdk = 31`. Tests with `robolectric.properties` `sdk=35`. Local JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. `:app` is only verified in CI (`gh run watch`).
- Room schema: `app/schemas/dev.smsforwarder.data.AppDatabase/3.json` is committed in the same task that bumps the version; CI fails if the schema changes without being committed.
- The URL, the token, the secret headers and the SMS body are never written to the log or to `toString()`.
- No new UI: no screen changes its look. New texts (failed-migration notice) go in `values/` and `values-es/`; `:app` tests compare with `context.getString(R.string.x)`.
- Each task leaves `:core:test` and the `:app` build and tests green (CI): no task changes a type and leaves its users for another.
- Code review: OCR only (`ocr delegate rule`), never the generic reviewers. No Claude attribution lines in commits or PRs.
- Branch `v2-delivery-2` from `main`, in worktree `/srv/http/sms-forwarder-wt/v2-d2`. Push and secrets: authorized by the user per task.

## Review Focus

1. A `send-<smsId>` job queued by 1.1.x and still pending when updating: after the migration it runs and sends the SMS exactly once to Main (T7).
2. Updating with an SMS in `ERROR` 401 and with historical attempts: they stay in error with their attempts visible in the detail, and when credentials are saved they go back to the queue (T3, T6).
3. An import that fails halfway (Keystore unavailable): it is not marked as done, the app sends nothing, warns, and on the next start it retries without duplicating rules (T5).
4. An SMS that matches two rules with the same destination: a single delivery to that destination (T2, T6).
5. A paused destination next to an active one: the active one sends and the SMS stays `QUEUED` with no attempts on the paused one; the job does not retry in a loop because of the paused one (T7).

---

### Task 1: Rules in `:core` and an up-to-date spec

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Rules.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/RulesTest.kt`
- Modify: `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 status, §11 success of the migrated destination, §12 with the decisions above)

**Interfaces:**
- Produces:
  - `enum class SenderMode { EXACT, CONTAINS, REGEX }`, `enum class BodyMode { CONTAINS, REGEX }`, `enum class SimMatch { ANY, SIM1, SIM2 }`
  - `data class RuleSpec(val id: Long, val enabled: Boolean, val senderMode: SenderMode, val senderPattern: String, val bodyMode: BodyMode?, val bodyPattern: String?, val sim: SimMatch, val destinationIds: Set<Long>)`
  - `data class IncomingSms(val sender: String, val body: String, val simSlot: Int?)`
  - `object RuleEngine { fun destinationsFor(sms: IncomingSms, rules: List<RuleSpec>, regexBudgetMs: Long = 50): RuleMatch; fun validatePattern(pattern: String): Boolean }`
  - `data class RuleMatch(val destinationIds: Set<Long>, val timedOutRuleIds: Set<Long>)`

- [ ] **Step 1: Write the failing tests** in `RulesTest`:
  - `exactIgnoresSpacesAndCase`: EXACT rule `"AB C"` matches sender `"abc"`; not `"ABCD"`.
  - `containsNormalizes`: CONTAINS `"bank"` matches `"MY BANK"`.
  - `regexUsesRawSenderAndFind`: REGEX `"^Ab"` matches `"Abc"` and not `"abc"`; REGEX `"(?i)^ab"` matches `"abc"`.
  - `bodyFilterMustAlsoMatch`: sender matches but BodyMode CONTAINS `"code"` and a body without "code" → no destinations.
  - `simFilter`: `SimMatch.SIM2` does not match `simSlot = 1` nor `null`; `ANY` matches `null`.
  - `disabledRuleIgnored`.
  - `unionWithoutDuplicates`: two rules with destinations `{1,2}` and `{2,3}` → `{1,2,3}`.
  - `catastrophicRegexTimesOut`: REGEX body `"(a+)+$"` over `"a".repeat(40) + "!"` → no destinations, `timedOutRuleIds == setOf(id)`, and the call takes less than 1 s.
  - `invalidPatternRejected`: `validatePattern("(")` is false; `validatePattern("^a.*")` is true.
  - `invalidStoredPatternNeverMatches`: a rule with an invalid pattern does not match (does not throw).
- [ ] **Step 2: Run** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test --tests '*RulesTest'`; expected: FAIL (does not compile).
- [ ] **Step 3: Implement `Rules.kt`.** EXACT and CONTAINS compare `SenderFilter.normalize` on both sides; an empty normalized pattern matches nothing. REGEX and BodyMode.REGEX use `Regex.find` over the original text wrapped in a `CharSequence` that throws its own exception when the deadline passes in `charAt` (the standard trick to cut backtracking); that exception counts as "does not match" and adds the id to `timedOutRuleIds`. A pattern that does not compile counts as "does not match".
- [ ] **Step 4: Run** the same command; expected: PASS.
- [ ] **Step 5: Record in the spec** the six decisions from the header of this plan (§11: standard JSON = any 2xx; §12: Main always, worker per SMS, aggregate status, encrypted secrets, `{sim}` = slot) and the status of §3 (delivery 1 published as 1.1.0).
- [ ] **Step 6: Commit** `feat(core): rule engine with regex time limit`.

### Task 2: Templates, escaping and delivery policy in `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Templates.kt`, `core/src/main/kotlin/dev/smsforwarder/core/DeliveryPolicy.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/TemplatesTest.kt`, `DeliveryPolicyTest.kt`

**Interfaces:**
- Produces:
  - `data class SmsVars(val sender: String, val body: String, val receivedAt: Long, val simSlot: Int?, val device: String)`
  - `enum class HttpMethod { POST, PUT, GET }`, `enum class SuccessPolicy { STATUS_200, ANY_2XX }`, `enum class TemplateKind { STANDARD_JSON, TELEGRAM, NTFY, CUSTOM }`
  - `data class DestinationSpec(val method: HttpMethod, val url: String, val headers: List<Pair<String, String>>, val body: String?, val bodyIsJson: Boolean)`
  - `data class RenderedRequest(val method: HttpMethod, val url: String, val headers: List<Pair<String, String>>, val body: String?, val contentType: String?)` with `toString()` without URL, headers or body
  - `object Templates { val VARIABLES: Set<String>; fun unknownVariables(text: String): Set<String>; fun render(spec: DestinationSpec, vars: SmsVars): RenderedRequest; fun standardJson(url: String, token: String): DestinationSpec }`
  - `enum class DeliveryStatus { QUEUED, SENT, ERROR }`
  - `fun classifyDelivery(httpCode: Int?, responseBody: String?, policy: SuccessPolicy): SendOutcome`
  - `fun aggregateStatus(statuses: Collection<DeliveryStatus>): SmsStatus`
  - `fun isAuthRejection(httpCode: Int?): Boolean` (401 or 403)

- [ ] **Step 1: Write the failing tests** in `TemplatesTest`:
  - `standardJsonIsByteIdenticalToV1`: for bodies `"hello"`, `"say \"naïve\""`, `"café\nnaïve"`, `"c:\\path"`, `"{body} literal"`, `"emoji 😀\t"`, `render(standardJson(url, "t"), vars).body == IngestPayload.encode(sender, body, receivedAt)`; POST method; headers `[("Authorization", "Bearer t")]`; `contentType == "application/json; charset=utf-8"`.
  - `bodyContainingVariableNameIsNotExpandedTwice`: an SMS with body `"{sender}"` produces `"body":"{sender}"`.
  - `jsonEscapeBlocksInjection`: JSON template `{"t":"{body}"}` with body `"\",\"x\":\"1"` → the result parses as JSON with a single key `t`.
  - `urlVariablesAreEncoded`: URL `https://h/x?m={body}` with body `"a b&c"` → `https://h/x?m=a%20b%26c`.
  - `headerVariablesDropLineBreaks`: header `X: {body}` with `"a\r\nb"` → value without `\r` or `\n`.
  - `receivedAtIsMillisDigits`: `{received_at}` and `{received_at_ms}` → `"1730000000000"` for `receivedAt = 1730000000000`.
  - `unknownVariablesListed`: `unknownVariables("{sender} {foo} {body}") == setOf("foo")`.
  - `toStringHidesSecrets`: `render(...).toString()` contains neither the URL nor the token nor the body.
- [ ] **Step 2: Write the failing tests** in `DeliveryPolicyTest`:
  - `any2xxAccepts`: 200, 201 and 204 → `Accepted` with `ANY_2XX`; with `STATUS_200`, 201 → `Retry(201, "http_201")`.
  - `authRejections`: 401 and 403 → `Rejected` with that code and `errorCode` from the body `{"error":"x"}`.
  - `badRequest`: 400 → `Rejected(400, ...)`.
  - `transient`: `null` → `Retry(null, "network")`; 429, 500, 302 → `Retry`.
  - `aggregate`: `{SENT, QUEUED} → QUEUED`; `{SENT, ERROR, QUEUED} → ERROR`; `{SENT, SENT} → SENT`; empty → `QUEUED`.
- [ ] **Step 3: Run** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`; expected: FAIL (does not compile).
- [ ] **Step 4: Implement.** The substitution walks the template only once (a `Regex("\\{([a-z_]+)\\}").replace` with a function), so the inserted text is never expanded again. JSON escape: `JsonPrimitive(s).toString()` without the outer quotes, to match kotlinx byte for byte. URL escape: `URLEncoder.encode(s, UTF_8).replace("+", "%20")`. Headers: remove `\r` and `\n`. `standardJson` uses as body `{"sender":"{sender}","body":"{body}","received_at":{received_at}}` with `bodyIsJson = true`. The v1 `classify` stays as a wrapper of `classifyDelivery(..., ANY_2XX)` so as not to break its users.
- [ ] **Step 5: Run** the same command; expected: PASS.
- [ ] **Step 6: Commit** `feat(core): destination templates and delivery policy`.

### Task 3: Room v3 schema and SQL migration

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/DestinationEntity.kt`, `RuleEntity.kt` (with `RuleDestinationEntity`), `DeliveryEntity.kt`, `DestinationDao.kt`, `RuleDao.kt`, `DeliveryDao.kt`
- Modify: `app/src/main/kotlin/dev/smsforwarder/data/AppDatabase.kt`, `AttemptEntity.kt`, `AppGraph.kt` (`addMigrations(MIGRATION_1_2, migration2to3(...))`)
- Create: `app/schemas/dev.smsforwarder.data.AppDatabase/3.json` (generated by KSP)
- Test: `app/src/test/kotlin/dev/smsforwarder/data/MigrationTest.kt`

**Interfaces:**
- Consumes: enums from T1 and T2 (stored by name with `Converters`).
- Produces:
  - `destination(id PK, name TEXT, enabled INT, template TEXT, method TEXT, bodyTemplate TEXT?, bodyIsJson INT, publicHeaders TEXT (JSON of pairs), successPolicy TEXT, createdAt INT, deletedAt INT?)`. The URL and secret headers do not go here (T4).
  - `rule(id PK, name TEXT, enabled INT, senderMode TEXT, senderPattern TEXT, bodyMode TEXT?, bodyPattern TEXT?, sim TEXT, createdAt INT)` and `rule_destination(ruleId, destinationId, composite PK, FK CASCADE to both)`.
  - `delivery(id PK, smsId FK CASCADE, destinationId FK SET NULL nullable, destinationName TEXT, status TEXT, attempts INT, lastHttpCode INT?, lastError TEXT?, sentAt INT?, UNIQUE(smsId, destinationId))`, index on `status`.
  - `attempt.deliveryId INTEGER` nullable, FK CASCADE to `delivery`, with an index.
  - `fun migration2to3(primaryName: String): Migration`; `const val PRIMARY_DESTINATION_ID = 1L`.
  - DAOs with the queries that T6 and T7 need: `DeliveryDao.forSms(smsId)`, `pendingForSms(smsId)` (`status = QUEUED`), `get(id)`, `update`, `insertAll`, `smsIdsWithStatus(status)`, `idsAuthRejected(destinationId)`, `observeForSms(smsId)`; `RuleDao.activeWithDestinations(): List<RuleWithDestinations>` (`@Relation` relationship), `insert`, `deleteExactForDestination(pattern, destinationId)`; `DestinationDao.get(id)`, `firstActive()`, `insert`, `update`.
- [ ] **Step 1: Write the failing tests** in `MigrationTest` (same pattern as the current one: create the database from `2.json`, seed rows with SQL and open with the real class):
  - `migrates2to3KeepingEverySms`: with 3 SMS (`SENT` with 2 attempts, `QUEUED` with 0, `ERROR` 401 with 1) and their attempts, after opening there is a destination with id 1 named `"Main"`, template `STANDARD_JSON`, `successPolicy = ANY_2XX`, `enabled = 1`; 3 deliveries with the same `status`, `attempts`, `lastHttpCode`, `lastError` and `sentAt` as their SMS and `destinationName = "Main"`; each attempt with the `deliveryId` of its SMS's delivery.
  - `migrationIsValidatedByRoom`: opening with Room does not throw (Room compares the tables with the entities).
  - `migrates1to3`: a v1 database reaches v3 by chaining both migrations.
  - `emptyDatabaseStillGetsPrimary`: with no SMS, the migration leaves destination id 1 and zero deliveries.
- [ ] **Step 2: Run in CI** `./gradlew :app:testDebugUnitTest --tests '*MigrationTest'`; expected: FAIL.
- [ ] **Step 3: Implement entities, DAOs and `migration2to3`.** The SQL creates the tables with the exact `createSql` that Room will generate (copy it from `3.json` after the first build), inserts the destination with `primaryName`, inserts one delivery per SMS with `INSERT INTO delivery (...) SELECT ... FROM sms`, and adds the column to `attempt`. Room already wraps the migration in a transaction. The `attempt` entity gains the FK to `delivery`: since SQLite does not add an FK with `ALTER TABLE`, the migration recreates `attempt` (`CREATE TABLE attempt_new`, `INSERT ... SELECT` with `deliveryId` by join to `delivery`, `DROP`, `ALTER TABLE ... RENAME`, indexes). `AppDatabase` goes up to `version = 3`; `AppGraph` passes `context.getString(R.string.destination_primary_name)` (`"Main"` / `"Principal"`).
- [ ] **Step 4: Run in CI**; expected: PASS, and `3.json` committed identical to the generated one.
- [ ] **Step 5: Commit** `feat(data): destinations, rules and deliveries schema with migration`.

### Task 4: Secrets per destination

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/data/SecretStore.kt`
- Test: manual checklist (Keystore does not run in Robolectric) and `app/src/test/kotlin/dev/smsforwarder/data/DestinationSecretsTest.kt` for the serialization

**Interfaces:**
- Produces:
  - `data class DestinationSecrets(val url: String, val headers: List<Pair<String, String>>)` with `toString() = "DestinationSecrets(***)"`
  - `interface DestinationSecretStore { suspend fun get(destinationId: Long): DestinationSecrets?; suspend fun set(destinationId: Long, s: DestinationSecrets); suspend fun remove(destinationId: Long) }`
  - `class KeystoreDestinationSecretStore(context: Context) : DestinationSecretStore` (same Keystore key `smsfwd-secrets`, entries `dest_<id>_iv` and `dest_<id>_blob` in the `secrets` DataStore)
  - `fun DestinationSecrets.Companion.encode/decode` (JSON) and `fun Credentials.toDestinationSecrets()`, `fun DestinationSecrets.toCredentialsOrNull(): Credentials?` (reads `Authorization: Bearer <token>`)
  - `class FakeDestinationSecretStore` in `app/src/test` for T5 to T7.
  - The legacy `SecretStore` stays unchanged: only the import reads it (T5).
- [ ] **Step 1: Failing test** `DestinationSecretsTest`: `encode`/`decode` round trip with a URL and a header with quotes and `\u0000`; `Credentials("https://h", "t").toDestinationSecrets().toCredentialsOrNull() == Credentials("https://h", "t")`; `toString()` contains neither URL nor token.
- [ ] **Step 2: Implement.** A corrupt blob or an invalidated key deletes only that entry and returns `null`, like the current `get()`.
- [ ] **Step 3: CI green. Commit** `feat(data): encrypted secrets per destination`.

### Task 5: Import from 1.1.x and safe mode

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/LegacyImport.kt`
- Modify: `SettingsRepository.kt` (keys `v2_import_done`, `v2_import_failed`), `AppGraph.kt`, `SmsForwarderApp.kt`, `health/HealthChecker.kt` (`HealthIssue.MIGRATION_FAILED`), `notify/Notifier.kt`, `ui/main/MainScreen.kt` (card text), `res/values*/strings.xml` (`issue_migration_failed`, `notif_issue_migration_failed`)
- Test: `app/src/test/kotlin/dev/smsforwarder/data/LegacyImportTest.kt`

**Interfaces:**
- Consumes: legacy `SecretStore`, `DestinationSecretStore`, `RuleDao`, `SettingsRepository.senders` (the legacy list in DataStore), `PRIMARY_DESTINATION_ID`.
- Produces: `class LegacyImport(db, legacySecrets: SecretStore, secrets: DestinationSecretStore, settings: SettingsRepository) { suspend fun run(): Boolean }` (true = done or already was); `suspend fun SettingsRepository.isImportFailed(): Boolean`.
- [ ] **Step 1: Failing tests** in `LegacyImportTest`:
  - `copiesCredentialsAndSenders`: with legacy credentials and senders `["AAA", "B B"]`, after `run()` destination 1 has secrets equivalent to the credentials and there are two active EXACT rules towards destination 1 with those patterns.
  - `idempotent`: two consecutive `run()` calls leave two rules, not four.
  - `noCredentialsStillImportsRules`: without credentials, rules created and destination 1 without secrets.
  - `keystoreFailureLeavesNothingHalfDone`: with a `DestinationSecretStore` that throws in `set`, `run()` returns false, there are no new rules, `isImportFailed()` is true; a second `run()` with a working store imports everything and clears the failure.
  - `freshInstallSkips`: a database without destination 1 (fresh install) → `run()` marks it done without creating anything.
- [ ] **Step 2: Implement.** Order: if `v2_import_done`, exit. Copy secrets (before anything in Room). Then, in a Room transaction, insert the missing rules (comparing the normalized pattern). Finally mark done and clear `v2_import_failed`. Any exception that is not a cancellation marks `v2_import_failed` and returns false. `SmsForwarderApp.onCreate` launches `run()` in the existing `applicationScope`; `SendWorker` (T7) and the sweep call `run()` before working and do not send if it returns false. `HealthChecker` adds `MIGRATION_FAILED` when `isImportFailed()`.
- [ ] **Step 3: CI green. Commit** `feat(data): import 1.1 settings into destinations and rules`.

### Task 6: Repositories and SMS intake with rules

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/DestinationRepository.kt`, `RuleRepository.kt`, `app/src/main/kotlin/dev/smsforwarder/sms/SimSlots.kt`
- Modify: `data/SmsRepository.kt`, `data/SettingsRepository.kt`, `sms/SmsReceiver.kt`, `sms/Sweeper.kt`, `ui/recovery/RecoveryViewModel.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/MainActivity.kt`, `health/HealthChecker.kt`, `AppGraph.kt`
- Test: `SmsRepositoryTest.kt`, `SweeperTest.kt`, `SmsReceiverTest.kt`, `SettingsRepositoryTest.kt`, `HealthCheckerTest.kt`, new `RuleRepositoryTest.kt`

**Interfaces:**
- Consumes: `RuleEngine`, `RuleSpec`, `IncomingSms` (T1); DAOs (T3); `DestinationSecretStore` (T4).
- Produces:
  - `DestinationRepository { suspend fun ensurePrimary(): Long; suspend fun primaryCredentials(): Credentials?; suspend fun savePrimaryCredentials(c: Credentials); suspend fun get(id: Long): DestinationEntity?; suspend fun secrets(id: Long): DestinationSecrets? }`. "Primary" = the active destination with the lowest id and no `deletedAt`; `ensurePrimary` creates it with the name from resources and `STANDARD_JSON` + `ANY_2XX` if there is none.
  - `RuleRepository { val senders: Flow<List<String>>; suspend fun addSender(s: String); suspend fun removeSender(s: String); suspend fun activeRules(): List<RuleSpec>; suspend fun hasActiveRules(): Boolean }`. `senders` = patterns of EXACT rules towards the primary; `addSender` calls `ensurePrimary` and does not duplicate (normalized).
  - `SmsRepository.ingest(record: SmsRecord, origin: SmsOrigin, destinationIds: Set<Long>): IngestResult` creates the SMS and deliveries (`QUEUED`, current `destinationName`) in one transaction; an `AlreadyKnown` adds no deliveries.
  - `SmsRepository.requeue(id)` sets the SMS's unsent deliveries to `QUEUED` and recomputes the aggregate; `idsWithAuthError(): List<Long>` (SMS ids with any delivery in `ERROR` 401/403) replaces `idsWithError401()`; `requeueAuthErrors(destinationId): List<Long>`.
  - `SimSlots.slotOf(context, subId: Int?): Int?`.
  - `SettingsRepository.senders/addSender/removeSender` delegate to `RuleRepository`; `saveCredentials` calls `savePrimaryCredentials`, `requeueAuthErrors(primary)` and schedules those SMS and the `QUEUED` ones. Public signatures unchanged, so the UI is not touched.
  - `HealthIssue.NO_SENDERS` is renamed `NO_RULES` (same text) and comes from `!hasActiveRules()`; `NOT_CONFIGURED` comes from `primaryCredentials() == null`.
- [ ] **Step 1: Failing tests:**
  - `RuleRepositoryTest.addSenderCreatesExactRuleToPrimary`, `addSenderTwiceNormalizedIsOne`, `removeSenderDeletesRule`, `sendersFlowListsPatterns`.
  - `SmsRepositoryTest.ingestCreatesOneDeliveryPerDestination` (destinations `{1, 2}` → 2 `QUEUED` deliveries), `alreadyKnownAddsNoDelivery`, `requeueResetsUnsentDeliveriesOnly` (one `SENT` and one `ERROR` → `SENT` and `QUEUED`, SMS in `QUEUED`).
  - `SweeperTest.unmatchedSenderNotStored` and `matchedGoesToRuleDestinations` (replace the sender-list ones).
  - `SmsReceiverTest`: the existing allowed-sender case keeps passing with rules created by `addSender`.
  - `SettingsRepositoryTest.saveCredentialsRequeuesAuthErrors`: a 403 `ERROR` delivery of the primary goes back to `QUEUED` and its SMS is scheduled.
  - `HealthCheckerTest`: `NO_RULES` without rules; `NOT_CONFIGURED` with a primary without secrets.
- [ ] **Step 2: Implement.** Receiver, sweep and recovery build `IncomingSms(sender, body, SimSlots.slotOf(context, subId))`, ask for `RuleEngine.destinationsFor(..., ruleRepository.activeRules())` and, if the set is empty, store nothing (as today with a sender outside the list). The `timedOutRuleIds` are noted with `Log.w` using only the rule id.
- [ ] **Step 3: CI green. Commit** `feat(data): ingest through rules into per-destination deliveries`.

### Task 7: Sending engine per delivery

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/net/DeliveryClient.kt`
- Modify: `work/SendWorker.kt`, `data/SmsRepository.kt` (`recordAttempt` per delivery), `notify/Notifier.kt` (`tokenRejected(destinationId, waiting)` with a per-destination tag, same text), `net/IngestClient.kt` (only `testConnection` remains)
- Test: `SendWorkerTest.kt`, `SmsRepositoryTest.kt`, new `DeliveryClientTest.kt` (MockWebServer, like `IngestClientTest`)

**Interfaces:**
- Consumes: `Templates.render`, `classifyDelivery`, `aggregateStatus`, `isAuthRejection` (T2); `DeliveryDao` (T3); `DestinationSecretStore` (T4); `LegacyImport.run` (T5).
- Produces:
  - `class DeliveryClient(http: OkHttpClient) { suspend fun send(req: RenderedRequest, policy: SuccessPolicy): SendOutcome }` (same bounded reading of the 4xx body and same caught exceptions as `IngestClient.post`).
  - `SmsRepository.recordAttempt(deliveryId: Long, outcome: SendOutcome)`: writes the attempt with `deliveryId` and `smsId`, updates the delivery (a `SENT` delivery is not undone) and recomputes the SMS aggregate in the same transaction: `status = aggregateStatus(...)`, `attempts` = sum, `lastHttpCode`/`lastError` from the last attempt, `sentAt` when the aggregate becomes `SENT`.
  - `SendWorker` (same `KEY_SMS_ID` key): if `LegacyImport.run()` is false or global forwarding is paused, it exits without sending (`retry()` and `success()` respectively, like the pause today). For each `QUEUED` delivery of the SMS, in parallel with `coroutineScope { async }`: paused or deleted destination → skipped without an attempt; no secrets → counts as "retry"; otherwise `render` + `send` + `recordAttempt`. Result: `retry()` if any delivery was left to retry, otherwise `success()`. Notices: 401/403 → `tokenRejected(destinationId, idsAuthRejected(destinationId).size)`; 400 → `rejected(sms)` as today.
- [ ] **Step 1: Failing tests:**
  - `DeliveryClientTest.postsRenderedRequest`: MockWebServer receives the exact method, headers and body; `ANY_2XX` with 204 → `Accepted`.
  - `SendWorkerTest.primarySendsV1Payload`: with the migrated destination, the server receives `IngestPayload.encode(...)` byte for byte and `Authorization: Bearer t`.
  - `SendWorkerTest.downDestinationDoesNotBlockOther`: destinations A (500) and B (200) → delivery B `SENT`, A `QUEUED` with 1 attempt, SMS `QUEUED`, result `retry`; a second run with A at 200 does not call B again.
  - `SendWorkerTest.pausedDestinationSkipped`: A paused and B at 200 → B `SENT`, A `QUEUED` with 0 attempts, result `success`.
  - `SendWorkerTest.legacyQueuedWorkStillSends`: a database migrated from v2 with a `QUEUED` SMS and a job with `KEY_SMS_ID` → a single request to the primary.
  - `SendWorkerTest.importFailedSendsNothing`: with `v2_import_failed` and a store that keeps failing → zero requests, `retry`.
  - `SmsRepositoryTest.lateResultDoesNotUndoSent` (per-delivery version of the existing test) and `attemptsVisibleOnSms` (the detail still shows the attempts through `observeAttempts(smsId)`).
- [ ] **Step 2: Implement.** `SendWorker` builds `SmsVars(sms.sender, sms.body, sms.receivedAt, SimSlots.slotOf(...), Build.MODEL)` and the `DestinationSpec` from the entity and its secrets.
- [ ] **Step 3: CI green. Commit** `feat(work): send each SMS to its destinations independently`.

### Task 8: Closing delivery 1.2.0

**Files:**
- Modify: `docs/manual-test-checklist.md` (section "Updating from 1.1 to 1.2"), `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 status)

- [ ] **Step 1: OCR review** of the whole branch against `main` (`ocr delegate rule`, model according to the diff; opus expected because of the schema). Fix High and Medium with one test each.
- [ ] **Step 2: New manual checklist:** update the real app from 1.1.0 to the branch APK without uninstalling; check that Home shows the same history, that Settings shows the same senders, that the connection test succeeds, that a new SMS reaches the Worker with 200 and that the app asks for nothing. The release is not debuggable, so its database cannot be copied before updating: the safety net is the migration tests of T3 and T5, and the user confirms before installing that they accept that risk.
- [ ] **Step 3: With the user's OK:** push the branch, CI green, fast-forward to `main`, tag `v1.2.0`, `adb install -r` of the release and go through the checklist with the user.
- [ ] **Step 4: Commit** `docs: 1.2.0 upgrade checks`.
