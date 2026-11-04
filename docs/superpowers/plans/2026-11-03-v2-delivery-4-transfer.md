# v2 entrega 4 (2.0.0): exportar, importar, diagnóstico y Acerca de. Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El usuario exporta sus destinos y reglas a un fichero JSON versionado (sin secretos por defecto), lo importa en otro teléfono o en el mismo sin perder nada de lo que ya tiene, y exporta desde Acerca de un diagnóstico de texto que no contiene ningún dato personal. Cierra v2 con un repaso de casos hostiles y la release 2.0.0.

**Architecture:** El formato del fichero, su validación, el plan de importación (renombrado de duplicados) y el informe de diagnóstico son puros y viven en `:core`. En `:app`, `ConfigTransfer` exporta e importa contra Room y el almacén de secretos en una sola transacción; `EngineLog` guarda los últimos eventos del motor en un fichero propio (sin Room); `DiagnosticsCollector` junta contadores, permisos y eventos para el informe. La UI añade Exportar e Importar al menú ⋮ con el Storage Access Framework (`CreateDocument`/`OpenDocument`, sin permisos nuevos) y un botón de diagnóstico en Acerca de. Antes de todo, un receptor solo de debug siembra destinos y reglas para que `scripts/e2e-emulator.sh` pruebe el envío en el emulador sin tocar la UI.

**Tech Stack:** Kotlin, Compose Material 3, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization (solo `JsonElement`, sin plugin), Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), entrega 4 (§3, §5 menú ⋮, §8 regex agotado "anotado en el diagnóstico", §13, §14, §16, §17). Plan anterior: [entrega 3](2026-11-03-v2-delivery-3-tabs.md); ledgers con lo diferido: `.superpowers/sdd/2026-11-03-v2-delivery-3-tabs/progress.md` y `.superpowers/sdd/2026-11-02-v2-delivery-2-model/progress.md`.

**Rama:** `v2-delivery-4` desde `main` @ `80161d7` (1.3.0 publicada). Worktree `/srv/http/sms-forwarder-wt/v2-d4`; el clon principal se queda en `main`. La revisión OCR de cierre compara contra `80161d7`.

## Decisiones de este plan

Escrito sin el usuario delante (pidió trabajo autónomo). Todo lo que no fija la spec es **(mi decisión)** y se revisa al cerrar la entrega.

- **(mi decisión) Registro de eventos en fichero, sin Room.** `EngineLog` guarda las últimas 500 líneas en `noBackupFilesDir/engine-events.log`. Motivo: los eventos son desechables y una migración 3→4 en la release que cierra v2 añade riesgo sin beneficio. **No hay migración de esquema**: Room sigue en la versión 3.
- **(mi decisión) Formato del fichero:** objeto JSON con `"format": "sms-forwarder/config"`, `"version": 1`, `"app"` (versión que lo escribió, informativo), `"secrets"` (informativo), `"destinations"` y `"rules"`. Cada destino lleva una `"key"` (`"d<id>"`) y las reglas apuntan a esas claves. Motivo: los ids no sirven entre teléfonos.
- **(mi decisión) Versión del formato:** `version` mayor que la conocida → fichero no válido ("creado por una versión más nueva"); campos desconocidos se ignoran; un valor de enum desconocido invalida el fichero. Motivo: compatible hacia delante sin adivinar valores.
- **(mi decisión) Sin secretos** = sin URL (siempre cifrada, §12) y sin valores de cabeceras secretas; los nombres de esas cabeceras sí se exportan. Al importar, un destino sin URL se crea sin nada en el almacén de secretos: cuenta como "sin credenciales", `HealthIssue.NOT_CONFIGURED` avisa si una regla activa lo usa, y su fila en Destinos lo marca. Sus cabeceras secretas se descartan y el resumen las nombra para que el usuario las vuelva a añadir. Motivo: no hay dónde guardar una cabecera secreta sin valor sin tocar el almacén ni el esquema.
- **(mi decisión) El campo `secrets` no manda:** cada destino decide por sí mismo (URL presente o no). Motivo: un fichero editado a mano no puede dejar la app a medias.
- **(mi decisión) Diálogo de exportar:** casilla "Incluir URL, tokens y cabeceras secretas" desmarcada al abrir, con aviso al marcarla; botones Exportar y Cancelar. Motivo: "no" por defecto sin que el botón principal cambie de sentido.
- **(mi decisión) Menú ⋮:** tres entradas, Exportar, Importar y Acerca de, en ese orden. Motivo: la spec agrupa "Exportar/Importar"; dos entradas evitan una pantalla intermedia.
- **(mi decisión) Renombrado:** sufijo ` (2)`, ` (3)`… comparando nombres sin espacios a los lados y sin distinguir mayúsculas, contra los vivos y contra los ya planificados del mismo fichero. También para reglas, aunque la spec solo lo pide para destinos. Motivo: dos reglas con el mismo nombre confunden igual.
- **(mi decisión) Límites del fichero:** 1 MiB, 100 destinos, 500 reglas, 50 cabeceras por destino; BOM UTF-8 inicial admitido. Motivo: un fichero hostil no puede bloquear la app.
- **(mi decisión) Importar conserva `enabled`** de destinos y reglas tal como vienen. Motivo: es una copia; quien exportó algo en pausa lo quiere en pausa.
- **(mi decisión) Importar es atómico también con el Keystore:** filas, enlaces y secretos se escriben dentro de una transacción de Room (como `DestinationRepository.update`); si guardar un secreto falla, la transacción se deshace y se borran los secretos ya escritos para esos ids. Motivo: "un fichero inválido no cambia nada" se extiende a un fallo a medias.
- **(mi decisión) Exportar sin secretos también lee el almacén** (para los nombres de cabeceras secretas); un error transitorio del Keystore aborta la exportación con su mensaje y no escribe nada. El texto se construye antes de abrir el selector de fichero y se descarta al escribirlo o al cancelar; nunca va al estado guardado ni al log.
- **(mi decisión) Completar un destino sin credenciales lo pone en marcha:** `DestinationRepository.update` devuelve también los SMS con entrega `QUEUED` a ese destino cuando antes no tenía secretos, y quien llama los programa con `kickSends`. Motivo: sin esto esperan el backoff de WorkManager (hasta horas).
- **(mi decisión) Diagnóstico:** destinos por id y tipo de plantilla, nunca por nombre (el nombre lo escribe el usuario y puede llevar cualquier cosa). Errores agrupados por `http_<código>`, `network`, `config_<motivo>`, `destination_deleted` u `other`: el `lastError` que viene del servidor (`errorCodeOf`) nunca se copia. Últimos 50 eventos. Fabricante y modelo sí (pesan en MIUI).
- **(mi decisión) Nombres de fichero:** `sms-forwarder-config-<AAAA-MM-DD>.json` y `sms-forwarder-diagnostic-<AAAA-MM-DD>.txt`. Tipos MIME al abrir: `application/json`, `text/*`, `application/octet-stream` (algunos gestores marcan así el JSON).
- **(mi decisión) Acerca de:** flecha de volver (`Icons.AutoMirrored.Filled.ArrowBack`, la de los editores) en vez del botón de texto "Atrás", y lo mismo en Detalle y Recuperar para que todas las pantallas completas vuelvan igual; icono recortado en círculo (`CircleShape`) en vez del cuadrado. Botón "Exportar diagnóstico" `OutlinedButton`, como los de Detalle.
- **(mi decisión) Receptor de siembra solo en debug**, exportado y protegido con `android.permission.DUMP` (lo tiene `adb shell`, no una app normal). Motivo: `am broadcast` desde el script sin abrir la superficie a otras apps.
- **(mi decisión) El script e2e** usa `https://httpbin.org/anything` (200) y `https://httpbin.org/status/403` por defecto, cambiables por variables de entorno; se niega a correr si el serial no es `emulator-*`.

### Pendiente de OK visual

Se aplican con componentes y estilos que ya usa la app; el usuario los revisa en las capturas de la T9 y cualquier cambio es un commit pequeño:

1. Menú ⋮ con Exportar, Importar y Acerca de (`DropdownMenuItem` solo texto).
2. Diálogo de exportar: `AlertDialog` con fila `Checkbox` + texto; aviso al marcar con `MaterialTheme.colorScheme.error` (como el aviso de regla sin destinos).
3. Resumen de importación, fichero no válido y resultado: `AlertDialog` con texto desplazable.
4. Fila de destino sin credenciales: etiqueta "Sin credenciales" en el mismo sitio y estilo que "En pausa".
5. Flecha de volver en Acerca de, Detalle y Recuperar.
6. Icono de Acerca de recortado en círculo.
7. "Exportar diagnóstico" como `OutlinedButton` con una línea de resultado `bodyMedium` debajo.

## Global Constraints

- Reglas de v1 que siguen: **solo https**; **sin servicio en primer plano** ni notificación fija; **sin `fallbackToDestructiveMigration`**; **copias de seguridad desactivadas**; nada de textos de SMS reales, remitentes reales, teléfonos ni nombres de proyectos del autor en código, tests, strings ni docs (ejemplos: `AAA`, `TEST`, `https://h.example`).
- Esquema de Room: sigue en versión 3. Si una tarea necesitara tocarlo, lleva migración 3→4 explícita, `4.json` commiteado y test en `MigrationTest`; CI falla con esquema sin commitear.
- **Sin permisos nuevos** en el manifiesto de release (SAF no los pide). El manifiesto de release no contiene `SeedReceiver` ni `DiagReceiver`.
- Todo texto visible nuevo va en `values/strings.xml` (inglés) y `values-es/strings.xml`; los tests de `:app` comparan con `context.getString(R.string.x)`, nunca con literales.
- `minSdk = 31`. Navegación manual (`Nav.kt`), sin `navigation-compose`. ViewModels con `ViewModelProvider.Factory` creados en `MainActivity`.
- JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. `:app` corre en local (`./gradlew :app:testDebugUnitTest --tests ...`, con red) y CI es la referencia. Robolectric `sdk=35`, `TestApp`; `WorkManagerTestInitHelper` antes de lanzar actividades.
- La URL, el token, las cabeceras secretas, el cuerpo y el remitente del SMS, el texto exportado y los borradores nunca se escriben en el log, en `toString()` ni en el informe de diagnóstico.
- Plantillas y sustitución sin cambios de comportamiento (`Templates.render`); el destino migrado manda byte a byte lo mismo que 1.3.0.
- Reencolar siempre con `WorkScheduler.kickSends` (nunca sustituye un trabajo `RUNNING`).
- **Diseño:** lo que la spec no fija se hace con componentes Material 3 y estilos ya presentes, y va a "Pendiente de OK visual"; nada de colores ni medidas a mano.
- Cada tarea deja `:core:test`, compilación, lint y tests de `:app` en verde (CI).
- Revisión de código: solo OCR (`ocr delegate rule`). Sin líneas de atribución a Claude en commits ni PR. Push a `origin v2-delivery-4` autorizado; nunca `main` ni etiquetas (los hace el usuario).

## Review Focus

1. Importar un fichero válido cuando el Keystore falla al guardar el segundo secreto: no queda ningún destino, regla ni secreto nuevo (T3, `secretFailureRollsBackEverything`).
2. El diagnóstico con un SMS de cuerpo y remitente reconocibles, un destino con token en la URL, una cabecera secreta y un `lastError` del servidor con una URL dentro: ninguno aparece en el texto (T5, `reportLeaksNothing`).
3. Nombres que chocan solo por mayúsculas o espacios (`"Main "` frente a `"main"`), y duplicados dentro del propio fichero: todos acaban distintos (T2, `renamesCaseAndSpaceInsensitive`, `renamesDuplicatesInsideFile`).
4. Fichero hostil: JSON anidado 10 000 niveles, más de 1 MiB, tipos cambiados, enum desconocido, regla que apunta a una clave que no existe: `Invalid` sin excepción y sin tocar la base (T2, `hostileFilesAreInvalid`; T3, `invalidFileChangesNothing`).
5. Destino importado sin credenciales con una regla activa: el SMS queda en cola sin enviarse, avisa `NOT_CONFIGURED`, y al guardar la URL en el editor se programa enseguida (T3, `completingCredentialsKicksQueued`).

---

### Task 1: Siembra por broadcast en debug y `scripts/e2e-emulator.sh`

Lo diferido de la entrega 3 (`task-10-fix-brief.md`): probar el envío de punta a punta en el emulador sin manejar la UI.

**Files:**
- Create: `app/src/debug/kotlin/dev/smsforwarder/diag/SeedCommands.kt`, `app/src/debug/kotlin/dev/smsforwarder/diag/SeedReceiver.kt`, `scripts/e2e-emulator.sh`
- Modify: `app/src/debug/AndroidManifest.xml`, `README.md` (una línea en la sección de desarrollo)
- Test: `app/src/testDebug/kotlin/dev/smsforwarder/diag/SeedCommandsTest.kt`

**Interfaces:**
- Consumes: `DestinationRepository.create`, `RuleRepository.create`, `DestinationPresets.standardJson/ntfy/custom`, `RuleDraft`, `SettingsRepository.setSweepFloor/setOnboardingDone` (lo mismo que `OnboardingViewModel.finish`).
- Produces:
  - `data class SeedResult(val ok: Boolean, val data: String)`; `internal class SeedCommands(graph: AppGraph) { suspend fun run(extras: Map<String, String>): SeedResult }`.
  - Operaciones (`op`): `done` (onboarding terminado, suelo = ahora) → `"ok"`; `destination` (`template` = `standard|ntfy|custom`, `name`, `url`, `token` solo para `standard`, `body` y `json` = `true|false` solo para `custom`, `enabled` = `true|false`, por defecto `true`) → `"id=<n>"`; `rule` (`name`, `sender`, `mode` = `exact|contains|regex`, por defecto `exact`, `body_mode` = `contains|not_contains|regex`, `body`, `sim` = `any|1|2`, `destinations` = ids separados por comas) → `"id=<n>"`; `status` → `"sms queued=<a> sent=<b> error=<c> deliveries queued=<d> sent=<e> error=<f> canceled=<g>"`. Error: `ok = false`, `data = "error=<NOMBRES>"` (los de `DestinationError`/`RuleError`, o `bad_op`, `bad_arg`).
  - `SeedReceiver`: acción `dev.smsforwarder.debug.SEED`, `goAsync()`, timeout 9 s, `setResult(if (ok) 0 else 1, data, null)`. Nunca escribe en el log URL, token ni cuerpo.
- [ ] **Step 1: Tests que fallan** en `SeedCommandsTest` (Robolectric, `FakeDestinationSecretStore` en `graph.destinationSecretStore`):
  - `seedsStandardDestinationAndRule`: `destination` con `https://h.example/x` y token `t` → `id=1`; `rule` `sender=AAA destinations=1` → `id=…`; `activeRules()` contiene la regla hacia 1; `secrets(1)?.url == "https://h.example/x"`.
  - `httpUrlRejected`: `url=http://h.example` → `ok == false`, `data` contiene `URL_NOT_HTTPS`, ningún destino.
  - `invalidRegexRejected`: `mode=regex sender=(` → `data` contiene `SENDER_REGEX_INVALID`.
  - `unknownOpFails`: `op=x` → `error=bad_op`.
  - `statusCountsOnly`: tras ingerir un SMS de cuerpo `SECRET-BODY` y remitente `AAA`, `status` no contiene `SECRET-BODY` ni `AAA` y dice `sms queued=1`.
  - `doneMarksOnboarding`: `onboardingDone.first() == true` y `sweepFloor() != null`.
- [ ] **Step 2: Ejecutar** `./gradlew :app:testDebugUnitTest --tests '*SeedCommandsTest'`; esperado: FAIL (no compila).
- [ ] **Step 3: Implementar.** `SeedReceiver` pasa los extras a `Map<String, String>` y delega en `SeedCommands`; manifiesto debug: `<receiver android:name=".diag.SeedReceiver" android:exported="true" android:permission="android.permission.DUMP">` con el `intent-filter` de la acción. Contadores de `status` con consultas de recuento existentes o nuevas `@Query` en los DAO (la T5 las reutiliza: `SmsDao.countsByStatus(): List<StatusCount>` y `DeliveryDao.countsByStatus(): List<DeliveryStatusCount>`, solo SMS sin `deletedAt`).
- [ ] **Step 4: Escribir `scripts/e2e-emulator.sh`** (`bash`, `set -euo pipefail`): `ANDROID_SERIAL` por defecto `emulator-5554` y salida 2 si no empieza por `emulator-`; `E2E_OK_URL` (`https://httpbin.org/anything`) y `E2E_FAIL_URL` (`https://httpbin.org/status/403`); `--no-install` salta `./gradlew :app:installDebug`. Pasos: `pm clear dev.smsforwarder.debug`; `pm grant` de `RECEIVE_SMS`, `READ_SMS`, `POST_NOTIFICATIONS`; `am broadcast -a dev.smsforwarder.debug.SEED -n dev.smsforwarder.debug/dev.smsforwarder.diag.SeedReceiver --es op ...` para `done`, un destino `custom` que funciona, otro que falla, y una regla `TEST` hacia ambos; `adb emu sms send TEST "e2e <epoch>"`; sondear `op=status` cada 3 s hasta 90 s, esperando `sms` en error con `deliveries sent=1 error=1`; imprimir `PASS`/`FAIL` y salir 0/1. Los destinos `custom` se siembran con `body` `{"b":"{body}"}` y `json=true`.
- [ ] **Step 5: Ejecutar** el test (PASS); `shellcheck scripts/e2e-emulator.sh` si está instalado; `./gradlew :app:processReleaseMainManifest` y `grep -rc 'SeedReceiver\|DiagReceiver' $(find app/build/intermediates -path '*release*' -name AndroidManifest.xml)` → todo 0.
- [ ] **Step 6: En el emulador:** `android-emu`, `scripts/e2e-emulator.sh`; esperado `PASS`; `android-emu-stop` al acabar.
- [ ] **Step 7: Commit** `test(e2e): debug seed receiver and emulator script`.

### Task 2: Formato del fichero, validación y plan de importación en `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/ConfigFile.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/ConfigFileTest.kt`

**Interfaces:**
- Consumes: `DestinationDraft`, `HeaderDraft`, `RuleDraft`, `validateDestination`, `validateRule`, enums de `Templates.kt` y `Rules.kt`.
- Produces:
  - `const val CONFIG_FORMAT = "sms-forwarder/config"`, `CONFIG_VERSION = 1`, `CONFIG_MAX_BYTES = 1_048_576`, `CONFIG_MAX_DESTINATIONS = 100`, `CONFIG_MAX_RULES = 500`, `CONFIG_MAX_HEADERS = 50`.
  - `data class ConfigDestination(val key: String, val draft: DestinationDraft)` (`draft.url == null` = sin credenciales; cabecera secreta con `value == null` = sin valor).
  - `data class ConfigRule(val draft: RuleDraft, val destinationKeys: List<String>)` (`draft.destinationIds` vacío).
  - `data class ConfigFile(val destinations: List<ConfigDestination>, val rules: List<ConfigRule>, val includesSecrets: Boolean)` con `toString()` sin URL ni valores de cabecera.
  - `enum class ConfigError { TOO_LARGE, NOT_JSON, WRONG_FORMAT, NEWER_VERSION, BAD_VALUE, TOO_MANY, DUPLICATE_KEY, UNKNOWN_DESTINATION, INVALID_DESTINATION, INVALID_RULE }`.
  - `sealed interface ConfigParse { data class Ok(val file: ConfigFile); data class Invalid(val error: ConfigError, val item: String?) }` (`item` = nombre del destino o regla culpable si se pudo leer).
  - `object ConfigCodec { fun encode(file: ConfigFile, appVersion: String): String; fun decode(text: String): ConfigParse }`.
  - `data class PlannedDestination(val key: String, val draft: DestinationDraft, val originalName: String, val withoutCredentials: Boolean, val droppedSecretHeaders: List<String>)`; `data class PlannedRule(val draft: RuleDraft, val originalName: String, val destinationKeys: List<String>)`; `data class ImportPlan(val destinations: List<PlannedDestination>, val rules: List<PlannedRule>)` con `val renamed: List<Pair<String, String>>` y `val withoutCredentials: List<String>`.
  - `fun planImport(file: ConfigFile, takenDestinationNames: Collection<String>, takenRuleNames: Collection<String>): ImportPlan`; `fun uniqueName(name: String, taken: Set<String>): String`.
- [ ] **Step 1: Tests que fallan** en `ConfigFileTest`:
  - `roundTripWithSecrets`: un JSON estándar, un Telegram y un ntfy con dos reglas → `decode(encode(f))` da `Ok` igual a `f`.
  - `withoutSecretsHasNoUrlNorSecretValues`: `encode` de un fichero con `url = null` y cabecera secreta `value = null` → el texto no contiene `https://h.example` ni `Bearer`, sí `"Authorization"`; `"secrets":false`.
  - `versionAndFormatChecked`: `"version":2` → `NEWER_VERSION`; sin `format` o con otro → `WRONG_FORMAT`; campo extra desconocido → `Ok`.
  - `everythingValidated`: URL `http://h.example` → `INVALID_DESTINATION` con `item` = su nombre; `{foo}` en el cuerpo → `INVALID_DESTINATION`; regex `"("` → `INVALID_RULE`; cabecera pública con `value` null → `BAD_VALUE`; `"method":"PATCH"` → `BAD_VALUE`.
  - `rulesPointAtKeys`: regla con clave inexistente → `UNKNOWN_DESTINATION`; dos destinos con la misma clave → `DUPLICATE_KEY`; regla sin destinos → `Ok` (se conserva inactiva, spec §8).
  - `hostileFilesAreInvalid`: 10 000 `[` → `NOT_JSON` sin excepción (capturar también `StackOverflowError`); texto de `CONFIG_MAX_BYTES + 1` bytes → `TOO_LARGE` sin parsear; 101 destinos → `TOO_MANY`; `"destinations":"x"` → `BAD_VALUE`; `"enabled":"yes"` → `BAD_VALUE`; cadena vacía → `NOT_JSON`; BOM inicial → `Ok`.
  - `renamesCaseAndSpaceInsensitive`: tomados `["main"]`, fichero con `"Main "` → `"Main (2)"`; tomados `["Main", "Main (2)"]` → `"Main (3)"`; `renamed == [("Main ", "Main (2)")]`.
  - `renamesDuplicatesInsideFile`: dos destinos `"A"` en el fichero, nada tomado → `"A"` y `"A (2)"`; igual con reglas.
  - `withoutCredentialsDropsSecretHeaders`: destino sin URL con cabecera secreta `X-Key` → `withoutCredentials`, `droppedSecretHeaders == ["X-Key"]`, el borrador ya no la lleva; las públicas se quedan.
  - `fileToStringHidesSecrets`.
- [ ] **Step 2: Ejecutar** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test --tests '*ConfigFileTest'`; esperado: FAIL.
- [ ] **Step 3: Implementar `ConfigFile.kt`** con `Json.parseToJsonElement` y lectura a mano de `JsonObject` (sin plugin de serialización). Forma de cada destino: `key`, `name`, `enabled`, `template`, `method`, `url` (cadena o `null`), `headers` (`[{"name","value","secret"}]`), `body`, `bodyIsJson`, `successPolicy`; de cada regla: `name`, `enabled`, `senderMode`, `senderPattern`, `bodyMode`, `bodyPattern`, `sim`, `destinations` (claves). Enums por `name`. Validación: `validateDestination(d, urlStored = d.url == null)`; `validateRule(r)` ignorando `NO_DESTINATIONS`. `encode` con `prettyPrint`, claves en orden fijo. `uniqueName` sobre `trim()` y `lowercase()`.
- [ ] **Step 4: Ejecutar** el mismo comando; esperado: PASS.
- [ ] **Step 5: Commit** `feat(core): versioned config file with validation and import plan`.

### Task 3: Exportar e importar contra la base (`ConfigTransfer`)

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/ConfigTransfer.kt`
- Modify: `data/DestinationRepository.kt` (inserción sin secretos dentro de una transacción ajena; `update` devuelve los `QUEUED` si antes no había secretos; `observeLiveWithConfigured`), `data/DestinationDao.kt`, `data/RuleDao.kt`, `AppGraph.kt`
- Test: nuevo `app/src/test/kotlin/dev/smsforwarder/data/ConfigTransferTest.kt`, `DestinationRepositoryTest.kt`

**Interfaces:**
- Consumes: T2 (`ConfigCodec`, `ConfigFile`, `planImport`, `ImportPlan`); `DestinationRepository`, `RuleRepository`, `DestinationSecretStore`, `TransientSecretException`.
- Produces:
  - `sealed interface ImportPreview { data class Ready(val file: ConfigFile, val plan: ImportPlan); data class Invalid(val error: ConfigError, val item: String?) }`.
  - `data class ImportResult(val destinations: Int, val rules: Int, val withoutCredentials: Int)`.
  - `class ConfigTransfer(db: AppDatabase, destinations: DestinationRepository, rules: RuleRepository, secretStore: DestinationSecretStore) { suspend fun export(includeSecrets: Boolean, appVersion: String): String; suspend fun preview(text: String): ImportPreview; suspend fun apply(file: ConfigFile): ImportResult }`; `AppGraph.configTransfer`.
  - `data class DestinationWithConfig(val entity: DestinationEntity, val configured: Boolean)`; `DestinationRepository.observeLiveWithConfigured(): Flow<List<DestinationWithConfig>>` (la usa T6).
- [ ] **Step 1: Tests que fallan** en `ConfigTransferTest` (Room en memoria + `FakeDestinationSecretStore`):
  - `exportWithoutSecrets`: un JSON estándar con token y una regla → `decode` da destino con `url == null`, cabecera `Authorization` secreta sin valor, regla con su clave; el texto no contiene la URL ni el token.
  - `exportWithSecrets`: URL y token presentes; destinos borrados y sus enlaces fuera.
  - `importAddsNeverReplaces`: con un destino `"Main"` existente, importar un fichero con `"Main"` → dos destinos (`"Main"`, `"Main (2)"`); el original intacto (fila y secretos); la regla importada apunta al nuevo.
  - `invalidFileChangesNothing`: `preview` de un fichero con regex inválido → `Invalid(INVALID_RULE, …)`; recuentos de destinos, reglas, enlaces y secretos sin cambio.
  - `importWithoutSecretsIsUnconfigured`: tras `apply`, `secrets(id) == null`, `unconfiguredInUse()` lo contiene, `ImportResult.withoutCredentials == 1`.
  - `secretFailureRollsBackEverything`: almacén falso que lanza en el segundo `set` → `apply` relanza; ningún destino, regla ni secreto nuevo (tampoco el primero).
  - `exportFailsOnTransientKeystore`: `get` lanza `TransientSecretException` → `export` relanza.
  - `applyRenamesAgainstCurrentNames`: un destino `"A"` creado entre `preview` y `apply` → el importado acaba `"A (2)"`.
  - `DestinationRepositoryTest.completingCredentialsKicksQueued`: destino sin secretos con una entrega `QUEUED` → `update` con URL devuelve ese SMS.
  - `DestinationRepositoryTest.observeLiveWithConfiguredFlagsMissingSecrets`.
- [ ] **Step 2: Ejecutar** `./gradlew :app:testDebugUnitTest --tests '*ConfigTransferTest' --tests '*DestinationRepositoryTest'`; esperado: FAIL.
- [ ] **Step 3: Implementar.** `export`: destinos vivos (`DestinationRepository.editable` no sirve, oculta secretos: leer entidad + `secretStore.get`), clave `"d<id>"`, reglas de `RuleRepository.observeAll().first()`. `apply`: dentro de `db.withTransaction`, nombres vivos actuales → `planImport` → insertar filas → enlaces y reglas (mismo recorte que `RuleRepository.create`) → `secretStore.set` de los que tienen URL; ante cualquier excepción, en `NonCancellable` `secretStore.remove` de cada id insertado y relanzar. `update`: si `stored == null` antes y ahora hay URL, añade `deliveries.smsIdsQueuedFor(id)` al resultado.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(data): export and transactional import of destinations and rules`.

### Task 4: Registro de eventos del motor

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/EngineEvent.kt`, `app/src/main/kotlin/dev/smsforwarder/diag/EngineLog.kt`
- Modify: `AppGraph.kt`, `sms/SmsReceiver.kt`, `sms/Sweeper.kt`, `ui/recovery/RecoveryViewModel.kt`, `work/SendWorker.kt`, `data/LegacyImport.kt`, `data/ConfigTransfer.kt`
- Test: `core/.../EngineEventTest.kt`, `app/src/test/kotlin/dev/smsforwarder/diag/EngineLogTest.kt`, `SendWorkerTest.kt`, `SmsReceiverTest.kt`

**Interfaces:**
- Produces:
  - `enum class EngineEventType { RECEIVED, SWEEP, SEND, RULE_TIMEOUT, SAFE_MODE, IMPORT, EXPORT }`.
  - `data class EngineEvent(val at: Long, val type: EngineEventType, val smsId: Long? = null, val destinationId: Long? = null, val ruleId: Long? = null, val httpCode: Int? = null, val code: String? = null, val count: Int? = null)`.
  - `fun EngineEvent.toLine(): String` (`<at> <TYPE> sms=… dest=… rule=… http=… code=… n=…`, campos nulos fuera); `fun parseEngineEvent(line: String): EngineEvent?`; `fun safeCode(s: String?): String?` (`[a-z0-9_]{1,40}` o `"other"`; `null` → `null`).
  - `class EngineLog(file: File, capacity: Int = 500) { suspend fun add(e: EngineEvent); suspend fun recent(limit: Int): List<EngineEvent> }`: `add` nunca lanza salvo cancelación; `AppGraph.engineLog` (fichero `noBackupFilesDir/engine-events.log`).
  - Códigos: `RECEIVED` `inserted|known|no_destination|no_rule`; `SWEEP` `ok|unreadable` con `n` = recuperados; `SEND` `accepted|retry|rejected|invalid|no_secrets` con `http`; `IMPORT`/`EXPORT` `with_secrets|no_secrets` con `n` = destinos; `SAFE_MODE` `legacy_import`.
- [ ] **Step 1: Tests que fallan:**
  - `EngineEventTest.lineRoundTrip`, `unknownLineIsNull`, `safeCodeRejectsFreeText` (`"https://h.example x"` → `"other"`, `"config_template"` → igual).
  - `EngineLogTest.keepsLastCapacity` (capacidad 5, 12 eventos → `recent(10)` devuelve los 5 últimos en orden), `survivesCorruptLine`, `addNeverThrowsOnIoError` (directorio de solo lectura), `concurrentAddsKeepAll` (100 `add` en paralelo con capacidad 500 → 100 líneas).
  - `SendWorkerTest.logsOutcomePerDelivery`: un SMS con una entrega 200 y otra 403 → dos eventos `SEND` con `dest` y `http`, sin cuerpo ni URL en el fichero.
  - `SmsReceiverTest.regexTimeoutLogged`: regla con regex catastrófico → evento `RULE_TIMEOUT` con su id (spec §8).
- [ ] **Step 2: Ejecutar** `:core:test` y las clases de `:app` afectadas; esperado: FAIL.
- [ ] **Step 3: Implementar.** `EngineLog` con `Mutex`; añade una línea y, al pasar de `2 × capacity`, reescribe las últimas `capacity` en un temporal y lo renombra. Los `Log.w(TAG, "rule $it timed out")` se quedan y además van al registro. En `SendWorker`, el evento se escribe después de `recordAttempt`; un fallo del registro no cambia el resultado del trabajo. `ConfigTransfer` recibe `engineLog: EngineLog? = null` como último parámetro (los tests de T3 siguen igual) y registra `IMPORT`/`EXPORT` al terminar bien.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(diag): engine event log`.

### Task 5: Informe de diagnóstico

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Diagnostic.kt`, `app/src/main/kotlin/dev/smsforwarder/diag/DiagnosticsCollector.kt`
- Modify: `data/DeliveryDao.kt` (recuento de errores por código), `AppGraph.kt`
- Test: `core/.../DiagnosticTest.kt`, `app/src/test/kotlin/dev/smsforwarder/diag/DiagnosticsCollectorTest.kt`

**Interfaces:**
- Consumes: T1 (`SmsDao.countsByStatus`, `DeliveryDao.countsByStatus`), T4 (`EngineLog.recent`, `EngineEvent.toLine`), `HealthChecker.check`, `MiuiIntents.autostartState`, `DestinationRepository.observeLiveWithConfigured` (T3).
- Produces:
  - `data class DestinationDiag(val id: Long, val template: TemplateKind, val enabled: Boolean, val configured: Boolean, val counts: Map<DeliveryStatus, Int>)`.
  - `data class DiagnosticInput(val generatedAt: Long, val appVersion: String, val versionCode: Long, val androidRelease: String, val sdkInt: Int, val device: String, val miui: Boolean, val autostart: Boolean?, val issues: List<String>, val forwardingEnabled: Boolean, val sms: Map<SmsStatus, Int>, val destinations: List<DestinationDiag>, val errors: Map<String, Int>, val rules: Int, val activeRules: Int, val events: List<EngineEvent>)`.
  - `fun errorKey(httpCode: Int?, lastError: String?): String` (`http_<n>` si hay código; si no `network`, `destination_deleted`, `config_<a-z_>` tal cual; resto `other`).
  - `fun renderDiagnostic(i: DiagnosticInput): String` (secciones `App`, `Device`, `Health`, `Queue`, `Destinations`, `Errors`, `Rules`, `Events`; horas en ISO UTC).
  - `data class ErrorCodeCount(val lastHttpCode: Int?, val lastError: String?, val n: Int)`, `DeliveryDao.errorCodes(): List<ErrorCodeCount>` (entregas `ERROR` de SMS sin `deletedAt`).
  - `class DiagnosticsCollector(context: Context, graph: AppGraph) { suspend fun collect(): DiagnosticInput; suspend fun report(): String }`; `AppGraph.diagnostics`.
- [ ] **Step 1: Tests que fallan:**
  - `DiagnosticTest.errorKeys`: `(403, "invalid_token")` → `http_403`; `(null, "network")` → `network`; `(null, "config_template")` → igual; `(null, "leak https://h.example")` → `other`.
  - `DiagnosticTest.rendersAllSections` (cada cabecera de sección presente, contadores y eventos).
  - `DiagnosticsCollectorTest.reportLeaksNothing`: SMS de cuerpo `BODY-7f3a` y remitente `AAA-7f3a`, destino custom con URL `https://h.example/tok-7f3a` y cabecera secreta `X-Key: key-7f3a`, una entrega `ERROR` con `lastError = "srv https://h.example/tok-7f3a"` y un evento por cada tipo → `report()` no contiene `7f3a` ni `h.example`; sí `http_` u `other`, `queued`, la versión y `SEND`.
  - `DiagnosticsCollectorTest.countsMatchDatabase`.
- [ ] **Step 2: Ejecutar**; esperado: FAIL.
- [ ] **Step 3: Implementar.** Versión con `PackageManager.getPackageInfo(...).versionName/longVersionCode`; `device = "${Build.MANUFACTURER} ${Build.MODEL}"`; `issues` = nombres de `HealthChecker.check()`; eventos `recent(50)`. Nada del informe sale de `SmsEntity.body`, `sender`, `senderNormalized`, nombres de destino o regla, ni del almacén de secretos (solo `configured`).
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(diag): diagnostic report without personal data`.

### Task 6: Exportar e Importar desde el menú ⋮

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/transfer/TransferViewModel.kt`, `TransferDialogs.kt`, `app/src/main/kotlin/dev/smsforwarder/ui/DocumentIo.kt`
- Modify: `ui/AppShell.kt`, `ui/Nav.kt`, `ui/MainActivity.kt`, `ui/destinations/DestinationsViewModel.kt`, `ui/destinations/DestinationsScreen.kt`, `res/values*/strings.xml`
- Test: nuevos `TransferViewModelTest.kt`, `TransferDialogsTest.kt`; `AppShellTest.kt`, `DestinationsViewModelTest.kt`, `DestinationsScreenTest.kt`

**Interfaces:**
- Consumes: T3 (`ConfigTransfer`, `ImportPreview`, `ImportResult`, `observeLiveWithConfigured`), T2 (`ConfigError`, `ImportPlan`), T4 (`EngineLog` no se toca aquí: lo registra `ConfigTransfer`).
- Produces:
  - `interface DocumentIo { suspend fun write(uri: Uri, text: String); suspend fun read(uri: Uri, maxBytes: Int): String? }` (`null` = más grande que `maxBytes`); `class ContentResolverDocumentIo(resolver: ContentResolver) : DocumentIo`. La reutiliza T7.
  - `sealed interface TransferUi { Idle; AskExport(val includeSecrets: Boolean); PickExportFile(val fileName: String); PickImportFile; Waiting; ImportSummary(val plan: ImportPlan); ImportInvalid(val error: ConfigError, val item: String?); Done(val message: TransferMessage) }` con `toString()` sin secretos.
  - `sealed interface TransferMessage { data object Exported; data class Imported(val result: ImportResult); data object ReadFailed; data object WriteFailed; data object SecretsUnavailable }`.
  - `class TransferViewModel(transfer: ConfigTransfer, io: DocumentIo, appVersion: String, today: () -> LocalDate)`: `state: StateFlow<TransferUi>`, `startExport()`, `setIncludeSecrets(Boolean)`, `confirmExport()`, `pickerLaunched()`, `onExportFile(uri: Uri?)`, `startImport()`, `onImportFile(uri: Uri?)`, `confirmImport()`, `dismiss()` + `Factory`.
  - `AppShell(..., onExport: () -> Unit, onImport: () -> Unit, onAbout: () -> Unit, ...)`.
  - `DestinationRowUi.configured: Boolean`.
- [ ] **Step 1: Tests que fallan:**
  - `TransferViewModelTest.exportDefaultsToNoSecrets`: `startExport()` → `AskExport(false)`; `confirmExport()` → `PickExportFile("sms-forwarder-config-2026-11-03.json")`; `onExportFile(uri)` → `DocumentIo` recibe un texto sin la URL guardada; `Done(Exported)`.
  - `cancelPickerWritesNothing`: `onExportFile(null)` → `Idle`, ninguna escritura.
  - `pickerLaunchedOnce`: tras `pickerLaunched()` el estado es `Waiting` (Nav solo lanza el selector en `Pick*`).
  - `tooLargeFileRejected`: `read` devuelve `null` → `ImportInvalid(TOO_LARGE, null)`.
  - `invalidFileChangesNothing`: fichero con `http://` → `ImportInvalid(INVALID_DESTINATION, nombre)`; base sin cambios.
  - `summaryThenApply`: `onImportFile` → `ImportSummary` con renombrados y sin credenciales; `confirmImport()` → `Done(Imported(...))` y filas nuevas.
  - `stateToStringHidesSecrets`: con `includeSecrets = true`, ningún `toString()` de estados contiene la URL ni el token.
  - `AppShellTest.menuHasExportImportAbout`: el ⋮ muestra `menu_export`, `menu_import`, `about_title` en ese orden y cada uno llama a su callback.
  - `TransferDialogsTest.exportCheckboxStartsOff` (casilla `Off`; al marcarla aparece `export_secrets_warning`), `summaryShowsCountsRenamesAndMissingCredentials` (`getQuantityString(R.plurals.import_summary_destinations, 2, 2)`, `getString(R.string.import_renamed, "Main", "Main (2)")`, `import_without_credentials`), `invalidShowsReason` (`getString(R.string.import_error_newer_version)`).
  - `DestinationsViewModelTest.rowFlagsMissingCredentials`; `DestinationsScreenTest.withoutCredentialsLabel` (`destination_without_credentials`).
- [ ] **Step 2: Ejecutar** las clases afectadas; esperado: FAIL.
- [ ] **Step 3: Implementar.** En `Nav.kt`, `rememberLauncherForActivityResult(CreateDocument("application/json"))` y `OpenDocument()` (`arrayOf("application/json", "text/*", "application/octet-stream")`), lanzados desde un `LaunchedEffect(state)` cuando el estado es `PickExportFile`/`PickImportFile`, seguidos de `pickerLaunched()`. `TransferDialogs(state, vm)` se pinta sobre cualquier pestaña. El texto exportado vive en un campo privado del ViewModel, fuera de `TransferUi`, y se borra al escribir, cancelar o `dismiss()`. `ContentResolverDocumentIo.read` corta en `maxBytes + 1` y lee UTF-8. Strings nuevas (en/es) para menú, diálogo de exportar, resumen, cada `ConfigError`, cada `TransferMessage` y la etiqueta de fila. Copia propuesta: `export_secrets_label` "Include URL, tokens and secret headers" / "Incluir URL, tokens y cabeceras secretas"; `export_secrets_warning` "Anyone with this file can send to your destinations" / "Quien tenga este fichero podrá enviar a tus destinos"; `import_without_credentials` "No URL or token: add them in Destinations" / "Sin URL ni token: añádelos en Destinos"; `destination_without_credentials` "No credentials" / "Sin credenciales".
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(ui): export and import from the overflow menu`.

### Task 7: Acerca de con diagnóstico, flecha de volver e icono redondo

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/about/AboutViewModel.kt`
- Modify: `ui/about/AboutScreen.kt`, `ui/detail/DetailScreen.kt`, `ui/recovery/RecoveryScreen.kt`, `ui/Nav.kt`, `ui/MainActivity.kt`, `res/values*/strings.xml`
- Test: `AboutScreenTest.kt`, `DetailScreenTest.kt`, nuevo `AboutViewModelTest.kt` (y los tests que pulsaban `R.string.back` por texto)

**Interfaces:**
- Consumes: T5 (`DiagnosticsCollector.report`), T6 (`DocumentIo`).
- Produces:
  - `enum class DiagnosticMessage { SAVED, FAILED }`; `data class AboutState(val busy: Boolean, val message: DiagnosticMessage?, val pickFile: String?)`.
  - `class AboutViewModel(diagnostics: DiagnosticsCollector, io: DocumentIo, today: () -> LocalDate) { val state: StateFlow<AboutState>; fun startExport(); fun pickerLaunched(); fun onFile(uri: Uri?) }` + `Factory`.
  - `AboutScreen(versionName: String, state: AboutState, onOpenLink: (String) -> Unit, onExportDiagnostic: () -> Unit, onBack: () -> Unit)`.
- [ ] **Step 1: Tests que fallan:**
  - `AboutViewModelTest.exportWritesReport`: `startExport()` → `pickFile == "sms-forwarder-diagnostic-2026-11-03.txt"`; `onFile(uri)` → `DocumentIo` recibe `report()`; `message == SAVED`; `onFile(null)` → nada escrito, `message == null`; fallo de escritura → `FAILED`.
  - `AboutScreenTest.exportDiagnosticButton` (`about_export_diagnostic` llama al callback; con `SAVED` se ve `about_diagnostic_saved`), `backIsArrow` (`onNodeWithContentDescription(getString(R.string.back))` llama a `onBack`; no hay nodo con texto `back`), `iconIsShown` (el resto de tests actuales siguen).
  - `DetailScreenTest`/`RecoveryScreen` (si tiene test): volver por descripción de contenido.
- [ ] **Step 2: Ejecutar**; esperado: FAIL.
- [ ] **Step 3: Implementar.** `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } }` en las tres pantallas, como en los editores. Icono: `Modifier.clip(CircleShape).background(colorResource(R.color.ic_launcher_background))`. Selector con `CreateDocument("text/plain")` en `Nav.kt`, igual que en T6. Strings nuevas (en/es): `about_export_diagnostic` "Export diagnostics" / "Exportar diagnóstico", `about_diagnostic_hint` "Text file without SMS text, senders, URLs or tokens" / "Fichero de texto sin texto de SMS, remitentes, URLs ni tokens", `about_diagnostic_saved`, `about_diagnostic_failed`.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(about): diagnostic export, back arrow and round icon`.

### Task 8: Repaso de casos hostiles en `:core` (spec §16)

**Files:**
- Create: `core/src/test/kotlin/dev/smsforwarder/core/HostileInputsTest.kt`
- Modify: el código de `:core` que falle (solo si falla)

**Interfaces:**
- Consumes: `Templates.render`, `DestinationPresets`, `RuleEngine`, `validateDestination`, `ConfigCodec` (T2).
- Produces: nada nuevo; si un caso falla, el arreglo va en esta tarea con su test.
- [ ] **Step 1: Leer** `TemplatesTest`, `RulesTest`, `DraftsTest`, `UrlValidatorTest`, `ConfigFileTest` y anotar en el informe qué casos de §16 ya están; no duplicarlos.
- [ ] **Step 2: Tests nuevos** en `HostileInputsTest`, cuerpos `"a\"b"`, `"l1\nl2\r\n"`, `"back\\slash"`, `"{body}{sender}"`, `"  "`, `"\u0000"`, `"ñ€😀"`:
  - `jsonPresetsStayValid`: con JSON estándar, Telegram y un personalizado JSON, el cuerpo pintado parsea y el campo de texto es exactamente el original (Telegram: `"<remitente>: <cuerpo>"`).
  - `ntfyTextBodyIsLiteral`: el cuerpo es `"TEST: " + cuerpo`, sin expandir `{body}` dos veces.
  - `urlVariableCannotChangeHost`: `https://h.example/x?b={body}` con cuerpo `"x@evil.example/#"` → `URI.host == "h.example"`.
  - `headerVariableCannotInject`: `X-S: {body}` con `"a\r\nX-Injected: 1"` → sin CR/LF y solo ASCII.
  - `catastrophicRegexInImportedRule`: un fichero con regla `(a+)+\\1$` importa (patrón válido) y `RuleEngine` lo da como agotado con `"a".repeat(30) + "b"`.
  - `httpVariants`: `HTTP://h.example`, `http://h.example`, `https://`, `ftp://h.example` no se guardan ni se importan.
- [ ] **Step 3: Ejecutar** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`; arreglar lo que falle con el cambio mínimo.
- [ ] **Step 4: Commit** `test(core): hostile inputs across templates, rules and import`.

### Task 9: Cierre de la entrega 2.0.0

**Files:**
- Modify: `docs/manual-test-checklist.md` (apartado `## v2.0.0`), `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 estado, §13, §14 y decisiones de este plan), `README.md` (exportar/importar y diagnóstico en la lista de funciones)

- [ ] **Step 1: Capturas** en el emulador (`android-emu`, APK debug sembrada con `scripts/e2e-emulator.sh --no-install` o a mano): menú ⋮, diálogo de exportar con la casilla marcada y sin marcar, resumen de importación, fichero no válido, Destinos con un destino sin credenciales, Acerca de, en claro y oscuro; JPEG de 1000 px; si son más de tres, comparación con un subagente sonnet. Se guardan en el ledger para la revisión de "Pendiente de OK visual". `android-emu-stop` al acabar.
- [ ] **Step 2: Revisión OCR** de la rama entera contra `80161d7` (`ocr delegate rule`, modelo según el diff; esperable opus por datos y secretos). Corregir High y Medium, cada uno con su test.
- [ ] **Step 3: Lista manual** `## v2.0.0` en `docs/manual-test-checklist.md`:
      exportar sin secretos y abrir el fichero (sin URL ni token); exportar con secretos (aviso visible);
      importar en el mismo teléfono (todo duplicado con sufijo, nada reemplazado); importar sin secretos
      (fila "Sin credenciales", aviso `NOT_CONFIGURED`, al añadir la URL se envía lo que esperaba);
      importar un fichero no válido (http, regex roto, versión 2, fichero de texto cualquiera): mensaje y
      nada cambia; exportar diagnóstico y buscar en él un cuerpo, un remitente y la URL (no aparecen);
      Acerca de con flecha, icono redondo y enlace; ⋮ con Exportar, Importar y Acerca de en todas las
      pestañas; actualizar de 1.3.0 a 2.0.0 sin perder destinos, reglas ni historial; sin permisos nuevos.
- [ ] **Step 4: Spec:** §3 estado (1.2.0 y 1.3.0 publicadas; 2.0.0 implementada en `v2-delivery-4`), §13 con las decisiones de formato, secretos, renombrado y registro de eventos, §14 con el diagnóstico en Acerca de.
- [ ] **Step 5: Commit** `docs: 2.0.0 checks and spec status`; push a `origin v2-delivery-4`; CI en verde.
- [ ] **Step 6: Release (la hace el usuario; el agente no empuja `main` ni etiquetas):**
      `git -C /srv/http/sms-forwarder checkout main && git merge --ff-only origin/v2-delivery-4 && git push origin main && git tag v2.0.0 && git push origin v2.0.0`.
      El flujo `release.yml` firma y publica la APK.
- [ ] **Step 7: Actualización en el emulador con APKs de release firmadas en local** (después de la etiqueta o, si el usuario lo pide antes, con la rama): 1.3.0 de la release de GitHub (`gh release download v1.3.0`), configurada por la UI con un destino JSON estándar y una regla `TEST` y unos SMS enviados; 2.0.0 construida con `GITHUB_REF_NAME=v2.0.0` y la firma de `~/sms-forwarder-keys` (variables solo dentro del mismo comando, como en la entrega 3), `versionCode` 20000, mismo certificado SHA-256 (`apksigner verify --print-certs`), `adb -s emulator-5554 install -r`; recorrer la lista `## v2.0.0` (el fichero a importar se sube con `adb push` a `/sdcard/Download`). Informe por punto en el ledger; los fallos son tareas nuevas. `android-emu-stop` al acabar. La instalación en el teléfono real espera al usuario.
