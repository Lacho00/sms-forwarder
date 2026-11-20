# v2 delivery 3 (1.3.0): Rules and Destinations tabs. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The user creates, edits, pauses, tests and deletes destinations and rules from their own tabs, with templates (standard JSON, Telegram, ntfy, custom), and sees on Home and in the detail view the status of each SMS per destination, with "Resend to a destination".

**Architecture:** Drafts, validation, templates and the test policy are pure and live in `:core`. In `:app`, `DestinationRepository` and `RuleRepository` gain create, edit and delete operations; deleting a destination cancels its pending deliveries (`DeliveryStatus.CANCELED`) and recomputes the SMS aggregate in the same transaction. The UI adds two tabs to the manual navigation (`Nav.kt`), two lists and two full-screen editors; Settings loses the Connection and Senders groups. No Room schema change: the new state is just one more value of the enum that is already stored by name.

**Tech Stack:** Kotlin, Compose Material 3, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization, Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), delivery 3 (§3, §5, §6, §7, §8, §9, §10, §11). Previous plan: [delivery 2](2026-11-02-v2-delivery-2-model.md); its ledger with the deferred items: `.superpowers/sdd/2026-11-02-v2-delivery-2-model/progress.md`.

**Branch:** `v2-delivery-3` **from `v2-delivery-2`, not from `main`**, because 1.2.0 is not merged yet (the update test on the phone is missing). Worktree `/srv/http/sms-forwarder-wt/v2-d3`. If 1.2.0 is merged before this is done, rebase onto `main` with no changes. The closing OCR review compares against `v2-delivery-2`.

## Decisions of this plan (recorded in the spec in T10; those marked ⚠ are confirmed with the user before T1)

**Confirmed by the user on 2026-11-03:** all eight marked ⚠. Open questions resolved: "Resend to a destination" accepts a destination that already received the SMS; SMS whose deliveries are all `CANCELED` are purged with the normal retention; how Home shows canceled or misconfigured deliveries is asked of the user in T9.

- ⚠ **Deleting a destination:** its `QUEUED` and `ERROR` deliveries become `CANCELED` (new `DeliveryStatus`); `SENT` ones keep their name. `aggregateStatus` ignores `CANCELED`; an SMS whose deliveries are all `CANCELED` ends up `ERROR` with `lastError = "destination_deleted"`, with no stuck warning, and can be resent to another destination. Rules lose the link to that destination; a rule with no destinations stays but does not count as active and its row warns.
- ⚠ **Configuration errors** (a template that cannot be rendered, a URL or header that OkHttp rejects): new `SendOutcome.Invalid(reason)`, terminal. An attempt is recorded with `httpCode = null` and `error = "config_<reason>"`, the delivery becomes `ERROR`, it warns like a 400 (its own text) and goes back to the queue when the destination is saved. Today it retries endlessly with no visible attempt.
- ⚠ **Sweep and in-flight jobs:** `kickQueued` replaces (`REPLACE`) the `send-<id>` job only if it is not `RUNNING`; if it is running, it leaves it alone. Reason: `REPLACE` cancels a half-done send and, with several destinations, can duplicate the SMS at the one that already responded. Plain `KEEP` would leave an SMS waiting out the backoff (up to hours) after resuming.
- **Real `lastHttpCode` on 2xx:** `SendOutcome.Accepted` carries the code (`Accepted(httpCode)`); today 200 is stored for any 2xx. Home shows `✓204` if the destination answered 204.
- **Stuck by deliveries:** an SMS is stuck if any delivery to a live, active destination is still `QUEUED` past the deadline, or any is `ERROR` 400 or a configuration error. 401/403 are covered by the token warning; those of paused destinations and `CANCELED` ones do not count.
- **Valid JSON on save:** if the body is JSON, every text variable and `{sim}` is replaced by the empty string and `{received_at}`/`{received_at_ms}` by `0`, and the result must parse. This way unquoted `{sim}`, `{body}`, `{sender}` and `{device}` cannot be saved (delivery 2 resolution T2).
- **Secret URL and headers are never shown again**, for any destination type (same as the URL in v1). When editing, an empty field = keep what is stored.
- ⚠ **GET carries no body:** a GET destination with a body cannot be saved.
- ⚠ **Templates:** Telegram sends `{"chat_id":"<chat id>","text":"{sender}: {body}"}`; ntfy sends a POST with a text body `{sender}: {body}` and no default headers (headers are URL-encoded and a `Title: {sender}` would come out with `%2B`). Custom starts as POST, JSON, empty body.
- ⚠ **Test** uses what is in the form, completed with the stored secrets where the field is empty; there is no need to save first. The test SMS has sender `TEST` and body `R.string.destination_test_body` ("test" / "prueba"); it is never stored or recorded as an attempt.
- **`removeSender`** removes only the rule's link to the primary and deletes the rule only if it is left with no destinations.
- ⚠ **Onboarding unchanged:** it still asks for senders and connection, which create the Main destination (standard JSON) and exact rules. The spec does not mention it.
- ⚠ **`NOT_CONFIGURED`** becomes "some destination used by an active rule has no secrets" (before: the primary without secrets).

## Global Constraints

- v1 rules that still apply: **https only**; **no foreground service** or fixed notification; **no `fallbackToDestructiveMigration`**; **backups disabled**; no real SMS texts, real senders or the author's project names in code, tests, strings or docs (examples: `AAA`, `TEST`, `https://h.example`).
- Room schema: stays at version 3. If a task needed to touch it, it carries an explicit 3→4 migration, a committed `4.json` and a test in `MigrationTest`; CI fails on an uncommitted schema.
- All new visible text goes in `values/strings.xml` (English) and `values-es/strings.xml`; `:app` tests compare with `context.getString(R.string.x)`, never with literals.
- `minSdk = 31`. Compose with manual navigation (`Nav.kt`, stack in a `String` with `rememberSaveable`), **no `navigation-compose`**. ViewModels with `ViewModelProvider.Factory` created in `MainActivity`, like the current ones.
- Local JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. **`:app` tests only run in CI** (Robolectric, `robolectric.properties` `sdk=35`, a `TestApp` that does not launch the legacy import in `onCreate`: it resolves the race noted in T5 of delivery 2; the new `:app` tests rely on it and do not create their own `Application`).
- The URL, the token, secret headers, the SMS body and destination drafts are never written to the log or to `toString()`.
- Templates and substitution: those of delivery 2 (`Templates.render`) with no change in behavior; `{received_at}` identical across retries. The migrated destination sends byte for byte the same as 1.2.0.
- **Design:** colors, sizes, spacing, tab icons and form layout that the spec does not fix are asked of the user before applying them (steps marked **ask the user**). Material 3 components and styles the app already uses are used; no hand-picked colors or measurements without an OK.
- Each task leaves `:core:test` and the `:app` build and tests green (CI): no task changes a type and leaves its users for another.
- Code review: OCR only (`ocr delegate rule`), never the generic reviewers. No Claude attribution lines in commits or PRs. Push to `origin v2-delivery-3` with the user's OK (can be given once for the whole plan); `main`, tags and installation always with explicit OK.

## Review Focus

1. Deleting a destination while a `SendWorker` is sending to it: the late result does not resurrect the `CANCELED` delivery or change the aggregate (T3, `lateResultOnCanceledIgnored`).
2. Editing a destination leaving the secret URL and headers empty: the stored ones are kept and never appear on screen or in the ViewModel state (T3, T6).
3. Custom JSON body with unquoted `{sim}` or `{body}`, an unknown variable or an `http` URL: it cannot be saved or tested (T1, T6).
4. Pausing and resuming a destination with queued SMS: on resume they are scheduled right away, and the other destination, already sent, is not repeated (T3, T9).
5. An SMS whose only destination was deleted: it does not stay "queued" forever or warn as stuck, "Retry" does not revive it toward the deleted destination and "Resend to a destination" sends it to another (T3, T9).

---

### Task 1: Drafts, validation and templates in `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Drafts.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/DraftsTest.kt`

**Interfaces:**
- Consumes: `Templates.VARIABLES`, `Templates.unknownVariables`, `HttpMethod`, `SuccessPolicy`, `TemplateKind`, `SenderMode`, `BodyMode`, `SimMatch`, `RuleEngine.validatePattern`, `SenderFilter.normalize`, `UrlValidator.isValid`, `SmsVars`.
- Produces:
  - `data class HeaderDraft(val name: String, val value: String?, val secret: Boolean)` (`value == null` = keep the stored one) with a `toString()` that hides `value` if `secret`.
  - `data class DestinationDraft(val name: String, val enabled: Boolean, val template: TemplateKind, val method: HttpMethod, val url: String?, val headers: List<HeaderDraft>, val body: String, val bodyIsJson: Boolean, val successPolicy: SuccessPolicy)` (`url == null` = keep), `toString()` without URL, headers or body.
  - `enum class DestinationError { NAME_EMPTY, URL_MISSING, URL_NOT_HTTPS, UNKNOWN_VARIABLE, HEADER_NAME_INVALID, HEADER_VALUE_INVALID, BODY_NOT_JSON, BODY_WITH_GET }`
  - `fun validateDestination(d: DestinationDraft, urlStored: Boolean): Set<DestinationError>`
  - `object DestinationPresets { fun standardJson(name: String, url: String, token: String): DestinationDraft; fun telegram(name: String, botToken: String, chatId: String): DestinationDraft; fun ntfy(name: String, topicUrl: String): DestinationDraft; fun custom(name: String): DestinationDraft }`
  - `data class RuleDraft(val name: String, val enabled: Boolean, val senderMode: SenderMode, val senderPattern: String, val bodyMode: BodyMode?, val bodyPattern: String?, val sim: SimMatch, val destinationIds: Set<Long>)`
  - `enum class RuleError { NAME_EMPTY, SENDER_EMPTY, SENDER_REGEX_INVALID, BODY_EMPTY, BODY_REGEX_INVALID, NO_DESTINATIONS }`, `fun validateRule(r: RuleDraft): Set<RuleError>`
  - `fun testSmsVars(body: String, now: Long, device: String): SmsVars` (sender `"TEST"`, `simSlot = null`)

- [ ] **Step 1: Write the failing tests** in `DraftsTest`:
  - `httpUrlRejected`: `custom` with `url = "http://h.example"` → contains `URL_NOT_HTTPS`; with `url = null, urlStored = true` → does not contain `URL_MISSING`; with `url = null, urlStored = false` → `URL_MISSING`.
  - `urlWithVariablesValidatedAfterSubstitution`: `"https://h.example/x?s={sender}"` with no URL errors.
  - `unknownVariableInAnyField`: `{foo}` in the URL, in a header or in the body → `UNKNOWN_VARIABLE`.
  - `jsonBodyRequiresQuotedTextVariables`: `{"s":{sim}}` and `{"b":{body}}` → `BODY_NOT_JSON`; `{"s":"{sim}","b":"{body}","t":{received_at}}` → no errors; with `bodyIsJson = false`, a bare `{sim}` → no errors.
  - `standardPresetIsValidAndMatchesV1`: `standardJson("Main", "https://h.example", "t")` with no errors, `body == STANDARD_JSON_BODY` from delivery 2 (copy the constant to `:core` as `Templates.STANDARD_JSON_BODY` and have `:app` use it), header `HeaderDraft("Authorization", "Bearer t", secret = true)`, `ANY_2XX`.
  - `telegramPreset`: `telegram("T", "123:abc", "-100")` → URL `https://api.telegram.org/bot123:abc/sendMessage`, body `{"chat_id":"-100","text":"{sender}: {body}"}`, JSON, no errors; a chat id containing `"` ends up escaped and the body still parses.
  - `ntfyPreset`: POST, `bodyIsJson = false`, body `{sender}: {body}`, no headers.
  - `getWithBodyRejected`: GET method and non-empty body → `BODY_WITH_GET`.
  - `headerChecks`: name `"X Bad"` → `HEADER_NAME_INVALID`; a literal value with `\n` or `ñ` → `HEADER_VALUE_INVALID`; value `"Bearer {sender}"` → valid.
  - `ruleValidation`: REGEX `"("` → `SENDER_REGEX_INVALID`; EXACT `"  "` → `SENDER_EMPTY`; `bodyMode = REGEX` with `"(a"` → `BODY_REGEX_INVALID`; `bodyMode = CONTAINS` with `""` → `BODY_EMPTY`; empty destinations → `NO_DESTINATIONS`; blank name → `NAME_EMPTY`; a correct rule → empty.
  - `draftToStringHidesSecrets`: the `toString()` of a `DestinationDraft` with a URL and token contains neither.
- [ ] **Step 2: Run** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test --tests '*DraftsTest'`; expected: FAIL (does not compile).
- [ ] **Step 3: Implement `Drafts.kt`.** Header name: `^[!#$%&'*+.^_`|~0-9A-Za-z-]+$`. Header value: the literal part (without the variables) is printable ASCII only (`0x20..0x7E`). JSON: test substitution as described in "Decisions" and `Json.parseToJsonElement`; a blank body is not validated as JSON. URL: replace variables with `x`/`0` and pass `UrlValidator.isValid`.
- [ ] **Step 4: Run** the same command; expected: PASS.
- [ ] **Step 5: Commit** `feat(core): destination and rule drafts with validation and presets`.

### Task 2: Send engine adjustments (what was deferred from delivery 2)

**Files:**
- Modify: `core/.../SendOutcome.kt`, `core/.../DeliveryPolicy.kt`, `core/.../QueuePolicy.kt`, `app/.../net/DeliveryClient.kt`, `app/.../work/SendWorker.kt`, `app/.../work/WorkScheduler.kt`, `app/.../data/SmsRepository.kt`, `app/.../notify/Notifier.kt`, `res/values*/strings.xml`
- Test: `DeliveryPolicyTest.kt`, `QueuePolicyTest.kt`, `SendOutcomeTest.kt`, `DeliveryClientTest.kt`, `SendWorkerTest.kt`, `SmsRepositoryTest.kt`, `NotifierTest.kt`, new `app/src/test/kotlin/dev/smsforwarder/work/WorkSchedulerTest.kt`

**Interfaces:**
- Produces:
  - `SendOutcome.Accepted` becomes `data class Accepted(val httpCode: Int)`; new `data class Invalid(val reason: String) : SendOutcome` (terminal); `val SendOutcome.httpCode: Int?`.
  - `DeliveryStatus.CANCELED`; `aggregateStatus` ignores `CANCELED` and returns `ERROR` if all are `CANCELED`.
  - `data class DeliveryState(val status: DeliveryStatus, val lastHttpCode: Int?, val lastError: String?, val live: Boolean)` and `QueuePolicy.isStuck(deliveries: List<DeliveryState>, firstSeenAt: Long, now: Long, stuckAfterMs: Long): Boolean` (the per-SMS version stays for whoever uses it).
  - `const val CONFIG_ERROR_PREFIX = "config_"`, `fun isConfigError(lastError: String?): Boolean`.
  - `internal fun kickPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy?` in `WorkScheduler` (`null` = leave alone).
  - `Notifier.tokenRejected(destinationId: Long, destinationName: String, waiting: Int)`; `Notifier.configError(item: SmsEntity, destinationName: String)`; `Notifier.cancelTokenRejected(destinationId: Long)` (used by T3 on delete).
- [ ] **Step 1: Failing tests in `:core`:**
  - `DeliveryPolicyTest.acceptedCarriesCode`: 204 with `ANY_2XX` → `Accepted(204)`.
  - `DeliveryPolicyTest.canceledIgnoredInAggregate`: `{SENT, CANCELED} → SENT`; `{QUEUED, CANCELED} → QUEUED`; `{CANCELED} → ERROR`.
  - `QueuePolicyTest.queuedDeliveryUnderErrorAggregateIsStuck`: `[ERROR 401 live, QUEUED live]`, 7 h, plazo 6 h → true.
  - `QueuePolicyTest.pausedOrCanceledNotStuck`: `[QUEUED live=false]` → false; `[CANCELED]` → false; `[ERROR 403]` → false; `[ERROR 400]` → true; `[ERROR null "config_template"]` → true.
- [ ] **Step 2: Failing tests in `:app`:**
  - `SmsRepositoryTest.acceptedStoresRealCode`: `recordAttempt(d, Accepted(204))` → delivery and SMS with `lastHttpCode == 204`.
  - `SmsRepositoryTest.invalidIsTerminalWithAttempt`: `Invalid("template")` → delivery `ERROR`, `lastError == "config_template"`, one attempt with `httpCode == null`; SMS `ERROR`.
  - `SmsRepositoryTest.stuckSeesQueuedDeliveryUnderError`: an SMS with one `ERROR` 401 delivery and another `QUEUED` from 7 h ago → appears in `stuckToNotify(6 h)`.
  - `DeliveryClientTest.malformedHeaderIsInvalid`: a header with a non-ASCII value → `Invalid("request")`, zero requests to MockWebServer.
  - `SendWorkerTest.renderFailureRecordedOnce`: a destination whose `publicHeaders` carries an invalid header → a single run leaves the delivery `ERROR` `config_request` with one attempt, result `success` (not `retry`), and `configError` published.
  - `WorkSchedulerTest.kickSkipsRunning` (`[RUNNING]` → `null`), `kickReplacesWaiting` (`[ENQUEUED]` → `REPLACE`), `kickWithoutWork` (`[]` → `REPLACE`), `kickAfterFinished` (`[SUCCEEDED]` → `REPLACE`).
  - `NotifierTest.tokenRejectedNamesDestination`: the text is `getString(R.string.notif_token_rejected_text, "Main", 3)`.
- [ ] **Step 3: Implement.** `recordAttempt` takes the code from `outcome.httpCode`; treats `Invalid` as `ERROR` with `error = CONFIG_ERROR_PREFIX + reason`; ignores `SENT` deliveries **and `CANCELED`**. `SendWorker.sendOne`: an exception when building `DestinationSpec` or in `Templates.render` (other than cancellation) records `Invalid("template")` instead of returning "retry"; missing secrets still mean "retry". `DeliveryClient`: `IllegalArgumentException` → `Invalid("request")`. Warning: `Invalid` calls `configError(sms, destinationName)` and `markStuckNotified`, like the 400. `stuckToNotify` builds the `DeliveryState` values with `live = destination not deleted and active`. `kickQueued` reads `WorkManager.getWorkInfosForUniqueWork("send-$id")` and applies `kickPolicy`. New or changed strings (en/es): `notif_token_rejected_text` = "Token rejected at %1$s: %2$d SMS waiting" / "Token rechazado en %1$s: %2$d SMS esperando" (copy of spec §11); `notif_stuck_config` = "%1$s (%2$s) was not sent: %3$s is misconfigured" / "%1$s (%2$s) no se envió: %3$s está mal configurado"; `issue_token_rejected` no longer names "the Worker": "A destination rejected its token" / "Un destino rechazó su token".
- [ ] **Step 4: Run** `:core:test` locally and CI for `:app`; expected: PASS.
- [ ] **Step 5: Commit** `fix(work): real 2xx codes, terminal config errors, stuck by delivery, safe kick`.

### Task 3: Create, edit, pause and delete destinations

**Files:**
- Modify: `app/.../data/DestinationRepository.kt`, `DestinationDao.kt`, `DeliveryDao.kt`, `RuleDao.kt`, `SmsRepository.kt`, `health/HealthChecker.kt`, `AppGraph.kt`
- Test: new `app/src/test/kotlin/dev/smsforwarder/data/DestinationRepositoryTest.kt`, `SmsRepositoryTest.kt`, `HealthCheckerTest.kt`

**Interfaces:**
- Consumes: `DestinationDraft`, `HeaderDraft`, `validateDestination` (T1); `DeliveryStatus.CANCELED`, `isConfigError` (T2); `DestinationSecretStore`.
- Produces:
  - `data class DestinationCounts(val destinationId: Long, val queued: Int, val errors: Int)`; `DeliveryDao.observeCounts(): Flow<List<DestinationCounts>>`, `DeliveryDao.smsIdsQueuedFor(destinationId: Long): List<Long>`, `DeliveryDao.pendingCountFor(destinationId: Long): Int` (`QUEUED` + `ERROR`).
  - `DestinationDao.observeLive(): Flow<List<DestinationEntity>>` (without `deletedAt`, by id).
  - `DestinationRepository`: `fun observeLive(): Flow<List<DestinationEntity>>`; `suspend fun create(d: DestinationDraft): Long`; `suspend fun editable(id: Long): EditableDestination?` with `data class EditableDestination(val draft: DestinationDraft, val urlStored: Boolean)` (`draft.url == null`, secret headers with `value == null`); `suspend fun update(id: Long, d: DestinationDraft): List<Long>` (ids of requeued SMS); `suspend fun setEnabled(id: Long, enabled: Boolean): List<Long>` (on resume, SMS with a `QUEUED` delivery to that destination); `suspend fun pendingCount(id: Long): Int`; `suspend fun delete(id: Long)`; `suspend fun unconfiguredInUse(): List<Long>`.
  - `SmsRepository`: `suspend fun cancelDeliveriesTo(destinationId: Long): List<Long>`; `suspend fun requeueConfigErrors(destinationId: Long): List<Long>`; `requeue(id)` does not touch `CANCELED` deliveries.
  - `DestinationRepository` receives `SmsRepository` and `Notifier` in its constructor (`AppGraph`); `SmsRepository` does not depend on it.
- [ ] **Step 1: Failing tests** in `DestinationRepositoryTest` (in-memory Room + `FakeDestinationSecretStore`):
  - `createStoresSecretsApart`: custom with a URL, public header `X-A` and secret `X-Key` → `publicHeaders` only with `X-A`; `secrets(id)` with the URL and `X-Key`.
  - `createRejectsInvalid`: an `http` draft → `IllegalArgumentException` and no new row.
  - `editableHidesSecrets`: `editable(id)` → `url == null`, `urlStored == true`, `X-Key` value `null`, `X-A` visible.
  - `updateKeepsBlankSecrets`: `update` with `url = null` and `X-Key` as `null` keeps both; with a new URL it replaces it and keeps `X-Key`.
  - `updateRequeuesAuthAndConfigErrors`: `ERROR` 403, `ERROR` `config_template` and `ERROR` 400 deliveries to that destination → the first two `QUEUED`, the 400 unchanged; returns the ids of those two SMS.
  - `deleteCancelsPendingKeepsFinished`: SMS A with destination 1 `SENT` and destination 2 `QUEUED`; SMS B only with destination 2 `QUEUED`. `delete(2)` → delivery A/2 `CANCELED` with `destinationName` intact, A stays `SENT`; B `ERROR` with `lastError == "destination_deleted"`; `secrets(2) == null`; no `rule_destination` with destination 2; `queuedIds()` does not contain B; `stuckToNotify` does not contain B.
  - `lateResultOnCanceledIgnored`: after `delete(2)`, `recordAttempt(delivery B/2, Accepted(200))` leaves the delivery `CANCELED` and B in `ERROR`.
  - `requeueSkipsCanceled`: `requeue(B)` does not change the `CANCELED` delivery.
  - `resumeReturnsQueuedSms`: `setEnabled(2, false)`, ingest an SMS toward 1 and 2; `setEnabled(2, true)` returns its id.
  - `HealthCheckerTest.notConfiguredWhenRuleTargetsDestinationWithoutSecrets` and `noIssueForUnlinkedDestinationWithoutSecrets`.
- [ ] **Step 2: Run in CI** `./gradlew :app:testDebugUnitTest --tests '*DestinationRepositoryTest' --tests '*HealthCheckerTest'`; expected: FAIL.
- [ ] **Step 3: Implement.** `create`: `require(validateDestination(d, urlStored = false).isEmpty())`, inserts the row and stores the secrets; if storing the secrets fails, deletes the row and rethrows. `update`: completes with what is stored, validates, saves, and in the same transaction requeues 401/403 (`requeueAuthErrors`) and configuration errors. `delete`, in one transaction: `deletedAt = now`, `cancelDeliveriesTo` (recomputes the aggregate of each affected SMS; if only `CANCELED` remain, `lastError = "destination_deleted"`), deletes its rule links. Afterwards: `secretStore.remove(id)` and cancels its token warning (`tag` per destination). The SMS returned by `update` and `setEnabled` are scheduled by the caller (T6).
- [ ] **Step 4: Run in CI**; expected: PASS.
- [ ] **Step 5: Commit** `feat(data): create, edit, pause and delete destinations`.

### Task 4: Create, edit and delete rules

**Files:**
- Modify: `app/.../data/RuleRepository.kt`, `RuleDao.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/data/RuleRepositoryTest.kt`

**Interfaces:**
- Consumes: `RuleDraft`, `validateRule` (T1).
- Produces:
  - `RuleDao`: `observeAllWithDestinations(): Flow<List<RuleWithDestinations>>`, `getWithDestinations(id): RuleWithDestinations?`, `update(rule)`, `delete(id)`, `deleteLinks(ruleId)`, `deleteLink(ruleId, destinationId)`, `linkCount(ruleId): Int`.
  - `RuleRepository`: `fun observeAll(): Flow<List<RuleWithDestinations>>` (deleted destinations left out); `suspend fun draft(id: Long): RuleDraft?`; `suspend fun create(r: RuleDraft): Long`; `suspend fun update(id: Long, r: RuleDraft)`; `suspend fun setEnabled(id: Long, enabled: Boolean)`; `suspend fun delete(id: Long)`.
- [ ] **Step 1: Failing tests** in `RuleRepositoryTest`:
  - `createAndReadBack`: `create` and `draft(id)` return the same draft.
  - `invalidRuleRejected`: regex `"("` → `IllegalArgumentException`, no row.
  - `updateReplacesDestinations`: `{1}` → `{2}`; `activeRules()` only with 2.
  - `disabledRuleNotActive`: `setEnabled(id, false)` → out of `activeRules()` and `hasActiveRules()` false if it was the only one.
  - `deleteRemovesRuleAndLinks`.
  - `editingKeepsQueuedDeliveries`: a `QUEUED` delivery created by the rule stays `QUEUED` after `update` and after `delete` (spec §8).
  - `removeSenderKeepsMultiDestinationRule`: EXACT rule `"AAA"` toward `{primary, 2}`; `removeSender("AAA")` → the rule stays, linked only to 2, and `senders` no longer lists it; with only the primary, the rule disappears.
- [ ] **Step 2: Run in CI**; expected: FAIL.
- [ ] **Step 3: Implement.** `create`/`update` do `require(validateRule(r).isEmpty())` and write the rule and links in one transaction. `removeSender` uses `deleteLink` and deletes the rule if `linkCount == 0`.
- [ ] **Step 4: Run in CI**; expected: PASS.
- [ ] **Step 5: Commit** `feat(data): create, edit and delete rules`.

### Task 5: Test a destination

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/DestinationTest.kt`, `app/src/main/kotlin/dev/smsforwarder/net/DestinationTester.kt`
- Modify: `AppGraph.kt`, `res/values*/strings.xml` (`destination_test_body`)
- Test: `core/.../DestinationTestTest.kt`, `app/src/test/kotlin/dev/smsforwarder/net/DestinationTesterTest.kt`

**Interfaces:**
- Consumes: `DestinationDraft` (T1), `SendOutcome.httpCode` (T2), `DeliveryClient`, `DestinationSecretStore`, `Templates.render`, `testSmsVars`.
- Produces:
  - `enum class TestVerdict { OK, BAD_TOKEN, FAILED, UNREACHABLE }`, `data class DestinationTestResult(val httpCode: Int?, val verdict: TestVerdict)`.
  - `fun interpretDestinationTest(template: TemplateKind, outcome: SendOutcome): TestVerdict`: `STANDARD_JSON` → 400 `OK`, 401/403 `BAD_TOKEN`, no code `UNREACHABLE`, rest `FAILED`; the others → `Accepted` `OK`, 401/403 `BAD_TOKEN`, no code `UNREACHABLE`, `Invalid` and the rest `FAILED`.
  - `class DestinationTester(client: DeliveryClient, secrets: DestinationSecretStore, testBody: String, device: String, clock: Clock) { suspend fun test(d: DestinationDraft, existingId: Long?): DestinationTestResult }`. It does not validate (the ViewModel does, T6), and does not write to the database or the log.
- [ ] **Step 1: Failing tests:**
  - `DestinationTestTest`: table of cases for `interpretDestinationTest` for both branches.
  - `DestinationTesterTest.standardSendsEmptyJsonAnd400IsOk` (MockWebServer receives a POST with body `{}` and `Authorization`; never the test SMS), `customSendsSampleSms` (body with `TEST` and `getString(R.string.destination_test_body)`; 200 → `OK`, code 200), `blankFieldsUseStoredSecrets` (`url = null` and secret header `null` with `existingId` → the request carries the stored URL and value), `serverErrorIsFailedWithCode` (500 → `FAILED`, 500), `unreachable` (server shut down → `UNREACHABLE`, `null`), `nothingRecorded` (no attempt or delivery in the database after testing).
- [ ] **Step 2: Run** `:core:test` locally and CI; expected: FAIL.
- [ ] **Step 3: Implement.** `STANDARD_JSON`: the same request as `IngestClient.testConnection` (POST `{}` with the draft's headers) but through `DeliveryClient`, so `IngestClient` can go away; if nothing else uses it, it is deleted together with `IngestClientTest` in this task.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(net): test a destination from its form`.

### Task 6: Destinations ViewModels

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/destinations/DestinationsViewModel.kt`, `DestinationEditorViewModel.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/DestinationsViewModelTest.kt`, `DestinationEditorViewModelTest.kt`

**Interfaces:**
- Consumes: T1 (drafts, presets, validation), T3 (`DestinationRepository`), T5 (`DestinationTester`).
- Produces:
  - `data class DestinationRowUi(val id: Long, val name: String, val template: TemplateKind, val enabled: Boolean, val queued: Int, val errors: Int)`; `class DestinationsViewModel(repo: DestinationRepository, enqueue: (List<Long>) -> Unit) { val rows: StateFlow<List<DestinationRowUi>>; fun setEnabled(id: Long, enabled: Boolean) }` + `Factory`.
  - `data class PresetFields(val url: String = "", val token: String = "", val chatId: String = "")`; `data class HeaderRowUi(val name: String, val value: String, val secret: Boolean, val stored: Boolean)`.
  - `data class DestinationEditorState(val loaded: Boolean, val isNew: Boolean, val template: TemplateKind, val preset: PresetFields, val name: String, val enabled: Boolean, val method: HttpMethod, val url: String, val urlStored: Boolean, val headers: List<HeaderRowUi>, val body: String, val bodyIsJson: Boolean, val successPolicy: SuccessPolicy, val errors: Set<DestinationError>, val test: DestinationTestResult?, val busy: Boolean, val deletePending: Int?, val done: Boolean)` with a `toString()` without secrets.
  - `class DestinationEditorViewModel(id: Long?, repo: DestinationRepository, tester: DestinationTester, enqueue: (List<Long>) -> Unit, defaultName: (TemplateKind) -> String)`: `chooseTemplate(k)`, `setPreset(p: PresetFields)`, `applyPreset()`, `setName`, `setEnabled`, `setMethod`, `setUrl`, `setHeaders(List<HeaderRowUi>)`, `setBody`, `setBodyIsJson`, `setSuccessPolicy`, `test()`, `save()`, `requestDelete()`, `confirmDelete()`, `dismissDelete()` + `Factory`.
- [ ] **Step 1: Failing tests:**
  - `DestinationsViewModelTest.listShowsCounts` (two destinations, one with 2 `QUEUED` and 1 `ERROR`), `resumeEnqueuesQueued` (`setEnabled(id, true)` calls `enqueue` with the ids from T3).
  - `DestinationEditorViewModelTest.telegramPresetFillsForm`: `chooseTemplate(TELEGRAM)`, `setPreset(token, chatId)`, `applyPreset()` → `url`, `body` and `bodyIsJson` as in `DestinationPresets.telegram`; `name == defaultName(TELEGRAM)`.
  - `saveRejectsUnquotedSim`: body `{"s":{sim}}` → `errors` contains `BODY_NOT_JSON`, no new row, `done == false`.
  - `saveRejectsHttpAndUnknownVariable`.
  - `testBlockedWhenInvalid`: with errors, `test()` does not call the tester and shows the errors.
  - `editLoadsWithoutSecrets`: opening a stored destination → `url == ""`, `urlStored == true`, secret header with `value == ""` and `stored == true`; `state.toString()` contains neither the stored URL nor the token.
  - `saveEnqueuesRequeued`: `save()` of a destination with 401 deliveries → `enqueue` receives those ids; `done == true`.
  - `deleteAsksWithPendingCount`: `requestDelete()` → `deletePending == 3`; `confirmDelete()` → destination deleted and `done == true`; `dismissDelete()` deletes nothing.
- [ ] **Step 2: Run in CI**; expected: FAIL.
- [ ] **Step 3: Implement.** A `stored` secret header row with an empty value maps to `HeaderDraft(value = null)`; an empty `url` with `urlStored` maps to `null`. The template is only chosen on creation; afterwards the form is the same for all of them (spec §10). `save()` and `test()` run `validateDestination` first.
- [ ] **Step 4: Run in CI**; expected: PASS.
- [ ] **Step 5: Commit** `feat(ui): destination list and editor view models`.

### Task 7: Destinations tab, navigation and Settings without Connection

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/destinations/DestinationsScreen.kt`, `DestinationEditorScreen.kt`
- Modify: `ui/Nav.kt`, `ui/AppShell.kt`, `ui/MainActivity.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `res/values*/strings.xml`
- Test: `AppShellTest.kt`, `SettingsScreenTest.kt`, `SettingsViewModelTest.kt`, new `app/src/test/kotlin/dev/smsforwarder/ui/NavTest.kt`, `DestinationsScreenTest.kt`, `DestinationEditorScreenTest.kt`

**Interfaces:**
- Consumes: T6.
- Produces:
  - `Routes.DESTINATIONS = "destinations"`, `Routes.RULES = "rules"` (used by T8), `Routes.destination(id: Long?)` (`"destination/new"` or `"destination/<id>"`), `Routes.destinationTarget(route: String): DestinationTarget?` with `sealed interface DestinationTarget { data object New; data class Edit(val id: Long) }`; `Routes.isTab` includes `DESTINATIONS` and `RULES`; `stackForTab(tab)` = `main` or `main|<tab>`.
  - `internal fun issueTarget(issue: HealthIssue): String?` (`TOKEN_REJECTED`, `NOT_CONFIGURED` → `DESTINATIONS`; `NO_RULES` → `RULES`; the rest `null` = system screen). Replaces `IN_APP_ISSUES`. Until T8, `NO_RULES` still goes to `SETTINGS`.
  - `@Composable fun DestinationsScreen(rows: List<DestinationRowUi>, onOpen: (Long) -> Unit, onAdd: () -> Unit, onToggle: (Long, Boolean) -> Unit, padding: PaddingValues)` and `@Composable fun DestinationEditorScreen(state: DestinationEditorState, vm: DestinationEditorViewModel, onBack: () -> Unit)`.
  - `SettingsViewModel` loses `testConnection`, `loadSaved`, `saveCredentials`, `form`, `setUrl`, `setToken`, `save`, `test`; `SettingsScreen` loses the Connection group.
- [ ] **Step 1: Ask the user** for the Destinations tab icon, the final order of the bar (spec: Home, Rules, Destinations, Settings), how a list row looks (name, type, pause, counters) and the editor layout (template selector at the top when creating, headers as a list with a "secret" switch). Show a text sketch or screenshot; do not proceed without an OK.
- [ ] **Step 2: Failing tests:**
  - `NavTest.stackForTabPinsUnderMain` (`DESTINATIONS` → `"main|destinations"`), `destinationRoutesRoundTrip` (`destination(null)` → `New`, `destination(7)` → `Edit(7)`, `"detail/3"` → `null`), `issueTargets`.
  - `AppShellTest.destinationsTabShown`: the bar shows `getString(R.string.tab_destinations)` and tapping it calls `onTab(Routes.DESTINATIONS)`.
  - `DestinationsScreenTest.showsRowsAndPausedState` (name, `getString(R.string.destination_paused)` on the paused one, counters), `emptyStateShown` (no destinations, text `destinations_empty`).
  - `DestinationEditorScreenTest.storedSecretsNotShown`: with `urlStored` and a stored secret header, no node contains the value; the field shows `getString(R.string.destination_secret_kept)`.
  - `DestinationEditorScreenTest.deleteDialogShowsCount`: `deletePending = 3` → text `getQuantityString(R.plurals.destination_delete_confirm, 3, 3)`.
  - `SettingsScreenTest.noConnectionGroup`: `getString(R.string.settings_group_connection)` does not appear.
- [ ] **Step 3: Run in CI**; expected: FAIL.
- [ ] **Step 4: Implement.** The editor is full screen with no bottom bar, like the detail view; `done` goes back. `MainActivity` creates the ViewModels with their `Factory` (`enqueue = { ids -> ids.forEach { WorkScheduler.enqueueSend(ctx, it) } }`) and the editor with `remember(target)`, like `detailViewModel`. New strings (en/es) for the tab, list, editor, templates, methods, `DestinationError` errors, test result (`TestVerdict` + code), pause and delete.
- [ ] **Step 5: Run in CI**; expected: PASS.
- [ ] **Step 6: Commit** `feat(ui): destinations tab and editor`.

### Task 8: Rules tab and Settings without Senders

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/rules/RulesViewModel.kt`, `RuleEditorViewModel.kt`, `RulesScreen.kt`, `RuleEditorScreen.kt`
- Modify: `ui/Nav.kt`, `ui/AppShell.kt`, `ui/MainActivity.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `res/values*/strings.xml`
- Test: `NavTest.kt`, `AppShellTest.kt`, `SettingsScreenTest.kt`, new `RulesViewModelTest.kt`, `RuleEditorViewModelTest.kt`, `RulesScreenTest.kt`, `RuleEditorScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`RuleDraft`, `validateRule`), T3 (`DestinationRepository.observeLive`), T4 (`RuleRepository`), T7 (`Routes.RULES`, `issueTarget`).
- Produces:
  - `Routes.rule(id: Long?)`, `Routes.ruleTarget(route): RuleTarget?` (`New` / `Edit(id)`).
  - `data class RuleRowUi(val id: Long, val name: String, val enabled: Boolean, val senderMode: SenderMode, val senderPattern: String, val hasBodyFilter: Boolean, val sim: SimMatch, val destinationNames: List<String>)`; `class RulesViewModel(rules: RuleRepository) { val rows: StateFlow<List<RuleRowUi>>; fun setEnabled(id: Long, v: Boolean) }`.
  - `data class RuleEditorState(val loaded: Boolean, val isNew: Boolean, val draft: RuleDraft, val destinations: List<DestinationEntity>, val errors: Set<RuleError>, val deleteAsked: Boolean, val done: Boolean)`; `class RuleEditorViewModel(id: Long?, rules: RuleRepository, destinations: DestinationRepository)` with `update(transform: (RuleDraft) -> RuleDraft)`, `save()`, `requestDelete()`, `confirmDelete()`, `dismissDelete()`.
  - `SettingsViewModel` loses `senders`, `addSender`, `removeSender`; `SettingsScreen` loses the Senders group.
- [ ] **Step 1: Ask the user** for the Rules tab icon, the list row (sender summary, text filter, SIM, destinations) and the editor layout (sender mode and text mode selector, SIM, destination checkboxes). Do not proceed without an OK.
- [ ] **Step 2: Failing tests:**
  - `RuleEditorViewModelTest.invalidRegexBlocksSave` (`SENDER_REGEX_INVALID`, no new rule), `noDestinationBlocksSave`, `saveCreatesRule` (`done`, rule in `activeRules()`), `editLoadsExisting`, `pausedDestinationsListed` (a paused destination appears in `destinations`), `deleteAsksFirst`.
  - `RulesViewModelTest.rowsShowDestinationNames`, `toggleDisables`.
  - `RulesScreenTest.ruleWithoutDestinationsWarns`: a rule whose only destination was deleted shows `getString(R.string.rule_no_destinations)`.
  - `RulesScreenTest.emptyStateShown` (`rules_empty`).
  - `RuleEditorScreenTest.regexErrorShown`: with `SENDER_REGEX_INVALID`, `getString(R.string.rule_error_regex)` is visible.
  - `NavTest.issueTargets` updated: `NO_RULES` → `RULES`. `AppShellTest.fourTabsInSpecOrder`: Home, Rules, Destinations, Settings.
  - `SettingsScreenTest.noSendersGroup`.
- [ ] **Step 3: Run in CI**; expected: FAIL.
- [ ] **Step 4: Implement.** Help under the sender field depending on the mode: exact/contains ignore whitespace and case; regex is applied as is (it may use `(?i)`), texts from spec §8 in en/es. New strings (en/es) for the tab, modes, SIM, `RuleError` errors, empty state and delete.
- [ ] **Step 5: Run in CI**; expected: PASS.
- [ ] **Step 6: Commit** `feat(ui): rules tab and editor`.

### Task 9: Home and detail per destination

**Files:**
- Modify: `data/SmsDao.kt` or `DeliveryDao.kt` (flow of deliveries for all SMS), `data/SmsRepository.kt`, `ui/main/MainViewModel.kt`, `ui/main/MainScreen.kt`, `ui/detail/DetailViewModel.kt`, `ui/detail/DetailScreen.kt`, `ui/MainActivity.kt`, `res/values*/strings.xml`
- Test: `MainViewModelTest.kt`, `MainScreenTest.kt`, `DetailScreenTest.kt`, `SmsRepositoryTest.kt`, new `DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `DeliveryStatus.CANCELED`, `isConfigError` (T2); `DestinationRepository.observeLive` (T3).
- Produces:
  - `DeliveryDao.observeAll(): Flow<List<DeliveryEntity>>` (deliveries of non-deleted SMS); the ViewModel groups them by `smsId`.
  - `data class DeliveryChipUi(val destinationName: String, val status: DeliveryStatus, val httpCode: Int?, val configError: Boolean)`; `RowUi.deliveries: List<DeliveryChipUi>`.
  - `MainUiState.destinations: List<Pair<Long, String>>`, `MainUiState.destinationFilter: Long?`; `MainViewModel.setDestinationFilter(id: Long?)`. Filter: the SMS has a delivery to that destination.
  - `SmsRepository.resendTo(smsId: Long, destinationId: Long)`: creates the `QUEUED` delivery if it does not exist (with the current name) or moves the existing one to `QUEUED` (even if it was `SENT` or `CANCELED`); recomputes the aggregate; does nothing if the SMS is deleted or the destination does not exist.
  - `DetailUiState.deliveries: List<DeliveryEntity>`, `DetailUiState.destinations: List<DestinationEntity>`; `DetailViewModel.resendTo(destinationId: Long)` (calls `enqueueSend` afterwards).
  - `@Composable fun deliveryLine(chips: List<DeliveryChipUi>): String` en `ui/main/Strings.kt`.
- [ ] **Step 1: Ask the user** for the text of each state in the row line (the spec only fixes `Main ✓200 · Telegram queued`; proposal: error `Telegram ✗403`, configuration `Telegram: check destination`, canceled `Telegram: canceled`), where the destination chips go relative to the status chips, and how the destination is chosen in "Resend to a destination" (dialog with a list). Do not proceed without an OK.
- [ ] **Step 2: Failing tests:**
  - `MainViewModelTest.rowShowsPerDestinationStatus`: an SMS with a `SENT` 204 delivery to "Main" and `QUEUED` to "T2" → `deliveries` in that order with that data.
  - `MainViewModelTest.destinationFilterKeepsOnlyMatching`: three SMS, one with no delivery to destination 2; filter 2 → two rows; counters unchanged.
  - `MainScreenTest.rowRendersDeliveryLine`: the text is `getString(R.string.delivery_sent, "Main", 204) + " · " + getString(R.string.delivery_queued, "T2")`.
  - `SmsRepositoryTest.resendToSentDestinationQueuesAgain` (`SENT` delivery → `QUEUED`, SMS `QUEUED`), `resendToNewDestinationCreatesDelivery`, `resendToDeletedSmsIsNoop`, `resendAfterDestinationDeletedGoesElsewhere` (SMS in `ERROR` `destination_deleted` → `resendTo(1)` leaves it `QUEUED` with the `CANCELED` delivery intact).
  - `DetailViewModelTest.resendEnqueues`.
  - `DetailScreenTest.showsDeliveriesAndResend`: one line per delivery with its status; the `getString(R.string.detail_resend_to)` button opens the list of live destinations; each attempt shows its destination's name.
- [ ] **Step 3: Run in CI**; expected: FAIL.
- [ ] **Step 4: Implement.** `MainViewModel` combines the deliveries flow and `observeLive()` inside the current `view` so the main `combine` stays at five flows. The detail attempts are matched to their delivery by `deliveryId`; those from 1.1 (no `deliveryId`) carry no name.
- [ ] **Step 5: Run in CI**; expected: PASS.
- [ ] **Step 6: Commit** `feat(home): per-destination status, destination filter and resend`.

### Task 10: Closing delivery 1.3.0

**Files:**
- Modify: `docs/manual-test-checklist.md` (section `## v1.3.0`), `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 status, §5, §7 Connection and Senders no longer exist, §9, §11 and this plan's decisions), `res/values*/strings.xml` (only if the user changes texts)

- [ ] **Step 1: Texts for the user.** Show en/es of the card and the failed-import notification (`issue_migration_failed`, `notif_issue_migration_failed`, written in delivery 2 without review) and of the new warning texts (`notif_stuck_config`, `issue_token_rejected`). Apply what they ask for.
- [ ] **Step 2: Screenshots.** Home, Rules, rule editor, Destinations, destination editor and detail, light and dark, reduced to 1000 px JPEG; compare them with a sonnet subagent if there are more than three. Show them to the user and wait for an OK; anything not approved in the earlier tasks gets fixed.
- [ ] **Step 3: OCR review** of the whole branch against `v2-delivery-2` (`ocr delegate rule`, model depending on the diff; opus expected). Fix High and Medium, each with its own test.
- [ ] **Step 4: New manual checklist** in `docs/manual-test-checklist.md`: create a destination from each template and test it (standard JSON with `{}`, 400 = correct; Telegram and ntfy receive the test SMS); a down destination next to one that works; pause and resume with queued SMS; delete a destination with queued SMS (warning with the count, canceled deliveries, nothing stuck); a regex rule with `(?i)` and another with a text filter; a rule with SIM 2 on a single-SIM phone; edit the token of a destination with a 401 and check it is resent; "Resend to a destination" from the detail view; destination filter on Home; language change in the editors; update from 1.2.0 to 1.3.0 without losing destinations, rules or history.
- [ ] **Step 5: Record in the spec** the status of §3 and the decisions in this plan's header.
- [ ] **Step 6: Commit** `docs: 1.3.0 checks and spec status`.
- [ ] **Step 7: With the user's OK, in this order:** (a) 1.2.0 must be merged into `main` and tagged `v1.2.0`; if not, do that first with its own checklist; (b) rebase `v2-delivery-3` onto `main` if needed, push and CI green; (c) fast-forward `main` and `git tag v1.3.0 && git push origin v1.3.0`; (d) `adb install -r` the signed release APK over 1.2.0 and walk through the checklist with the user. Failures become new tasks.
