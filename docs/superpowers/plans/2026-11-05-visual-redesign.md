# Visual redesign. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every screen answers at a glance "is it forwarding, and has anything failed?": a single status language (color + icon + text), Home with a status panel and two- or three-line rows, a button hierarchy, and shorter Settings and editors.

**Architecture:** Shared pieces first: `ui/theme/Status.kt` (fixed tones via `CompositionLocal`, `StatusPill`, mappings) and `ui/components/` (empty state, section title, pinned bottom bar, destructive button, setting row, single-choice dialog). Then each screen is rewritten on top of them, without touching ViewModels except for two data changes: `MainUiState.sentToday` and the default template of a new destination. Nothing changes in forwarding, Room, workers or navigation.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (BOM `2026.09.00`), `material-icons-core`, custom vector drawables, Robolectric (`sdk=35`), JUnit.

**Spec:** [2026-11-04-visual-redesign-design.md](../specs/2026-11-04-visual-redesign-design.md). Approved by the user on 2026-11-05.

**Branch:** `visual-redesign` from `main` @ `c06432d`, in the worktree `/srv/http/sms-forwarder-wt/visual-redesign`; the main clone stays on `main`. The branch's first commit adds the spec and this plan. The closing OCR review compares against `c06432d`.

## Decisions of this plan

What the spec leaves to the builder, decided here **(my decision)**:

- **Tone colors.** ERROR uses `colorScheme.errorContainer` / `onErrorContainer` (in M3 they do not depend on the screen background). NEUTRAL uses `surfaceVariant` / `onSurfaceVariant`. OK and PENDING are fixed:

  | Tone | Light container / on | Dark container / on |
  |---|---|---|
  | OK | `#D3F1D8` / `#0B4F1C` | `#1F4D2A` / `#B9F2C3` |
  | PENDING | `#FFE6AE` / `#5A3B00` | `#5B4300` / `#FFDE9E` |

  All exceed 7:1 (text over its container). The panel dot uses the tone's `on` color.
- **Icons missing from `material-icons-core`**: drawn as vector drawables in `res/drawable` (24 dp, stroke/fill from Material Symbols outlined, Apache 2.0; there is no local copy of the SVGs on the machine, they are written by hand). Names: `ic_status_schedule` (clock), `ic_status_error` (circle with !), `ic_upload`, `ic_download`, `ic_tab_home` / `ic_tab_home_filled` (inbox), `ic_tab_rules_filled`, `ic_tab_destinations_filled`. Those from core that are used as is: `Check`, `Close`, `Warning`, `Info`, `Lock`, `Search`, `Refresh`, `Add`, `Delete`, `Settings`, `KeyboardArrowRight`, `ArrowDropDown`, `AutoMirrored.Filled.ArrowBack`, `MoreVert`.
- **Tab icons:** Home = inbox (`ic_tab_home*`); Rules and Destinations keep their current drawing (funnel and fork, chosen by the user) and gain a filled variant for the selected state; Settings = `Icons.Outlined.Settings`/`Icons.Filled.Settings` (core ships both).
- **`sentToday`** is computed in memory in the `combine` of `MainViewModel` over `observeAll()` (the already unfiltered list): `status == SmsStatus.SENT && receivedAt >= startOfDay(now)`. `RECOVERED` and `MANUAL` are `SENT` with a different `origin`, so they count automatically. No new query or index. Clock and zone are injectable.
- **Destination template:** dropdown (`ExposedDropdownMenuBox`, same style as the HTTP method) if any of the four labels is cut off in a `SingleChoiceSegmentedButtonRow` at 360 dp; segmented if they fit. Measured in T8 with `android-ui` on the emulator at 360 dp and the result is noted in the commit.
- **Shared components** in `ui/components/Components.kt` (one file; they are small). The spec's empty state is called `EmptyState`.
- **Search in the bar:** `AppShell` gains a parameter `titleContent: (@Composable () -> Unit)? = null` and `navigationIcon: @Composable () -> Unit = {}`; if `titleContent` is not null it replaces the title. The field is an `OutlinedTextField` with its outline (as the spec asks), a magnifier icon at the start, a clear `IconButton` at the end when there is text, and a back arrow in `navigationIcon` that closes and clears.
- **Warnings sheet:** M3 `ModalBottomSheet`.
- **Version:** `versionName 2.1.0`, `versionCode` +1, in T12. The release only with the user's OK.

### New texts

English / Spanish. All in `values/strings.xml` and `values-es/strings.xml`.

| Key | en | es |
|---|---|---|
| `main_forwarding` | Forwarding | Reenviando |
| `main_last_sweep` | Last sweep %1$s | Último barrido %1$s |
| `main_counter_sent_today` | Sent today | Enviados hoy |
| `main_counter_queued` | Queued | En cola |
| `main_counter_errors` | Errors | Errores |
| `main_issues_count` (plurals) | %d setting may stop forwarding / %d settings may stop forwarding | %d ajuste puede detener el reenvío / %d ajustes pueden detener el reenvío |
| `main_day_today` | Today | Hoy |
| `main_day_yesterday` | Yesterday | Ayer |
| `main_clear_filters` | Clear filters | Quitar filtros |
| `main_empty_body` | Messages from your senders will appear here. | Aquí aparecerán los mensajes de tus remitentes. |
| `main_no_results_body` | Try another search or filter. | Prueba otra búsqueda u otro filtro. |
| `detail_destinations` | Destinations | Destinos |
| `detail_attempts` | Attempts | Intentos |
| `destinations_empty_body` | A destination is where SMS are sent. | Un destino es adonde se envían los SMS. |
| `rules_empty_body` | A rule picks which SMS go to which destination. | Una regla elige qué SMS van a qué destino. |
| `onboarding_progress` | Step %1$d of %2$d | Paso %1$d de %2$d |

Reused: `forwarding_paused` (the panel shows "Forwarding paused"), `main_sweep_now` (description of the sweep button), `main_empty`, `main_no_results`, `action_add`, `recovery_already_saved`, `destination_error_count`, `rule_no_destinations`. If an existing text already says what the table says, the existing one is used and the new key is omitted. Keys that stop being used (`main_status_line`, `main_open_settings`; the `delivery_*` ones with ✓/✗ are rewritten, not deleted) are removed in the task that leaves them unused.

## Global Constraints

- No new features or behavior changes: forwarding, Room (stays at version 3, no migration), workers, routes, tabs and the ⋮ menu stay the same. Only new data: `MainUiState.sentToday` and the default template `TemplateKind.STANDARD_JSON` of a new destination.
- No new dependencies. `material-icons-extended` is not added. Nothing is installed from the SDK.
- No custom fonts or brand palette. Dynamic color stays on by default.
- Status is never color alone: always icon + text. 48 dp touch targets. Meaningful TalkBack descriptions (`IconButton`s without text carry a `contentDescription`).
- All new text in `values/strings.xml` and `values-es/strings.xml`; tests compare with `context.getString(R.string.x)`, never with literals.
- Tests that check texts are updated on purpose, not deleted. Each task leaves the `:app` tests it touches green.
- `minSdk = 31`. JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests '<class>'`. CI is the reference.
- No real SMS texts, real senders, phone numbers or the author's project names in code, tests, strings or screenshots (examples: `AAA`, `TEST`, `https://h.example`).
- The user's design rule: every visual change is measured before/after on the emulator (`emulator-5554`, `pixel35`), light and dark, and at 360 dp width wherever there are chip rows or segmented controls. Emulator shut down when finished (`android-emu-stop`).
- Commits without attribution lines. OCR review before any push.

## Review Focus

1. **Long destination names and senders** (30+ characters, no spaces). The Home row, the pills in `FlowRow` and the Detail header must ellipsize or wrap, never push the time off screen. Test in T5: a 60-character sender and the time is still visible.
2. **System font at 130 % and 360 dp.** The three panel counters, the chip row and the segmented controls in Settings are not cut off or overlapping. Manual check in T12 (`adb shell settings put system font_scale 1.3`), noted in the ledger.
3. **Dynamic color off and wallpapers with green or red tones.** An OK pill is still distinguishable from an ERROR one and readable. Covered by the T1 contrast test (the colors are fixed) and by the T12 screenshots with dynamic color off.
4. **Empty list while there are health warnings or an active search.** With a non-empty search, the panel and warning are hidden; the empty state shows "Clear filters", which clears the search and filters. Test in T5.
5. **Day change with the app open.** Yesterday's SMS does not count in "Sent today" and its header says "Yesterday". Pure tests with a fixed clock and zone in T3, including an SMS at 23:59 and another at 00:00.

---

### Task 1: Status system

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/theme/Status.kt`
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/theme/Theme.kt` (provide `LocalStatusColors` according to `dark`)
- Create: `app/src/main/res/drawable/ic_status_schedule.xml`, `ic_status_error.xml`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/StatusTest.kt`

**Interfaces:**
- Produces:
  - `enum class StatusTone { OK, PENDING, ERROR, NEUTRAL }`
  - `data class ToneColors(val container: Color, val onContainer: Color)`
  - `@Immutable class StatusColors(val ok: ToneColors, val pending: ToneColors)`; `val LightStatusColors`, `val DarkStatusColors`; `val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }`
  - `@Composable fun toneColors(tone: StatusTone): ToneColors` (ERROR and NEUTRAL come from `MaterialTheme.colorScheme`)
  - `fun toneOf(kind: StatusKind): StatusTone`, `fun toneOf(status: DeliveryStatus): StatusTone`
  - `@Composable fun toneIcon(tone: StatusTone): Painter` (OK = `Icons.Filled.Check`, PENDING = `ic_status_schedule`, ERROR = `ic_status_error`, NEUTRAL = `Icons.Filled.Close`)
  - `@Composable fun StatusPill(tone: StatusTone, text: String, modifier: Modifier = Modifier)`: rounded `Surface` (`RoundedCornerShape(50)`), 14 dp decorative icon (`contentDescription = null`; the text carries the meaning), `labelMedium`, 8×4 dp padding.
  - `internal fun contrastRatio(a: Color, b: Color): Double` (WCAG, with `Color.luminance()`)

- [ ] **Step 1: Failing tests.** `toneMapsEveryStatusKind` (SENT, RECOVERED, MANUAL → OK; QUEUED → PENDING; ERROR → ERROR), `toneMapsEveryDeliveryStatus` (SENT → OK, QUEUED → PENDING, ERROR → ERROR, CANCELED → NEUTRAL), `fixedTonesPassAaInBothModes` (for `LightStatusColors` and `DarkStatusColors`, `ok` and `pending`: `contrastRatio(container, onContainer) >= 4.5`), `pillShowsItsText` (Compose: `StatusPill(OK, "x")` inside `SmsForwarderTheme` shows "x").
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.ui.StatusTest'` → FAIL (does not compile).
- [ ] **Step 3:** Implement in `Status.kt` with the hex values from the decisions table; `SmsForwarderTheme` wraps `MaterialTheme` in `CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors)`. The `when`s over the enums, without `else`.
- [ ] **Step 4:** Same command → PASS, and `ThemeTest` stays green.
- [ ] **Step 5: Commit** `feat(ui): status tones and pill`.

### Task 2: Shared components

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/components/Components.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/ComponentsTest.kt`

**Interfaces:**
- Consumes: nothing from T1 except the theme.
- Produces:
  - `@Composable fun EmptyState(icon: Painter, title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null)`: centered, 48 dp `onSurfaceVariant` icon, `titleMedium`, `bodyMedium`, action as a `TextButton`.
  - `@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier)`: `titleSmall`, `colorScheme.primary`, 24 dp top padding, 8 dp bottom, with `semantics { heading() }`.
  - `@Composable fun BottomActionBar(content: @Composable ColumnScope.() -> Unit)`: `Surface` with 3 dp `tonalElevation`, 16 dp padding, `navigationBarsPadding()`; the buttons inside take the full width.
  - `@Composable fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier)`: `OutlinedButton` with `contentColor = colorScheme.error` and `colorScheme.error` border, `Icons.Filled.Delete` icon.
  - `@Composable fun SettingRow(title: String, modifier: Modifier = Modifier, supporting: String? = null, leadingIcon: Painter? = null, chevron: Boolean = false, onClick: () -> Unit)`: clickable `ListItem`, chevron `Icons.AutoMirrored.Filled.KeyboardArrowRight`.
  - `@Composable fun <T> SingleChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit)`: `AlertDialog` with `RadioButton` + text rows (`selectable` with `Role.RadioButton`), choosing closes it; existing "Cancel" button (`R.string.action_cancel` or whichever the app uses).
  - `@Composable fun InsetDivider()`: `HorizontalDivider(Modifier.padding(start = 16.dp))`.

- [ ] **Step 1: Failing tests.** `emptyStateActionFires` (tapping the action calls `onAction`), `emptyStateWithoutActionShowsNoButton`, `sectionTitleIsHeading` (`SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)`), `singleChoiceDialogSelectsAndCloses` (tapping the second option → `onSelect(option2)`), `settingRowShowsSupportingText`.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.ComponentsTest'` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Commit** `feat(ui): shared empty state, section title, bottom bar and setting row`.

### Task 3: Home data: sent today and day groups

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/main/MainViewModel.kt`
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/MainActivity.kt` (if the factory changes signature; the new parameters have default values, so it should not)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/MainViewModelTest.kt`, `app/src/test/kotlin/dev/smsforwarder/ui/DayGroupTest.kt`

**Interfaces:**
- Produces:
  - `MainUiState.sentToday: Int = 0`
  - `MainViewModel(..., clock: () -> Long = System::currentTimeMillis, zone: () -> ZoneId = ZoneId::systemDefault)` (also in `Factory`, with the same defaults)
  - `internal fun startOfDay(now: Long, zone: ZoneId): Long`
  - `enum class DayKind { TODAY, YESTERDAY, OTHER }`; `data class DayGroup(val day: LocalDate, val kind: DayKind, val rows: List<RowUi>)`
  - `internal fun groupByDay(rows: List<RowUi>, now: Long, zone: ZoneId): List<DayGroup>` (keeps the order of `rows`, which arrives by `receivedAt DESC`)

- [ ] **Step 1: Failing tests.**
  - `DayGroupTest`: with `zone = ZoneId.of("America/Havana")` and `now` = 2026-11-05 10:00 local: `startOfDay` = 2026-11-05 00:00 local; rows at 09:00 today, 23:59 yesterday, 00:00 yesterday and 2026-11-01 → groups `[TODAY(1), YESTERDAY(2), OTHER(1)]`; empty list → `[]`.
  - `MainViewModelTest.sentTodayCountsOnlySentSinceMidnight`: with the fixed clock above, SENT SMS today (normal origin), SENT today recovered, SENT today manual, QUEUED today, ERROR today, SENT yesterday 23:59 → `sentToday == 3`.
  - `MainViewModelTest.sentTodayIgnoresFilters`: with `setFilter(StatusFilter.ERROR)` and `setQuery("zzz")`, `sentToday` does not change.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DayGroupTest' --tests 'dev.smsforwarder.ui.MainViewModelTest'` → FAIL.
- [ ] **Step 3:** Implement. `sentToday` is computed over `all` in the existing `combine`, with `clock()` and `zone()` on each emission.
- [ ] **Step 4:** → PASS (and the rest of `MainViewModelTest`).
- [ ] **Step 5: Commit** `feat(ui): sent-today count and day grouping for home`.

### Task 4: Home, top block (search, panel, warning)

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/AppShell.kt` (`titleContent`, `navigationIcon` parameters)
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/Nav.kt` (`Routes.MAIN` branch: search in the bar)
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/main/MainScreen.kt`
- Modify: `values/strings.xml`, `values-es/strings.xml`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/MainScreenTest.kt`, `AppShellTest.kt`, `NavTest.kt` (if it checks the search)

**Interfaces:**
- Consumes: T1 (`StatusTone`, `toneColors`, `StatusPill`), T3 (`sentToday`).
- Produces:
  - `fun issueTone(issue: HealthIssue): StatusTone` in `MainScreen.kt`: ERROR for `SMS_PERMISSION`, `TOKEN_REJECTED`, `NOT_CONFIGURED`, `NO_RULES`, `MIUI_SMS_BLOCKED`, `MIGRATION_FAILED`; PENDING for the rest. `when` without `else`.
  - `fun bannerTone(issues: Set<HealthIssue>): StatusTone?` (null if empty; ERROR if any blocks).
  - `MainScreen(...)` loses `searchOpen` as the trigger for the field (the field lives in the bar) and gains `onClearFilters: () -> Unit = {}`; keeps `onSweepNow`, `onFilterChange`, `onOpenIssue`.
  - `@Composable fun MainSearchField(query: String, onQueryChange: (String) -> Unit)` (used by `Nav.kt` as `titleContent`).

- [ ] **Step 1: Failing tests** (replace `showsWarningCardWithButtonForAutostartOff`, `showsPausedLineOnlyWhenPaused`, `showsStatusLineAndSweepButton`):
  - `IssueToneTest` (pure): each of the 11 `HealthIssue` values with its tone; `bannerTone(setOf(AUTOSTART_OFF, TOKEN_REJECTED)) == ERROR`; `bannerTone(setOf(BATTERY_OPTIMIZED)) == PENDING`; `bannerTone(emptySet()) == null`.
  - `singleIssueBannerShowsItsTitleAndOpensIt`: `issues = {AUTOSTART_OFF}` → text `issue_autostart_off` visible; tapping it → `onOpenIssue(AUTOSTART_OFF)`.
  - `severalIssuesShowCountAndOpenSheet`: two warnings → text of the `main_issues_count` plural with 2; tapping → both titles appear; tapping one → `onOpenIssue` with that value.
  - `panelShowsForwardingAndCounters`: `paused = false`, `sentToday = 4`, `queued = 2`, `errors = 1` → `main_forwarding`, "4", "2", "1" and the three labels visible.
  - `panelShowsPausedWhenPaused`: → `forwarding_paused` visible, `main_forwarding` does not exist.
  - `refreshButtonSweeps`: node with `contentDescription == main_sweep_now` → click → `onSweepNow` called.
  - `tappingErrorsCounterFilters`: click on the errors counter → `onFilterChange(StatusFilter.ERROR)`; on "Queued" → `QUEUED`.
  - `searchHidesPanelAndBanner`: `query = "x"` with a warning → neither the panel nor the warning exists.
  - `AppShellTest.titleContentReplacesTitle`: with `titleContent` the title is not shown.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.MainScreenTest' --tests 'dev.smsforwarder.ui.IssueToneTest' --tests 'dev.smsforwarder.ui.AppShellTest'` → FAIL.
- [ ] **Step 3:** Implement per spec §2 points 1–3 and "Answers to builder questions". Panel: `Card` with `CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer)`; line 1 = 10 dp dot of the tone (OK or PENDING if `paused`) + text + `main_last_sweep` with `agoLabel` on the right + `IconButton(Icons.Filled.Refresh)`; line 2 = three counters (number in `titleMedium`, label in `labelSmall`); "Errors" in `colorScheme.error` if > 0; the counters are tappable with `Role.Button`. Warning: `Surface` with `toneColors(bannerTone)`, tone icon, text, chevron. Sheet: `ModalBottomSheet` with a `SettingRow` per warning (icon of each one's tone). Search: in `Nav.kt`, when `searchOpen`, `titleContent = { MainSearchField(...) }` and `navigationIcon` = back arrow that does `setQuery("")` and closes; the magnifier in `actions` is only shown when closed. Delete `main_status_line` and `main_open_settings` if they are left unused.
- [ ] **Step 4:** → PASS; also `NavTest` and `SmokeTest`.
- [ ] **Step 5: Measure** on the emulator, light and dark: screenshot of Home with one warning and with two (seed with the debug receivers or by removing a permission). Compare with the audit screenshots if they are still in the scratchpad; if not, with screenshots of `main` taken before installing the branch. Reduce screenshots to JPEG ≤ 1000 px before looking at them.
- [ ] **Step 6: Commit** `feat(ui): home status panel, single health banner, search in the top bar`.

### Task 5: Home, filters and list

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/main/MainScreen.kt`, `ui/main/Strings.kt`, `ui/Nav.kt` (`onClearFilters`)
- Modify: `values/strings.xml`, `values-es/strings.xml` (`delivery_*` without ✓/✗)
- Test: `MainScreenTest.kt`, `ScreenshotsTest.kt` (if it references `deliveryLine`)

**Interfaces:**
- Consumes: T1 (`StatusPill`, `toneOf`), T2 (`EmptyState`, `InsetDivider`), T3 (`groupByDay`, `DayKind`), T4 (`onClearFilters`).
- Produces:
  - `deliveryChipText(chip)` returns text without glyphs: `delivery_sent` = `%1$s %2$d`, `delivery_sent_nocode` = `%1$s`, `delivery_error` = `%1$s %2$d`, `delivery_error_nocode` = `%1$s` (en and es); `delivery_queued`, `delivery_config`, `delivery_canceled` unchanged.
  - `@Composable fun DeliveryPills(chips: List<DeliveryChipUi>, fallback: StatusLabel)`: `FlowRow` with one `StatusPill` per delivery (`toneOf(chip.status)`, text `deliveryChipText`); with no deliveries, one pill with `toneOf(label.kind)` and `statusText(label)`.
  - `@Composable internal fun rowTime(millis: Long, kind: DayKind): String`: short time for TODAY/YESTERDAY, short date for OTHER (locale from `LocalConfiguration`).
  - `deliveryLine` is removed.

- [ ] **Step 1: Failing tests** (rewrite `rowRendersDeliveryLine` and `deliveryLineCoversErrorConfigAndCanceled`; `rowShowsLabel`, `rowShowsPreviewWhenTextIsVisible`, `hideTextKeepsSenderAndHidesBody`, `filterChipsAreShownAndReportTheChoice`, `emptyResultOfASearchSaysNoMatch`, `destinationChipsFilter` are kept and adapted):
  - `rowRendersOnePillPerDelivery`: chips `Main SENT 204` and `T2 QUEUED` → nodes with text `str(delivery_sent, "Main", 204)` and `str(delivery_queued, "T2")`, each one separately.
  - `pillsCoverErrorConfigAndCanceled`: the three texts visible separately.
  - `rowWithoutDeliveriesShowsStatusPill`: a row with no deliveries with `RECOVERED 200` → `status_recovered_code` visible.
  - `rowsAreGroupedUnderDayHeaders`: rows from today and yesterday → `main_day_today` and `main_day_yesterday` visible. (The test state passes `receivedAt` relative to `System.currentTimeMillis()`; `MainScreen` uses the real clock to group.)
  - `longSenderKeepsTimeVisible`: a 60-character sender → the row's time `assertExists()` and the sender has `maxLines = 1` (check that its text exists and that the time node is within the root's bounds).
  - `filterRowHasStatusAndDestinationChips`: with two destinations, `main_filter_queued` and "T2" in the same scrollable row.
  - `emptyStateWhileFilteringOffersClearFilters`: `query = "x"`, no rows → `main_no_results` and `main_clear_filters`; tapping → `onClearFilters` called.
  - `emptyStateWithoutFilterHasNoClearButton`: `main_empty` visible, `main_clear_filters` does not exist.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.MainScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §2 points 4–6. Row: 12 dp vertical padding, line 1 = sender (`titleSmall`, `maxLines = 1`, ellipsis, `weight(1f)`) + time; line 2 = preview (`bodyMedium`, `onSurfaceVariant`, `maxLines = 1`), hidden with `hideText`; line 3 = `DeliveryPills`. `InsetDivider` between rows. Day header: `labelLarge`, `onSurfaceVariant`, no `stickyHeader` (kept simple: `item`). Filter row: a `Row` with `horizontalScroll`, status chips, a 24 dp tall `VerticalDivider` and destination chips when there is more than one. `onClearFilters` in `Nav.kt` calls `setQuery("")`, `setFilter(ALL)`, `setDestinationFilter(null)`. `EmptyState` icons: `Icons.Filled.Search` when filtering, `ic_tab_home` (from T11; until then `Icons.Filled.Email`) when not filtering.
- [ ] **Step 4:** → PASS; `ScreenshotsTest` compiles and passes.
- [ ] **Step 5: Measure** on the emulator light/dark and at 360 dp (`adb shell wm size 720x1600` with density 320 → 360 dp; restore with `wm size reset`): scrollable chip row without cut-offs, 2–3 line rows.
- [ ] **Step 6: Commit** `feat(ui): home rows with delivery pills, day headers and empty state`.

### Task 6: Detail

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/detail/DetailScreen.kt`, strings
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/DetailScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`StatusPill`, `toneOf`), T2 (`SectionTitle`, `DestructiveButton`), T5 (`deliveryChipText`).
- Produces: nothing new for other tasks.

- [ ] **Step 1: Failing tests:** `headerShowsSenderAndStatusPill` (sender and the `statusText` of its label), `destinationsSectionListsEachDeliveryWithPill` (title `detail_destinations`, name of each delivery and its pill), `retryOnlyWhenItApplies` (error state → retry button exists; sent → it does not exist), `deleteIsDestructiveAndStillConfirms` (tapping delete opens today's confirmation dialog; confirming calls `onDelete`), `attemptsSectionShowsHttpCode` (title `detail_attempts` and the code in a pill). Existing tests that look at texts are adapted.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DetailScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §4. Message body in a `surfaceContainerLow` `Card` with `SelectionContainer`. Actions in a `Row` (`FlowRow` if they do not fit at 360 dp): Retry `Button`, Resend `OutlinedButton`, Delete `DestructiveButton`. Attempt pill: OK tone for 2xx, ERROR for the rest, NEUTRAL with no code.
- [ ] **Step 4:** → PASS. Use `assertExists()` where the Robolectric viewport cuts things off.
- [ ] **Step 5: Measure** on the emulator light/dark, an SMS with two deliveries (one failed).
- [ ] **Step 6: Commit** `feat(ui): detail header, destinations and attempts with status pills`.

### Task 7: Lists: Destinations, Rules, Recovery

**Files:**
- Modify: `ui/destinations/DestinationsScreen.kt`, `ui/rules/RulesScreen.kt`, `ui/recovery/RecoveryScreen.kt`, `ui/Nav.kt` (extended FAB if it lives in `AppShell`/`Nav`), strings
- Test: `DestinationsScreenTest.kt`, `RulesScreenTest.kt`, `RecoveryScreenTest.kt`

**Interfaces:**
- Consumes: T1, T2 (`EmptyState`, `InsetDivider`, `BottomActionBar`).

- [ ] **Step 1: Failing tests:**
  - Destinations: `errorCountIsAnErrorPill` (row with 3 errors → plural text `destination_error_count` with 3); `emptyStateAddOpensEditor` (empty list → `EmptyState` with `action_add`; tapping → same lambda as the FAB); `fabIsExtendedWithAddText` (node with text `destination_add` or `action_add`, whichever is chosen, and click → callback).
  - Rules: `noDestinationsIsAnErrorPill` (`rule_no_destinations` visible); `emptyStateAddOpensEditor`.
  - Recovery: `alreadySavedRowShowsNeutralPill` (`recovery_already_saved` visible in a pill and the row not selectable, as today); `sendSelectedIsPinnedAtBottom` (the button exists and is outside the `LazyColumn`: check that it is still visible after `performScrollToIndex` to the end).
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DestinationsScreenTest' --tests 'dev.smsforwarder.ui.RulesScreenTest' --tests 'dev.smsforwarder.ui.RecoveryScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §5. The destination switch keeps its accessible label. Already-saved rows in Recovery: texts with `colorScheme.onSurface.copy(alpha = 0.38f)`. FAB: `ExtendedFloatingActionButton(icon = Icons.Filled.Add, text = action_add)`. Both versions of "Send selected" (onboarding and Settings → Recovery) use the same `BottomActionBar`.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Measure** on the emulator light/dark: Destinations with one destination with errors and one paused; Rules with one with no destinations; Recovery with already-saved rows.
- [ ] **Step 6: Commit** `feat(ui): list pills, inset dividers, empty states and extended FABs`.

### Task 8: Destination and rule editors

**Files:**
- Modify: `ui/destinations/DestinationEditorScreen.kt`, `ui/destinations/DestinationEditorViewModel.kt` (only the default value), `ui/rules/RuleEditorScreen.kt`, strings
- Test: `DestinationEditorScreenTest.kt`, `DestinationEditorViewModelTest.kt`, `RuleEditorScreenTest.kt`

**Interfaces:**
- Consumes: T2 (`BottomActionBar`, `DestructiveButton`).
- Produces: `DestinationEditorState.template` defaults to `TemplateKind.STANDARD_JSON`.

- [ ] **Step 1: Failing tests:**
  - `newDestinationDefaultsToStandardJson` (ViewModel: `reset()` for a new destination → `template == STANDARD_JSON` and `name == defaultName(STANDARD_JSON)`); review the existing tests that assumed `CUSTOM` and adapt them by choosing `CUSTOM` explicitly with `chooseTemplate`.
  - `editingKeepsSavedTemplate` (a destination saved as `CUSTOM` stays `CUSTOM` when opened).
  - `templateControlSelectsTemplate` (tapping the Telegram option → `chooseTemplate(TELEGRAM)`; works for segmented or dropdown).
  - `headerRowHasNameValueSecretAndRemove` (one header → its four controls; delete → callback).
  - `storedSecretHelperHasLockIcon` (existing "stored" text visible next to an icon; check the text).
  - `saveIsPinnedAtBottom` (in both editors, the Save button exists after scrolling to the end of the form).
  - Rule: `matchModeIsSegmented` (tapping a mode → callback with that mode; `Role.RadioButton` on the segments).
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DestinationEditor*' --tests 'dev.smsforwarder.ui.RuleEditorScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §6. Before deciding the template control, measure on the emulator at 360 dp (see "Decisions"). HTTP method: the current `ExposedDropdownMenuBox` with `fillMaxWidth()` and its label. Headers: an `OutlinedCard` per header; "Add header" `OutlinedButton` with `Icons.Filled.Add`. Body and JSON: `textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)`. Stored-secret helper: `Row` with a 16 dp `Icons.Filled.Lock`. Save in `BottomActionBar` (`Scaffold.bottomBar`), Test `OutlinedButton` above Save in the same bar; Delete `DestructiveButton` at the end of the form.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Measure** on the emulator light/dark and at 360 dp: editor for a new destination, for one with two headers, rule editor.
- [ ] **Step 6: Commit** `feat(ui): editors with segmented choices, header cards and pinned save`.

### Task 9: Settings

**Files:**
- Modify: `ui/settings/SettingsScreen.kt`, strings
- Test: `SettingsScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`toneColors(PENDING)`), T2 (`SectionTitle`, `SettingRow`, `SingleChoiceDialog`).

- [ ] **Step 1: Failing tests** (adapt the current radio ones):
  - `sweepIntervalRowShowsCurrentValueAndOpensDialog`: the row shows the current value as secondary text; tapping → dialog with all the options; choosing another → `setSweepInterval(value)` and the dialog closes. Same for `stuckAfter`, `retention` and `language` (one test per group; a shared helper).
  - `themeIsSegmented`: three segments; tapping Dark → `setTheme(ThemeMode.DARK)`.
  - `tokenAlertWarningHasIcon`: the existing warning text visible next to `Icons.Filled.Info` (check the text).
  - `maintenanceRowsOpenTheirScreens`: tapping "Recover SMS" → `onOpenRecovery`; the permissions row → its callback.
  - `sectionTitlesAreHeadings`: at least one `heading` node per section.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.SettingsScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §7. The switches (forwarding, hide text, notify, dynamic color) stay as `ListItem` with a `Switch` in `trailingContent` and `toggleable` on the row.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Measure** on the emulator light/dark and at 360 dp (theme segmented control). Total height of Settings before/after noted (goal: under two screens).
- [ ] **Step 6: Commit** `feat(ui): compact settings with value rows and choice dialogs`.

### Task 10: Onboarding

**Files:**
- Modify: `ui/onboarding/OnboardingScreen.kt`, strings
- Test: create `app/src/test/kotlin/dev/smsforwarder/ui/OnboardingScreenTest.kt` (today there is only a ViewModel test)

**Interfaces:**
- Consumes: T1 (`StatusPill`), T2 (`BottomActionBar`).

- [ ] **Step 1: Failing tests:**
  - `showsStepProgress`: step 3 of 8 → text `onboarding_progress` with 3 and 8.
  - `permissionStatusIsAPill`: permission granted → pill with the current "granted" status text; denied → the "denied" one. (Use the keys the permissions step already uses.)
  - `connectionResultIsAPill`: OK result and error result → their current texts visible.
  - `continueIsPinnedAndAdvances`: tapping Continue → advance callback.
  - `noBackArrow`: no node with the back description exists.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.OnboardingScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §8. `LinearProgressIndicator(progress = { step / total.toFloat() })` with the text above it. Title `headlineSmall`. Step actions as full-width `FilledTonalButton`s in a column. Connection test tone: OK if it passes, ERROR if it fails, PENDING while testing.
- [ ] **Step 4:** → PASS; `OnboardingViewModelTest` unchanged.
- [ ] **Step 5: Measure** on the emulator light/dark the permissions and connection steps (clear the debug app's data to get back to onboarding: `adb -s emulator-5554 shell pm clear <debug package>`).
- [ ] **Step 6: Commit** `feat(ui): onboarding progress, status pills and pinned continue`.

### Task 11: Menu, export, About and tab icons

**Files:**
- Modify: `ui/AppShell.kt`, `ui/transfer/TransferDialogs.kt`, `ui/about/AboutScreen.kt`
- Create: `res/drawable/ic_upload.xml`, `ic_download.xml`, `ic_tab_home.xml`, `ic_tab_home_filled.xml`, `ic_tab_rules_filled.xml`, `ic_tab_destinations_filled.xml`
- Modify: `ui/main/MainScreen.kt` (empty-state icon, if the provisional one was left in T5)
- Test: `AppShellTest.kt`, `TransferDialogsTest.kt`, `AboutScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`toneColors(PENDING)`), T2 (`SettingRow`).

- [ ] **Step 1: Failing tests:**
  - `menuItemsKeepOrderAndCallbacks`: Export, Import, About, in that order, each with its callback (the current test, adapted if it only looks at text).
  - `exportWarningAppearsOnlyWhenChecked`: checkbox unchecked → existing warning text does not exist; checked → it exists.
  - `aboutLinkOpensCode`: the code link is tappable text with `Role.Button`/`LinkAnnotation` and fires the same callback as today.
  - `aboutDiagnosticsIsARow`: tapping the diagnostics row → export diagnostics callback.
  - `selectedTabUsesFilledIcon`: not tested in Robolectric (the icon cannot be meaningfully inspected); checked in the T12 screenshot.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.AppShellTest' --tests 'dev.smsforwarder.ui.TransferDialogsTest' --tests 'dev.smsforwarder.ui.AboutScreenTest'` → FAIL.
- [ ] **Step 3:** Implement spec §9 and §10. Menu: `leadingIcon` = `ic_upload` (export), `ic_download` (import), `Icons.Outlined.Info`; `HorizontalDivider` before About. Export warning: `Row` with `Icons.Filled.Warning` and text in `toneColors(PENDING).onContainer` over its container. Tabs: filled icon if `selected`, outline if not.
- [ ] **Step 4:** → PASS; `IconTest` and `NavTest` green.
- [ ] **Step 5: Measure** on the emulator light/dark: open menu, export dialog checked, About, bottom bar with each tab selected.
- [ ] **Step 6: Commit** `feat(ui): menu icons, export warning, about rows and tab icon pairs`.

### Task 12: Overall verification and closing

**Files:**
- Modify: `app/build.gradle.kts` (`versionName "2.1.0"`, `versionCode` +1), `README.md` (screenshots if it links them), `docs/superpowers/specs/2026-11-04-visual-redesign-design.md` (status line: implemented)
- Test: full suite

- [ ] **Step 1:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew :app:assembleDebug :app:lintDebug` → no new lint errors.
- [ ] **Step 3: Screenshots** on the emulator of all screens (Home with and without warnings, Detail, Destinations, destination editor, Rules, rule editor, Settings, Onboarding at permissions and connection, Recovery, About, menu, export dialog), light and dark, with dynamic color on and off. Comparison against the audit screenshots delegated to a sonnet subagent that returns only the verdict per screen.
- [ ] **Step 4: Review Focus 2 cases:** `font_scale 1.3` at 360 dp on Home, Settings and the destination editor; restore `font_scale 1.0` and `wm size reset`. Note the result.
- [ ] **Step 5: Quick TalkBack check:** with `android-ui`, check that the sweep button, the magnifier, the errors counter and the pills have text or a description.
- [ ] **Step 6:** `android-emu-stop`.
- [ ] **Step 7: OCR review** of the branch against `c06432d` (skill `ocr-review`). Fix High/Medium in small commits.
- [ ] **Step 8: Commit** `chore: 2.1.0 visual redesign` and present the before/after screenshots to the user. Push and release wait for their OK.

---

## Order and dependencies

T1 → T2 → T3 → T4 → T5 are sequential (T4 and T5 touch `MainScreen.kt` and `Nav.kt`). After T5, T6, T7, T8, T9, T10 and T11 touch different files and only depend on T1–T2 (T11 touches `MainScreen.kt` only for the empty-state icon; if run in parallel, that change moves to T12). `strings.xml` is touched by almost all of them: in parallel execution, each task adds its keys at the end of its block and conflicts are resolved on integration. T12 last.
