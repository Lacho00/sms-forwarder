# v2 entrega 1 (1.1.0): icono, navegación, ajustes e idiomas. Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La app instalada trae icono propio, barra inferior Inicio/Ajustes, Ajustes por grupos, búsqueda y filtros en Inicio, borrado, Acerca de y textos en español e inglés, sin tocar cómo se reciben y envían los SMS.

**Architecture:** Las políticas nuevas (enums de ajustes, filtro de búsqueda, etiqueta de estado sin texto) viven en `:core` y se prueban en JVM. `SettingsRepository` guarda los ajustes nuevos en DataStore. La UI sigue siendo Compose con navegación manual; se añade un `Scaffold` con `NavigationBar` solo para las pantallas de primer nivel. Los textos pasan a `strings.xml` (`values/` inglés, `values-es/`).

**Tech Stack:** Kotlin, Compose Material 3, Room, DataStore, WorkManager, Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), entrega 1 (§3 a §7, §14, §15). Plan de v1 en [2026-10-17-sms-forwarder.md](2026-10-17-sms-forwarder.md).

## Global Constraints

- Reglas de v1 que siguen: solo https; sin servicio en primer plano; sin `fallbackToDestructiveMigration`; copias de seguridad desactivadas; nada de textos de SMS, remitentes reales ni nombres de proyectos del autor en código, tests, strings ni docs.
- `minSdk = 31`. Tests con `robolectric.properties` `sdk=35`. JDK 17 local: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. `:app` solo se verifica en CI (`gh run watch`).
- Comportamiento que NO cambia: recepción, barrido, cola, reintentos, `localKey`, formato `received_at` (`date_sent`), avisos de MIUI.
- Cambios visuales (colores, tipografía, espaciado, tamaños): el color dinámico sigue siendo el valor por defecto. Cualquier valor visual nuevo que no esté en la spec se consulta al usuario. Todo cambio de etiqueta o clases se mide antes y después (`getComputedStyle` no aplica a Compose: se comparan capturas del mismo estado en el teléfono antes y después, en claro y oscuro).
- Tests de `:app` que comparan texto de interfaz: usar `context.getString(R.string.x)` (Robolectric corre en inglés, `values/`); nunca literales en español ni inglés.
- Cada tarea deja `:core:test` y la compilación y tests de `:app` en verde (CI) al terminar: ninguna tarea cambia un tipo y deja a sus usuarios para otra.
- Revisión de código: solo OCR (`ocr delegate rule`), nunca los revisores genéricos. Sin líneas de atribución a Claude en commits ni PR.
- Push y secretos: los pide el usuario o los autoriza por tarea.

## Review Focus

Entradas o condiciones que la spec implica y ningún test de v1 cubre; cada una tiene su test en la tarea que se indica:

1. SMS borrado desde Inicio que sigue en la bandeja: el barrido no lo vuelve a reenviar (T8).
2. Reenvío pausado: un SMS que llega se guarda en `QUEUED`, sin contar intentos ni mandar nada, y se envía al reanudar (T3).
3. Retención de "para siempre": no borra nada; con 30 días solo borra `SENT`, nunca `QUEUED` ni `ERROR` (T3).
4. Búsqueda con espacios, mayúsculas y caracteres de regex (`(`, `*`) escritos en el cuadro: se trata como texto literal y no revienta (T2).
5. Cambiar de idioma con la app abierta no pierde el estado de navegación ni deja el aviso de notificación en el otro idioma (T4).

---

### Task 1: Icono de la app

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `app/src/main/res/values/ic_launcher_background.xml` (`<color name="ic_launcher_background">` índigo), `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml`
- Create: `docs/icon/icon.svg` (fuente del dibujo, sin datos personales)
- Modify: `app/src/main/AndroidManifest.xml` (`android:icon`, `android:roundIcon`)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/IconTest.kt`

**Interfaces:**
- Produces: `R.mipmap.ic_launcher`, `R.mipmap.ic_launcher_round`, `R.drawable.ic_launcher_monochrome` (lo usa T9 para Acerca de).

- [ ] **Step 1: Dibujar `docs/icon/icon.svg`** (108x108 dp, zona segura 66 dp central): burbuja de SMS delantera con flecha de reenviar de trazo redondeado y una burbuja tenue detrás, boceto "3, copia que sale" de `.superpowers/brainstorm/*/content/`, fondo índigo.
- [ ] **Step 2: Mostrar el SVG al usuario** en el navegador del panel (versión grande, squircle, circular y monocroma) y esperar su OK. No avanzar sin él (es un cambio visual).
- [ ] **Step 3: Escribir el test que falla**

```kotlin
@Test fun launcherIconIsDeclared() {
    val info = context.packageManager.getApplicationInfo(context.packageName, 0)
    assertNotEquals(0, info.icon)
    assertNotEquals(0, info.roundIcon)
}
```

- [ ] **Step 4: Ejecutar** `./gradlew :app:testDebugUnitTest --tests '*IconTest'` en CI; esperado: FAIL (icon == 0).
- [ ] **Step 5: Convertir el SVG a `VectorDrawable`** (primer plano y monocromo) y declarar el icono adaptativo (`<adaptive-icon>` con `<background>`, `<foreground>`, `<monochrome>`) en los dos `mipmap-anydpi`; añadir `android:icon`/`android:roundIcon` al `<application>`.
- [ ] **Step 6: Commit** `feat(icon): add adaptive launcher icon`.

### Task 2: Políticas nuevas en `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Prefs.kt`, `core/src/main/kotlin/dev/smsforwarder/core/SmsSearch.kt`
- Modify: `core/src/main/kotlin/dev/smsforwarder/core/QueuePolicy.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/PrefsTest.kt`, `SmsSearchTest.kt`, `QueuePolicyTest.kt` (ya existe, ampliar)

**Interfaces:**
- Produces:
  - `enum class SweepInterval(val minutes: Long) { M15(15), M30(30), M60(60) }`
  - `enum class StuckAfter(val hours: Long) { H1(1), H6(6), H24(24) }` con `val millis: Long`
  - `enum class Retention(val days: Long?) { D30(30), D90(90), D365(365), FOREVER(null) }` con `fun cutoff(now: Long): Long?` (null para `FOREVER`)
  - `enum class Language(val tag: String?) { SYSTEM(null), ES("es"), EN("en") }`
  - `enum class NotifyKind { RECOVERED, STUCK, PERMISSIONS }`
  - `enum class StatusFilter { ALL, QUEUED, ERROR }`
  - `object SmsSearch { fun matches(sender: String, body: String, query: String): Boolean }`
  - `QueuePolicy.isStuck(status, lastHttpCode, firstSeenAt, now, stuckAfterMs: Long = STUCK_AFTER_MS)`
  - `data class StatusLabel(val kind: StatusKind, val attempts: Int, val httpCode: Int?)`, `enum class StatusKind { QUEUED, ERROR, SENT, RECOVERED, MANUAL }`, `fun statusLabelOf(status, origin, attempts, lastHttpCode): StatusLabel`. La función `statusLabel(...): String` actual se conserva sin tocar hasta T5, que migra a sus usuarios (`MainViewModel`, `MainScreen`, `DetailScreen`) y la borra

- [ ] **Step 1: Tests que fallan**, con estas aserciones:
  - `Retention.D30.cutoff(now) == now - 30 * 86_400_000L`, `Retention.FOREVER.cutoff(now) == null`, `Retention.D90.cutoff(now) == now - QueuePolicy.RETENTION_MS`.
  - `StuckAfter.H6.millis == QueuePolicy.STUCK_AFTER_MS`; `isStuck(QUEUED, null, 0, 2 * 3_600_000, StuckAfter.H1.millis) == true`.
  - `SmsSearch.matches("OPERADOR", "Saldo 10", "opera")`, `matches("A B", "x", "ab")` es true (ignora mayúsculas y espacios en remitente), `matches("A", "Saldo (10)", "(10)")` es true, `matches("A", "x", "(")` es false sin lanzar, `matches("A", "x", "")` es true.
  - `statusLabelOf(QUEUED, RECEIVER, 2, null) == StatusLabel(StatusKind.QUEUED, 2, null)`; `SENT` con `SWEEP` da `RECOVERED`; con `MANUAL` da `MANUAL`; `ERROR` con 401 conserva `httpCode = 401`.
- [ ] **Step 2: Ejecutar** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`; esperado: FAIL de compilación.
- [ ] **Step 3: Implementar.** `SmsSearch` busca con `contains(ignoreCase = true)` sobre el cuerpo y sobre el remitente normalizado con `SenderFilter.normalize` contra la consulta normalizada; nada de regex.
- [ ] **Step 4: Ejecutar** `:core:test`; esperado: PASS, y los tests de `statusLabel` (String) siguen pasando sin cambios.
- [ ] **Step 5: Commit** `feat(core): add settings policies, search and structured status label`.

### Task 3: Ajustes nuevos, pausa, intervalo y retención

**Files:**
- Modify: `app/.../data/SettingsRepository.kt`, `app/.../work/WorkScheduler.kt`, `app/.../work/SendWorker.kt`, `app/.../work/SweepWorker.kt`, `app/.../data/SmsRepository.kt`, `app/.../AppGraph.kt`
- Test: `SettingsRepositoryTest.kt`, `SendWorkerTest.kt`, `SmsRepositoryTest.kt` (ampliar)

**Interfaces:**
- Consumes: enums de T2.
- Produces en `SettingsRepository` (todos con valor por defecto igual al comportamiento de v1):
  - `val forwardingEnabled: Flow<Boolean>` (true); `suspend fun isForwardingEnabled(): Boolean`; `suspend fun setForwardingEnabled(v: Boolean)` (al pasar a true reencola con el `scheduler` todos los `queuedIds()`, como `saveCredentials`)
  - `val sweepInterval: Flow<SweepInterval>` (M15), `suspend fun setSweepInterval(v: SweepInterval)`
  - `val stuckAfter: Flow<StuckAfter>` (H6), `suspend fun stuckAfterNow(): StuckAfter`, `setStuckAfter`
  - `val retention: Flow<Retention>` (D90), `suspend fun retentionNow(): Retention`, `setRetention`
  - `val hideText: Flow<Boolean>` (false), `setHideText`
  - `val dynamicColor: Flow<Boolean>` (true), `setDynamicColor`
  - `val language: Flow<Language>` (SYSTEM), `setLanguage`
  - `fun notifyEnabled(kind: NotifyKind): Flow<Boolean>` (true), `suspend fun isNotifyEnabled(kind: NotifyKind): Boolean`, `suspend fun setNotifyEnabled(kind: NotifyKind, v: Boolean)`
- `WorkScheduler.schedulePeriodicSweep(context: Context, interval: SweepInterval = SweepInterval.M15, update: Boolean = false)`; con `update = true` usa `ExistingPeriodicWorkPolicy.UPDATE`.
- `SmsRepository.purgeOld(retention: Retention = Retention.D90)` (el valor por defecto mantiene el `SmsRepositoryTest` de v1); con `FOREVER` no hace nada; con otro plazo borra `SENT` anteriores al corte (T8 añade las filas con `deletedAt`).
- `SmsRepository.stuckToNotify(stuckAfterMs: Long = QueuePolicy.STUCK_AFTER_MS)`; `AppGraph.runSweepAlerts` (`AppGraph.kt:50`) lo llama con `settings.stuckAfterNow().millis`.
- Mientras `forwardingEnabled` es false, `runSweepAlerts` no consulta `stuckToNotify`: un SMS en pausa no es un SMS atascado.
- `MainUiState` añade `paused: Boolean`; la línea de estado de Inicio muestra "Reenvío en pausa" (recurso de T5) cuando es true.
- `suspend fun sweepIntervalNow(): SweepInterval`.

- [ ] **Step 1: Tests que fallan:**
  - `SettingsRepositoryTest.defaultsMatchV1`: `forwardingEnabled` true, `sweepInterval` M15, `stuckAfter` H6, `retention` D90, `notifyEnabled(STUCK)` true.
  - `setForwardingEnabled(true)` tras `false` llama al `scheduler` con los ids en cola (lista registrada, como el test de `saveCredentials`).
  - `SendWorkerTest.pausedForwardingKeepsSmsQueued`: con `forwardingEnabled=false` y un SMS `QUEUED`, el worker devuelve `Result.success()`, el servidor simulado recibe 0 peticiones, `attempts` sigue en 0 y el estado en `QUEUED`.
  - Pausa de extremo a extremo: con `forwardingEnabled=false` llega un SMS por el receptor (queda `QUEUED`, 0 peticiones); `setForwardingEnabled(true)` lo reencola y el worker lo envía: 1 petición.
  - `SmsRepositoryTest.purgeOldForeverKeepsEverything`, `purgeOld30DaysDeletesOnlySentOlder`: con filas `SENT` de 40 días, `QUEUED` de 40 días y `ERROR` de 40 días, solo se borra la primera.
- [ ] **Step 2: Ejecutar** los tests en CI; esperado: FAIL.
- [ ] **Step 3: Implementar** las claves de DataStore y las funciones; `SendWorker.doWork` lee `isForwardingEnabled()` dentro del `try` existente (un fallo de DataStore reintenta, no cae) y devuelve `Result.success()` si está pausado: no pierde nada porque reanudar y cada barrido reencolan los `QUEUED`. `SweepWorker` pasa `retentionNow()` a `purgeOld`. Quien programa el barrido (`MainActivity.onCreate`, `BootReceiver`) pasa el intervalo guardado (`sweepIntervalNow()`), para que un trabajo perdido no vuelva a 15 min en silencio; el cambio en vivo (T7) usa `update = true`.
- [ ] **Step 4: Ejecutar** los tests; esperado: PASS.
- [ ] **Step 5: Commit** `feat(settings): pause, sweep interval, stuck window, retention and toggles`.

### Task 4: Infraestructura de idiomas

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/Localized.kt`, `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`, `app/.../ui/MainActivity.kt`, `app/.../notify/Notifier.kt`, `app/.../data/SettingsRepository.kt`, `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§15)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/LocalizedTest.kt`

**Interfaces:**
- Produces:
  - `object Localized { fun wrap(base: Context, tag: String?): Context; fun savedTag(context: Context): String?; fun saveTag(context: Context, tag: String?) }`. `wrap` devuelve `base` si `tag == null`; si no, `base.createConfigurationContext` con `Locale.forLanguageTag(tag)`.
  - `savedTag`/`saveTag` usan SharedPreferences `ui_prefs`, clave `lang`: espejo síncrono del ajuste, necesario porque `attachBaseContext` no puede esperar a DataStore.
  - `SettingsRepository.setLanguage` llama a `Localized.saveTag`.
- `MainActivity.attachBaseContext(newBase)` aplica `Localized.wrap(newBase, Localized.savedTag(newBase))`. Al cambiar el idioma, la actividad se recrea (`recreate()`); la pila de navegación sobrevive porque `AppNavHost` ya la guarda con `rememberSaveable`.
- `Notifier` resuelve sus textos con `Localized.wrap(context, Localized.savedTag(context))` en cada `post`, y `Notifier.refreshChannel()` vuelve a llamar a `ensureChannel` con el nombre localizado del canal al cambiar el idioma.
- Desviación de la spec §15 (decisión razonada): no se usa `LocaleManager`/`AppCompatDelegate`, porque con `minSdk 31` obligarían a `AppCompatActivity` y a su tema en Android 12. Esta tarea actualiza §15.

- [ ] **Step 1: Tests que fallan:** `wrap(ctx, "en")` y `wrap(ctx, "es")` devuelven textos distintos para `R.string.about_title`; `wrap(ctx, null)` devuelve el mismo contexto. Review Focus 5: con `ActivityScenario.launch(MainActivity)`, tras `setLanguage(ES)` y `recreate()` la pila de navegación es la misma, y `Notifier.stuck` publica el título en español.
- [ ] **Step 2: Ejecutar** en CI; esperado: FAIL.
- [ ] **Step 3: Implementar** lo anterior. `values/strings.xml` es el inglés; `values-es/` el español. Añadir `android:localeConfig` no hace falta (el selector es propio).
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(i18n): language wrapper and settings mirror`.

### Task 5: Extraer todos los textos a recursos

Mecánica; delegable a sonnet con la lista de ficheros.

**Files:**
- Modify: `app/.../ui/main/MainScreen.kt`, `ui/detail/DetailScreen.kt`, `ui/onboarding/OnboardingScreen.kt` y su `ViewModel`, `ui/recovery/RecoveryScreen.kt` y su `ViewModel`, `ui/settings/SettingsScreen.kt` y su `ViewModel`, `ui/Nav.kt`, `notify/Notifier.kt`, `ui/main/MainViewModel.kt`, `res/values/strings.xml`, `res/values-es/strings.xml`
- Test: `MainScreenTest`, `DetailScreenTest`, `SettingsScreenTest`, `NotifierTest`, `OnboardingViewModelTest`, `RecoveryViewModelTest` (ajustar)

**Interfaces:**
- Consumes: `StatusLabel`/`StatusKind`/`statusLabelOf` de T2.
- Produces: `RowUi.label` y el estado del detalle pasan a `StatusLabel`; `@Composable fun statusText(label: StatusLabel): String` (en `ui/main/Strings.kt`; `pluralStringResource` para "intentos"); `issueTitle(issue: HealthIssue)` pasa a `@Composable` con `stringResource`. Al final de la tarea se borra `statusLabel(...): String` de `core` y su test viejo.

- [ ] **Step 1: Cambiar primero los tests** para que comparen contra `context.getString(R.string.x)` o `stringResource`, no contra literales en español; y que `formatDateTime`/`agoLabel` usen recursos (`ago_*`). Verlos fallar en CI.
- [ ] **Step 2: Extraer** cada literal de usuario a `strings.xml` (inglés por defecto) y su traducción al español, con las cadenas actuales del código como texto en español, sin reescribirlas. Los plurales ("intento/intentos", "SMS recuperados") usan `<plurals>`.
- [ ] **Step 3: Verificar** que no quedan literales: `grep -rnE '"[A-ZÁÉÍÓÚa-záéíóú][^"]{3,}"' app/src/main/kotlin --include=*.kt` solo devuelve claves, rutas y logs; sin literales visibles.
- [ ] **Step 4: Ejecutar** todos los tests en CI; esperado: PASS.
- [ ] **Step 5: Commit** `refactor(i18n): move all user-visible text to string resources`.

### Task 6: Estructura de navegación (barra inferior y menú ⋮)

**Files:**
- Create: `app/.../ui/AppShell.kt`
- Modify: `app/.../ui/Nav.kt`, `app/.../ui/MainActivity.kt`, `gradle/libs.versions.toml` y `app/build.gradle.kts` (añadir `androidx.compose.material:material-icons-core`; comprobar que la BOM instalada lo gestiona y, si no, fijar versión)
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/AppShellTest.kt`

**Interfaces:**
- Produces:
  - `object Routes` añade `ABOUT = "about"`; `Routes.MAIN` y `Routes.SETTINGS` pasan a ser pestañas de primer nivel.
  - `@Composable fun AppShell(current: String, title: String, onTab: (String) -> Unit, onAbout: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit)`: `Scaffold` con `NavigationBar` de dos destinos (Inicio, Ajustes) y `TopAppBar` con `actions` (T8 pone ahí la búsqueda) y el menú ⋮ (solo "Acerca de"). Solo envuelve `MAIN` y `SETTINGS`; detalle, recuperación, onboarding y Acerca de van a pantalla completa.
  - La pila de `AppNavHost` en una pestaña es exactamente `main` o `main|settings`. Tocar una pestaña fija la pila a esa forma (no apila). `Nav.kt:86` y `:90` (`navigate(Routes.SETTINGS)` desde un aviso) pasan a la misma operación. `SettingsScreen.onBack` deja de existir: atrás desde `main|settings` lo resuelve el `BackHandler` existente.

- [ ] **Step 1: Tests que fallan:** `AppShellTest` con Robolectric y Compose: en `MAIN` existen los nodos con las descripciones `R.string.tab_home` y `R.string.tab_settings`; tocar la segunda llama a `onTab(Routes.SETTINGS)`; el menú ⋮ muestra `R.string.about_title` y no tiene entrada de exportar (llega en la entrega 4); en onboarding no hay barra. Test de pila: desde `main`, un aviso interno y luego la pestaña Inicio dejan la pila en `main`, no en `main|settings|main`.
- [ ] **Step 2: Ejecutar** en CI; esperado: FAIL.
- [ ] **Step 3: Implementar** `AppShell` y enrutar `Nav.kt`. `MainScreen` y `SettingsScreen` pierden su propio `Scaffold` de barra superior de v1 (el engranaje de texto desaparece). Título de barra: nombre de la app en Inicio, "Ajustes" en Ajustes.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(ui): bottom navigation and overflow menu`.

### Task 7: Ajustes por grupos

**Files:**
- Modify: `app/.../ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `ui/Nav.kt`, `ui/MainActivity.kt`, `ui/theme/Theme.kt`
- Test: `SettingsScreenTest.kt`, nuevo `SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: ajustes y enums de T3; `AppShell` de T6.
- Produces en `SettingsViewModel`: `val prefs: StateFlow<SettingsPrefs>` donde `data class SettingsPrefs(val forwarding: Boolean, val sweepInterval: SweepInterval, val stuckAfter: StuckAfter, val retention: Retention, val hideText: Boolean, val notify: Map<NotifyKind, Boolean>, val themeMode: ThemeMode, val dynamicColor: Boolean, val language: Language)`; y `setForwarding`, `setSweepInterval`, `setStuckAfter`, `setRetention`, `setHideText`, `setNotify(kind, v)`, `setDynamicColor`, `setLanguage`. El `ViewModel` no llama a `recreate()` ni a `WorkManager`: su `Factory` recibe `onSweepIntervalChanged: (SweepInterval) -> Unit` y `onLanguageChanged: () -> Unit`, que `MainActivity` cablea a `schedulePeriodicSweep(..., update = true)` y a `recreate()`.

Orden de grupos (spec §7): Conexión, Remitentes, Reenvío, Historial, Notificaciones, Apariencia, Mantenimiento.

- [ ] **Step 1: Tests que fallan:**
  - Se muestran las siete cabeceras de grupo, en ese orden (por `R.string`).
  - El grupo Mantenimiento tiene los botones de recuperación y de permisos/MIUI; tocar cada uno llama a su callback (`onOpenRecovery`, `onOpenSystemIssue`).
  - Apagar el interruptor de reenvío llama a `setForwarding(false)`.
  - Elegir 30 min en barrido llama a `setSweepInterval(M30)`.
  - El interruptor del aviso de token rechazado no existe como ajuste.
  - El token sigue sin mostrarse nunca (test de v1 conservado).
- [ ] **Step 2: Ejecutar** en CI; esperado: FAIL.
- [ ] **Step 3: Implementar.** Selecciones de lista con `SingleChoiceSegmentedButtonRow` o diálogo de radio (lo que ya use el selector de tema de v1; reutilizarlo). Cambiar el intervalo llama a `WorkScheduler.schedulePeriodicSweep(ctx, v, update = true)` desde `MainActivity`. Cambiar idioma llama a `recreate()` tras guardar. Apagar color dinámico hace que `SmsForwarderTheme(mode, dynamicColor)` use `lightColorScheme()`/`darkColorScheme()` (nuevo parámetro `dynamicColor: Boolean = true`).
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(settings): grouped settings screen`.

### Task 8: Inicio con búsqueda, filtros, ocultar texto y borrado

**Files:**
- Modify: `app/.../data/SmsEntity.kt` (`val deletedAt: Long? = null`), `data/AppDatabase.kt` (versión 2 + `MIGRATION_1_2`), `data/SmsDao.kt`, `data/SmsRepository.kt`, `ui/main/MainViewModel.kt`, `MainScreen.kt`, `ui/detail/DetailScreen.kt`, `DetailViewModel.kt`, `ui/MainActivity.kt`, `AppGraph.kt`, `work/SendWorker.kt`, `app/build.gradle.kts`; esquemas `app/schemas/dev.smsforwarder.data.AppDatabase/1.json` y `2.json`
- Test: `SmsRepositoryTest.kt`, `SendWorkerTest.kt`, `MainScreenTest.kt`, `DetailScreenTest.kt`, nuevos `MigrationTest.kt` y `MainViewModelTest.kt`

**Interfaces:**
- Consumes: `SmsSearch`, `StatusFilter` (T2); `hideText` (T3).
- Produces:
  - `MIGRATION_1_2`: `ALTER TABLE sms ADD COLUMN deletedAt INTEGER`. La base se construye con `.addMigrations(MIGRATION_1_2)`.
  - `SmsRepository.softDelete(id: Long)` (pone `deletedAt = clock.now()`), `observeAll()` excluye las borradas.
  - `SmsRepository.purgeOld` también elimina las borradas anteriores al corte, cualquiera que sea su estado.
  - `MainViewModel`: `fun setQuery(q: String)`, `fun setFilter(f: StatusFilter)`; `MainUiState` añade `query: String`, `filter: StatusFilter`, `hideText: Boolean`; `RowUi.label: StatusLabel`.
  - `DetailViewModel.delete()`: llama a `softDelete` y cancela el trabajo único `send-$id` en WorkManager (lambda `cancelSend: (Long) -> Unit`).

- [ ] **Step 0: Fijar el esquema 1.** `app/schemas/` está vacío. Antes de tocar la entidad, hacer que CI genere `1.json` desde el código actual (subirlo como artefacto, bajarlo con `gh run download`) y commitearlo. Añadir `testImplementation(libs.room.testing)` y `android.sourceSets["test"].assets.srcDir("$projectDir/schemas")` a `app/build.gradle.kts`.
- [ ] **Step 1: Tests que fallan:**
  - `MigrationTest`: una base v1 con 2 SMS migra a v2 con los datos intactos y `deletedAt` nulo (usar `MigrationTestHelper` con el esquema 1 ya commiteado).
  - Review Focus 1: `SmsRepositoryTest.deletedSmsIsNotReinsertedBySweep`: tras `softDelete`, `Sweeper` con el mismo SMS en la bandeja no crea fila nueva ni encola envío (la fila sigue existiendo con su `localKey`).
  - Review Focus 1 completo: tras `softDelete` de un `QUEUED`: `queuedIds()` no lo incluye; `SweepWorker` corre y hace 0 peticiones; `SendWorker` sobre su id devuelve `Result.success()` sin petición; `idsWithError401()` no incluye una fila `ERROR 401` borrada; `stuckToNotify()` no la incluye; los contadores `queued` y `errors` de Inicio no la cuentan.
  - `MainViewModelTest`: con tres SMS, `setQuery("etec")` deja solo los de ese remitente; `setFilter(ERROR)` deja solo los `ERROR`; las dos a la vez se combinan (Y).
  - `MainScreenTest`: con `hideText = true` el cuerpo no aparece en la fila.
  - `DetailScreenTest`: el botón "Borrar" pide confirmación y, al confirmar, llama a `onDelete`.
- [ ] **Step 2: Ejecutar** en CI; esperado: FAIL.
- [ ] **Step 3: Implementar.** `AND deletedAt IS NULL` en `observeAll`, `idsWithStatus`, `idsWithStatusAndCode` y `unsentNotNotified`; **no** en `findByLocalKey` ni `candidates`, para que la deduplicación siga viendo la fila borrada. `SendWorker` devuelve `Result.success()` si `deletedAt != null`. Filtrado en memoria sobre el flujo de `observeAll()` (la lista está acotada por la retención). Búsqueda con un icono en el slot `actions` de `AppShell` (T6) que abre un campo; chips de filtro bajo la línea de estado (Todos, En cola, Error). "Mostrar" en el detalle revela el cuerpo aunque `hideText` esté activo.
- [ ] **Step 4: Ejecutar**; esperado: PASS. Añadir a CI `git diff --exit-code app/schemas` tras compilar: un esquema sin commitear rompe el build.
- [ ] **Step 5: Commit** `feat(home): search, filters, hide text and soft delete`.

### Task 9: Acerca de

**Files:**
- Create: `app/.../ui/about/AboutScreen.kt`
- Modify: `app/.../ui/Nav.kt`, `res/values*/strings.xml`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/AboutScreenTest.kt`

**Interfaces:**
- Produces: `@Composable fun AboutScreen(versionName: String, onOpenLink: (String) -> Unit, onBack: () -> Unit)`. Muestra icono (T1), nombre, versión, enlace al código (`https://github.com/Lacho00/sms-forwarder`), licencia (el repo no tiene `LICENSE`: el usuario elige la licencia antes de empezar esta tarea, y la tarea la añade como fichero) y lista de bibliotecas (AndroidX, Compose, Room, WorkManager, OkHttp, kotlinx.serialization) con su licencia, escrita a mano en recursos.

- [ ] **Step 1: Test que falla:** se ve la versión recibida y el enlace; tocar el enlace llama a `onOpenLink` con la URL del repo.
- [ ] **Step 2: Ejecutar** en CI; esperado: FAIL.
- [ ] **Step 3: Implementar.** `versionName` sale de `PackageManager.getPackageInfo`. Abrir enlaces con `Intent.ACTION_VIEW`, capturando `ActivityNotFoundException`.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(about): about screen`.

### Task 10: Avisos que se pueden apagar

**Files:**
- Modify: `app/.../notify/Notifier.kt`, `app/.../AppGraph.kt`, `app/.../work/SendWorker.kt`
- Test: `NotifierTest.kt`, `SendWorkerTest.kt`, nuevo `AppGraphAlertsTest.kt`

**Interfaces:**
- Consumes: `NotifyKind`, `isNotifyEnabled` (T2, T3). Métodos reales de `Notifier`: `recovered`, `stuck`, `health`, `tokenRejected`; ninguno es `suspend`.
- Produces: el filtrado ocurre en los puntos de llamada, no dentro de `Notifier`, para no cambiar su constructor (`Notifier(context, zone)` de los tests sigue compilando):
  - `AppGraph.onRecovered` consulta `isNotifyEnabled(RECOVERED)` antes de `notifier.recovered`.
  - `AppGraph.runSweepAlerts`: `STUCK` apagado salta el bucle de `stuckToNotify`; `PERMISSIONS` apagado quita de `issues` solo `SMS_PERMISSION`, `NOTIFICATION_PERMISSION`, `BATTERY_OPTIMIZED`, `BACKGROUND_RESTRICTED`, `AUTOSTART_OFF` y `MIUI_SMS_BLOCKED`; `TOKEN_REJECTED`, `NOT_CONFIGURED` y `NO_SENDERS` se notifican siempre. `setLastNotifiedIssues` guarda el conjunto sin filtrar, para que no reaparezca al volver a encender el aviso.
  - El aviso inmediato de `400` (hoy `SendWorker.kt:51` llama a `notifier.stuck`) pasa a `Notifier.rejected(item: SmsEntity)` con el mismo texto y no se puede apagar.

- [ ] **Step 1: Tests que fallan:** con `RECOVERED` apagado, `onRecovered` no publica; con `STUCK` apagado un SMS atascado de 7 h no publica pero un `ERROR 400` sí (por `rejected`); con `PERMISSIONS` apagado `SMS_PERMISSION` no publica pero `TOKEN_REJECTED` sí; volver a encender el aviso de permisos no repite lo ya avisado.
- [ ] **Step 2: Ejecutar** en CI; esperado: FAIL.
- [ ] **Step 3: Implementar** según las interfaces.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(notify): per-kind notification toggles`.

### Task 11: Cierre de la entrega 1.1.0

**Files:**
- Modify: `docs/manual-test-checklist.md`, `README.md`, `docs/superpowers/specs/2026-11-02-v2-redesign.md` (estado de la entrega)

- [ ] **Step 1: Ampliar la lista manual** con: icono en el cajón (claro, oscuro, temático), cambio de idioma sin perder la pantalla, pausa y reanudación, borrar un SMS que sigue en la bandeja y esperar un barrido, intervalo de barrido (`adb shell dumpsys jobscheduler | grep dev.smsforwarder`), apagar el color dinámico, ocultar texto.
- [ ] **Step 2: Revisión OCR** del diff completo (`ocr delegate rule`, subagente opus) y corregir hallazgos Medium o superiores.
- [ ] **Step 3: Comparar capturas** de Inicio y Detalle antes (v1.0.0) y después, en claro y oscuro, reducidas a JPEG de 1000 px; enseñárselas al usuario y esperar su OK. Cualquier diferencia no listada en la spec se corrige.
- [ ] **Step 4: Push y CI** (con permiso del usuario por tarea): `git push origin main`, `gh run watch`.
- [ ] **Step 5: Release.** `git tag v1.1.0 && git push origin v1.1.0` (con permiso). Instalar con `adb install -r` (actualiza sin perder datos, misma firma) y recorrer la lista manual en el teléfono. Los fallos son tareas nuevas.
- [ ] **Step 6: Commit** de docs `docs: v1.1.0 checklist and status`.
