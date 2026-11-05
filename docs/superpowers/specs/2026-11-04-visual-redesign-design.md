# Visual redesign — design

Date: 2026-11-04. Status: approved by the user on 2026-11-05. Plan: `docs/superpowers/plans/2026-11-05-visual-redesign.md`.

## Why

The app works, but reading it is slow. A visual audit of every screen (light and dark, on the emulator) found the same problems everywhere:

- State is never shown visually. "Error 400", "Queued", ✓/✗, "3 errors", "paused", the connection test result and permission status are all plain text in the same colour. You have to read each row to know whether something failed.
- Home has no hierarchy. Health cards (each with its own filled button), the status line, "Sweep inbox now" and two chip rows take over 60 % of the screen before the first SMS, and they scroll away.
- SMS rows are five lines of equal weight with an absolute date and the deliveries as one long " · " string.
- Buttons have no system: filled buttons for secondary actions, mixed widths, stacked and left-aligned; Delete is not red.
- Settings is about three screens tall: five vertical radio groups; section titles look like labels.
- Search appears in the middle of the list, uses a filled `TextField` (everything else is outlined), has no search icon and no clear button.

The question the user opens the app with is "is it forwarding, and did anything fail?". Every screen should answer its own version of that at a glance.

## Goals

1. One status language across the app: colour + icon + short text, the same for an SMS, a delivery, a destination, a test result and a permission.
2. Home answers "is it working?" in the top block and shows SMS rows in two or three lines each.
3. One button hierarchy across the app.
4. Shorter, scannable Settings and editors.
5. Correct in light and dark, with dynamic colour on and off.

## Non-goals

- No new features, no behaviour changes, no changes to forwarding, storage or workers. The only new data is the "sent today" count on Home.
- No navigation changes (same four tabs, same overflow menu, same routes).
- No new copy beyond what the new components need; existing strings are reused where they fit.
- No custom fonts and no brand palette.

## Anti-goals

- Status colour that depends on the wallpaper. A red error must look red on every phone.
- Any loss of accessibility: status must never be colour-only (always icon + text), touch targets stay at 48 dp, TalkBack labels stay meaningful.
- Breaking existing tests silently: tests that assert on text are updated deliberately, not deleted.

## Constraints

- Jetpack Compose + Material 3 (as today). No new dependencies. `material-icons-extended` is not added: it is not in the local Gradle cache and the home connection is slow. Icons missing from `material-icons-core` are added as vector drawables in `res/drawable` (Material Symbols, Apache 2.0, outlined, 24 dp), the way the two custom nav icons already are. Prefer a local copy of the Material Symbols SVGs if one exists on the machine (e.g. under some `node_modules`); otherwise write the path data by hand.
- minSdk 31. Dynamic colour stays the default (setting already exists).
- Every new string goes in both `values/strings.xml` and `values-es/strings.xml`.
- The global rule on visual changes applies: before/after measured on the emulator, desktop is not relevant, phone light and dark.

## Design

### 1. Status system (shared)

New file `ui/theme/Status.kt`:

- `enum StatusTone { OK, PENDING, ERROR, NEUTRAL }`.
- Fixed tone colours for light and dark, defined in the theme as an extension (`MaterialTheme.statusColors` via a `CompositionLocal`), not taken from the dynamic scheme. Each tone has a container and an on-container colour. ERROR may use `colorScheme.error/errorContainer` (already wallpaper-independent in M3); OK (green) and PENDING (amber) are fixed hues with AA contrast in both modes.
- `StatusPill(tone, icon, text)`: small rounded pill, icon 14 dp + `labelSmall`/`labelMedium` text.
- Mapping:
  - `StatusKind.SENT`, `RECOVERED`, `MANUAL` → OK, icon check.
  - `StatusKind.QUEUED` → PENDING, icon clock.
  - `StatusKind.ERROR` → ERROR, icon error/close.
  - `DeliveryStatus.SENT` → OK; `QUEUED` → PENDING; `ERROR` (incl. config error) → ERROR; `CANCELED` → NEUTRAL.
- The existing text functions (`statusText`, `deliveryChipText`) stay as the pill text. The ✓/✗ glyphs inside delivery strings are dropped from the pill text (the icon replaces them); `deliveryLine` is replaced by a `FlowRow` of pills.

Used in: Home rows, Detail (status and destinations), Destinations list (error count), Onboarding (permission status and connection test result), Recovery ("already saved" rows).

### 2. Home (direction A, approved)

Top to bottom:

1. **Top bar**: title, search icon, overflow. Search opens a search field *in the top bar* (outlined style with a search icon and a clear button), not in the list.
2. **Status panel** (one `Card`, `surfaceContainer` colour):
   - Line 1: tone dot + "Forwarding" / "Paused" (from `paused`), "last sweep N min ago" on the right, and a refresh `IconButton` that runs the sweep (replaces the "Sweep inbox now" button; content description = the old button text).
   - Line 2: three counters: sent today · queued · errors. Errors are shown in error colour when > 0. Tapping "queued" or "errors" sets the matching status filter.
3. **Health banner**: when there are issues, one banner instead of one card per issue. Tone: ERROR if any issue blocks forwarding (SMS permission, token rejected, not configured, no rules, MIUI SMS blocked, migration failed), otherwise PENDING. Text: the single issue's title when there is one; "N settings may stop forwarding" when there are several. Tapping it opens the issue directly when there is one, or a bottom sheet listing the issues (each row: icon, title, chevron, opens `onOpenIssue`) when there are several.
4. **Filters**: one horizontally scrolling chip row: status chips (All / Queued / Errors), then destination chips when there is more than one destination, after a small vertical divider.
5. **List**, grouped by day with a small header ("Today", "Yesterday", date). Row (`ListItem`-like, 12 dp vertical padding, inset divider):
   - Line 1: sender (+ " · SIM n") in `titleSmall`, time on the right (time only for today and yesterday, short date otherwise).
   - Line 2: preview, one line, ellipsized, `onSurfaceVariant` (hidden when `hideText`).
   - Line 3: delivery pills in a `FlowRow`. When there are no deliveries, a single pill from the SMS status.
6. **Empty state**: icon, headline, one line of body; when filtering, a "clear filters" text button.

The status panel and the filter row scroll with the list (keeps the code simple); the banner stays above the panel.

New data: `MainUiState.sentToday: Int` — SMS received since local midnight whose status is SENT, RECOVERED or MANUAL. Computed in the ViewModel/DAO, not from the filtered rows.

### 3. Button hierarchy (shared)

- At most one filled `Button` per screen, for the main action (Save, Continue, Send selected, Retry on an errored SMS).
- Secondary actions: `FilledTonalButton` or `OutlinedButton`. Tertiary/links: `TextButton`.
- Destructive (Delete): `OutlinedButton` with error content colour and error border; confirmation dialog stays as today.
- Bottom actions in forms, onboarding and recovery: full width, pinned to the bottom of the screen (Save in both editors, Continue in onboarding, Send selected in recovery — both versions identical).
- Groups of action buttons inside a screen (onboarding permission steps, Settings maintenance) become a vertical list of full-width tonal buttons or list rows with a chevron, not loose left-aligned buttons.

### 4. Detail

- Header: sender (+ SIM) as title, date/time and source underneath, status pill on the right.
- Message body inside a `Card` (`surfaceContainerLow`), selectable text.
- "Destinations" section: one row per delivery with name and status pill.
- Actions in one row: Retry (filled, only when it applies), Resend (outlined), Delete (destructive outlined).
- "Attempts": section title + rows with time, destination, HTTP code pill.
- Section titles use the shared section-title style (see 7).

### 5. Lists: Destinations, Rules, Recovery

- Destinations: supporting line keeps the template name; errors become an ERROR pill ("3 errors") in the trailing area or under the name. Switch keeps an accessible label.
- Rules: the existing "no destinations" error text becomes an ERROR pill; supporting line unchanged otherwise.
- Recovery: rows already saved are dimmed (`onSurface` at disabled alpha on all texts) and show a NEUTRAL pill "Already saved".
- All lists: inset dividers (start at the text column), not full width.
- Empty states (Destinations, Rules, Home): shared `EmptyState(icon, title, body, action?)` composable. Destinations and Rules empty states have an "Add" action that does what the FAB does.
- FABs become `ExtendedFloatingActionButton` with icon + "Add" text.

### 6. Editors (destination, rule)

- Destination template: the four radios become a single-choice `SegmentedButton` row (or a dropdown if the labels don't fit at 360 dp — builder's call, measured).
- New destination defaults to "Standard JSON", not "Custom". (This is the only default change; it does not change saved data.)
- HTTP method: an exposed dropdown field the same size as the other fields, with its label.
- Headers: each header in its own outlined container (name, value, secret switch, remove icon); "Add header" becomes an `OutlinedButton` with a plus icon.
- Body/JSON fields: monospace font.
- "Stored. Leave empty to keep it." helper: prefixed with a lock icon.
- Rule editor match modes: `SegmentedButton` rows instead of `FilterChip`s.
- Both editors: Save pinned at the bottom (see 3); Test in the destination editor stays outlined next to it or above it.

### 7. Settings

- Section titles: `titleSmall` in `colorScheme.primary`, 24 dp above, 8 dp below. Items inside a section use the standard list-row layout (title + current value as supporting text).
- Each radio group (automatic sweep, stuck alert, history, language) becomes one row showing the current value; tapping opens a dialog with the radio list.
- Theme: a 3-option `SegmentedButton` inline.
- Warnings that are real warnings (rejected-token alert cannot be turned off) use an info/warning icon and PENDING tone text.
- Maintenance: "Recover SMS" and "Permissions and system screens" become list rows with a leading icon and a chevron.

### 8. Onboarding

- Progress: `LinearProgressIndicator` with "Step N of 8" above the title. No back arrow: the ViewModel has no backwards navigation and adding it would be a behaviour change (some steps have side effects).
- Content keeps its order but is vertically balanced: title in `headlineSmall`, body under it, step actions as a full-width tonal button list; Continue pinned at the bottom.
- Permission status and connection test result use `StatusPill` (OK / ERROR / PENDING), not plain text.

### 9. Menu, Export, About

- Overflow menu items get leading icons (export, import, info); a divider before About.
- Export dialog: the "include URL, tokens and secret headers" checkbox gets a warning icon and a one-line warning text in PENDING tone when checked.
- About: the code link aligned with the text (no `TextButton` inset; use a clickable link-styled text), "Export diagnostics" as a list row with an icon.

### 10. Navigation bar icons

Use one icon family (Material outlined/filled pairs, filled when selected): Home = inbox, Rules = filter (alt), Destinations = send/outbound, Settings = settings. Replace the two custom drawables if the extended set has fitting icons.

## Answers to builder questions (my calls)

- Health banner tone: the `HealthIssue` enum is a closed set of 11 values. Blocking (ERROR): `SMS_PERMISSION`, `TOKEN_REJECTED`, `NOT_CONFIGURED`, `NO_RULES`, `MIUI_SMS_BLOCKED`, `MIGRATION_FAILED`. Non-blocking (PENDING): `NOTIFICATION_PERMISSION`, `BATTERY_OPTIMIZED`, `BACKGROUND_RESTRICTED`, `AUTOSTART_OFF`, `AUTOSTART_UNKNOWN`. A new enum value must be classified explicitly (`when` without `else`).
- `sentToday`: counted by SMS reception time since local midnight, as written. An SMS received yesterday and sent today does not count; that is fine. No midnight tick: recomputed whenever the flow emits or the screen is recomposed after resume. Add an index only if the query needs it.
- Counters: panel "queued" and "errors" keep today's global meaning (`state.queued`, `state.errors`, not affected by any filter). Status chips show no counts (drop "with counts" above) so two different numbers for the same thing never appear.
- Search: inline `TextField` replacing the title in the existing `TopAppBar` (not M3 `SearchBar`), with back arrow and clear icon. Closing clears the query (as today). While the query is not blank, the status panel and health banner are hidden; filter chips stay.
- Paused: the panel dot is PENDING and the text is "Paused". Row pills are unchanged (queued SMS show the queued pill).
- `deliveryChipText`: change it to return text without the ✓/✗ glyphs; it has no consumers outside `ui/`. Update its tests.
- Strings: "N settings may stop forwarding" is a `plurals` resource in both languages; "Today"/"Yesterday" are plain strings; everything else as needed.
- Theme: `ThemeMode` has exactly three values (system, light, dark); dynamic colour is a separate switch. The segmented control fits.

## Decided by the user

- Home layout: direction A (status panel, single health banner, status pills in rows).
- Keep dynamic colour as default; status colours fixed and independent of the wallpaper (proposed by me, presented, not objected to).

## Left to the builder

- Exact hex values for OK/PENDING tones (must pass WCAG AA for text on container in light and dark).
- Whether the destination template control is segmented or a dropdown (decide by measuring at 360 dp width).
- Component file layout beyond `ui/theme/Status.kt` and a shared `EmptyState`.
- How `sentToday` is queried (DAO count vs. flow), as long as it does not depend on the active filter.

## Verification

- Unit/UI tests (Robolectric) updated for the new structure; new tests for: status tone mapping, banner tone choice (blocking vs. non-blocking issue), `sentToday` count, day grouping labels.
- Screenshots of every screen on the emulator in light and dark, compared against the audit set, and a check at 360 dp width for the chip row and segmented controls.
- OCR review before any push.
