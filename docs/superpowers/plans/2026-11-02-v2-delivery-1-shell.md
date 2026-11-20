# v2 delivery 1 (1.1.0): icon, navigation, settings and languages. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The installed app ships with its own icon, a Home/Settings bottom bar, grouped Settings, search and filters in Home, deletion, About, and texts in Spanish and English, without touching how SMS are received and sent.

**Architecture:** The new policies (settings enums, search filter, status label without text) live in `:core` and are tested on the JVM. `SettingsRepository` stores the new settings in DataStore. The UI is still Compose with manual navigation; a `Scaffold` with `NavigationBar` is added only for the top-level screens. Texts move to `strings.xml` (`values/` English, `values-es/`).

**Tech Stack:** Kotlin, Compose Material 3, Room, DataStore, WorkManager, Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), delivery 1 (§3 to §7, §14, §15). v1 plan in [2026-10-17-sms-forwarder.md](2026-10-17-sms-forwarder.md).

## Global Constraints

- v1 rules that still apply: https only; no foreground service; no `fallbackToDestructiveMigration`; backups disabled; no SMS texts, real senders or the author's project names in code, tests, strings or docs.
- `minSdk = 31`. Tests with `robolectric.properties` `sdk=35`. Local JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. `:app` is only verified in CI (`gh run watch`).
- Behavior that does NOT change: reception, sweep, queue, retries, `localKey`, `received_at` format (`date_sent`), MIUI notices.
- Visual changes (colors, typography, spacing, sizes): dynamic color remains the default. Any new visual value that is not in the spec is checked with the user. Every tag or class change is measured before and after (`getComputedStyle` does not apply to Compose: screenshots of the same state are compared on the phone before and after, in light and dark).
- `:app` tests that compare UI text: use `context.getString(R.string.x)` (Robolectric runs in English, `values/`); never literals in Spanish or English.
- Each task leaves `:core:test` and the `:app` build and tests green (CI) when finished: no task changes a type and leaves its users for another.
- Code review: OCR only (`ocr delegate rule`), never the generic reviewers. No Claude attribution lines in commits or PRs.
- Push and secrets: requested by the user or authorized per task.

## Review Focus

Inputs or conditions that the spec implies and no v1 test covers; each one has its test in the indicated task:

1. An SMS deleted from Home that is still in the inbox: the sweep does not forward it again (T8).
2. Paused forwarding: an incoming SMS is saved as `QUEUED`, without counting attempts or sending anything, and is sent on resume (T3).
3. "Forever" retention: deletes nothing; with 30 days it only deletes `SENT`, never `QUEUED` or `ERROR` (T3).
4. Search with spaces, uppercase and regex characters (`(`, `*`) typed in the box: treated as literal text and does not blow up (T2).
5. Changing language with the app open does not lose the navigation state nor leave the notification notice in the other language (T4).

---

### Task 1: App icon

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `app/src/main/res/values/ic_launcher_background.xml` (`<color name="ic_launcher_background">` indigo), `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml`
- Create: `docs/icon/icon.svg` (source of the drawing, no personal data)
- Modify: `app/src/main/AndroidManifest.xml` (`android:icon`, `android:roundIcon`)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/IconTest.kt`

**Interfaces:**
- Produces: `R.mipmap.ic_launcher`, `R.mipmap.ic_launcher_round`, `R.drawable.ic_launcher_monochrome` (used by T9 for About).

- [ ] **Step 1: Draw `docs/icon/icon.svg`** (108x108 dp, 66 dp central safe zone): front SMS bubble with a forward arrow with a rounded stroke and a faint bubble behind it, sketch "3, copy coming out" from `.superpowers/brainstorm/*/content/`, indigo background.
- [ ] **Step 2: Show the SVG to the user** in the panel browser (large version, squircle, circular and monochrome) and wait for their OK. Do not go on without it (it is a visual change).
- [ ] **Step 3: Write the failing test**

```kotlin
@Test fun launcherIconIsDeclared() {
    val info = context.packageManager.getApplicationInfo(context.packageName, 0)
    assertNotEquals(0, info.icon)
    assertNotEquals(0, info.roundIcon)
}
```

- [ ] **Step 4: Run** `./gradlew :app:testDebugUnitTest --tests '*IconTest'` in CI; expected: FAIL (icon == 0).
- [ ] **Step 5: Convert the SVG to a `VectorDrawable`** (foreground and monochrome) and declare the adaptive icon (`<adaptive-icon>` with `<background>`, `<foreground>`, `<monochrome>`) in the two `mipmap-anydpi` files; add `android:icon`/`android:roundIcon` to `<application>`.
- [ ] **Step 6: Commit** `feat(icon): add adaptive launcher icon`.

### Task 2: New policies in `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Prefs.kt`, `core/src/main/kotlin/dev/smsforwarder/core/SmsSearch.kt`
- Modify: `core/src/main/kotlin/dev/smsforwarder/core/QueuePolicy.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/PrefsTest.kt`, `SmsSearchTest.kt`, `QueuePolicyTest.kt` (already exists, extend)

**Interfaces:**
- Produces:
  - `enum class SweepInterval(val minutes: Long) { M15(15), M30(30), M60(60) }`
  - `enum class StuckAfter(val hours: Long) { H1(1), H6(6), H24(24) }` with `val millis: Long`
  - `enum class Retention(val days: Long?) { D30(30), D90(90), D365(365), FOREVER(null) }` with `fun cutoff(now: Long): Long?` (null for `FOREVER`)
  - `enum class Language(val tag: String?) { SYSTEM(null), ES("es"), EN("en") }`
  - `enum class NotifyKind { RECOVERED, STUCK, PERMISSIONS }`
  - `enum class StatusFilter { ALL, QUEUED, ERROR }`
  - `object SmsSearch { fun matches(sender: String, body: String, query: String): Boolean }`
  - `QueuePolicy.isStuck(status, lastHttpCode, firstSeenAt, now, stuckAfterMs: Long = STUCK_AFTER_MS)`
  - `data class StatusLabel(val kind: StatusKind, val attempts: Int, val httpCode: Int?)`, `enum class StatusKind { QUEUED, ERROR, SENT, RECOVERED, MANUAL }`, `fun statusLabelOf(status, origin, attempts, lastHttpCode): StatusLabel`. The current `statusLabel(...): String` function is kept untouched until T5, which migrates its users (`MainViewModel`, `MainScreen`, `DetailScreen`) and deletes it

- [ ] **Step 1: Failing tests**, with these assertions:
  - `Retention.D30.cutoff(now) == now - 30 * 86_400_000L`, `Retention.FOREVER.cutoff(now) == null`, `Retention.D90.cutoff(now) == now - QueuePolicy.RETENTION_MS`.
  - `StuckAfter.H6.millis == QueuePolicy.STUCK_AFTER_MS`; `isStuck(QUEUED, null, 0, 2 * 3_600_000, StuckAfter.H1.millis) == true`.
  - `SmsSearch.matches("SENDER", "Balance 10", "send")`, `matches("A B", "x", "ab")` is true (ignores case and spaces in the sender), `matches("A", "Balance (10)", "(10)")` is true, `matches("A", "x", "(")` is false without throwing, `matches("A", "x", "")` is true.
  - `statusLabelOf(QUEUED, RECEIVER, 2, null) == StatusLabel(StatusKind.QUEUED, 2, null)`; `SENT` with `SWEEP` gives `RECOVERED`; with `MANUAL` gives `MANUAL`; `ERROR` with 401 keeps `httpCode = 401`.
- [ ] **Step 2: Run** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`; expected: compilation FAIL.
- [ ] **Step 3: Implement.** `SmsSearch` searches with `contains(ignoreCase = true)` over the body and over the sender normalized with `SenderFilter.normalize` against the normalized query; no regex.
- [ ] **Step 4: Run** `:core:test`; expected: PASS, and the `statusLabel` (String) tests keep passing unchanged.
- [ ] **Step 5: Commit** `feat(core): add settings policies, search and structured status label`.

### Task 3: New settings, pause, interval and retention

**Files:**
- Modify: `app/.../data/SettingsRepository.kt`, `app/.../work/WorkScheduler.kt`, `app/.../work/SendWorker.kt`, `app/.../work/SweepWorker.kt`, `app/.../data/SmsRepository.kt`, `app/.../AppGraph.kt`
- Test: `SettingsRepositoryTest.kt`, `SendWorkerTest.kt`, `SmsRepositoryTest.kt` (extend)

**Interfaces:**
- Consumes: enums from T2.
- Produces in `SettingsRepository` (all with a default value equal to v1 behavior):
  - `val forwardingEnabled: Flow<Boolean>` (true); `suspend fun isForwardingEnabled(): Boolean`; `suspend fun setForwardingEnabled(v: Boolean)` (when it becomes true it requeues all `queuedIds()` with the `scheduler`, like `saveCredentials`)
  - `val sweepInterval: Flow<SweepInterval>` (M15), `suspend fun setSweepInterval(v: SweepInterval)`
  - `val stuckAfter: Flow<StuckAfter>` (H6), `suspend fun stuckAfterNow(): StuckAfter`, `setStuckAfter`
  - `val retention: Flow<Retention>` (D90), `suspend fun retentionNow(): Retention`, `setRetention`
  - `val hideText: Flow<Boolean>` (false), `setHideText`
  - `val dynamicColor: Flow<Boolean>` (true), `setDynamicColor`
  - `val language: Flow<Language>` (SYSTEM), `setLanguage`
  - `fun notifyEnabled(kind: NotifyKind): Flow<Boolean>` (true), `suspend fun isNotifyEnabled(kind: NotifyKind): Boolean`, `suspend fun setNotifyEnabled(kind: NotifyKind, v: Boolean)`
- `WorkScheduler.schedulePeriodicSweep(context: Context, interval: SweepInterval = SweepInterval.M15, update: Boolean = false)`; with `update = true` it uses `ExistingPeriodicWorkPolicy.UPDATE`.
- `SmsRepository.purgeOld(retention: Retention = Retention.D90)` (the default value keeps the v1 `SmsRepositoryTest`); with `FOREVER` it does nothing; with another period it deletes `SENT` rows older than the cutoff (T8 adds the rows with `deletedAt`).
- `SmsRepository.stuckToNotify(stuckAfterMs: Long = QueuePolicy.STUCK_AFTER_MS)`; `AppGraph.runSweepAlerts` (`AppGraph.kt:50`) calls it with `settings.stuckAfterNow().millis`.
- While `forwardingEnabled` is false, `runSweepAlerts` does not query `stuckToNotify`: a paused SMS is not a stuck SMS.
- `MainUiState` adds `paused: Boolean`; the Home status line shows "Forwarding paused" (resource from T5) when it is true.
- `suspend fun sweepIntervalNow(): SweepInterval`.

- [ ] **Step 1: Failing tests:**
  - `SettingsRepositoryTest.defaultsMatchV1`: `forwardingEnabled` true, `sweepInterval` M15, `stuckAfter` H6, `retention` D90, `notifyEnabled(STUCK)` true.
  - `setForwardingEnabled(true)` after `false` calls the `scheduler` with the queued ids (recorded list, like the `saveCredentials` test).
  - `SendWorkerTest.pausedForwardingKeepsSmsQueued`: with `forwardingEnabled=false` and a `QUEUED` SMS, the worker returns `Result.success()`, the mock server receives 0 requests, `attempts` stays at 0 and the status at `QUEUED`.
  - End-to-end pause: with `forwardingEnabled=false` an SMS arrives through the receiver (stays `QUEUED`, 0 requests); `setForwardingEnabled(true)` requeues it and the worker sends it: 1 request.
  - `SmsRepositoryTest.purgeOldForeverKeepsEverything`, `purgeOld30DaysDeletesOnlySentOlder`: with `SENT` rows of 40 days, `QUEUED` of 40 days and `ERROR` of 40 days, only the first is deleted.
- [ ] **Step 2: Run** the tests in CI; expected: FAIL.
- [ ] **Step 3: Implement** the DataStore keys and the functions; `SendWorker.doWork` reads `isForwardingEnabled()` inside the existing `try` (a DataStore failure retries, it does not crash) and returns `Result.success()` if paused: nothing is lost because resuming and each sweep requeue the `QUEUED` ones. `SweepWorker` passes `retentionNow()` to `purgeOld`. Whoever schedules the sweep (`MainActivity.onCreate`, `BootReceiver`) passes the saved interval (`sweepIntervalNow()`), so a lost job does not silently go back to 15 min; the live change (T7) uses `update = true`.
- [ ] **Step 4: Run** the tests; expected: PASS.
- [ ] **Step 5: Commit** `feat(settings): pause, sweep interval, stuck window, retention and toggles`.

### Task 4: Language infrastructure

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/Localized.kt`, `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`, `app/.../ui/MainActivity.kt`, `app/.../notify/Notifier.kt`, `app/.../data/SettingsRepository.kt`, `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§15)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/LocalizedTest.kt`

**Interfaces:**
- Produces:
  - `object Localized { fun wrap(base: Context, tag: String?): Context; fun savedTag(context: Context): String?; fun saveTag(context: Context, tag: String?) }`. `wrap` returns `base` if `tag == null`; otherwise `base.createConfigurationContext` with `Locale.forLanguageTag(tag)`.
  - `savedTag`/`saveTag` use the `ui_prefs` SharedPreferences, key `lang`: a synchronous mirror of the setting, needed because `attachBaseContext` cannot wait for DataStore.
  - `SettingsRepository.setLanguage` calls `Localized.saveTag`.
- `MainActivity.attachBaseContext(newBase)` applies `Localized.wrap(newBase, Localized.savedTag(newBase))`. When the language changes, the activity is recreated (`recreate()`); the navigation stack survives because `AppNavHost` already saves it with `rememberSaveable`.
- `Notifier` resolves its texts with `Localized.wrap(context, Localized.savedTag(context))` on each `post`, and `Notifier.refreshChannel()` calls `ensureChannel` again with the localized channel name when the language changes.
- Deviation from spec §15 (reasoned decision): `LocaleManager`/`AppCompatDelegate` are not used, because with `minSdk 31` they would force `AppCompatActivity` and its theme on Android 12. This task updates §15.

- [ ] **Step 1: Failing tests:** `wrap(ctx, "en")` and `wrap(ctx, "es")` return different texts for `R.string.about_title`; `wrap(ctx, null)` returns the same context. Review Focus 5: with `ActivityScenario.launch(MainActivity)`, after `setLanguage(ES)` and `recreate()` the navigation stack is the same, and `Notifier.stuck` posts the title in Spanish.
- [ ] **Step 2: Run** in CI; expected: FAIL.
- [ ] **Step 3: Implement** the above. `values/strings.xml` is English; `values-es/` Spanish. Adding `android:localeConfig` is not needed (the selector is our own).
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(i18n): language wrapper and settings mirror`.

### Task 5: Extract all texts to resources

Mechanical; can be delegated to sonnet with the list of files.

**Files:**
- Modify: `app/.../ui/main/MainScreen.kt`, `ui/detail/DetailScreen.kt`, `ui/onboarding/OnboardingScreen.kt` and its `ViewModel`, `ui/recovery/RecoveryScreen.kt` and its `ViewModel`, `ui/settings/SettingsScreen.kt` and its `ViewModel`, `ui/Nav.kt`, `notify/Notifier.kt`, `ui/main/MainViewModel.kt`, `res/values/strings.xml`, `res/values-es/strings.xml`
- Test: `MainScreenTest`, `DetailScreenTest`, `SettingsScreenTest`, `NotifierTest`, `OnboardingViewModelTest`, `RecoveryViewModelTest` (adjust)

**Interfaces:**
- Consumes: `StatusLabel`/`StatusKind`/`statusLabelOf` from T2.
- Produces: `RowUi.label` and the detail state become `StatusLabel`; `@Composable fun statusText(label: StatusLabel): String` (in `ui/main/Strings.kt`; `pluralStringResource` for "attempts"); `issueTitle(issue: HealthIssue)` becomes `@Composable` with `stringResource`. At the end of the task `statusLabel(...): String` is deleted from `core` along with its old test.

- [ ] **Step 1: Change the tests first** so they compare against `context.getString(R.string.x)` or `stringResource`, not against Spanish literals; and so `formatDateTime`/`agoLabel` use resources (`ago_*`). See them fail in CI.
- [ ] **Step 2: Extract** each user-facing literal to `strings.xml` (English by default) and its Spanish translation, using the current strings in the code as the Spanish text, without rewriting them. Plurals ("attempt/attempts", "recovered SMS") use `<plurals>`.
- [ ] **Step 3: Verify** that no literals remain: `grep -rnE '"[A-ZÁÉÍÓÚa-záéíóú][^"]{3,}"' app/src/main/kotlin --include=*.kt` only returns keys, paths and logs; no visible literals.
- [ ] **Step 4: Run** all tests in CI; expected: PASS.
- [ ] **Step 5: Commit** `refactor(i18n): move all user-visible text to string resources`.

### Task 6: Navigation structure (bottom bar and ⋮ menu)

**Files:**
- Create: `app/.../ui/AppShell.kt`
- Modify: `app/.../ui/Nav.kt`, `app/.../ui/MainActivity.kt`, `gradle/libs.versions.toml` and `app/build.gradle.kts` (add `androidx.compose.material:material-icons-core`; check that the installed BOM manages it and, if not, pin a version)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/AppShellTest.kt`

**Interfaces:**
- Produces:
  - `object Routes` adds `ABOUT = "about"`; `Routes.MAIN` and `Routes.SETTINGS` become top-level tabs.
  - `@Composable fun AppShell(current: String, title: String, onTab: (String) -> Unit, onAbout: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit)`: `Scaffold` with a two-destination `NavigationBar` (Home, Settings) and a `TopAppBar` with `actions` (T8 puts the search there) and the ⋮ menu (only "About"). It only wraps `MAIN` and `SETTINGS`; detail, recovery, onboarding and About are full screen.
  - The `AppNavHost` stack on a tab is exactly `main` or `main|settings`. Tapping a tab sets the stack to that shape (it does not stack). `Nav.kt:86` and `:90` (`navigate(Routes.SETTINGS)` from a notice) switch to the same operation. `SettingsScreen.onBack` no longer exists: going back from `main|settings` is handled by the existing `BackHandler`.

- [ ] **Step 1: Failing tests:** `AppShellTest` with Robolectric and Compose: on `MAIN` the nodes with the descriptions `R.string.tab_home` and `R.string.tab_settings` exist; tapping the second calls `onTab(Routes.SETTINGS)`; the ⋮ menu shows `R.string.about_title` and has no export entry (it arrives in delivery 4); in onboarding there is no bar. Stack test: from `main`, an internal notice and then the Home tab leave the stack at `main`, not at `main|settings|main`.
- [ ] **Step 2: Run** in CI; expected: FAIL.
- [ ] **Step 3: Implement** `AppShell` and route `Nav.kt`. `MainScreen` and `SettingsScreen` lose their own v1 top-bar `Scaffold` (the text gear disappears). Bar title: the app name in Home, "Settings" in Settings.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(ui): bottom navigation and overflow menu`.

### Task 7: Grouped Settings

**Files:**
- Modify: `app/.../ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `ui/Nav.kt`, `ui/MainActivity.kt`, `ui/theme/Theme.kt`
- Test: `SettingsScreenTest.kt`, new `SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: settings and enums from T3; `AppShell` from T6.
- Produces in `SettingsViewModel`: `val prefs: StateFlow<SettingsPrefs>` where `data class SettingsPrefs(val forwarding: Boolean, val sweepInterval: SweepInterval, val stuckAfter: StuckAfter, val retention: Retention, val hideText: Boolean, val notify: Map<NotifyKind, Boolean>, val themeMode: ThemeMode, val dynamicColor: Boolean, val language: Language)`; and `setForwarding`, `setSweepInterval`, `setStuckAfter`, `setRetention`, `setHideText`, `setNotify(kind, v)`, `setDynamicColor`, `setLanguage`. The `ViewModel` does not call `recreate()` or `WorkManager`: its `Factory` receives `onSweepIntervalChanged: (SweepInterval) -> Unit` and `onLanguageChanged: () -> Unit`, which `MainActivity` wires to `schedulePeriodicSweep(..., update = true)` and to `recreate()`.

Group order (spec §7): Connection, Senders, Forwarding, History, Notifications, Appearance, Maintenance.

- [ ] **Step 1: Failing tests:**
  - The seven group headers are shown, in that order (by `R.string`).
  - The Maintenance group has the recovery and permissions/MIUI buttons; tapping each one calls its callback (`onOpenRecovery`, `onOpenSystemIssue`).
  - Turning off the forwarding switch calls `setForwarding(false)`.
  - Choosing 30 min for the sweep calls `setSweepInterval(M30)`.
  - The rejected-token notice switch does not exist as a setting.
  - The token is still never shown (v1 test kept).
- [ ] **Step 2: Run** in CI; expected: FAIL.
- [ ] **Step 3: Implement.** List selections with `SingleChoiceSegmentedButtonRow` or a radio dialog (whatever the v1 theme selector already uses; reuse it). Changing the interval calls `WorkScheduler.schedulePeriodicSweep(ctx, v, update = true)` from `MainActivity`. Changing the language calls `recreate()` after saving. Turning off dynamic color makes `SmsForwarderTheme(mode, dynamicColor)` use `lightColorScheme()`/`darkColorScheme()` (new parameter `dynamicColor: Boolean = true`).
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(settings): grouped settings screen`.

### Task 8: Home with search, filters, hide text and deletion

**Files:**
- Modify: `app/.../data/SmsEntity.kt` (`val deletedAt: Long? = null`), `data/AppDatabase.kt` (version 2 + `MIGRATION_1_2`), `data/SmsDao.kt`, `data/SmsRepository.kt`, `ui/main/MainViewModel.kt`, `MainScreen.kt`, `ui/detail/DetailScreen.kt`, `DetailViewModel.kt`, `ui/MainActivity.kt`, `AppGraph.kt`, `work/SendWorker.kt`, `app/build.gradle.kts`; schemas `app/schemas/dev.smsforwarder.data.AppDatabase/1.json` and `2.json`
- Test: `SmsRepositoryTest.kt`, `SendWorkerTest.kt`, `MainScreenTest.kt`, `DetailScreenTest.kt`, new `MigrationTest.kt` and `MainViewModelTest.kt`

**Interfaces:**
- Consumes: `SmsSearch`, `StatusFilter` (T2); `hideText` (T3).
- Produces:
  - `MIGRATION_1_2`: `ALTER TABLE sms ADD COLUMN deletedAt INTEGER`. The database is built with `.addMigrations(MIGRATION_1_2)`.
  - `SmsRepository.softDelete(id: Long)` (sets `deletedAt = clock.now()`), `observeAll()` excludes the deleted ones.
  - `SmsRepository.purgeOld` also removes deleted rows older than the cutoff, whatever their status.
  - `MainViewModel`: `fun setQuery(q: String)`, `fun setFilter(f: StatusFilter)`; `MainUiState` adds `query: String`, `filter: StatusFilter`, `hideText: Boolean`; `RowUi.label: StatusLabel`.
  - `DetailViewModel.delete()`: calls `softDelete` and cancels the unique work `send-$id` in WorkManager (lambda `cancelSend: (Long) -> Unit`).

- [ ] **Step 0: Pin schema 1.** `app/schemas/` is empty. Before touching the entity, make CI generate `1.json` from the current code (upload it as an artifact, download it with `gh run download`) and commit it. Add `testImplementation(libs.room.testing)` and `android.sourceSets["test"].assets.srcDir("$projectDir/schemas")` to `app/build.gradle.kts`.
- [ ] **Step 1: Failing tests:**
  - `MigrationTest`: a v1 database with 2 SMS migrates to v2 with the data intact and `deletedAt` null (use `MigrationTestHelper` with the already committed schema 1).
  - Review Focus 1: `SmsRepositoryTest.deletedSmsIsNotReinsertedBySweep`: after `softDelete`, `Sweeper` with the same SMS in the inbox does not create a new row nor queue a send (the row still exists with its `localKey`).
  - Full Review Focus 1: after `softDelete` of a `QUEUED`: `queuedIds()` does not include it; `SweepWorker` runs and makes 0 requests; `SendWorker` on its id returns `Result.success()` without a request; `idsWithError401()` does not include a deleted `ERROR 401` row; `stuckToNotify()` does not include it; the Home `queued` and `errors` counters do not count it.
  - `MainViewModelTest`: with three SMS, `setQuery("etec")` leaves only those of that sender; `setFilter(ERROR)` leaves only the `ERROR` ones; both at once combine (AND).
  - `MainScreenTest`: with `hideText = true` the body does not appear in the row.
  - `DetailScreenTest`: the "Delete" button asks for confirmation and, on confirming, calls `onDelete`.
- [ ] **Step 2: Run** in CI; expected: FAIL.
- [ ] **Step 3: Implement.** `AND deletedAt IS NULL` in `observeAll`, `idsWithStatus`, `idsWithStatusAndCode` and `unsentNotNotified`; **not** in `findByLocalKey` or `candidates`, so deduplication still sees the deleted row. `SendWorker` returns `Result.success()` if `deletedAt != null`. In-memory filtering over the `observeAll()` flow (the list is bounded by retention). Search with an icon in the `actions` slot of `AppShell` (T6) that opens a field; filter chips under the status line (All, Queued, Error). "Show" in the detail reveals the body even if `hideText` is on.
- [ ] **Step 4: Run**; expected: PASS. Add `git diff --exit-code app/schemas` to CI after compiling: an uncommitted schema breaks the build.
- [ ] **Step 5: Commit** `feat(home): search, filters, hide text and soft delete`.

### Task 9: About

**Files:**
- Create: `app/.../ui/about/AboutScreen.kt`
- Modify: `app/.../ui/Nav.kt`, `res/values*/strings.xml`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/AboutScreenTest.kt`

**Interfaces:**
- Produces: `@Composable fun AboutScreen(versionName: String, onOpenLink: (String) -> Unit, onBack: () -> Unit)`. Shows the icon (T1), name, version, link to the code (`https://github.com/Lacho00/sms-forwarder`), license (the repo has no `LICENSE`: the user chooses the license before this task starts, and the task adds it as a file) and a list of libraries (AndroidX, Compose, Room, WorkManager, OkHttp, kotlinx.serialization) with their license, written by hand in resources.

- [ ] **Step 1: Failing test:** the received version and the link are visible; tapping the link calls `onOpenLink` with the repo URL.
- [ ] **Step 2: Run** in CI; expected: FAIL.
- [ ] **Step 3: Implement.** `versionName` comes from `PackageManager.getPackageInfo`. Open links with `Intent.ACTION_VIEW`, catching `ActivityNotFoundException`.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(about): about screen`.

### Task 10: Notices that can be turned off

**Files:**
- Modify: `app/.../notify/Notifier.kt`, `app/.../AppGraph.kt`, `app/.../work/SendWorker.kt`
- Test: `NotifierTest.kt`, `SendWorkerTest.kt`, new `AppGraphAlertsTest.kt`

**Interfaces:**
- Consumes: `NotifyKind`, `isNotifyEnabled` (T2, T3). Actual `Notifier` methods: `recovered`, `stuck`, `health`, `tokenRejected`; none is `suspend`.
- Produces: filtering happens at the call sites, not inside `Notifier`, so as not to change its constructor (the tests' `Notifier(context, zone)` keeps compiling):
  - `AppGraph.onRecovered` checks `isNotifyEnabled(RECOVERED)` before `notifier.recovered`.
  - `AppGraph.runSweepAlerts`: `STUCK` off skips the `stuckToNotify` loop; `PERMISSIONS` off removes from `issues` only `SMS_PERMISSION`, `NOTIFICATION_PERMISSION`, `BATTERY_OPTIMIZED`, `BACKGROUND_RESTRICTED`, `AUTOSTART_OFF` and `MIUI_SMS_BLOCKED`; `TOKEN_REJECTED`, `NOT_CONFIGURED` and `NO_SENDERS` are always notified. `setLastNotifiedIssues` stores the unfiltered set, so it does not reappear when the notice is turned back on.
  - The immediate `400` notice (today `SendWorker.kt:51` calls `notifier.stuck`) becomes `Notifier.rejected(item: SmsEntity)` with the same text and cannot be turned off.

- [ ] **Step 1: Failing tests:** with `RECOVERED` off, `onRecovered` does not post; with `STUCK` off a 7 h stuck SMS does not post but an `ERROR 400` does (through `rejected`); with `PERMISSIONS` off `SMS_PERMISSION` does not post but `TOKEN_REJECTED` does; turning the permissions notice back on does not repeat what was already notified.
- [ ] **Step 2: Run** in CI; expected: FAIL.
- [ ] **Step 3: Implement** according to the interfaces.
- [ ] **Step 4: Run**; expected: PASS.
- [ ] **Step 5: Commit** `feat(notify): per-kind notification toggles`.

### Task 11: Closing delivery 1.1.0

**Files:**
- Modify: `docs/manual-test-checklist.md`, `README.md`, `docs/superpowers/specs/2026-11-02-v2-redesign.md` (delivery status)

- [ ] **Step 1: Extend the manual checklist** with: icon in the drawer (light, dark, themed), language change without losing the screen, pause and resume, deleting an SMS that is still in the inbox and waiting for a sweep, sweep interval (`adb shell dumpsys jobscheduler | grep dev.smsforwarder`), turning off dynamic color, hide text.
- [ ] **Step 2: OCR review** of the full diff (`ocr delegate rule`, opus subagent) and fix findings of Medium or higher.
- [ ] **Step 3: Compare screenshots** of Home and Detail before (v1.0.0) and after, in light and dark, reduced to 1000 px JPEG; show them to the user and wait for their OK. Any difference not listed in the spec is corrected.
- [ ] **Step 4: Push and CI** (with the user's permission per task): `git push origin main`, `gh run watch`.
- [ ] **Step 5: Release.** `git tag v1.1.0 && git push origin v1.1.0` (with permission). Install with `adb install -r` (updates without losing data, same signature) and go through the manual checklist on the phone. Failures become new tasks.
- [ ] **Step 6: Commit** the docs `docs: v1.1.0 checklist and status`.
