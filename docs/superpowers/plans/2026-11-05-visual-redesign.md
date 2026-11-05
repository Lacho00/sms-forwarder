# Rediseño visual. Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cada pantalla responde de un vistazo "¿está reenviando y ha fallado algo?": un único lenguaje de estado (color + icono + texto), Inicio con panel de estado y filas de dos o tres líneas, una jerarquía de botones y Ajustes y editores más cortos.

**Architecture:** Primero las piezas compartidas: `ui/theme/Status.kt` (tonos fijos por `CompositionLocal`, `StatusPill`, mapeos) y `ui/components/` (estado vacío, título de sección, barra inferior fija, botón destructivo, fila de ajuste, diálogo de opción única). Después cada pantalla se reescribe sobre ellas, sin tocar ViewModels salvo dos cambios de datos: `MainUiState.sentToday` y la plantilla por defecto de un destino nuevo. Nada cambia en reenvío, Room, workers ni navegación.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (BOM `2026.09.00`), `material-icons-core`, vector drawables propios, Robolectric (`sdk=35`), JUnit.

**Spec:** [2026-11-04-visual-redesign-design.md](../specs/2026-11-04-visual-redesign-design.md). Aprobada por el usuario el 2026-11-05.

**Rama:** `visual-redesign` desde `main` @ `c06432d`, en el worktree `/srv/http/sms-forwarder-wt/visual-redesign`; el clon principal se queda en `main`. El primer commit de la rama añade la spec y este plan. La revisión OCR de cierre compara contra `c06432d`.

## Decisiones de este plan

Lo que la spec deja al constructor, decidido aquí **(mi decisión)**:

- **Colores de tono.** ERROR usa `colorScheme.errorContainer` / `onErrorContainer` (no dependen del fondo de pantalla en M3). NEUTRAL usa `surfaceVariant` / `onSurfaceVariant`. OK y PENDING son fijos:

  | Tono | Claro container / on | Oscuro container / on |
  |---|---|---|
  | OK | `#D3F1D8` / `#0B4F1C` | `#1F4D2A` / `#B9F2C3` |
  | PENDING | `#FFE6AE` / `#5A3B00` | `#5B4300` / `#FFDE9E` |

  Todos superan 7:1 (texto sobre su container). El punto del panel usa el color `on` del tono.
- **Iconos que faltan en `material-icons-core`**: se dibujan como vector drawables en `res/drawable` (24 dp, trazo/relleno de Material Symbols outlined, Apache 2.0; no hay copia local de los SVG en la máquina, se escriben a mano). Nombres: `ic_status_schedule` (reloj), `ic_status_error` (círculo con !), `ic_upload`, `ic_download`, `ic_tab_home` / `ic_tab_home_filled` (bandeja), `ic_tab_rules_filled`, `ic_tab_destinations_filled`. Los de core que se usan tal cual: `Check`, `Close`, `Warning`, `Info`, `Lock`, `Search`, `Refresh`, `Add`, `Delete`, `Settings`, `KeyboardArrowRight`, `ArrowDropDown`, `AutoMirrored.Filled.ArrowBack`, `MoreVert`.
- **Iconos de pestañas:** Inicio = bandeja (`ic_tab_home*`); Reglas y Destinos conservan su dibujo actual (embudo y bifurcación, elegidos por el usuario) y ganan variante rellena para el estado seleccionado; Ajustes = `Icons.Outlined.Settings`/`Icons.Filled.Settings` (core trae ambos).
- **`sentToday`** se calcula en memoria en el `combine` de `MainViewModel` sobre `observeAll()` (lista ya sin filtrar): `status == SmsStatus.SENT && receivedAt >= startOfDay(now)`. `RECOVERED` y `MANUAL` son `SENT` con otro `origin`, así que entran solos. Sin consulta nueva ni índice. Reloj y zona inyectables.
- **Plantilla del destino:** desplegable (`ExposedDropdownMenuBox`, mismo estilo que el método HTTP) si alguna de las cuatro etiquetas se corta en un `SingleChoiceSegmentedButtonRow` a 360 dp; segmentado si caben. Se mide en la T8 con `android-ui` en el emulador a 360 dp y se anota el resultado en el commit.
- **Componentes compartidos** en `ui/components/Components.kt` (un fichero; son pequeños). El estado vacío de la spec se llama `EmptyState`.
- **Búsqueda en la barra:** `AppShell` gana un parámetro `titleContent: (@Composable () -> Unit)? = null` y `navigationIcon: @Composable () -> Unit = {}`; si `titleContent` no es nulo sustituye al título. El campo es `OutlinedTextField` con su contorno (como pide la spec), icono de lupa al inicio, `IconButton` de borrar al final cuando hay texto, flecha atrás en `navigationIcon` que cierra y limpia.
- **Hoja de avisos:** `ModalBottomSheet` de M3.
- **Versión:** `versionName 2.1.0`, `versionCode` +1, en la T12. La release solo con OK del usuario.

### Textos nuevos

Inglés / español. Todo en `values/strings.xml` y `values-es/strings.xml`.

| Clave | en | es |
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

Se reutilizan: `forwarding_paused` (el panel muestra "Forwarding paused"), `main_sweep_now` (descripción del botón de barrido), `main_empty`, `main_no_results`, `action_add`, `recovery_already_saved`, `destination_error_count`, `rule_no_destinations`. Si un texto existente ya dice lo de la tabla, se usa el existente y se omite la clave nueva. Las claves que dejan de usarse (`main_status_line`, `main_open_settings`, `delivery_*` con ✓/✗ se reescriben, no se borran) se eliminan en la tarea que las deja sin uso.

## Global Constraints

- Sin funcionalidades nuevas ni cambios de comportamiento: reenvío, Room (sigue en versión 3, sin migración), workers, rutas, pestañas y menú ⋮ quedan igual. Únicos datos nuevos: `MainUiState.sentToday` y la plantilla por defecto `TemplateKind.STANDARD_JSON` de un destino nuevo.
- Sin dependencias nuevas. No se añade `material-icons-extended`. No se instala nada del SDK.
- Sin fuentes propias ni paleta de marca. El color dinámico sigue por defecto.
- El estado nunca es solo color: siempre icono + texto. Objetivos táctiles de 48 dp. Descripciones de TalkBack con sentido (los `IconButton` sin texto llevan `contentDescription`).
- Todo texto nuevo en `values/strings.xml` y `values-es/strings.xml`; los tests comparan con `context.getString(R.string.x)`, nunca con literales.
- Los tests que comprueban textos se actualizan a propósito, no se borran. Cada tarea deja verdes los tests de `:app` que toca.
- `minSdk = 31`. JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest --tests '<clase>'`. CI es la referencia.
- Nada de textos de SMS reales, remitentes reales, teléfonos ni nombres de proyectos del autor en código, tests, strings o capturas (ejemplos: `AAA`, `TEST`, `https://h.example`).
- Regla de diseño del usuario: todo cambio visual se mide antes/después en el emulador (`emulator-5554`, `pixel35`), claro y oscuro, y a 360 dp de ancho donde haya filas de chips o segmentados. Emulador apagado al terminar (`android-emu-stop`).
- Commits sin líneas de atribución. Revisión con OCR antes de cualquier push.

## Review Focus

1. **Nombres de destino y remitentes largos** (30+ caracteres, sin espacios). La fila de Inicio, las píldoras en `FlowRow` y la cabecera de Detalle deben elipsar o saltar de línea, nunca empujar la hora fuera de pantalla. Test en T5: remitente de 60 caracteres y la hora sigue visible.
2. **Fuente del sistema al 130 % y 360 dp.** Los tres contadores del panel, la fila de chips y los segmentados de Ajustes no se cortan ni se solapan. Comprobación manual en T12 (`adb shell settings put system font_scale 1.3`), anotada en el ledger.
3. **Color dinámico desactivado y fondos de pantalla con tonos verdes o rojos.** Una píldora OK sigue siendo distinguible de una ERROR y legible. Cubierto por el test de contraste de T1 (los colores son fijos) y por las capturas de T12 con color dinámico apagado.
4. **Lista vacía mientras hay avisos de salud o búsqueda activa.** Con búsqueda no vacía se ocultan panel y aviso; el estado vacío muestra "Quitar filtros" que limpia búsqueda y filtros. Test en T5.
5. **Cambio de día con la app abierta.** Un SMS de ayer no cuenta en "Enviados hoy" y su cabecera dice "Ayer". Tests puros con reloj y zona fijos en T3, incluido un SMS a las 23:59 y otro a las 00:00.

---

### Task 1: Sistema de estado

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/theme/Status.kt`
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/theme/Theme.kt` (proveer `LocalStatusColors` según `dark`)
- Create: `app/src/main/res/drawable/ic_status_schedule.xml`, `ic_status_error.xml`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/StatusTest.kt`

**Interfaces:**
- Produces:
  - `enum class StatusTone { OK, PENDING, ERROR, NEUTRAL }`
  - `data class ToneColors(val container: Color, val onContainer: Color)`
  - `@Immutable class StatusColors(val ok: ToneColors, val pending: ToneColors)`; `val LightStatusColors`, `val DarkStatusColors`; `val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }`
  - `@Composable fun toneColors(tone: StatusTone): ToneColors` (ERROR y NEUTRAL salen de `MaterialTheme.colorScheme`)
  - `fun toneOf(kind: StatusKind): StatusTone`, `fun toneOf(status: DeliveryStatus): StatusTone`
  - `@Composable fun toneIcon(tone: StatusTone): Painter` (OK = `Icons.Filled.Check`, PENDING = `ic_status_schedule`, ERROR = `ic_status_error`, NEUTRAL = `Icons.Filled.Close`)
  - `@Composable fun StatusPill(tone: StatusTone, text: String, modifier: Modifier = Modifier)`: `Surface` redondeado (`RoundedCornerShape(50)`), icono 14 dp decorativo (`contentDescription = null`; el texto lleva el significado), `labelMedium`, padding 8×4 dp.
  - `internal fun contrastRatio(a: Color, b: Color): Double` (WCAG, con `Color.luminance()`)

- [ ] **Step 1: Tests que fallan.** `toneMapsEveryStatusKind` (SENT, RECOVERED, MANUAL → OK; QUEUED → PENDING; ERROR → ERROR), `toneMapsEveryDeliveryStatus` (SENT → OK, QUEUED → PENDING, ERROR → ERROR, CANCELED → NEUTRAL), `fixedTonesPassAaInBothModes` (para `LightStatusColors` y `DarkStatusColors`, `ok` y `pending`: `contrastRatio(container, onContainer) >= 4.5`), `pillShowsItsText` (Compose: `StatusPill(OK, "x")` dentro de `SmsForwarderTheme` muestra "x").
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'dev.smsforwarder.ui.StatusTest'` → FAIL (no compila).
- [ ] **Step 3:** Implementar en `Status.kt` con los hex de la tabla de decisiones; `SmsForwarderTheme` envuelve `MaterialTheme` en `CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors)`. Los `when` sobre los enums, sin `else`.
- [ ] **Step 4:** Mismo comando → PASS, y `ThemeTest` sigue en verde.
- [ ] **Step 5: Commit** `feat(ui): status tones and pill`.

### Task 2: Componentes compartidos

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/components/Components.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/ComponentsTest.kt`

**Interfaces:**
- Consumes: nada de T1 salvo el tema.
- Produces:
  - `@Composable fun EmptyState(icon: Painter, title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null)`: centrado, icono 48 dp `onSurfaceVariant`, `titleMedium`, `bodyMedium`, acción como `TextButton`.
  - `@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier)`: `titleSmall`, `colorScheme.primary`, padding superior 24 dp, inferior 8 dp, con `semantics { heading() }`.
  - `@Composable fun BottomActionBar(content: @Composable ColumnScope.() -> Unit)`: `Surface` con `tonalElevation` 3 dp, padding 16 dp, `navigationBarsPadding()`; los botones dentro ocupan todo el ancho.
  - `@Composable fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier)`: `OutlinedButton` con `contentColor = colorScheme.error` y borde `colorScheme.error`, icono `Icons.Filled.Delete`.
  - `@Composable fun SettingRow(title: String, modifier: Modifier = Modifier, supporting: String? = null, leadingIcon: Painter? = null, chevron: Boolean = false, onClick: () -> Unit)`: `ListItem` clicable, chevron `Icons.AutoMirrored.Filled.KeyboardArrowRight`.
  - `@Composable fun <T> SingleChoiceDialog(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit)`: `AlertDialog` con filas `RadioButton` + texto (`selectable` con `Role.RadioButton`), elegir cierra; botón "Cancel" existente (`R.string.action_cancel` o el que use la app).
  - `@Composable fun InsetDivider()`: `HorizontalDivider(Modifier.padding(start = 16.dp))`.

- [ ] **Step 1: Tests que fallan.** `emptyStateActionFires` (pulsar la acción llama a `onAction`), `emptyStateWithoutActionShowsNoButton`, `sectionTitleIsHeading` (`SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)`), `singleChoiceDialogSelectsAndCloses` (pulsar la segunda opción → `onSelect(opción2)`), `settingRowShowsSupportingText`.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.ComponentsTest'` → FAIL.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Commit** `feat(ui): shared empty state, section title, bottom bar and setting row`.

### Task 3: Datos de Inicio: enviados hoy y grupos por día

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/main/MainViewModel.kt`
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/MainActivity.kt` (si la fábrica cambia de firma; los parámetros nuevos llevan valor por defecto, así que no debería)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/MainViewModelTest.kt`, `app/src/test/kotlin/dev/smsforwarder/ui/DayGroupTest.kt`

**Interfaces:**
- Produces:
  - `MainUiState.sentToday: Int = 0`
  - `MainViewModel(..., clock: () -> Long = System::currentTimeMillis, zone: () -> ZoneId = ZoneId::systemDefault)` (también en `Factory`, con los mismos valores por defecto)
  - `internal fun startOfDay(now: Long, zone: ZoneId): Long`
  - `enum class DayKind { TODAY, YESTERDAY, OTHER }`; `data class DayGroup(val day: LocalDate, val kind: DayKind, val rows: List<RowUi>)`
  - `internal fun groupByDay(rows: List<RowUi>, now: Long, zone: ZoneId): List<DayGroup>` (conserva el orden de `rows`, que llega por `receivedAt DESC`)

- [ ] **Step 1: Tests que fallan.**
  - `DayGroupTest`: con `zone = ZoneId.of("America/Havana")` y `now` = 2026-11-05 10:00 local: `startOfDay` = 2026-11-05 00:00 local; filas a 09:00 hoy, 23:59 ayer, 00:00 ayer y 2026-11-01 → grupos `[TODAY(1), YESTERDAY(2), OTHER(1)]`; lista vacía → `[]`.
  - `MainViewModelTest.sentTodayCountsOnlySentSinceMidnight`: con el reloj fijo anterior, SMS SENT hoy (origin normal), SENT hoy recuperado, SENT hoy manual, QUEUED hoy, ERROR hoy, SENT ayer 23:59 → `sentToday == 3`.
  - `MainViewModelTest.sentTodayIgnoresFilters`: con `setFilter(StatusFilter.ERROR)` y `setQuery("zzz")`, `sentToday` no cambia.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DayGroupTest' --tests 'dev.smsforwarder.ui.MainViewModelTest'` → FAIL.
- [ ] **Step 3:** Implementar. `sentToday` se calcula sobre `all` en el `combine` existente, con `clock()` y `zone()` en cada emisión.
- [ ] **Step 4:** → PASS (y el resto de `MainViewModelTest`).
- [ ] **Step 5: Commit** `feat(ui): sent-today count and day grouping for home`.

### Task 4: Inicio, bloque superior (búsqueda, panel, aviso)

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/AppShell.kt` (parámetros `titleContent`, `navigationIcon`)
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/Nav.kt` (rama `Routes.MAIN`: búsqueda en la barra)
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/main/MainScreen.kt`
- Modify: `values/strings.xml`, `values-es/strings.xml`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/MainScreenTest.kt`, `AppShellTest.kt`, `NavTest.kt` (si comprueba la búsqueda)

**Interfaces:**
- Consumes: T1 (`StatusTone`, `toneColors`, `StatusPill`), T3 (`sentToday`).
- Produces:
  - `fun issueTone(issue: HealthIssue): StatusTone` en `MainScreen.kt`: ERROR para `SMS_PERMISSION`, `TOKEN_REJECTED`, `NOT_CONFIGURED`, `NO_RULES`, `MIUI_SMS_BLOCKED`, `MIGRATION_FAILED`; PENDING para el resto. `when` sin `else`.
  - `fun bannerTone(issues: Set<HealthIssue>): StatusTone?` (null si vacío; ERROR si alguno bloquea).
  - `MainScreen(...)` pierde `searchOpen` como disparador del campo (el campo vive en la barra) y gana `onClearFilters: () -> Unit = {}`; mantiene `onSweepNow`, `onFilterChange`, `onOpenIssue`.
  - `@Composable fun MainSearchField(query: String, onQueryChange: (String) -> Unit)` (lo usa `Nav.kt` como `titleContent`).

- [ ] **Step 1: Tests que fallan** (sustituyen a `showsWarningCardWithButtonForAutostartOff`, `showsPausedLineOnlyWhenPaused`, `showsStatusLineAndSweepButton`):
  - `IssueToneTest` (puro): cada uno de los 11 valores de `HealthIssue` con su tono; `bannerTone(setOf(AUTOSTART_OFF, TOKEN_REJECTED)) == ERROR`; `bannerTone(setOf(BATTERY_OPTIMIZED)) == PENDING`; `bannerTone(emptySet()) == null`.
  - `singleIssueBannerShowsItsTitleAndOpensIt`: `issues = {AUTOSTART_OFF}` → texto `issue_autostart_off` visible; pulsarlo → `onOpenIssue(AUTOSTART_OFF)`.
  - `severalIssuesShowCountAndOpenSheet`: dos avisos → texto del plural `main_issues_count` con 2; pulsar → aparecen los dos títulos; pulsar uno → `onOpenIssue` con ese valor.
  - `panelShowsForwardingAndCounters`: `paused = false`, `sentToday = 4`, `queued = 2`, `errors = 1` → `main_forwarding`, "4", "2", "1" y las tres etiquetas visibles.
  - `panelShowsPausedWhenPaused`: → `forwarding_paused` visible, `main_forwarding` no existe.
  - `refreshButtonSweeps`: nodo con `contentDescription == main_sweep_now` → click → `onSweepNow` llamado.
  - `tappingErrorsCounterFilters`: click en el contador de errores → `onFilterChange(StatusFilter.ERROR)`; en "En cola" → `QUEUED`.
  - `searchHidesPanelAndBanner`: `query = "x"` con un aviso → ni el panel ni el aviso existen.
  - `AppShellTest.titleContentReplacesTitle`: con `titleContent` el título no se muestra.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.MainScreenTest' --tests 'dev.smsforwarder.ui.IssueToneTest' --tests 'dev.smsforwarder.ui.AppShellTest'` → FAIL.
- [ ] **Step 3:** Implementar según spec §2 puntos 1–3 y "Answers to builder questions". Panel: `Card` con `CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer)`; línea 1 = punto 10 dp del tono (OK o PENDING si `paused`) + texto + `main_last_sweep` con `agoLabel` a la derecha + `IconButton(Icons.Filled.Refresh)`; línea 2 = tres contadores (número en `titleMedium`, etiqueta en `labelSmall`); "Errores" en `colorScheme.error` si > 0; los contadores pulsables con `Role.Button`. Aviso: `Surface` con `toneColors(bannerTone)`, icono del tono, texto, chevron. Hoja: `ModalBottomSheet` con `SettingRow` por aviso (icono del tono de cada uno). Búsqueda: en `Nav.kt`, cuando `searchOpen`, `titleContent = { MainSearchField(...) }` y `navigationIcon` = flecha atrás que hace `setQuery("")` y cierra; la lupa de `actions` solo se ve cerrada. Borrar `main_status_line` y `main_open_settings` si quedan sin uso.
- [ ] **Step 4:** → PASS; además `NavTest` y `SmokeTest`.
- [ ] **Step 5: Medir** en el emulador, claro y oscuro: captura de Inicio con un aviso y con dos (sembrar con los receptores de debug o quitando un permiso). Comparar con las capturas de la auditoría si siguen en el scratchpad; si no, con capturas de `main` tomadas antes de instalar la rama. Reducir capturas a JPEG ≤ 1000 px antes de mirarlas.
- [ ] **Step 6: Commit** `feat(ui): home status panel, single health banner, search in the top bar`.

### Task 5: Inicio, filtros y lista

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/main/MainScreen.kt`, `ui/main/Strings.kt`, `ui/Nav.kt` (`onClearFilters`)
- Modify: `values/strings.xml`, `values-es/strings.xml` (`delivery_*` sin ✓/✗)
- Test: `MainScreenTest.kt`, `ScreenshotsTest.kt` (si referencia `deliveryLine`)

**Interfaces:**
- Consumes: T1 (`StatusPill`, `toneOf`), T2 (`EmptyState`, `InsetDivider`), T3 (`groupByDay`, `DayKind`), T4 (`onClearFilters`).
- Produces:
  - `deliveryChipText(chip)` devuelve texto sin glifos: `delivery_sent` = `%1$s %2$d`, `delivery_sent_nocode` = `%1$s`, `delivery_error` = `%1$s %2$d`, `delivery_error_nocode` = `%1$s` (en y es); `delivery_queued`, `delivery_config`, `delivery_canceled` sin cambios.
  - `@Composable fun DeliveryPills(chips: List<DeliveryChipUi>, fallback: StatusLabel)`: `FlowRow` con una `StatusPill` por entrega (`toneOf(chip.status)`, texto `deliveryChipText`); sin entregas, una píldora con `toneOf(label.kind)` y `statusText(label)`.
  - `@Composable internal fun rowTime(millis: Long, kind: DayKind): String`: hora corta para TODAY/YESTERDAY, fecha corta para OTHER (locale de `LocalConfiguration`).
  - `deliveryLine` se elimina.

- [ ] **Step 1: Tests que fallan** (reescriben `rowRendersDeliveryLine` y `deliveryLineCoversErrorConfigAndCanceled`; `rowShowsLabel`, `rowShowsPreviewWhenTextIsVisible`, `hideTextKeepsSenderAndHidesBody`, `filterChipsAreShownAndReportTheChoice`, `emptyResultOfASearchSaysNoMatch`, `destinationChipsFilter` se conservan y se adaptan):
  - `rowRendersOnePillPerDelivery`: chips `Main SENT 204` y `T2 QUEUED` → nodos con texto `str(delivery_sent, "Main", 204)` y `str(delivery_queued, "T2")`, cada uno por separado.
  - `pillsCoverErrorConfigAndCanceled`: los tres textos visibles por separado.
  - `rowWithoutDeliveriesShowsStatusPill`: fila sin entregas con `RECOVERED 200` → `status_recovered_code` visible.
  - `rowsAreGroupedUnderDayHeaders`: filas de hoy y de ayer → `main_day_today` y `main_day_yesterday` visibles. (El estado de test pasa `receivedAt` relativo a `System.currentTimeMillis()`; `MainScreen` usa el reloj real para agrupar.)
  - `longSenderKeepsTimeVisible`: remitente de 60 caracteres → la hora de la fila `assertExists()` y el remitente tiene `maxLines = 1` (comprobar que su texto existe y que el nodo de la hora está dentro de los límites de la raíz).
  - `filterRowHasStatusAndDestinationChips`: con dos destinos, `main_filter_queued` y "T2" en la misma fila desplazable.
  - `emptyStateWhileFilteringOffersClearFilters`: `query = "x"`, sin filas → `main_no_results` y `main_clear_filters`; pulsar → `onClearFilters` llamado.
  - `emptyStateWithoutFilterHasNoClearButton`: `main_empty` visible, `main_clear_filters` no existe.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.MainScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §2 puntos 4–6. Fila: padding vertical 12 dp, línea 1 = remitente (`titleSmall`, `maxLines = 1`, ellipsis, `weight(1f)`) + hora; línea 2 = vista previa (`bodyMedium`, `onSurfaceVariant`, `maxLines = 1`), oculta con `hideText`; línea 3 = `DeliveryPills`. `InsetDivider` entre filas. Cabecera de día: `labelLarge`, `onSurfaceVariant`, `stickyHeader` no (se mantiene simple: `item`). Fila de filtros: una `Row` con `horizontalScroll`, chips de estado, `VerticalDivider` de 24 dp de alto y chips de destino cuando hay más de uno. `onClearFilters` en `Nav.kt` llama a `setQuery("")`, `setFilter(ALL)`, `setDestinationFilter(null)`. Iconos de `EmptyState`: `Icons.Filled.Search` al filtrar, `ic_tab_home` (de T11; hasta entonces `Icons.Filled.Email`) sin filtrar.
- [ ] **Step 4:** → PASS; `ScreenshotsTest` compila y pasa.
- [ ] **Step 5: Medir** en emulador claro/oscuro y a 360 dp (`adb shell wm size 720x1600` con densidad 320 → 360 dp; restaurar con `wm size reset`): fila de chips desplazable sin cortes, filas de 2–3 líneas.
- [ ] **Step 6: Commit** `feat(ui): home rows with delivery pills, day headers and empty state`.

### Task 6: Detalle

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/ui/detail/DetailScreen.kt`, strings
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/DetailScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`StatusPill`, `toneOf`), T2 (`SectionTitle`, `DestructiveButton`), T5 (`deliveryChipText`).
- Produces: nada nuevo para otras tareas.

- [ ] **Step 1: Tests que fallan:** `headerShowsSenderAndStatusPill` (remitente y `statusText` de su etiqueta), `destinationsSectionListsEachDeliveryWithPill` (título `detail_destinations`, nombre de cada entrega y su píldora), `retryOnlyWhenItApplies` (estado con error → botón de reintentar existe; enviado → no existe), `deleteIsDestructiveAndStillConfirms` (pulsar borrar abre el diálogo de confirmación de hoy; confirmar llama `onDelete`), `attemptsSectionShowsHttpCode` (título `detail_attempts` y el código en una píldora). Los tests existentes que miran textos se adaptan.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DetailScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §4. Cuerpo del mensaje en `Card` `surfaceContainerLow` con `SelectionContainer`. Acciones en una `Row` (`FlowRow` si no caben a 360 dp): Reintentar `Button`, Reenviar `OutlinedButton`, Borrar `DestructiveButton`. Píldora de intento: tono OK para 2xx, ERROR para el resto, NEUTRAL sin código.
- [ ] **Step 4:** → PASS. Usar `assertExists()` donde el viewport de Robolectric corte.
- [ ] **Step 5: Medir** en emulador claro/oscuro, SMS con dos entregas (una en error).
- [ ] **Step 6: Commit** `feat(ui): detail header, destinations and attempts with status pills`.

### Task 7: Listas: Destinos, Reglas, Recuperar

**Files:**
- Modify: `ui/destinations/DestinationsScreen.kt`, `ui/rules/RulesScreen.kt`, `ui/recovery/RecoveryScreen.kt`, `ui/Nav.kt` (FAB extendido si vive en `AppShell`/`Nav`), strings
- Test: `DestinationsScreenTest.kt`, `RulesScreenTest.kt`, `RecoveryScreenTest.kt`

**Interfaces:**
- Consumes: T1, T2 (`EmptyState`, `InsetDivider`, `BottomActionBar`).

- [ ] **Step 1: Tests que fallan:**
  - Destinos: `errorCountIsAnErrorPill` (fila con 3 errores → texto plural `destination_error_count` con 3); `emptyStateAddOpensEditor` (lista vacía → `EmptyState` con `action_add`; pulsar → misma lambda que el FAB); `fabIsExtendedWithAddText` (nodo con texto `destination_add` o `action_add`, el que se elija, y click → callback).
  - Reglas: `noDestinationsIsAnErrorPill` (`rule_no_destinations` visible); `emptyStateAddOpensEditor`.
  - Recuperar: `alreadySavedRowShowsNeutralPill` (`recovery_already_saved` visible en una píldora y la fila no seleccionable, como hoy); `sendSelectedIsPinnedAtBottom` (el botón existe y está fuera del `LazyColumn`: comprobar que sigue visible tras `performScrollToIndex` al final).
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DestinationsScreenTest' --tests 'dev.smsforwarder.ui.RulesScreenTest' --tests 'dev.smsforwarder.ui.RecoveryScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §5. Interruptor de destino conserva su etiqueta accesible. Filas ya guardadas de Recuperar: textos con `colorScheme.onSurface.copy(alpha = 0.38f)`. FAB: `ExtendedFloatingActionButton(icon = Icons.Filled.Add, text = action_add)`. Las dos versiones de "Enviar seleccionados" (onboarding y Ajustes → Recuperar) usan la misma `BottomActionBar`.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Medir** en emulador claro/oscuro: Destinos con un destino con errores y uno en pausa; Reglas con una sin destinos; Recuperar con filas ya guardadas.
- [ ] **Step 6: Commit** `feat(ui): list pills, inset dividers, empty states and extended FABs`.

### Task 8: Editores de destino y regla

**Files:**
- Modify: `ui/destinations/DestinationEditorScreen.kt`, `ui/destinations/DestinationEditorViewModel.kt` (solo el valor por defecto), `ui/rules/RuleEditorScreen.kt`, strings
- Test: `DestinationEditorScreenTest.kt`, `DestinationEditorViewModelTest.kt`, `RuleEditorScreenTest.kt`

**Interfaces:**
- Consumes: T2 (`BottomActionBar`, `DestructiveButton`).
- Produces: `DestinationEditorState.template` por defecto `TemplateKind.STANDARD_JSON`.

- [ ] **Step 1: Tests que fallan:**
  - `newDestinationDefaultsToStandardJson` (ViewModel: `reset()` para destino nuevo → `template == STANDARD_JSON` y `name == defaultName(STANDARD_JSON)`); revisar los tests existentes que daban por hecho `CUSTOM` y adaptarlos eligiendo `CUSTOM` explícitamente con `chooseTemplate`.
  - `editingKeepsSavedTemplate` (destino guardado como `CUSTOM` sigue en `CUSTOM` al abrirlo).
  - `templateControlSelectsTemplate` (pulsar la opción Telegram → `chooseTemplate(TELEGRAM)`; vale para segmentado o desplegable).
  - `headerRowHasNameValueSecretAndRemove` (una cabecera → sus cuatro controles; borrar → callback).
  - `storedSecretHelperHasLockIcon` (texto existente de "guardado" visible junto a un icono; comprobar el texto).
  - `saveIsPinnedAtBottom` (en ambos editores, el botón Guardar existe tras desplazar al final del formulario).
  - Regla: `matchModeIsSegmented` (pulsar un modo → callback con ese modo; `Role.RadioButton` en los segmentos).
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.DestinationEditor*' --tests 'dev.smsforwarder.ui.RuleEditorScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §6. Antes de decidir el control de plantilla, medir en el emulador a 360 dp (ver "Decisiones"). Método HTTP: el `ExposedDropdownMenuBox` actual con `fillMaxWidth()` y su etiqueta. Cabeceras: `OutlinedCard` por cabecera; "Añadir cabecera" `OutlinedButton` con `Icons.Filled.Add`. Cuerpo y JSON: `textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)`. Ayuda de secreto guardado: `Row` con `Icons.Filled.Lock` 16 dp. Guardar en `BottomActionBar` (`Scaffold.bottomBar`), Probar `OutlinedButton` encima de Guardar en la misma barra; Borrar `DestructiveButton` al final del formulario.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Medir** en emulador claro/oscuro y a 360 dp: editor de destino nuevo, de uno con dos cabeceras, editor de regla.
- [ ] **Step 6: Commit** `feat(ui): editors with segmented choices, header cards and pinned save`.

### Task 9: Ajustes

**Files:**
- Modify: `ui/settings/SettingsScreen.kt`, strings
- Test: `SettingsScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`toneColors(PENDING)`), T2 (`SectionTitle`, `SettingRow`, `SingleChoiceDialog`).

- [ ] **Step 1: Tests que fallan** (adaptan los actuales de radios):
  - `sweepIntervalRowShowsCurrentValueAndOpensDialog`: la fila muestra el valor actual como texto secundario; pulsar → diálogo con todas las opciones; elegir otra → `setSweepInterval(valor)` y el diálogo se cierra. Igual para `stuckAfter`, `retention` y `language` (un test por grupo; un helper común).
  - `themeIsSegmented`: tres segmentos; pulsar Oscuro → `setTheme(ThemeMode.DARK)`.
  - `tokenAlertWarningHasIcon`: el texto de aviso existente visible junto a `Icons.Filled.Info` (comprobar el texto).
  - `maintenanceRowsOpenTheirScreens`: pulsar "Recuperar SMS" → `onOpenRecovery`; la fila de permisos → su callback.
  - `sectionTitlesAreHeadings`: al menos un nodo `heading` por sección.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.SettingsScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §7. Los interruptores (reenvío, ocultar texto, notificar, color dinámico) quedan como `ListItem` con `Switch` en `trailingContent` y `toggleable` en la fila.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5: Medir** en emulador claro/oscuro y a 360 dp (segmentado de tema). Altura total de Ajustes antes/después anotada (objetivo: menos de dos pantallas).
- [ ] **Step 6: Commit** `feat(ui): compact settings with value rows and choice dialogs`.

### Task 10: Onboarding

**Files:**
- Modify: `ui/onboarding/OnboardingScreen.kt`, strings
- Test: crear `app/src/test/kotlin/dev/smsforwarder/ui/OnboardingScreenTest.kt` (hoy solo hay test del ViewModel)

**Interfaces:**
- Consumes: T1 (`StatusPill`), T2 (`BottomActionBar`).

- [ ] **Step 1: Tests que fallan:**
  - `showsStepProgress`: paso 3 de 8 → texto `onboarding_progress` con 3 y 8.
  - `permissionStatusIsAPill`: permiso concedido → píldora con el texto de estado actual de "concedido"; denegado → el de "denegado". (Usar las claves que el paso de permisos ya usa.)
  - `connectionResultIsAPill`: resultado OK y resultado de error → sus textos actuales visibles.
  - `continueIsPinnedAndAdvances`: pulsar Continuar → callback de avanzar.
  - `noBackArrow`: no existe nodo con la descripción de volver.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.OnboardingScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §8. `LinearProgressIndicator(progress = { step / total.toFloat() })` con el texto encima. Título `headlineSmall`. Acciones del paso como `FilledTonalButton` a todo el ancho en columna. Tono de la prueba de conexión: OK si pasa, ERROR si falla, PENDING mientras prueba.
- [ ] **Step 4:** → PASS; `OnboardingViewModelTest` sin cambios.
- [ ] **Step 5: Medir** en emulador claro/oscuro los pasos de permisos y conexión (borrar datos de la app debug para volver al onboarding: `adb -s emulator-5554 shell pm clear <paquete debug>`).
- [ ] **Step 6: Commit** `feat(ui): onboarding progress, status pills and pinned continue`.

### Task 11: Menú, exportar, Acerca de e iconos de pestañas

**Files:**
- Modify: `ui/AppShell.kt`, `ui/transfer/TransferDialogs.kt`, `ui/about/AboutScreen.kt`
- Create: `res/drawable/ic_upload.xml`, `ic_download.xml`, `ic_tab_home.xml`, `ic_tab_home_filled.xml`, `ic_tab_rules_filled.xml`, `ic_tab_destinations_filled.xml`
- Modify: `ui/main/MainScreen.kt` (icono del estado vacío, si en T5 quedó el provisional)
- Test: `AppShellTest.kt`, `TransferDialogsTest.kt`, `AboutScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`toneColors(PENDING)`), T2 (`SettingRow`).

- [ ] **Step 1: Tests que fallan:**
  - `menuItemsKeepOrderAndCallbacks`: Exportar, Importar, Acerca de, en ese orden, cada uno con su callback (el test actual, adaptado si mira solo texto).
  - `exportWarningAppearsOnlyWhenChecked`: casilla desmarcada → texto de aviso existente no existe; marcar → existe.
  - `aboutLinkOpensCode`: el enlace al código es texto pulsable con `Role.Button`/`LinkAnnotation` y dispara el mismo callback que hoy.
  - `aboutDiagnosticsIsARow`: pulsar la fila de diagnóstico → callback de exportar diagnóstico.
  - `selectedTabUsesFilledIcon`: no se prueba en Robolectric (icono no inspeccionable con sentido); se comprueba en la captura de T12.
- [ ] **Step 2:** `--tests 'dev.smsforwarder.ui.AppShellTest' --tests 'dev.smsforwarder.ui.TransferDialogsTest' --tests 'dev.smsforwarder.ui.AboutScreenTest'` → FAIL.
- [ ] **Step 3:** Implementar spec §9 y §10. Menú: `leadingIcon` = `ic_upload` (exportar), `ic_download` (importar), `Icons.Outlined.Info`; `HorizontalDivider` antes de Acerca de. Aviso de exportar: `Row` con `Icons.Filled.Warning` y texto en `toneColors(PENDING).onContainer` sobre su container. Pestañas: icono relleno si `selected`, contorno si no.
- [ ] **Step 4:** → PASS; `IconTest` y `NavTest` en verde.
- [ ] **Step 5: Medir** en emulador claro/oscuro: menú abierto, diálogo de exportar marcado, Acerca de, barra inferior con cada pestaña seleccionada.
- [ ] **Step 6: Commit** `feat(ui): menu icons, export warning, about rows and tab icon pairs`.

### Task 12: Verificación de conjunto y cierre

**Files:**
- Modify: `app/build.gradle.kts` (`versionName "2.1.0"`, `versionCode` +1), `README.md` (capturas si las enlaza), `docs/superpowers/specs/2026-11-04-visual-redesign-design.md` (línea de estado: implementado)
- Test: suite completa

- [ ] **Step 1:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test :app:testDebugUnitTest` → BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew :app:assembleDebug :app:lintDebug` → sin errores nuevos de lint.
- [ ] **Step 3: Capturas** en el emulador de todas las pantallas (Inicio con y sin avisos, Detalle, Destinos, editor de destino, Reglas, editor de regla, Ajustes, Onboarding en permisos y conexión, Recuperar, Acerca de, menú, diálogo de exportar), en claro y oscuro, con color dinámico encendido y apagado. Comparación contra las capturas de la auditoría delegada a un subagente sonnet que devuelve solo el veredicto por pantalla.
- [ ] **Step 4: Casos de Review Focus 2:** `font_scale 1.3` a 360 dp en Inicio, Ajustes y editor de destino; restaurar `font_scale 1.0` y `wm size reset`. Anotar el resultado.
- [ ] **Step 5: TalkBack rápido:** con `android-ui`, comprobar que el botón de barrido, la lupa, el contador de errores y las píldoras tienen texto o descripción.
- [ ] **Step 6:** `android-emu-stop`.
- [ ] **Step 7: Revisión OCR** de la rama contra `c06432d` (skill `ocr-review`). Corregir High/Medium en commits pequeños.
- [ ] **Step 8: Commit** `chore: 2.1.0 visual redesign` y presentar al usuario las capturas antes/después. El push y la release esperan su OK.

---

## Orden y dependencias

T1 → T2 → T3 → T4 → T5 son secuenciales (T4 y T5 tocan `MainScreen.kt` y `Nav.kt`). Después de T5, T6, T7, T8, T9, T10 y T11 tocan ficheros distintos y solo dependen de T1–T2 (T11 toca `MainScreen.kt` solo para el icono del estado vacío; si se ejecuta en paralelo, ese cambio pasa a T12). `strings.xml` lo tocan casi todas: en ejecución paralela, cada tarea añade sus claves al final de su bloque y los conflictos se resuelven al integrar. T12 al final.
