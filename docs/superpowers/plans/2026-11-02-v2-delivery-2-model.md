# v2 entrega 2 (1.2.0): destinos, reglas, entregas y migración. Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La app guarda destinos, reglas y una entrega por cada pareja SMS + destino, migra los datos de 1.1.x sin intervención y envía con un motor genérico, sin que cambie nada de lo que el usuario ve ni de lo que recibe el Worker.

**Architecture:** Las políticas puras (reglas, plantillas, escape, política de éxito y error, estado agregado) viven en `:core` y se prueban en JVM. En `:app`, Room pasa a versión 3 con tablas `destination`, `rule`, `rule_destination` y `delivery`. La migración se hace en dos fases: la SQL de Room (atómica) crea el esquema y el destino Principal con una entrega por SMS, y una importación de la app (idempotente) copia el token al almacén cifrado y convierte los remitentes en reglas. La pantalla de Ajustes sigue editando "la conexión" y "los remitentes", que ahora son el destino principal y sus reglas exactas.

**Tech Stack:** Kotlin, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization, Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), entrega 2 (§8, §9, §11, §12, §16). Plan anterior: [entrega 1](2026-11-02-v2-delivery-1-shell.md).

## Decisiones de este plan (se anotan en la spec en la T1)

- **Éxito del destino migrado: cualquier 2xx** (decisión del usuario, 2026-11-02). La spec §11 decía "solo 200"; un 201/204 se reintentaría sin fin y duplicaría envíos.
- **Principal se crea siempre** que haga falta un destino: en la migración SQL (aunque no haya credenciales) y en instalación nueva al añadir el primer remitente o guardar credenciales. Sin secretos, el destino cuenta como "sin configurar" (`NOT_CONFIGURED`), igual que v1 sin credenciales. Motivo: reglas y entregas necesitan un destino al que apuntar.
- **La unidad de trabajo sigue siendo el SMS**: `SendWorker` con `KEY_SMS_ID` y nombre único `send-<smsId>`, que manda en paralelo las entregas pendientes de ese SMS. Así los trabajos ya encolados por 1.1.x siguen valiendo tras actualizar, y un destino caído no frena a los demás (cada entrega se registra sola; las ya enviadas no se repiten).
- **`sms.status`, `attempts`, `lastHttpCode`, `lastError`, `sentAt` y `stuckNotified` se mantienen como agregado** de sus entregas, recalculado en la misma transacción que cada intento. Inicio, detalle, purga, aviso de atascados y salud siguen leyendo la fila del SMS.
- **La URL y las cabeceras secretas de cada destino van cifradas** (no solo las secretas): la v1 ya ocultaba la URL guardada.
- `{sim}` es la ranura (1 o 2) calculada con `SubscriptionManager.getSlotIndex(subId) + 1`; vacía si no se sabe. La condición SIM de una regla usa ese mismo valor.

## Global Constraints

- Reglas de v1 que siguen: solo https; sin servicio en primer plano; sin `fallbackToDestructiveMigration`; copias de seguridad desactivadas; nada de textos de SMS, remitentes reales ni nombres de proyectos del autor en código, tests, strings ni docs.
- El Worker no puede notar el cambio: para el destino migrado, el cuerpo enviado es byte a byte el de `IngestPayload.encode(sender, body, receivedAt)`, con la misma cabecera `Authorization: Bearer <token>` y `Content-Type: application/json; charset=utf-8`, método POST.
- `localKey`, la deduplicación por SMS, el barrido, el suelo de barrido y los avisos de MIUI no cambian.
- `minSdk = 31`. Tests con `robolectric.properties` `sdk=35`. JDK 17 local: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. `:app` solo se verifica en CI (`gh run watch`).
- Esquema de Room: `app/schemas/dev.smsforwarder.data.AppDatabase/3.json` se commitea en la misma tarea que sube la versión; CI falla si el esquema cambia sin commitear.
- La URL, el token, las cabeceras secretas y el cuerpo del SMS nunca se escriben en el log ni en `toString()`.
- Sin UI nueva: ninguna pantalla cambia de aspecto. Textos nuevos (aviso de migración fallida) van en `values/` y `values-es/`; tests de `:app` comparan con `context.getString(R.string.x)`.
- Cada tarea deja `:core:test` y la compilación y tests de `:app` en verde (CI): ninguna tarea cambia un tipo y deja a sus usuarios para otra.
- Revisión de código: solo OCR (`ocr delegate rule`), nunca los revisores genéricos. Sin líneas de atribución a Claude en commits ni PR.
- Rama `v2-delivery-2` desde `main`, en worktree `/srv/http/sms-forwarder-wt/v2-d2`. Push y secretos: los autoriza el usuario por tarea.

## Review Focus

1. Trabajo `send-<smsId>` encolado por 1.1.x y aún pendiente al actualizar: tras la migración se ejecuta y manda el SMS una sola vez al Principal (T7).
2. Actualizar con SMS en `ERROR` 401 y con intentos históricos: siguen en error con sus intentos visibles en el detalle, y al guardar credenciales vuelven a la cola (T3, T6).
3. Importación que falla a medias (Keystore no disponible): no se marca como hecha, la app no envía nada, avisa, y en el siguiente arranque se reintenta sin duplicar reglas (T5).
4. Un SMS que cumple dos reglas con el mismo destino: una sola entrega a ese destino (T2, T6).
5. Destino en pausa junto a otro activo: el activo envía y el SMS queda `QUEUED` sin intentos en el pausado; el trabajo no reintenta en bucle por el pausado (T7).

---

### Task 1: Reglas en `:core` y spec al día

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Rules.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/RulesTest.kt`
- Modify: `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 estado, §11 éxito del destino migrado, §12 con las decisiones de arriba)

**Interfaces:**
- Produces:
  - `enum class SenderMode { EXACT, CONTAINS, REGEX }`, `enum class BodyMode { CONTAINS, REGEX }`, `enum class SimMatch { ANY, SIM1, SIM2 }`
  - `data class RuleSpec(val id: Long, val enabled: Boolean, val senderMode: SenderMode, val senderPattern: String, val bodyMode: BodyMode?, val bodyPattern: String?, val sim: SimMatch, val destinationIds: Set<Long>)`
  - `data class IncomingSms(val sender: String, val body: String, val simSlot: Int?)`
  - `object RuleEngine { fun destinationsFor(sms: IncomingSms, rules: List<RuleSpec>, regexBudgetMs: Long = 50): RuleMatch; fun validatePattern(pattern: String): Boolean }`
  - `data class RuleMatch(val destinationIds: Set<Long>, val timedOutRuleIds: Set<Long>)`

- [ ] **Step 1: Escribir los tests que fallan** en `RulesTest`:
  - `exactIgnoresSpacesAndCase`: regla EXACT `"AB C"` coincide con remitente `"abc"`; no con `"ABCD"`.
  - `containsNormalizes`: CONTAINS `"bank"` coincide con `"MY BANK"`.
  - `regexUsesRawSenderAndFind`: REGEX `"^Ab"` coincide con `"Abc"` y no con `"abc"`; REGEX `"(?i)^ab"` coincide con `"abc"`.
  - `bodyFilterMustAlsoMatch`: remitente coincide pero BodyMode CONTAINS `"code"` y cuerpo sin "code" → sin destinos.
  - `simFilter`: `SimMatch.SIM2` no coincide con `simSlot = 1` ni con `null`; `ANY` coincide con `null`.
  - `disabledRuleIgnored`.
  - `unionWithoutDuplicates`: dos reglas con destinos `{1,2}` y `{2,3}` → `{1,2,3}`.
  - `catastrophicRegexTimesOut`: REGEX cuerpo `"(a+)+$"` sobre `"a".repeat(40) + "!"` → sin destinos, `timedOutRuleIds == setOf(id)`, y la llamada tarda menos de 1 s.
  - `invalidPatternRejected`: `validatePattern("(")` es false; `validatePattern("^a.*")` es true.
  - `invalidStoredPatternNeverMatches`: una regla con patrón inválido no coincide (no lanza).
- [ ] **Step 2: Ejecutar** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test --tests '*RulesTest'`; esperado: FAIL (no compila).
- [ ] **Step 3: Implementar `Rules.kt`.** EXACT y CONTAINS comparan `SenderFilter.normalize` de ambos lados; un patrón normalizado vacío no coincide con nada. REGEX y BodyMode.REGEX usan `Regex.find` sobre el texto original envuelto en un `CharSequence` que lanza una excepción propia al pasar el plazo en `charAt` (el truco estándar para cortar el backtracking); esa excepción cuenta como "no coincide" y añade el id a `timedOutRuleIds`. Un patrón que no compila cuenta como "no coincide".
- [ ] **Step 4: Ejecutar** el mismo comando; esperado: PASS.
- [ ] **Step 5: Anotar en la spec** las seis decisiones de la cabecera de este plan (§11: JSON estándar = cualquier 2xx; §12: Principal siempre, worker por SMS, estado agregado, secretos cifrados, `{sim}` = ranura) y el estado de §3 (entrega 1 publicada como 1.1.0).
- [ ] **Step 6: Commit** `feat(core): rule engine with regex time limit`.

### Task 2: Plantillas, escape y política de entrega en `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Templates.kt`, `core/src/main/kotlin/dev/smsforwarder/core/DeliveryPolicy.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/TemplatesTest.kt`, `DeliveryPolicyTest.kt`

**Interfaces:**
- Produces:
  - `data class SmsVars(val sender: String, val body: String, val receivedAt: Long, val simSlot: Int?, val device: String)`
  - `enum class HttpMethod { POST, PUT, GET }`, `enum class SuccessPolicy { STATUS_200, ANY_2XX }`, `enum class TemplateKind { STANDARD_JSON, TELEGRAM, NTFY, CUSTOM }`
  - `data class DestinationSpec(val method: HttpMethod, val url: String, val headers: List<Pair<String, String>>, val body: String?, val bodyIsJson: Boolean)`
  - `data class RenderedRequest(val method: HttpMethod, val url: String, val headers: List<Pair<String, String>>, val body: String?, val contentType: String?)` con `toString()` sin URL, cabeceras ni cuerpo
  - `object Templates { val VARIABLES: Set<String>; fun unknownVariables(text: String): Set<String>; fun render(spec: DestinationSpec, vars: SmsVars): RenderedRequest; fun standardJson(url: String, token: String): DestinationSpec }`
  - `enum class DeliveryStatus { QUEUED, SENT, ERROR }`
  - `fun classifyDelivery(httpCode: Int?, responseBody: String?, policy: SuccessPolicy): SendOutcome`
  - `fun aggregateStatus(statuses: Collection<DeliveryStatus>): SmsStatus`
  - `fun isAuthRejection(httpCode: Int?): Boolean` (401 o 403)

- [ ] **Step 1: Escribir los tests que fallan** en `TemplatesTest`:
  - `standardJsonIsByteIdenticalToV1`: para cuerpos `"hola"`, `"di \"sí\""`, `"línea1\nlínea2"`, `"c:\\ruta"`, `"{body} literal"`, `"emoji 😀\t"`, `render(standardJson(url, "t"), vars).body == IngestPayload.encode(sender, body, receivedAt)`; método POST; cabeceras `[("Authorization", "Bearer t")]`; `contentType == "application/json; charset=utf-8"`.
  - `bodyContainingVariableNameIsNotExpandedTwice`: SMS con cuerpo `"{sender}"` produce `"body":"{sender}"`.
  - `jsonEscapeBlocksInjection`: plantilla JSON `{"t":"{body}"}` con cuerpo `"\",\"x\":\"1"` → el resultado parsea como JSON con una sola clave `t`.
  - `urlVariablesAreEncoded`: URL `https://h/x?m={body}` con cuerpo `"a b&c"` → `https://h/x?m=a%20b%26c`.
  - `headerVariablesDropLineBreaks`: cabecera `X: {body}` con `"a\r\nb"` → valor sin `\r` ni `\n`.
  - `receivedAtIsMillisDigits`: `{received_at}` y `{received_at_ms}` → `"1730000000000"` para `receivedAt = 1730000000000`.
  - `unknownVariablesListed`: `unknownVariables("{sender} {foo} {body}") == setOf("foo")`.
  - `toStringHidesSecrets`: `render(...).toString()` no contiene ni la URL ni el token ni el cuerpo.
- [ ] **Step 2: Escribir los tests que fallan** en `DeliveryPolicyTest`:
  - `any2xxAccepts`: 200, 201 y 204 → `Accepted` con `ANY_2XX`; con `STATUS_200`, 201 → `Retry(201, "http_201")`.
  - `authRejections`: 401 y 403 → `Rejected` con ese código y `errorCode` del cuerpo `{"error":"x"}`.
  - `badRequest`: 400 → `Rejected(400, ...)`.
  - `transient`: `null` → `Retry(null, "network")`; 429, 500, 302 → `Retry`.
  - `aggregate`: `{SENT, QUEUED} → QUEUED`; `{SENT, ERROR, QUEUED} → ERROR`; `{SENT, SENT} → SENT`; vacío → `QUEUED`.
- [ ] **Step 3: Ejecutar** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`; esperado: FAIL (no compila).
- [ ] **Step 4: Implementar.** La sustitución recorre la plantilla una sola vez (un `Regex("\\{([a-z_]+)\\}").replace` con función), así el texto insertado nunca se vuelve a expandir. Escape JSON: `JsonPrimitive(s).toString()` sin las comillas exteriores, para coincidir con kotlinx byte a byte. Escape URL: `URLEncoder.encode(s, UTF_8).replace("+", "%20")`. Cabeceras: quitar `\r` y `\n`. `standardJson` usa como cuerpo `{"sender":"{sender}","body":"{body}","received_at":{received_at}}` con `bodyIsJson = true`. `classify` de v1 queda como envoltorio de `classifyDelivery(..., ANY_2XX)` para no romper a sus usuarios.
- [ ] **Step 5: Ejecutar** el mismo comando; esperado: PASS.
- [ ] **Step 6: Commit** `feat(core): destination templates and delivery policy`.

### Task 3: Esquema de Room v3 y migración SQL

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/DestinationEntity.kt`, `RuleEntity.kt` (con `RuleDestinationEntity`), `DeliveryEntity.kt`, `DestinationDao.kt`, `RuleDao.kt`, `DeliveryDao.kt`
- Modify: `app/src/main/kotlin/dev/smsforwarder/data/AppDatabase.kt`, `AttemptEntity.kt`, `AppGraph.kt` (`addMigrations(MIGRATION_1_2, migration2to3(...))`)
- Create: `app/schemas/dev.smsforwarder.data.AppDatabase/3.json` (generado por KSP)
- Test: `app/src/test/kotlin/dev/smsforwarder/data/MigrationTest.kt`

**Interfaces:**
- Consumes: enums de T1 y T2 (guardados por nombre con `Converters`).
- Produces:
  - `destination(id PK, name TEXT, enabled INT, template TEXT, method TEXT, bodyTemplate TEXT?, bodyIsJson INT, publicHeaders TEXT (JSON de pares), successPolicy TEXT, createdAt INT, deletedAt INT?)`. URL y cabeceras secretas no van aquí (T4).
  - `rule(id PK, name TEXT, enabled INT, senderMode TEXT, senderPattern TEXT, bodyMode TEXT?, bodyPattern TEXT?, sim TEXT, createdAt INT)` y `rule_destination(ruleId, destinationId, PK compuesta, FK CASCADE a ambas)`.
  - `delivery(id PK, smsId FK CASCADE, destinationId FK SET NULL nullable, destinationName TEXT, status TEXT, attempts INT, lastHttpCode INT?, lastError TEXT?, sentAt INT?, UNIQUE(smsId, destinationId))`, índice por `status`.
  - `attempt.deliveryId INTEGER` nullable, FK CASCADE a `delivery`, con índice.
  - `fun migration2to3(primaryName: String): Migration`; `const val PRIMARY_DESTINATION_ID = 1L`.
  - DAOs con las consultas que necesitan T6 y T7: `DeliveryDao.forSms(smsId)`, `pendingForSms(smsId)` (`status = QUEUED`), `get(id)`, `update`, `insertAll`, `smsIdsWithStatus(status)`, `idsAuthRejected(destinationId)`, `observeForSms(smsId)`; `RuleDao.activeWithDestinations(): List<RuleWithDestinations>` (relación `@Relation`), `insert`, `deleteExactForDestination(pattern, destinationId)`; `DestinationDao.get(id)`, `firstActive()`, `insert`, `update`.
- [ ] **Step 1: Escribir los tests que fallan** en `MigrationTest` (mismo patrón que el actual: crear la base desde `2.json`, sembrar filas con SQL y abrir con la clase real):
  - `migrates2to3KeepingEverySms`: con 3 SMS (`SENT` con 2 intentos, `QUEUED` con 0, `ERROR` 401 con 1) y sus intentos, tras abrir hay un destino id 1 con nombre `"Main"`, plantilla `STANDARD_JSON`, `successPolicy = ANY_2XX`, `enabled = 1`; 3 entregas con el mismo `status`, `attempts`, `lastHttpCode`, `lastError` y `sentAt` que su SMS y `destinationName = "Main"`; cada intento con `deliveryId` de la entrega de su SMS.
  - `migrationIsValidatedByRoom`: abrir con Room no lanza (Room compara las tablas con las entidades).
  - `migrates1to3`: una base v1 llega a v3 encadenando ambas migraciones.
  - `emptyDatabaseStillGetsPrimary`: sin SMS, la migración deja el destino id 1 y cero entregas.
- [ ] **Step 2: Ejecutar en CI** `./gradlew :app:testDebugUnitTest --tests '*MigrationTest'`; esperado: FAIL.
- [ ] **Step 3: Implementar entidades, DAOs y `migration2to3`.** La SQL crea las tablas con el `createSql` exacto que generará Room (copiarlo de `3.json` tras el primer build), inserta el destino con `primaryName`, inserta una entrega por SMS con `INSERT INTO delivery (...) SELECT ... FROM sms`, y añade la columna a `attempt`. Room ya envuelve la migración en una transacción. La entidad `attempt` gana el FK a `delivery`: como SQLite no añade FK con `ALTER TABLE`, la migración recrea `attempt` (`CREATE TABLE attempt_new`, `INSERT ... SELECT` con `deliveryId` por join a `delivery`, `DROP`, `ALTER TABLE ... RENAME`, índices). `AppDatabase` sube a `version = 3`; `AppGraph` pasa `context.getString(R.string.destination_primary_name)` (`"Main"` / `"Principal"`).
- [ ] **Step 4: Ejecutar en CI**; esperado: PASS, y `3.json` commiteado igual al generado.
- [ ] **Step 5: Commit** `feat(data): destinations, rules and deliveries schema with migration`.

### Task 4: Secretos por destino

**Files:**
- Modify: `app/src/main/kotlin/dev/smsforwarder/data/SecretStore.kt`
- Test: lista manual (Keystore no corre en Robolectric) y `app/src/test/kotlin/dev/smsforwarder/data/DestinationSecretsTest.kt` para la serialización

**Interfaces:**
- Produces:
  - `data class DestinationSecrets(val url: String, val headers: List<Pair<String, String>>)` con `toString() = "DestinationSecrets(***)"`
  - `interface DestinationSecretStore { suspend fun get(destinationId: Long): DestinationSecrets?; suspend fun set(destinationId: Long, s: DestinationSecrets); suspend fun remove(destinationId: Long) }`
  - `class KeystoreDestinationSecretStore(context: Context) : DestinationSecretStore` (misma clave de Keystore `smsfwd-secrets`, entradas `dest_<id>_iv` y `dest_<id>_blob` en el DataStore `secrets`)
  - `fun DestinationSecrets.Companion.encode/decode` (JSON) y `fun Credentials.toDestinationSecrets()`, `fun DestinationSecrets.toCredentialsOrNull(): Credentials?` (lee `Authorization: Bearer <token>`)
  - `class FakeDestinationSecretStore` en `app/src/test` para T5 a T7.
  - El `SecretStore` legado se queda sin cambios: solo lo lee la importación (T5).
- [ ] **Step 1: Test que falla** `DestinationSecretsTest`: `encode`/`decode` ida y vuelta con URL y una cabecera con comillas y `\u0000`; `Credentials("https://h", "t").toDestinationSecrets().toCredentialsOrNull() == Credentials("https://h", "t")`; `toString()` no contiene ni URL ni token.
- [ ] **Step 2: Implementar.** Un blob corrupto o una clave invalidada borran solo esa entrada y devuelven `null`, igual que el `get()` actual.
- [ ] **Step 3: CI en verde. Commit** `feat(data): encrypted secrets per destination`.

### Task 5: Importación de 1.1.x y modo seguro

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/LegacyImport.kt`
- Modify: `SettingsRepository.kt` (claves `v2_import_done`, `v2_import_failed`), `AppGraph.kt`, `SmsForwarderApp.kt`, `health/HealthChecker.kt` (`HealthIssue.MIGRATION_FAILED`), `notify/Notifier.kt`, `ui/main/MainScreen.kt` (texto de la tarjeta), `res/values*/strings.xml` (`issue_migration_failed`, `notif_issue_migration_failed`)
- Test: `app/src/test/kotlin/dev/smsforwarder/data/LegacyImportTest.kt`

**Interfaces:**
- Consumes: `SecretStore` legado, `DestinationSecretStore`, `RuleDao`, `SettingsRepository.senders` (la lista legada en DataStore), `PRIMARY_DESTINATION_ID`.
- Produces: `class LegacyImport(db, legacySecrets: SecretStore, secrets: DestinationSecretStore, settings: SettingsRepository) { suspend fun run(): Boolean }` (true = hecha o ya estaba); `suspend fun SettingsRepository.isImportFailed(): Boolean`.
- [ ] **Step 1: Tests que fallan** en `LegacyImportTest`:
  - `copiesCredentialsAndSenders`: con credenciales legadas y remitentes `["AAA", "B B"]`, tras `run()` el destino 1 tiene secretos equivalentes a las credenciales y hay dos reglas EXACT activas hacia el destino 1 con esos patrones.
  - `idempotent`: dos `run()` seguidos dejan dos reglas, no cuatro.
  - `noCredentialsStillImportsRules`: sin credenciales, reglas creadas y destino 1 sin secretos.
  - `keystoreFailureLeavesNothingHalfDone`: con un `DestinationSecretStore` que lanza en `set`, `run()` devuelve false, no hay reglas nuevas, `isImportFailed()` es true; un segundo `run()` con un almacén que funciona importa todo y limpia el fallo.
  - `freshInstallSkips`: base sin destino 1 (instalación nueva) → `run()` marca hecho sin crear nada.
- [ ] **Step 2: Implementar.** Orden: si `v2_import_done`, salir. Copiar secretos (antes que nada de Room). Después, en una transacción de Room, insertar las reglas que falten (comparando patrón normalizado). Por último marcar hecho y borrar `v2_import_failed`. Cualquier excepción que no sea cancelación marca `v2_import_failed` y devuelve false. `SmsForwarderApp.onCreate` lanza `run()` en el `applicationScope` existente; `SendWorker` (T7) y el barrido llaman a `run()` antes de trabajar y no envían si devuelve false. `HealthChecker` añade `MIGRATION_FAILED` cuando `isImportFailed()`.
- [ ] **Step 3: CI en verde. Commit** `feat(data): import 1.1 settings into destinations and rules`.

### Task 6: Repositorios y entrada de SMS con reglas

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/DestinationRepository.kt`, `RuleRepository.kt`, `app/src/main/kotlin/dev/smsforwarder/sms/SimSlots.kt`
- Modify: `data/SmsRepository.kt`, `data/SettingsRepository.kt`, `sms/SmsReceiver.kt`, `sms/Sweeper.kt`, `ui/recovery/RecoveryViewModel.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/MainActivity.kt`, `health/HealthChecker.kt`, `AppGraph.kt`
- Test: `SmsRepositoryTest.kt`, `SweeperTest.kt`, `SmsReceiverTest.kt`, `SettingsRepositoryTest.kt`, `HealthCheckerTest.kt`, nuevo `RuleRepositoryTest.kt`

**Interfaces:**
- Consumes: `RuleEngine`, `RuleSpec`, `IncomingSms` (T1); DAOs (T3); `DestinationSecretStore` (T4).
- Produces:
  - `DestinationRepository { suspend fun ensurePrimary(): Long; suspend fun primaryCredentials(): Credentials?; suspend fun savePrimaryCredentials(c: Credentials); suspend fun get(id: Long): DestinationEntity?; suspend fun secrets(id: Long): DestinationSecrets? }`. "Primario" = destino activo de menor id sin `deletedAt`; `ensurePrimary` lo crea con el nombre de recursos y `STANDARD_JSON` + `ANY_2XX` si no hay ninguno.
  - `RuleRepository { val senders: Flow<List<String>>; suspend fun addSender(s: String); suspend fun removeSender(s: String); suspend fun activeRules(): List<RuleSpec>; suspend fun hasActiveRules(): Boolean }`. `senders` = patrones de reglas EXACT hacia el primario; `addSender` llama a `ensurePrimary` y no duplica (normalizado).
  - `SmsRepository.ingest(record: SmsRecord, origin: SmsOrigin, destinationIds: Set<Long>): IngestResult` crea SMS y entregas (`QUEUED`, `destinationName` actual) en una transacción; un `AlreadyKnown` no añade entregas.
  - `SmsRepository.requeue(id)` pasa a `QUEUED` las entregas no enviadas del SMS y recalcula el agregado; `idsWithAuthError(): List<Long>` (ids de SMS con alguna entrega `ERROR` 401/403) sustituye a `idsWithError401()`; `requeueAuthErrors(destinationId): List<Long>`.
  - `SimSlots.slotOf(context, subId: Int?): Int?`.
  - `SettingsRepository.senders/addSender/removeSender` delegan en `RuleRepository`; `saveCredentials` llama a `savePrimaryCredentials`, `requeueAuthErrors(primario)` y programa esos SMS y los `QUEUED`. Firmas públicas sin cambios, así la UI no se toca.
  - `HealthIssue.NO_SENDERS` se renombra `NO_RULES` (mismo texto) y sale de `!hasActiveRules()`; `NOT_CONFIGURED` sale de `primaryCredentials() == null`.
- [ ] **Step 1: Tests que fallan:**
  - `RuleRepositoryTest.addSenderCreatesExactRuleToPrimary`, `addSenderTwiceNormalizedIsOne`, `removeSenderDeletesRule`, `sendersFlowListsPatterns`.
  - `SmsRepositoryTest.ingestCreatesOneDeliveryPerDestination` (destinos `{1, 2}` → 2 entregas `QUEUED`), `alreadyKnownAddsNoDelivery`, `requeueResetsUnsentDeliveriesOnly` (una `SENT` y una `ERROR` → `SENT` y `QUEUED`, SMS en `QUEUED`).
  - `SweeperTest.unmatchedSenderNotStored` y `matchedGoesToRuleDestinations` (sustituyen a los de lista de remitentes).
  - `SmsReceiverTest`: el caso existente de remitente permitido sigue pasando con reglas creadas por `addSender`.
  - `SettingsRepositoryTest.saveCredentialsRequeuesAuthErrors`: una entrega `ERROR` 403 del primario vuelve a `QUEUED` y su SMS se programa.
  - `HealthCheckerTest`: `NO_RULES` sin reglas; `NOT_CONFIGURED` con primario sin secretos.
- [ ] **Step 2: Implementar.** Receptor, barrido y recuperación construyen `IncomingSms(sender, body, SimSlots.slotOf(context, subId))`, piden `RuleEngine.destinationsFor(..., ruleRepository.activeRules())` y, si el conjunto está vacío, no guardan nada (como hoy con un remitente fuera de la lista). Los `timedOutRuleIds` se anotan con `Log.w` solo con el id de la regla.
- [ ] **Step 3: CI en verde. Commit** `feat(data): ingest through rules into per-destination deliveries`.

### Task 7: Motor de envío por entregas

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/net/DeliveryClient.kt`
- Modify: `work/SendWorker.kt`, `data/SmsRepository.kt` (`recordAttempt` por entrega), `notify/Notifier.kt` (`tokenRejected(destinationId, waiting)` con tag por destino, mismo texto), `net/IngestClient.kt` (solo queda `testConnection`)
- Test: `SendWorkerTest.kt`, `SmsRepositoryTest.kt`, nuevo `DeliveryClientTest.kt` (MockWebServer, como `IngestClientTest`)

**Interfaces:**
- Consumes: `Templates.render`, `classifyDelivery`, `aggregateStatus`, `isAuthRejection` (T2); `DeliveryDao` (T3); `DestinationSecretStore` (T4); `LegacyImport.run` (T5).
- Produces:
  - `class DeliveryClient(http: OkHttpClient) { suspend fun send(req: RenderedRequest, policy: SuccessPolicy): SendOutcome }` (misma lectura acotada del cuerpo de 4xx y mismas excepciones capturadas que `IngestClient.post`).
  - `SmsRepository.recordAttempt(deliveryId: Long, outcome: SendOutcome)`: escribe el intento con `deliveryId` y `smsId`, actualiza la entrega (una entrega `SENT` no se deshace) y recalcula en la misma transacción el agregado del SMS: `status = aggregateStatus(...)`, `attempts` = suma, `lastHttpCode`/`lastError` del último intento, `sentAt` cuando el agregado pasa a `SENT`.
  - `SendWorker` (misma clave `KEY_SMS_ID`): si `LegacyImport.run()` es false o el reenvío global está en pausa, sale sin enviar (`retry()` y `success()` respectivamente, como hoy la pausa). Para cada entrega `QUEUED` del SMS, en paralelo con `coroutineScope { async }`: destino en pausa o borrado → se salta sin intento; sin secretos → cuenta como "reintentar"; si no, `render` + `send` + `recordAttempt`. Resultado: `retry()` si alguna entrega quedó para reintentar, si no `success()`. Avisos: 401/403 → `tokenRejected(destinationId, idsAuthRejected(destinationId).size)`; 400 → `rejected(sms)` como hoy.
- [ ] **Step 1: Tests que fallan:**
  - `DeliveryClientTest.postsRenderedRequest`: MockWebServer recibe método, cabeceras y cuerpo exactos; `ANY_2XX` con 204 → `Accepted`.
  - `SendWorkerTest.primarySendsV1Payload`: con el destino migrado, el servidor recibe `IngestPayload.encode(...)` byte a byte y `Authorization: Bearer t`.
  - `SendWorkerTest.downDestinationDoesNotBlockOther`: destinos A (500) y B (200) → entrega B `SENT`, A `QUEUED` con 1 intento, SMS `QUEUED`, resultado `retry`; segunda ejecución con A en 200 no vuelve a llamar a B.
  - `SendWorkerTest.pausedDestinationSkipped`: A en pausa y B en 200 → B `SENT`, A `QUEUED` con 0 intentos, resultado `success`.
  - `SendWorkerTest.legacyQueuedWorkStillSends`: base migrada desde v2 con un SMS `QUEUED` y trabajo con `KEY_SMS_ID` → una sola petición al primario.
  - `SendWorkerTest.importFailedSendsNothing`: con `v2_import_failed` y almacén que sigue fallando → cero peticiones, `retry`.
  - `SmsRepositoryTest.lateResultDoesNotUndoSent` (versión por entrega del test existente) y `attemptsVisibleOnSms` (el detalle sigue mostrando los intentos por `observeAttempts(smsId)`).
- [ ] **Step 2: Implementar.** `SendWorker` construye `SmsVars(sms.sender, sms.body, sms.receivedAt, SimSlots.slotOf(...), Build.MODEL)` y el `DestinationSpec` a partir de la entidad y sus secretos.
- [ ] **Step 3: CI en verde. Commit** `feat(work): send each SMS to its destinations independently`.

### Task 8: Cierre de la entrega 1.2.0

**Files:**
- Modify: `docs/manual-test-checklist.md` (apartado "Actualizar de 1.1 a 1.2"), `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 estado)

- [ ] **Step 1: Revisión OCR** de la rama entera contra `main` (`ocr delegate rule`, modelo según el diff; esperable opus por el esquema). Corregir High y Medium con un test cada uno.
- [ ] **Step 2: Lista manual nueva:** actualizar la app real de 1.1.0 a la APK de la rama sin desinstalar; comprobar que Inicio muestra el mismo historial, que Ajustes muestra los mismos remitentes, que la prueba de conexión da correcto, que un SMS nuevo llega al Worker con 200 y que la app no pide nada. La release no es depurable, así que no se puede copiar su base antes de actualizar: la red de seguridad son los tests de migración de T3 y T5, y el usuario confirma antes de instalar que acepta ese riesgo.
- [ ] **Step 3: Con OK del usuario:** push de la rama, CI verde, fast-forward a `main`, tag `v1.2.0`, `adb install -r` de la release y recorrido de la lista con el usuario.
- [ ] **Step 4: Commit** `docs: 1.2.0 upgrade checks`.
