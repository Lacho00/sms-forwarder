# v2 entrega 3 (1.3.0): pestañas Reglas y Destinos. Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El usuario crea, edita, pausa, prueba y borra destinos y reglas desde sus propias pestañas, con plantillas (JSON estándar, Telegram, ntfy, personalizado), y ve en Inicio y en el detalle el estado de cada SMS por destino, con "Reenviar a un destino".

**Architecture:** Borradores, validación, plantillas y política de prueba son puros y viven en `:core`. En `:app`, `DestinationRepository` y `RuleRepository` ganan altas, ediciones y bajas; el borrado de un destino cancela sus entregas pendientes (`DeliveryStatus.CANCELED`) y recalcula el agregado del SMS en la misma transacción. La UI añade dos pestañas a la navegación manual (`Nav.kt`), dos listas y dos editores a pantalla completa; Ajustes pierde los grupos Conexión y Remitentes. Sin cambio de esquema de Room: el estado nuevo es un valor más del enum que ya se guarda por nombre.

**Tech Stack:** Kotlin, Compose Material 3, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization, Robolectric, JUnit.

**Spec:** [2026-11-02-v2-redesign.md](../specs/2026-11-02-v2-redesign.md), entrega 3 (§3, §5, §6, §7, §8, §9, §10, §11). Plan anterior: [entrega 2](2026-11-02-v2-delivery-2-model.md); su ledger con lo diferido: `.superpowers/sdd/2026-11-02-v2-delivery-2-model/progress.md`.

**Rama:** `v2-delivery-3` **desde `v2-delivery-2`, no desde `main`**, porque la 1.2.0 aún no está fusionada (falta la prueba de actualización en el teléfono). Worktree `/srv/http/sms-forwarder-wt/v2-d3`. Si la 1.2.0 se fusiona antes de acabar, se rebasa sobre `main` sin cambios. La revisión OCR de cierre compara contra `v2-delivery-2`.

## Decisiones de este plan (se anotan en la spec en la T10; las marcadas ⚠ se confirman con el usuario antes de la T1)

- ⚠ **Borrar un destino:** sus entregas `QUEUED` y `ERROR` pasan a `CANCELED` (nuevo `DeliveryStatus`); las `SENT` se quedan con su nombre. `aggregateStatus` ignora `CANCELED`; un SMS cuyas entregas son todas `CANCELED` queda en `ERROR` con `lastError = "destination_deleted"`, sin aviso de atascado, y se puede reenviar a otro destino. Las reglas pierden el enlace a ese destino; una regla sin destinos se queda pero no cuenta como activa y su fila avisa.
- ⚠ **Errores de configuración** (plantilla que no se puede pintar, URL o cabecera que OkHttp rechaza): nuevo `SendOutcome.Invalid(reason)`, terminal. Se registra un intento con `httpCode = null` y `error = "config_<reason>"`, la entrega queda `ERROR`, avisa como un 400 (texto propio) y vuelve a la cola al guardar el destino. Hoy se reintenta sin fin y sin intento visible.
- ⚠ **Barrido y trabajos en vuelo:** `kickQueued` reemplaza (`REPLACE`) el trabajo `send-<id>` solo si no está `RUNNING`; si está corriendo, lo deja. Motivo: `REPLACE` cancela un envío a medias y, con varios destinos, puede duplicar el SMS en el que ya respondió. `KEEP` a secas dejaría un SMS esperando el backoff (hasta horas) tras reanudar.
- **`lastHttpCode` real en 2xx:** `SendOutcome.Accepted` lleva el código (`Accepted(httpCode)`); hoy se guarda 200 para cualquier 2xx. Inicio muestra `✓204` si el destino respondió 204.
- **Atascado por entregas:** un SMS está atascado si alguna entrega a un destino vivo y activo sigue `QUEUED` pasado el plazo, o alguna está `ERROR` 400 o de configuración. Las 401/403 las cubre el aviso de token; las de destinos en pausa y las `CANCELED` no cuentan.
- **JSON válido al guardar:** si el cuerpo es JSON, se sustituye cada variable de texto y `{sim}` por la cadena vacía y `{received_at}`/`{received_at_ms}` por `0`, y el resultado tiene que parsear. Así `{sim}`, `{body}`, `{sender}` y `{device}` sin comillas no se pueden guardar (resolución T2 de la entrega 2).
- **URL y cabeceras secretas nunca se vuelven a mostrar**, en ningún tipo de destino (igual que la URL en v1). Al editar, campo vacío = conservar lo guardado.
- ⚠ **GET no lleva cuerpo:** un destino GET con cuerpo no se puede guardar.
- ⚠ **Plantillas:** Telegram manda `{"chat_id":"<chat id>","text":"{sender}: {body}"}`; ntfy manda POST con cuerpo de texto `{sender}: {body}` y sin cabeceras por defecto (las cabeceras van codificadas como URL y un `Title: {sender}` saldría con `%2B`). Personalizado empieza en POST, JSON, cuerpo vacío.
- ⚠ **Probar** usa lo que hay en el formulario, completado con los secretos guardados donde el campo está vacío; no hace falta guardar antes. El SMS de prueba es remitente `TEST` y cuerpo `R.string.destination_test_body` ("prueba" / "test"); nunca se guarda ni se registra como intento.
- **`removeSender`** quita solo el enlace de la regla con el primario y borra la regla solo si se queda sin destinos.
- ⚠ **Onboarding sin cambios:** sigue pidiendo remitentes y conexión, que crean el destino Principal (JSON estándar) y reglas exactas. La spec no lo menciona.
- ⚠ **`NOT_CONFIGURED`** pasa a "algún destino usado por una regla activa no tiene secretos" (antes: el primario sin secretos).

## Global Constraints

- Reglas de v1 que siguen: **solo https**; **sin servicio en primer plano** ni notificación fija; **sin `fallbackToDestructiveMigration`**; **copias de seguridad desactivadas**; nada de textos de SMS reales, remitentes reales ni nombres de proyectos del autor en código, tests, strings ni docs (ejemplos: `AAA`, `TEST`, `https://h.example`).
- Esquema de Room: sigue en versión 3. Si una tarea necesitara tocarlo, lleva migración 3→4 explícita, `4.json` commiteado y test en `MigrationTest`; CI falla con esquema sin commitear.
- Todo texto visible nuevo va en `values/strings.xml` (inglés) y `values-es/strings.xml`; los tests de `:app` comparan con `context.getString(R.string.x)`, nunca con literales.
- `minSdk = 31`. Compose con navegación manual (`Nav.kt`, pila en un `String` con `rememberSaveable`), **sin `navigation-compose`**. ViewModels con `ViewModelProvider.Factory` creados en `MainActivity`, como los actuales.
- JDK 17 local: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test`. **Los tests de `:app` solo corren en CI** (Robolectric, `robolectric.properties` `sdk=35`, `TestApp` que no lanza la importación legada en `onCreate`: resuelve la carrera anotada en la T5 de la entrega 2; los tests nuevos de `:app` se apoyan en él y no crean su propia `Application`).
- La URL, el token, las cabeceras secretas, el cuerpo del SMS y los borradores de destino nunca se escriben en el log ni en `toString()`.
- Plantillas y sustitución: las de la entrega 2 (`Templates.render`) sin cambios de comportamiento; `{received_at}` idéntico entre reintentos. El destino migrado manda byte a byte lo mismo que 1.2.0.
- **Diseño:** colores, tamaños, espaciado, iconos de pestaña y disposición de los formularios que la spec no fija se consultan al usuario antes de aplicarlos (pasos marcados **consultar al usuario**). Se usan componentes Material 3 y estilos que ya usa la app; nada de colores ni medidas a mano sin OK.
- Cada tarea deja `:core:test` y la compilación y tests de `:app` en verde (CI): ninguna tarea cambia un tipo y deja a sus usuarios para otra.
- Revisión de código: solo OCR (`ocr delegate rule`), nunca los revisores genéricos. Sin líneas de atribución a Claude en commits ni PR. Push a `origin v2-delivery-3` con OK del usuario (puede darlo una vez para todo el plan); `main`, etiquetas e instalación siempre con OK explícito.

## Review Focus

1. Borrar un destino mientras un `SendWorker` le está mandando: el resultado tardío no resucita la entrega `CANCELED` ni cambia el agregado (T3, `lateResultOnCanceledIgnored`).
2. Editar un destino dejando vacíos URL y cabeceras secretas: se conservan los guardados y nunca aparecen en pantalla ni en el estado del ViewModel (T3, T6).
3. Cuerpo JSON personalizado con `{sim}` o `{body}` sin comillas, variable desconocida o URL `http`: no se puede guardar ni probar (T1, T6).
4. Pausar y reanudar un destino con SMS en cola: al reanudar se programan enseguida, y el otro destino, ya enviado, no se repite (T3, T9).
5. SMS cuyo único destino se borró: no queda "en cola" para siempre ni avisa de atascado, "Reintentar" no lo revive hacia el destino borrado y "Reenviar a un destino" lo manda a otro (T3, T9).

---

### Task 1: Borradores, validación y plantillas en `:core`

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/Drafts.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/DraftsTest.kt`

**Interfaces:**
- Consumes: `Templates.VARIABLES`, `Templates.unknownVariables`, `HttpMethod`, `SuccessPolicy`, `TemplateKind`, `SenderMode`, `BodyMode`, `SimMatch`, `RuleEngine.validatePattern`, `SenderFilter.normalize`, `UrlValidator.isValid`, `SmsVars`.
- Produces:
  - `data class HeaderDraft(val name: String, val value: String?, val secret: Boolean)` (`value == null` = conservar el guardado) con `toString()` que oculta `value` si `secret`.
  - `data class DestinationDraft(val name: String, val enabled: Boolean, val template: TemplateKind, val method: HttpMethod, val url: String?, val headers: List<HeaderDraft>, val body: String, val bodyIsJson: Boolean, val successPolicy: SuccessPolicy)` (`url == null` = conservar), `toString()` sin URL, cabeceras ni cuerpo.
  - `enum class DestinationError { NAME_EMPTY, URL_MISSING, URL_NOT_HTTPS, UNKNOWN_VARIABLE, HEADER_NAME_INVALID, HEADER_VALUE_INVALID, BODY_NOT_JSON, BODY_WITH_GET }`
  - `fun validateDestination(d: DestinationDraft, urlStored: Boolean): Set<DestinationError>`
  - `object DestinationPresets { fun standardJson(name: String, url: String, token: String): DestinationDraft; fun telegram(name: String, botToken: String, chatId: String): DestinationDraft; fun ntfy(name: String, topicUrl: String): DestinationDraft; fun custom(name: String): DestinationDraft }`
  - `data class RuleDraft(val name: String, val enabled: Boolean, val senderMode: SenderMode, val senderPattern: String, val bodyMode: BodyMode?, val bodyPattern: String?, val sim: SimMatch, val destinationIds: Set<Long>)`
  - `enum class RuleError { NAME_EMPTY, SENDER_EMPTY, SENDER_REGEX_INVALID, BODY_EMPTY, BODY_REGEX_INVALID, NO_DESTINATIONS }`, `fun validateRule(r: RuleDraft): Set<RuleError>`
  - `fun testSmsVars(body: String, now: Long, device: String): SmsVars` (remitente `"TEST"`, `simSlot = null`)

- [ ] **Step 1: Escribir los tests que fallan** en `DraftsTest`:
  - `httpUrlRejected`: `custom` con `url = "http://h.example"` → contiene `URL_NOT_HTTPS`; con `url = null, urlStored = true` → no contiene `URL_MISSING`; con `url = null, urlStored = false` → `URL_MISSING`.
  - `urlWithVariablesValidatedAfterSubstitution`: `"https://h.example/x?s={sender}"` sin errores de URL.
  - `unknownVariableInAnyField`: `{foo}` en URL, en una cabecera o en el cuerpo → `UNKNOWN_VARIABLE`.
  - `jsonBodyRequiresQuotedTextVariables`: `{"s":{sim}}` y `{"b":{body}}` → `BODY_NOT_JSON`; `{"s":"{sim}","b":"{body}","t":{received_at}}` → sin errores; con `bodyIsJson = false`, `{sim}` suelto → sin errores.
  - `standardPresetIsValidAndMatchesV1`: `standardJson("Main", "https://h.example", "t")` sin errores, `body == STANDARD_JSON_BODY` de la entrega 2 (copiar la constante a `:core` como `Templates.STANDARD_JSON_BODY` y que `:app` la use), cabecera `HeaderDraft("Authorization", "Bearer t", secret = true)`, `ANY_2XX`.
  - `telegramPreset`: `telegram("T", "123:abc", "-100")` → URL `https://api.telegram.org/bot123:abc/sendMessage`, cuerpo `{"chat_id":"-100","text":"{sender}: {body}"}`, JSON, sin errores; un chat id con `"` queda escapado y el cuerpo sigue parseando.
  - `ntfyPreset`: POST, `bodyIsJson = false`, cuerpo `{sender}: {body}`, sin cabeceras.
  - `getWithBodyRejected`: método GET y cuerpo no vacío → `BODY_WITH_GET`.
  - `headerChecks`: nombre `"X Bad"` → `HEADER_NAME_INVALID`; valor literal con `\n` o con `ñ` → `HEADER_VALUE_INVALID`; valor `"Bearer {sender}"` → válido.
  - `ruleValidation`: REGEX `"("` → `SENDER_REGEX_INVALID`; EXACT `"  "` → `SENDER_EMPTY`; `bodyMode = REGEX` con `"(a"` → `BODY_REGEX_INVALID`; `bodyMode = CONTAINS` con `""` → `BODY_EMPTY`; destinos vacíos → `NO_DESTINATIONS`; nombre en blanco → `NAME_EMPTY`; una regla correcta → vacío.
  - `draftToStringHidesSecrets`: `toString()` de un `DestinationDraft` con URL y token no contiene ninguno de los dos.
- [ ] **Step 2: Ejecutar** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test --tests '*DraftsTest'`; esperado: FAIL (no compila).
- [ ] **Step 3: Implementar `Drafts.kt`.** Nombre de cabecera: `^[!#$%&'*+.^_`|~0-9A-Za-z-]+$`. Valor de cabecera: la parte literal (sin las variables) solo ASCII imprimible (`0x20..0x7E`). JSON: sustitución de prueba descrita en "Decisiones" y `Json.parseToJsonElement`; un cuerpo en blanco no se valida como JSON. URL: sustituir variables por `x`/`0` y pasar `UrlValidator.isValid`.
- [ ] **Step 4: Ejecutar** el mismo comando; esperado: PASS.
- [ ] **Step 5: Commit** `feat(core): destination and rule drafts with validation and presets`.

### Task 2: Ajustes del motor de envío (lo diferido de la entrega 2)

**Files:**
- Modify: `core/.../SendOutcome.kt`, `core/.../DeliveryPolicy.kt`, `core/.../QueuePolicy.kt`, `app/.../net/DeliveryClient.kt`, `app/.../work/SendWorker.kt`, `app/.../work/WorkScheduler.kt`, `app/.../data/SmsRepository.kt`, `app/.../notify/Notifier.kt`, `res/values*/strings.xml`
- Test: `DeliveryPolicyTest.kt`, `QueuePolicyTest.kt`, `SendOutcomeTest.kt`, `DeliveryClientTest.kt`, `SendWorkerTest.kt`, `SmsRepositoryTest.kt`, `NotifierTest.kt`, nuevo `app/src/test/kotlin/dev/smsforwarder/work/WorkSchedulerTest.kt`

**Interfaces:**
- Produces:
  - `SendOutcome.Accepted` pasa a `data class Accepted(val httpCode: Int)`; nuevo `data class Invalid(val reason: String) : SendOutcome` (terminal); `val SendOutcome.httpCode: Int?`.
  - `DeliveryStatus.CANCELED`; `aggregateStatus` ignora `CANCELED` y devuelve `ERROR` si todas son `CANCELED`.
  - `data class DeliveryState(val status: DeliveryStatus, val lastHttpCode: Int?, val lastError: String?, val live: Boolean)` y `QueuePolicy.isStuck(deliveries: List<DeliveryState>, firstSeenAt: Long, now: Long, stuckAfterMs: Long): Boolean` (la versión por SMS se queda para quien la use).
  - `const val CONFIG_ERROR_PREFIX = "config_"`, `fun isConfigError(lastError: String?): Boolean`.
  - `internal fun kickPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy?` en `WorkScheduler` (`null` = no tocar).
  - `Notifier.tokenRejected(destinationId: Long, destinationName: String, waiting: Int)`; `Notifier.configError(item: SmsEntity, destinationName: String)`; `Notifier.cancelTokenRejected(destinationId: Long)` (la usa T3 al borrar).
- [ ] **Step 1: Tests que fallan en `:core`:**
  - `DeliveryPolicyTest.acceptedCarriesCode`: 204 con `ANY_2XX` → `Accepted(204)`.
  - `DeliveryPolicyTest.canceledIgnoredInAggregate`: `{SENT, CANCELED} → SENT`; `{QUEUED, CANCELED} → QUEUED`; `{CANCELED} → ERROR`.
  - `QueuePolicyTest.queuedDeliveryUnderErrorAggregateIsStuck`: `[ERROR 401 live, QUEUED live]`, 7 h, plazo 6 h → true.
  - `QueuePolicyTest.pausedOrCanceledNotStuck`: `[QUEUED live=false]` → false; `[CANCELED]` → false; `[ERROR 403]` → false; `[ERROR 400]` → true; `[ERROR null "config_template"]` → true.
- [ ] **Step 2: Tests que fallan en `:app`:**
  - `SmsRepositoryTest.acceptedStoresRealCode`: `recordAttempt(d, Accepted(204))` → entrega y SMS con `lastHttpCode == 204`.
  - `SmsRepositoryTest.invalidIsTerminalWithAttempt`: `Invalid("template")` → entrega `ERROR`, `lastError == "config_template"`, un intento con `httpCode == null`; SMS `ERROR`.
  - `SmsRepositoryTest.stuckSeesQueuedDeliveryUnderError`: SMS con una entrega `ERROR` 401 y otra `QUEUED` de hace 7 h → aparece en `stuckToNotify(6 h)`.
  - `DeliveryClientTest.malformedHeaderIsInvalid`: cabecera con valor no ASCII → `Invalid("request")`, cero peticiones a MockWebServer.
  - `SendWorkerTest.renderFailureRecordedOnce`: un destino cuyo `publicHeaders` lleva una cabecera inválida → una sola ejecución deja la entrega `ERROR` `config_request` con un intento, resultado `success` (no `retry`), y `configError` publicado.
  - `WorkSchedulerTest.kickSkipsRunning` (`[RUNNING]` → `null`), `kickReplacesWaiting` (`[ENQUEUED]` → `REPLACE`), `kickWithoutWork` (`[]` → `REPLACE`), `kickAfterFinished` (`[SUCCEEDED]` → `REPLACE`).
  - `NotifierTest.tokenRejectedNamesDestination`: el texto es `getString(R.string.notif_token_rejected_text, "Main", 3)`.
- [ ] **Step 3: Implementar.** `recordAttempt` toma el código de `outcome.httpCode`; trata `Invalid` como `ERROR` con `error = CONFIG_ERROR_PREFIX + reason`; ignora entregas `SENT` **y `CANCELED`**. `SendWorker.sendOne`: una excepción al construir `DestinationSpec` o en `Templates.render` (que no sea cancelación) registra `Invalid("template")` en vez de devolver "reintentar"; secretos ausentes siguen siendo "reintentar". `DeliveryClient`: `IllegalArgumentException` → `Invalid("request")`. Aviso: `Invalid` llama a `configError(sms, destinationName)` y `markStuckNotified`, como el 400. `stuckToNotify` construye los `DeliveryState` con `live = destino no borrado y activo`. `kickQueued` lee `WorkManager.getWorkInfosForUniqueWork("send-$id")` y aplica `kickPolicy`. Strings nuevas o cambiadas (en/es): `notif_token_rejected_text` = "Token rejected at %1$s: %2$d SMS waiting" / "Token rechazado en %1$s: %2$d SMS esperando" (copia de la spec §11); `notif_stuck_config` = "%1$s (%2$s) was not sent: %3$s is misconfigured" / "%1$s (%2$s) no se envió: %3$s está mal configurado"; `issue_token_rejected` deja de nombrar "the Worker": "A destination rejected its token" / "Un destino rechazó su token".
- [ ] **Step 4: Ejecutar** `:core:test` en local y CI para `:app`; esperado: PASS.
- [ ] **Step 5: Commit** `fix(work): real 2xx codes, terminal config errors, stuck by delivery, safe kick`.

### Task 3: Altas, ediciones, pausa y borrado de destinos

**Files:**
- Modify: `app/.../data/DestinationRepository.kt`, `DestinationDao.kt`, `DeliveryDao.kt`, `RuleDao.kt`, `SmsRepository.kt`, `health/HealthChecker.kt`, `AppGraph.kt`
- Test: nuevo `app/src/test/kotlin/dev/smsforwarder/data/DestinationRepositoryTest.kt`, `SmsRepositoryTest.kt`, `HealthCheckerTest.kt`

**Interfaces:**
- Consumes: `DestinationDraft`, `HeaderDraft`, `validateDestination` (T1); `DeliveryStatus.CANCELED`, `isConfigError` (T2); `DestinationSecretStore`.
- Produces:
  - `data class DestinationCounts(val destinationId: Long, val queued: Int, val errors: Int)`; `DeliveryDao.observeCounts(): Flow<List<DestinationCounts>>`, `DeliveryDao.smsIdsQueuedFor(destinationId: Long): List<Long>`, `DeliveryDao.pendingCountFor(destinationId: Long): Int` (`QUEUED` + `ERROR`).
  - `DestinationDao.observeLive(): Flow<List<DestinationEntity>>` (sin `deletedAt`, por id).
  - `DestinationRepository`: `fun observeLive(): Flow<List<DestinationEntity>>`; `suspend fun create(d: DestinationDraft): Long`; `suspend fun editable(id: Long): EditableDestination?` con `data class EditableDestination(val draft: DestinationDraft, val urlStored: Boolean)` (`draft.url == null`, cabeceras secretas con `value == null`); `suspend fun update(id: Long, d: DestinationDraft): List<Long>` (ids de SMS reencolados); `suspend fun setEnabled(id: Long, enabled: Boolean): List<Long>` (al reanudar, SMS con entrega `QUEUED` a ese destino); `suspend fun pendingCount(id: Long): Int`; `suspend fun delete(id: Long)`; `suspend fun unconfiguredInUse(): List<Long>`.
  - `SmsRepository`: `suspend fun cancelDeliveriesTo(destinationId: Long): List<Long>`; `suspend fun requeueConfigErrors(destinationId: Long): List<Long>`; `requeue(id)` no toca entregas `CANCELED`.
  - `DestinationRepository` recibe `SmsRepository` y `Notifier` por constructor (`AppGraph`); `SmsRepository` no depende de él.
- [ ] **Step 1: Tests que fallan** en `DestinationRepositoryTest` (Room en memoria + `FakeDestinationSecretStore`):
  - `createStoresSecretsApart`: personalizado con URL, cabecera pública `X-A` y secreta `X-Key` → `publicHeaders` solo con `X-A`; `secrets(id)` con la URL y `X-Key`.
  - `createRejectsInvalid`: borrador `http` → `IllegalArgumentException` y ninguna fila nueva.
  - `editableHidesSecrets`: `editable(id)` → `url == null`, `urlStored == true`, valor de `X-Key` `null`, `X-A` visible.
  - `updateKeepsBlankSecrets`: `update` con `url = null` y `X-Key` a `null` conserva ambos; con URL nueva la sustituye y conserva `X-Key`.
  - `updateRequeuesAuthAndConfigErrors`: entregas `ERROR` 403, `ERROR` `config_template` y `ERROR` 400 a ese destino → las dos primeras `QUEUED`, la 400 igual; devuelve los ids de esos dos SMS.
  - `deleteCancelsPendingKeepsFinished`: SMS A con destino 1 `SENT` y destino 2 `QUEUED`; SMS B solo con destino 2 `QUEUED`. `delete(2)` → entrega A/2 `CANCELED` con `destinationName` intacto, A queda `SENT`; B `ERROR` con `lastError == "destination_deleted"`; `secrets(2) == null`; ningún `rule_destination` con destino 2; `queuedIds()` no contiene B; `stuckToNotify` no contiene B.
  - `lateResultOnCanceledIgnored`: tras `delete(2)`, `recordAttempt(entrega B/2, Accepted(200))` deja la entrega `CANCELED` y B en `ERROR`.
  - `requeueSkipsCanceled`: `requeue(B)` no cambia la entrega `CANCELED`.
  - `resumeReturnsQueuedSms`: `setEnabled(2, false)`, ingesta de un SMS hacia 1 y 2; `setEnabled(2, true)` devuelve su id.
  - `HealthCheckerTest.notConfiguredWhenRuleTargetsDestinationWithoutSecrets` y `noIssueForUnlinkedDestinationWithoutSecrets`.
- [ ] **Step 2: Ejecutar en CI** `./gradlew :app:testDebugUnitTest --tests '*DestinationRepositoryTest' --tests '*HealthCheckerTest'`; esperado: FAIL.
- [ ] **Step 3: Implementar.** `create`: `require(validateDestination(d, urlStored = false).isEmpty())`, inserta la fila y guarda los secretos; si guardar secretos falla, borra la fila y relanza. `update`: completa con lo guardado, valida, guarda, y en la misma transacción reencola 401/403 (`requeueAuthErrors`) y errores de configuración. `delete`, en una transacción: `deletedAt = now`, `cancelDeliveriesTo` (recalcula el agregado de cada SMS afectado; si quedan solo `CANCELED`, `lastError = "destination_deleted"`), borra sus enlaces de regla. Después: `secretStore.remove(id)` y cancela su aviso de token (`tag` por destino). Los SMS que devuelven `update` y `setEnabled` los programa quien llama (T6).
- [ ] **Step 4: Ejecutar en CI**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(data): create, edit, pause and delete destinations`.

### Task 4: Altas, ediciones y bajas de reglas

**Files:**
- Modify: `app/.../data/RuleRepository.kt`, `RuleDao.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/data/RuleRepositoryTest.kt`

**Interfaces:**
- Consumes: `RuleDraft`, `validateRule` (T1).
- Produces:
  - `RuleDao`: `observeAllWithDestinations(): Flow<List<RuleWithDestinations>>`, `getWithDestinations(id): RuleWithDestinations?`, `update(rule)`, `delete(id)`, `deleteLinks(ruleId)`, `deleteLink(ruleId, destinationId)`, `linkCount(ruleId): Int`.
  - `RuleRepository`: `fun observeAll(): Flow<List<RuleWithDestinations>>` (destinos borrados fuera); `suspend fun draft(id: Long): RuleDraft?`; `suspend fun create(r: RuleDraft): Long`; `suspend fun update(id: Long, r: RuleDraft)`; `suspend fun setEnabled(id: Long, enabled: Boolean)`; `suspend fun delete(id: Long)`.
- [ ] **Step 1: Tests que fallan** en `RuleRepositoryTest`:
  - `createAndReadBack`: `create` y `draft(id)` devuelven el mismo borrador.
  - `invalidRuleRejected`: regex `"("` → `IllegalArgumentException`, ninguna fila.
  - `updateReplacesDestinations`: `{1}` → `{2}`; `activeRules()` solo con 2.
  - `disabledRuleNotActive`: `setEnabled(id, false)` → fuera de `activeRules()` y `hasActiveRules()` false si era la única.
  - `deleteRemovesRuleAndLinks`.
  - `editingKeepsQueuedDeliveries`: una entrega `QUEUED` creada por la regla sigue `QUEUED` tras `update` y tras `delete` (spec §8).
  - `removeSenderKeepsMultiDestinationRule`: regla EXACT `"AAA"` hacia `{primario, 2}`; `removeSender("AAA")` → la regla sigue, enlazada solo a 2, y `senders` ya no la lista; con solo el primario, la regla desaparece.
- [ ] **Step 2: Ejecutar en CI**; esperado: FAIL.
- [ ] **Step 3: Implementar.** `create`/`update` hacen `require(validateRule(r).isEmpty())` y escriben regla y enlaces en una transacción. `removeSender` usa `deleteLink` y borra la regla si `linkCount == 0`.
- [ ] **Step 4: Ejecutar en CI**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(data): create, edit and delete rules`.

### Task 5: Probar un destino

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/DestinationTest.kt`, `app/src/main/kotlin/dev/smsforwarder/net/DestinationTester.kt`
- Modify: `AppGraph.kt`, `res/values*/strings.xml` (`destination_test_body`)
- Test: `core/.../DestinationTestTest.kt`, `app/src/test/kotlin/dev/smsforwarder/net/DestinationTesterTest.kt`

**Interfaces:**
- Consumes: `DestinationDraft` (T1), `SendOutcome.httpCode` (T2), `DeliveryClient`, `DestinationSecretStore`, `Templates.render`, `testSmsVars`.
- Produces:
  - `enum class TestVerdict { OK, BAD_TOKEN, FAILED, UNREACHABLE }`, `data class DestinationTestResult(val httpCode: Int?, val verdict: TestVerdict)`.
  - `fun interpretDestinationTest(template: TemplateKind, outcome: SendOutcome): TestVerdict`: `STANDARD_JSON` → 400 `OK`, 401/403 `BAD_TOKEN`, sin código `UNREACHABLE`, resto `FAILED`; las demás → `Accepted` `OK`, 401/403 `BAD_TOKEN`, sin código `UNREACHABLE`, `Invalid` y resto `FAILED`.
  - `class DestinationTester(client: DeliveryClient, secrets: DestinationSecretStore, testBody: String, device: String, clock: Clock) { suspend fun test(d: DestinationDraft, existingId: Long?): DestinationTestResult }`. No valida (lo hace el ViewModel, T6), no escribe en la base ni en el log.
- [ ] **Step 1: Tests que fallan:**
  - `DestinationTestTest`: tabla de casos de `interpretDestinationTest` para las dos ramas.
  - `DestinationTesterTest.standardSendsEmptyJsonAnd400IsOk` (MockWebServer recibe POST con cuerpo `{}` y `Authorization`; nunca el SMS de prueba), `customSendsSampleSms` (cuerpo con `TEST` y `getString(R.string.destination_test_body)`; 200 → `OK`, código 200), `blankFieldsUseStoredSecrets` (`url = null` y cabecera secreta `null` con `existingId` → la petición lleva la URL y el valor guardados), `serverErrorIsFailedWithCode` (500 → `FAILED`, 500), `unreachable` (servidor apagado → `UNREACHABLE`, `null`), `nothingRecorded` (ningún intento ni entrega en la base tras probar).
- [ ] **Step 2: Ejecutar** `:core:test` en local y CI; esperado: FAIL.
- [ ] **Step 3: Implementar.** `STANDARD_JSON`: misma petición que `IngestClient.testConnection` (POST `{}` con las cabeceras del borrador) pero por `DeliveryClient`, para que `IngestClient` pueda desaparecer; si nada más lo usa, se borra junto con `IngestClientTest` en esta tarea.
- [ ] **Step 4: Ejecutar**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(net): test a destination from its form`.

### Task 6: ViewModels de Destinos

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/destinations/DestinationsViewModel.kt`, `DestinationEditorViewModel.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/DestinationsViewModelTest.kt`, `DestinationEditorViewModelTest.kt`

**Interfaces:**
- Consumes: T1 (borradores, presets, validación), T3 (`DestinationRepository`), T5 (`DestinationTester`).
- Produces:
  - `data class DestinationRowUi(val id: Long, val name: String, val template: TemplateKind, val enabled: Boolean, val queued: Int, val errors: Int)`; `class DestinationsViewModel(repo: DestinationRepository, enqueue: (List<Long>) -> Unit) { val rows: StateFlow<List<DestinationRowUi>>; fun setEnabled(id: Long, enabled: Boolean) }` + `Factory`.
  - `data class PresetFields(val url: String = "", val token: String = "", val chatId: String = "")`; `data class HeaderRowUi(val name: String, val value: String, val secret: Boolean, val stored: Boolean)`.
  - `data class DestinationEditorState(val loaded: Boolean, val isNew: Boolean, val template: TemplateKind, val preset: PresetFields, val name: String, val enabled: Boolean, val method: HttpMethod, val url: String, val urlStored: Boolean, val headers: List<HeaderRowUi>, val body: String, val bodyIsJson: Boolean, val successPolicy: SuccessPolicy, val errors: Set<DestinationError>, val test: DestinationTestResult?, val busy: Boolean, val deletePending: Int?, val done: Boolean)` con `toString()` sin secretos.
  - `class DestinationEditorViewModel(id: Long?, repo: DestinationRepository, tester: DestinationTester, enqueue: (List<Long>) -> Unit, defaultName: (TemplateKind) -> String)`: `chooseTemplate(k)`, `setPreset(p: PresetFields)`, `applyPreset()`, `setName`, `setEnabled`, `setMethod`, `setUrl`, `setHeaders(List<HeaderRowUi>)`, `setBody`, `setBodyIsJson`, `setSuccessPolicy`, `test()`, `save()`, `requestDelete()`, `confirmDelete()`, `dismissDelete()` + `Factory`.
- [ ] **Step 1: Tests que fallan:**
  - `DestinationsViewModelTest.listShowsCounts` (dos destinos, uno con 2 `QUEUED` y 1 `ERROR`), `resumeEnqueuesQueued` (`setEnabled(id, true)` llama a `enqueue` con los ids de T3).
  - `DestinationEditorViewModelTest.telegramPresetFillsForm`: `chooseTemplate(TELEGRAM)`, `setPreset(token, chatId)`, `applyPreset()` → `url`, `body` y `bodyIsJson` como `DestinationPresets.telegram`; `name == defaultName(TELEGRAM)`.
  - `saveRejectsUnquotedSim`: cuerpo `{"s":{sim}}` → `errors` contiene `BODY_NOT_JSON`, ninguna fila nueva, `done == false`.
  - `saveRejectsHttpAndUnknownVariable`.
  - `testBlockedWhenInvalid`: con errores, `test()` no llama al tester y muestra los errores.
  - `editLoadsWithoutSecrets`: abrir un destino guardado → `url == ""`, `urlStored == true`, cabecera secreta con `value == ""` y `stored == true`; `state.toString()` no contiene la URL ni el token guardados.
  - `saveEnqueuesRequeued`: `save()` de un destino con entregas 401 → `enqueue` recibe esos ids; `done == true`.
  - `deleteAsksWithPendingCount`: `requestDelete()` → `deletePending == 3`; `confirmDelete()` → destino borrado y `done == true`; `dismissDelete()` no borra nada.
- [ ] **Step 2: Ejecutar en CI**; esperado: FAIL.
- [ ] **Step 3: Implementar.** Una fila de cabecera secreta `stored` con valor vacío se traduce a `HeaderDraft(value = null)`; `url` vacía con `urlStored` a `null`. La plantilla solo se elige al crear; después el formulario es el mismo para todas (spec §10). `save()` y `test()` corren `validateDestination` antes.
- [ ] **Step 4: Ejecutar en CI**; esperado: PASS.
- [ ] **Step 5: Commit** `feat(ui): destination list and editor view models`.

### Task 7: Pestaña Destinos, navegación y Ajustes sin Conexión

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/destinations/DestinationsScreen.kt`, `DestinationEditorScreen.kt`
- Modify: `ui/Nav.kt`, `ui/AppShell.kt`, `ui/MainActivity.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `res/values*/strings.xml`
- Test: `AppShellTest.kt`, `SettingsScreenTest.kt`, `SettingsViewModelTest.kt`, nuevos `app/src/test/kotlin/dev/smsforwarder/ui/NavTest.kt`, `DestinationsScreenTest.kt`, `DestinationEditorScreenTest.kt`

**Interfaces:**
- Consumes: T6.
- Produces:
  - `Routes.DESTINATIONS = "destinations"`, `Routes.RULES = "rules"` (la usa T8), `Routes.destination(id: Long?)` (`"destination/new"` o `"destination/<id>"`), `Routes.destinationTarget(route: String): DestinationTarget?` con `sealed interface DestinationTarget { data object New; data class Edit(val id: Long) }`; `Routes.isTab` incluye `DESTINATIONS` y `RULES`; `stackForTab(tab)` = `main` o `main|<tab>`.
  - `internal fun issueTarget(issue: HealthIssue): String?` (`TOKEN_REJECTED`, `NOT_CONFIGURED` → `DESTINATIONS`; `NO_RULES` → `RULES`; resto `null` = pantalla del sistema). Sustituye a `IN_APP_ISSUES`. Hasta la T8, `NO_RULES` sigue yendo a `SETTINGS`.
  - `@Composable fun DestinationsScreen(rows: List<DestinationRowUi>, onOpen: (Long) -> Unit, onAdd: () -> Unit, onToggle: (Long, Boolean) -> Unit, padding: PaddingValues)` y `@Composable fun DestinationEditorScreen(state: DestinationEditorState, vm: DestinationEditorViewModel, onBack: () -> Unit)`.
  - `SettingsViewModel` pierde `testConnection`, `loadSaved`, `saveCredentials`, `form`, `setUrl`, `setToken`, `save`, `test`; `SettingsScreen` pierde el grupo Conexión.
- [ ] **Step 1: Consultar al usuario** el icono de la pestaña Destinos, el orden final de la barra (spec: Inicio, Reglas, Destinos, Ajustes), cómo se ve una fila de la lista (nombre, tipo, pausa, contadores) y la disposición del editor (selector de plantilla arriba al crear, cabeceras como lista con interruptor "secreta"). Enseñar un boceto en texto o captura; no avanzar sin OK.
- [ ] **Step 2: Tests que fallan:**
  - `NavTest.stackForTabPinsUnderMain` (`DESTINATIONS` → `"main|destinations"`), `destinationRoutesRoundTrip` (`destination(null)` → `New`, `destination(7)` → `Edit(7)`, `"detail/3"` → `null`), `issueTargets`.
  - `AppShellTest.destinationsTabShown`: la barra muestra `getString(R.string.tab_destinations)` y tocarla llama a `onTab(Routes.DESTINATIONS)`.
  - `DestinationsScreenTest.showsRowsAndPausedState` (nombre, `getString(R.string.destination_paused)` en la pausada, contadores), `emptyStateShown` (sin destinos, texto `destinations_empty`).
  - `DestinationEditorScreenTest.storedSecretsNotShown`: con `urlStored` y cabecera secreta guardada, ningún nodo contiene el valor; el campo muestra `getString(R.string.destination_secret_kept)`.
  - `DestinationEditorScreenTest.deleteDialogShowsCount`: `deletePending = 3` → texto `getQuantityString(R.plurals.destination_delete_confirm, 3, 3)`.
  - `SettingsScreenTest.noConnectionGroup`: no aparece `getString(R.string.settings_group_connection)`.
- [ ] **Step 3: Ejecutar en CI**; esperado: FAIL.
- [ ] **Step 4: Implementar.** El editor es pantalla completa sin barra inferior, como el detalle; `done` vuelve atrás. `MainActivity` crea los ViewModels con sus `Factory` (`enqueue = { ids -> ids.forEach { WorkScheduler.enqueueSend(ctx, it) } }`) y el editor con `remember(target)`, como `detailViewModel`. Strings nuevas (en/es) para pestaña, lista, editor, plantillas, métodos, errores de `DestinationError`, resultado de la prueba (`TestVerdict` + código), pausa y borrado.
- [ ] **Step 5: Ejecutar en CI**; esperado: PASS.
- [ ] **Step 6: Commit** `feat(ui): destinations tab and editor`.

### Task 8: Pestaña Reglas y Ajustes sin Remitentes

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/rules/RulesViewModel.kt`, `RuleEditorViewModel.kt`, `RulesScreen.kt`, `RuleEditorScreen.kt`
- Modify: `ui/Nav.kt`, `ui/AppShell.kt`, `ui/MainActivity.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `res/values*/strings.xml`
- Test: `NavTest.kt`, `AppShellTest.kt`, `SettingsScreenTest.kt`, nuevos `RulesViewModelTest.kt`, `RuleEditorViewModelTest.kt`, `RulesScreenTest.kt`, `RuleEditorScreenTest.kt`

**Interfaces:**
- Consumes: T1 (`RuleDraft`, `validateRule`), T3 (`DestinationRepository.observeLive`), T4 (`RuleRepository`), T7 (`Routes.RULES`, `issueTarget`).
- Produces:
  - `Routes.rule(id: Long?)`, `Routes.ruleTarget(route): RuleTarget?` (`New` / `Edit(id)`).
  - `data class RuleRowUi(val id: Long, val name: String, val enabled: Boolean, val senderMode: SenderMode, val senderPattern: String, val hasBodyFilter: Boolean, val sim: SimMatch, val destinationNames: List<String>)`; `class RulesViewModel(rules: RuleRepository) { val rows: StateFlow<List<RuleRowUi>>; fun setEnabled(id: Long, v: Boolean) }`.
  - `data class RuleEditorState(val loaded: Boolean, val isNew: Boolean, val draft: RuleDraft, val destinations: List<DestinationEntity>, val errors: Set<RuleError>, val deleteAsked: Boolean, val done: Boolean)`; `class RuleEditorViewModel(id: Long?, rules: RuleRepository, destinations: DestinationRepository)` con `update(transform: (RuleDraft) -> RuleDraft)`, `save()`, `requestDelete()`, `confirmDelete()`, `dismissDelete()`.
  - `SettingsViewModel` pierde `senders`, `addSender`, `removeSender`; `SettingsScreen` pierde el grupo Remitentes.
- [ ] **Step 1: Consultar al usuario** el icono de la pestaña Reglas, la fila de la lista (resumen del remitente, filtro de texto, SIM, destinos) y la disposición del editor (selector de modo de remitente y de texto, SIM, casillas de destinos). No avanzar sin OK.
- [ ] **Step 2: Tests que fallan:**
  - `RuleEditorViewModelTest.invalidRegexBlocksSave` (`SENDER_REGEX_INVALID`, ninguna regla nueva), `noDestinationBlocksSave`, `saveCreatesRule` (`done`, regla en `activeRules()`), `editLoadsExisting`, `pausedDestinationsListed` (un destino en pausa aparece en `destinations`), `deleteAsksFirst`.
  - `RulesViewModelTest.rowsShowDestinationNames`, `toggleDisables`.
  - `RulesScreenTest.ruleWithoutDestinationsWarns`: una regla cuyo único destino se borró muestra `getString(R.string.rule_no_destinations)`.
  - `RulesScreenTest.emptyStateShown` (`rules_empty`).
  - `RuleEditorScreenTest.regexErrorShown`: con `SENDER_REGEX_INVALID`, se ve `getString(R.string.rule_error_regex)`.
  - `NavTest.issueTargets` actualizado: `NO_RULES` → `RULES`. `AppShellTest.fourTabsInSpecOrder`: Inicio, Reglas, Destinos, Ajustes.
  - `SettingsScreenTest.noSendersGroup`.
- [ ] **Step 3: Ejecutar en CI**; esperado: FAIL.
- [ ] **Step 4: Implementar.** Ayuda bajo el campo de remitente según el modo: exacto/contiene ignoran espacios y mayúsculas; regex se aplica tal cual (puede usar `(?i)`), textos de la spec §8 en en/es. Strings nuevas (en/es) para pestaña, modos, SIM, errores de `RuleError`, vacío y borrado.
- [ ] **Step 5: Ejecutar en CI**; esperado: PASS.
- [ ] **Step 6: Commit** `feat(ui): rules tab and editor`.

### Task 9: Inicio y detalle por destino

**Files:**
- Modify: `data/SmsDao.kt` o `DeliveryDao.kt` (flujo de entregas de todos los SMS), `data/SmsRepository.kt`, `ui/main/MainViewModel.kt`, `ui/main/MainScreen.kt`, `ui/detail/DetailViewModel.kt`, `ui/detail/DetailScreen.kt`, `ui/MainActivity.kt`, `res/values*/strings.xml`
- Test: `MainViewModelTest.kt`, `MainScreenTest.kt`, `DetailScreenTest.kt`, `SmsRepositoryTest.kt`, nuevo `DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `DeliveryStatus.CANCELED`, `isConfigError` (T2); `DestinationRepository.observeLive` (T3).
- Produces:
  - `DeliveryDao.observeAll(): Flow<List<DeliveryEntity>>` (entregas de SMS no borrados); el ViewModel las agrupa por `smsId`.
  - `data class DeliveryChipUi(val destinationName: String, val status: DeliveryStatus, val httpCode: Int?, val configError: Boolean)`; `RowUi.deliveries: List<DeliveryChipUi>`.
  - `MainUiState.destinations: List<Pair<Long, String>>`, `MainUiState.destinationFilter: Long?`; `MainViewModel.setDestinationFilter(id: Long?)`. Filtro: el SMS tiene una entrega a ese destino.
  - `SmsRepository.resendTo(smsId: Long, destinationId: Long)`: crea la entrega `QUEUED` si no existe (con el nombre actual) o pasa la existente a `QUEUED` (aunque estuviera `SENT` o `CANCELED`); recalcula el agregado; no hace nada si el SMS está borrado o el destino no existe.
  - `DetailUiState.deliveries: List<DeliveryEntity>`, `DetailUiState.destinations: List<DestinationEntity>`; `DetailViewModel.resendTo(destinationId: Long)` (llama a `enqueueSend` después).
  - `@Composable fun deliveryLine(chips: List<DeliveryChipUi>): String` en `ui/main/Strings.kt`.
- [ ] **Step 1: Consultar al usuario** el texto de cada estado en la línea de la fila (la spec fija solo `Principal ✓200 · Telegram en cola`; propuesta: error `Telegram ✗403`, configuración `Telegram: revisar destino`, cancelada `Telegram: cancelado`), dónde van los chips de destino respecto a los de estado, y cómo se elige el destino en "Reenviar a un destino" (diálogo con lista). No avanzar sin OK.
- [ ] **Step 2: Tests que fallan:**
  - `MainViewModelTest.rowShowsPerDestinationStatus`: SMS con entrega `SENT` 204 a "Main" y `QUEUED` a "T2" → `deliveries` en ese orden con esos datos.
  - `MainViewModelTest.destinationFilterKeepsOnlyMatching`: tres SMS, uno sin entrega al destino 2; filtro 2 → dos filas; contadores sin cambio.
  - `MainScreenTest.rowRendersDeliveryLine`: el texto es `getString(R.string.delivery_sent, "Main", 204) + " · " + getString(R.string.delivery_queued, "T2")`.
  - `SmsRepositoryTest.resendToSentDestinationQueuesAgain` (entrega `SENT` → `QUEUED`, SMS `QUEUED`), `resendToNewDestinationCreatesDelivery`, `resendToDeletedSmsIsNoop`, `resendAfterDestinationDeletedGoesElsewhere` (SMS en `ERROR` `destination_deleted` → `resendTo(1)` lo deja `QUEUED` con la entrega `CANCELED` intacta).
  - `DetailViewModelTest.resendEnqueues`.
  - `DetailScreenTest.showsDeliveriesAndResend`: una línea por entrega con su estado; el botón `getString(R.string.detail_resend_to)` abre la lista de destinos vivos; cada intento muestra el nombre de su destino.
- [ ] **Step 3: Ejecutar en CI**; esperado: FAIL.
- [ ] **Step 4: Implementar.** `MainViewModel` combina el flujo de entregas y `observeLive()` dentro del `view` actual para seguir en cinco flujos en el `combine` principal. Los intentos del detalle se emparejan con su entrega por `deliveryId`; los de 1.1 (sin `deliveryId`) no llevan nombre.
- [ ] **Step 5: Ejecutar en CI**; esperado: PASS.
- [ ] **Step 6: Commit** `feat(home): per-destination status, destination filter and resend`.

### Task 10: Cierre de la entrega 1.3.0

**Files:**
- Modify: `docs/manual-test-checklist.md` (apartado `## v1.3.0`), `docs/superpowers/specs/2026-11-02-v2-redesign.md` (§3 estado, §5, §7 Conexión y Remitentes ya no existen, §9, §11 y decisiones de este plan), `res/values*/strings.xml` (solo si el usuario cambia textos)

- [ ] **Step 1: Textos para el usuario.** Enseñar en/es de la tarjeta y la notificación de importación fallida (`issue_migration_failed`, `notif_issue_migration_failed`, escritas en la entrega 2 sin revisar) y de los textos nuevos de avisos (`notif_stuck_config`, `issue_token_rejected`). Aplicar lo que pida.
- [ ] **Step 2: Capturas.** Inicio, Reglas, editor de regla, Destinos, editor de destino y detalle, en claro y oscuro, reducidas a JPEG de 1000 px; compararlas con un subagente sonnet si son más de tres. Enseñarlas al usuario y esperar OK; lo que no esté aprobado en las tareas anteriores se corrige.
- [ ] **Step 3: Revisión OCR** de la rama entera contra `v2-delivery-2` (`ocr delegate rule`, modelo según el diff; esperable opus). Corregir High y Medium, cada uno con su test.
- [ ] **Step 4: Lista manual nueva** en `docs/manual-test-checklist.md`: crear un destino de cada plantilla y probarlo (el JSON estándar con `{}`, 400 = correcto; Telegram y ntfy reciben el SMS de prueba); un destino caído junto a otro que funciona; pausar y reanudar con SMS en cola; borrar un destino con SMS en cola (aviso con el número, entregas canceladas, nada atascado); regla regex con `(?i)` y otra con filtro de texto; regla con SIM 2 en un teléfono de una SIM; editar el token de un destino con 401 y ver que se reenvía; "Reenviar a un destino" desde el detalle; filtro por destino en Inicio; cambio de idioma en los editores; actualizar de 1.2.0 a 1.3.0 sin perder destinos, reglas ni historial.
- [ ] **Step 5: Anotar en la spec** el estado de §3 y las decisiones de la cabecera de este plan.
- [ ] **Step 6: Commit** `docs: 1.3.0 checks and spec status`.
- [ ] **Step 7: Con OK del usuario, en este orden:** (a) que la 1.2.0 esté fusionada en `main` y etiquetada `v1.2.0`; si no, se hace antes con su propia lista; (b) rebase de `v2-delivery-3` sobre `main` si hace falta, push y CI en verde; (c) fast-forward de `main` y `git tag v1.3.0 && git push origin v1.3.0`; (d) `adb install -r` de la APK firmada de la release sobre la 1.2.0 y recorrido de la lista con el usuario. Los fallos son tareas nuevas.
