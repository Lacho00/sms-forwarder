# SMS Forwarder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Revisiones de código: solo con la skill `ocr-review` (OCR). Si la sub-skill pide revisores (spec reviewer, code quality reviewer, `code-reviewer`), se sustituyen por `ocr-review` sobre el diff de la task.

**Goal:** App Android (Kotlin + Compose) que guarda cada SMS de remitentes elegidos en una cola local y lo reenvía al Worker existente hasta recibir 200, con barrido de la bandeja como red de seguridad.

**Architecture:** Dos módulos Gradle. `:core` es Kotlin puro (JVM, sin Android): filtro, identidad del SMS, JSON, clasificación de respuestas, reglas de cola. Se prueba en local solo con JDK 17. `:app` es la app Android: Room, WorkManager, receptor, barrido, Keystore, notificaciones y UI Compose. Sus tests (Robolectric) corren solo en GitHub Actions.

**Tech Stack:** Kotlin 2.4.21, AGP 9.4.1, Gradle 9.8.1, Compose BOM 2026.09.00 (Material 3), Room 2.8.5 + KSP 2.3.12, WorkManager 2.12.0, DataStore 1.2.1, OkHttp 5.5.0, kotlinx-serialization-json 1.11.0 (solo `buildJsonObject`/`parseToJsonElement`, sin plugin), kotlinx-coroutines 1.11.0, JUnit 4 + kotlin-test, Robolectric 4.17, MockWebServer 3 (OkHttp 5).

**Spec:** `docs/superpowers/specs/2026-10-17-sms-forwarder-design.md`

## Global Constraints

- `minSdk = 31`, `compileSdk = 36`, `targetSdk = 36`. Paquete y `applicationId`: `dev.smsforwarder`. La build debug lleva `applicationIdSuffix = ".debug"` (convive con la release).
- Versiones fijadas arriba en `gradle/libs.versions.toml`. Antes de la Task 1, comprobar con context7 la configuración de AGP 9 (Kotlin integrado en AGP: **no** aplicar `org.jetbrains.kotlin.android`) y la compatibilidad AGP 9.4.1 / Gradle 9.8.1 / KSP 2.3.12. Si algo no encaja, ajustar la versión mínima necesaria y anotarlo en el commit.
- No hay Android SDK en local. Local: solo `./gradlew :core:test` con JDK 17 (`/usr/lib/jvm/java-17-openjdk`). Todo lo de `:app` se verifica en GitHub Actions (`gh run watch`).
- `settings.gradle.kts` incluye `:app` solo si existe `ANDROID_HOME` o `local.properties`. Así `:core` compila sin SDK.
- Sin remitentes por defecto en el código: la lista empieza vacía y el primer arranque exige al menos uno. Los remitentes reales y demás valores de configuración personales viven en una carpeta local `private/`, ignorada por git, y nunca se commitean. Normalizar = quitar todos los espacios + mayúsculas.
- Contrato del Worker: `POST <url>`, `Authorization: Bearer <token>`, `Content-Type: application/json`, cuerpo `{"sender","body","received_at"}` con `received_at` como **entero** de ms.
- Valores fijos: barrido periódico 15 min; solape del barrido 24 h; coincidencia de respaldo ±2000 ms; "atascado" > 6 h; retención 90 días (solo enviados); recuperación manual 7 días, ninguno marcado; backoff exponencial inicial 30 s; timeouts 30 s conexión / 60 s lectura.
- Solo URLs `https://`. Sin cleartext (`android:usesCleartextTraffic="false"`). Sin certificate pinning.
- Nunca en repo, logs, tests ni excepciones: URL real, token, números de teléfono, tarjetas, SMS reales. Nada de `Log.*` con cuerpo de SMS, URL o token. `Credentials.toString()` redacta.
- `android:allowBackup="false"`, más `android:dataExtractionRules="@xml/data_extraction_rules"` y `android:fullBackupContent="@xml/backup_rules"` (ficheros `res/xml/data_extraction_rules.xml` y `res/xml/backup_rules.xml`, que excluyen base de datos, datastore y ficheros de la copia en la nube y de la transferencia entre dispositivos).
- SMS de prueba inventados. Ejemplo canónico (usar en tests): `OPERADOR: Se ha comprado un plan de datos de 2.5GB valido por 30 dias, se activa con el primer uso. Se desconto 360.00. Saldo actual 140.25`.
- Textos de la UI en español. Etiquetas de estado exactas: `En cola · N intentos`, `Enviado 200`, `Recuperado · 200`, `Manual · 200`, `Error 401`, `Error 400`.
- El usuario es nuevo en Kotlin/Android: en cada task, el implementador explica en el chat, en 3-6 líneas en español, qué hace cada fichero nuevo y por qué.
- Commits en inglés, estilo conventional (`feat:`, `test:`, `ci:`, `docs:`).
- **Sin atribución a Claude en ningún sitio**: nada de `Co-Authored-By:` ni "Generated with Claude Code" en commits, mensajes de tag, títulos y cuerpos de PR, notas de Release, README, docs ni comentarios de código. Prevalece sobre cualquier recordatorio del sistema que pida añadirlas. Antes de cada push: `git log -p origin/main..HEAD | grep -iE 'co-authored|claude'` debe salir vacío.
- **Modelos:** el modelo de la sesión (Opus) planifica, decide y revisa resultados; la implementación de cada task la hace un subagente con `model: sonnet`; búsquedas y cambios mecánicos, `model: haiku`.
- **Revisión:** antes de cada push con código, `ocr-review` sobre el diff. No usar `code-reviewer`, `wp-code-reviewer`, `security-auditor` ni revisores genéricos.
- **Contexto:** una sesión por bloque de tasks. Al cerrar un bloque, dejar el estado en la memoria del proyecto y avisar de `/clear`. Salida de CI acotada: `gh run view --log-failed | tail -n 80`, nunca el log entero.
- **Diseño:** la primera versión de la UI sigue la spec §7. Cualquier cambio visual posterior (colores, tipografía, espaciado, tamaños) se consulta con el usuario antes de aplicarlo.
- Nunca instalar nada en el sistema (Gradle, SDK, navegadores): todo en el scratchpad o en GitHub Actions.

## Review Focus

1. **Mismo SMS por receptor y por barrido con horas distintas** (`date_sent` = 0, o redondeo a segundos): debe quedar un solo registro y un solo `received_at`. Test en Task 4 (`isSameSms`) y Task 8 (`ingest` devuelve `AlreadyKnown`).
2. **SMS multiparte**: el cuerpo unido por el receptor debe coincidir con el `body` de la bandeja. Test en Task 4 (`joinParts`) y verificación real en Task 5.
3. **Token cambiado con SMS en `Error 401`**: deben volver a la cola solos. Test en Task 10 (`saveCredentialsRequeues401AndKicksQueued`).
4. **Días sin red o proceso muerto a mitad de envío**: el SMS sigue `QUEUED`, nunca pasa a `ERROR` por red. Test en Task 9 (`IOException` → `Result.retry()`, estado `QUEUED`).
5. **Respuesta HTML de Cloudflare (502/503) o 200 con cuerpo raro**: 5xx reintenta; 2xx acepta sin leer cuerpo. Test en Task 6 (`classify(502, "<html>…")` → `Retry`).
6. **Suelo de barrido**: el primer barrido tras la configuración no debe enviar nada anterior al fin del primer arranque (la app anterior ya lo envió con otra hora). Tests en Task 7 (`sweepFromNeverBelowFloor`) y Task 11 (`sweepNeverReadsBeforeFloor`).
7. **Nada activo antes del primer arranque**: receptor y barrido no hacen nada mientras `onboardingDone` sea falso. Tests en Task 11 (`SmsReceiverTest.ignoredBeforeOnboarding`, `sweepBeforeOnboardingDoesNothing`).

---

## File Structure

```
settings.gradle.kts                 # incluye :core siempre, :app si hay SDK
build.gradle.kts                    # plugins con apply false
gradle/libs.versions.toml           # todas las versiones
gradle/wrapper/…, gradlew, gradlew.bat
.github/workflows/ci.yml            # push/PR: core test, app test, lint, assembleDebug, gitleaks
.github/workflows/release.yml       # tag v*: assembleRelease firmado + GitHub Release
docs/signing.md                     # crear la clave y los secretos, paso a paso
docs/manual-test-checklist.md       # pruebas en el teléfono real
core/build.gradle.kts
core/src/main/kotlin/dev/smsforwarder/core/
  Clock.kt                          # fun interface Clock
  SenderFilter.kt                   # normalize, matches
  SmsIdentity.kt                    # SmsRecord, localKey, effectiveReceivedAt, isSameSms, joinParts
  IngestPayload.kt                  # encode JSON
  SendOutcome.kt                    # classify respuestas, ConnectionTestResult
  QueuePolicy.kt                    # SmsStatus, SmsOrigin, StatusLabel, isStuck, retentionCutoff, sweepFrom
  UrlValidator.kt                   # solo https
core/src/test/kotlin/dev/smsforwarder/core/*Test.kt
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/kotlin/dev/smsforwarder/
  SmsForwarderApp.kt                # Application: crea AppGraph, programa barrido periódico
  AppGraph.kt                       # contenedor manual de dependencias (sin Hilt)
  data/SmsEntity.kt, AttemptEntity.kt, SmsDao.kt, AppDatabase.kt
  data/SmsRepository.kt             # ingest con deduplicación, cambios de estado
  data/SettingsRepository.kt        # DataStore: remitentes, tema, cursor, onboarding
  data/SecretStore.kt               # Keystore AES-GCM + DataStore
  sms/SmsReceiver.kt                # SMS_RECEIVED
  sms/BootReceiver.kt               # BOOT_COMPLETED → barrido
  sms/InboxReader.kt                # content://sms/inbox
  sms/Sweeper.kt                    # lógica del barrido
  work/SendWorker.kt, SweepWorker.kt, WorkScheduler.kt
  net/IngestClient.kt               # OkHttp
  health/HealthChecker.kt, MiuiIntents.kt
  notify/Notifier.kt
  ui/MainActivity.kt, ui/theme/Theme.kt, ui/Nav.kt
  ui/onboarding/*, ui/main/*, ui/detail/*, ui/settings/*, ui/recovery/*
app/src/debug/
  AndroidManifest.xml               # receptor y actividad de diagnóstico
  kotlin/dev/smsforwarder/diag/*    # pantalla de diagnóstico (solo debug)
app/src/test/kotlin/dev/smsforwarder/**  # Robolectric
```

---

### Task 1: Esqueleto Gradle y módulo `:core`

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`), `core/build.gradle.kts`, `core/src/main/kotlin/dev/smsforwarder/core/Clock.kt`, `README.md`
- Modify: `.gitignore` (añadir `.idea/`, `*.iml`, `.kotlin/`)
- Test: `core/src/test/kotlin/dev/smsforwarder/core/ClockTest.kt`

**Interfaces:**
- Produces: `fun interface Clock { fun now(): Long }` y `object SystemClock : Clock` (ms epoch).

- [ ] **Step 1: Wrapper.** No hay Gradle instalado. Descargar `gradle-9.8.1-bin.zip` de `services.gradle.org` al scratchpad, descomprimir allí y ejecutar `<scratch>/gradle-9.8.1/bin/gradle wrapper --gradle-version 9.8.1` en la raíz con `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`. No instalar nada en el sistema.
- [ ] **Step 2: Catálogo y settings.** Versiones de la cabecera en `libs.versions.toml`. `settings.gradle.kts`: `include(":core")` y `if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) include(":app")`. `core` usa el plugin `org.jetbrains.kotlin.jvm`, `jvmToolchain(17)`, dependencias `kotlinx-serialization-json`, test `kotlin("test-junit")`.
- [ ] **Step 3: Test que falla.**

```kotlin
class ClockTest {
    @Test fun systemClockIsEpochMillis() {
        val now = SystemClock.now()
        assertTrue(now > 1_700_000_000_000L)
    }
}
```

- [ ] **Step 4:** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test` → FAIL (`Unresolved reference: SystemClock`).
- [ ] **Step 5:** Implementar `Clock.kt`.
- [ ] **Step 6:** Mismo comando → `BUILD SUCCESSFUL`.
- [ ] **Step 7:** `README.md` mínimo: qué es, cómo se compila (Actions), enlace a `docs/signing.md` (aún por escribir). Commit `chore: gradle skeleton with pure-kotlin core module`.

### Task 2: App mínima, CI y repo en GitHub

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/smsforwarder/ui/MainActivity.kt`, `app/src/main/kotlin/dev/smsforwarder/ui/theme/Theme.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/test/kotlin/dev/smsforwarder/SmokeTest.kt`, `.github/workflows/ci.yml`, `.gitleaks.toml` (opcional, solo si hace falta allowlist)

**Interfaces:**
- Produces: `@Composable fun SmsForwarderTheme(mode: ThemeMode, content: @Composable () -> Unit)` con `enum class ThemeMode { SYSTEM, LIGHT, DARK }` (en `ui/theme/Theme.kt`). Color dinámico siempre disponible (con `minSdk 31` no hace falta comprobar `SDK_INT`).

- [ ] **Step 1:** `app/build.gradle.kts`: plugins `com.android.application`, `org.jetbrains.kotlin.plugin.compose`, `com.google.devtools.ksp`; valores de Global Constraints; `buildFeatures { compose = true }`; `testOptions.unitTests.isIncludeAndroidResources = true`; `implementation(project(":core"))`; `debugImplementation` de `androidx.compose.ui:ui-test-manifest` (lo exige `createAndroidComposeRule`) y `testImplementation` de `androidx.compose.ui:ui-test-junit4`. Release: `isMinifyEnabled = true`, `isShrinkResources = true`.
- [ ] **Step 2:** Manifest: `allowBackup="false"`, `dataExtractionRules`, `fullBackupContent`, `usesCleartextTraffic="false"`, `MainActivity` launcher. Crear `res/xml/data_extraction_rules.xml` (`<cloud-backup>` y `<device-transfer>` con `<exclude>` de `database`, `sharedpref` y `file`; el datastore vive bajo `files/datastore`) y `res/xml/backup_rules.xml` (`<full-backup-content>` con los mismos `<exclude>`). `MainActivity` muestra `Text("SMS Forwarder")` dentro de `SmsForwarderTheme(ThemeMode.SYSTEM)`.
- [ ] **Step 3:** Smoke test Robolectric:

```kotlin
@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun showsTitle() { compose.onNodeWithText("SMS Forwarder").assertExists() }
}
```

- [ ] **Step 4:** `ci.yml`, en `push` y `pull_request`. Un job `build` en `ubuntu-latest`:
  - `actions/checkout`, `actions/setup-java` (temurin 17), `gradle/actions/setup-gradle`;
  - `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` (la release sale sin firmar: `signingConfig` solo se aplica si existe `SIGNING_STORE_FILE`, ver Task 15);
  - subir `app/build/outputs/apk/debug/*.apk` como artifact `app-debug`.
  
  Un job `gitleaks` con `gitleaks/gitleaks-action@v2` y `fetch-depth: 0`.
- [ ] **Step 5:** Commit `ci: minimal compose app with build, test, lint and gitleaks`.
- [ ] **Step 6: Pedir confirmación al usuario** antes de crear el repo público (es publicar). Con su ok: `gh repo create sms-forwarder --public --source . --push`.
- [ ] **Step 7:** `gh run watch` → los dos jobs en verde. Si falla, arreglar y repetir; no seguir con rojo.

### Task 3: Filtro de remitentes (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/SenderFilter.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/SenderFilterTest.kt`

**Interfaces:**
- Produces: `object SenderFilter { fun normalize(sender: String): String; fun matches(sender: String, allowed: Collection<String>): Boolean }` (sin lista por defecto; con lista vacía `matches` es siempre falso)`

- [ ] **Step 1: Tests que fallan.**

```kotlin
@Test fun normalizeRemovesAllWhitespaceAndUppercases() =
    assertEquals("BANCOX", SenderFilter.normalize(" Banco x\t"))
@Test fun matchesIgnoringCaseAndSpaces() {
    val allowed = listOf("OPERADOR", "BANCO")
    assertTrue(SenderFilter.matches("operador ", allowed))
    assertTrue(SenderFilter.matches("BANCO", listOf(" banco")))
}
@Test fun doesNotMatchPrefixOrOtherSenders() {
    val allowed = listOf("OPERADOR", "BANCO")
    assertFalse(SenderFilter.matches("OPERADOR-PROMO", allowed))
    assertFalse(SenderFilter.matches("+10000000000", allowed))
    assertFalse(SenderFilter.matches("", allowed))
}
@Test fun emptyAllowedListMatchesNothing() =
    assertFalse(SenderFilter.matches("OPERADOR", emptyList()))
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implementar. Espacio = `Char.isWhitespace()`; mayúsculas con `uppercase(Locale.ROOT)`.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): sender filter with whitespace and case normalization`.

### Task 4: Identidad del SMS y unión de multiparte (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/SmsIdentity.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/SmsIdentityTest.kt`

**Interfaces:**
- Consumes: `SenderFilter.normalize`.
- Produces:
  - `data class SmsRecord(val sender: String, val body: String, val receivedAt: Long, val phoneDate: Long?, val subId: Int?)`. `receivedAt` = hora del centro de servicio en ms, el valor que se envía.
  - `object SmsIdentity`, con:
    - `fun effectiveReceivedAt(dateSent: Long?, date: Long): Long`;
    - `fun bodyHash(body: String): String` (SHA-256 hex completo);
    - `fun localKey(r: SmsRecord): String` = `normalize(sender) + "|" + receivedAt + "|" + bodyHash(body)`;
    - `fun isSameSms(a: SmsRecord, b: SmsRecord): Boolean`;
    - `const val FALLBACK_WINDOW_MS = 2_000L`.
  - `fun joinParts(parts: List<String>): String`.

- [ ] **Step 1: Tests que fallan** (usar el SMS canónico de Global Constraints como `BODY`).

```kotlin
@Test fun effectiveReceivedAtPrefersDateSent() = assertEquals(1000L, SmsIdentity.effectiveReceivedAt(1000L, 5000L))
@Test fun effectiveReceivedAtFallsBackToDateWhenZeroOrNull() {
    assertEquals(5000L, SmsIdentity.effectiveReceivedAt(0L, 5000L))
    assertEquals(5000L, SmsIdentity.effectiveReceivedAt(null, 5000L))
}
@Test fun localKeyIsStableAcrossSenderFormatting() = assertEquals(
    SmsIdentity.localKey(SmsRecord("OPERADOR", BODY, 1_792_260_900_000, null, 1)),
    SmsIdentity.localKey(SmsRecord(" operador", BODY, 1_792_260_900_000, 1_792_260_901_234, 2)))
@Test fun sameSmsWithinTwoSeconds() = assertTrue(SmsIdentity.isSameSms(
    SmsRecord("OPERADOR", BODY, 1_792_260_900_000, null, null),
    SmsRecord("OPERADOR", BODY, 1_792_260_901_999, null, null)))
@Test fun notSameBeyondWindowOrDifferentBody() {
    val a = SmsRecord("OPERADOR", BODY, 1_792_260_900_000, null, null)
    assertFalse(SmsIdentity.isSameSms(a, a.copy(receivedAt = a.receivedAt + 2_001)))
    assertFalse(SmsIdentity.isSameSms(a, a.copy(body = BODY + " ")))
    assertFalse(SmsIdentity.isSameSms(a, a.copy(sender = "BANCO")))
}
@Test fun joinPartsConcatenatesInOrderWithoutSeparator() =
    assertEquals("abcdef", joinParts(listOf("abc", "def")))
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implementar. `isSameSms`: igual `localKey`, o (mismo remitente normalizado, mismo `body` exacto y `|Δ receivedAt| <= FALLBACK_WINDOW_MS`).
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): sms identity, local key and multipart join`.

### Task 5: Pantalla de diagnóstico (solo debug) y verificación en el teléfono

Primera comprobación real de la spec §5. **Bloquea las tasks 8 en adelante** hasta que el usuario confirme los valores.

**Files:**
- Create: `app/src/debug/AndroidManifest.xml`, `app/src/debug/kotlin/dev/smsforwarder/diag/DiagReceiver.kt`, `app/src/debug/kotlin/dev/smsforwarder/diag/DiagStore.kt`, `app/src/debug/kotlin/dev/smsforwarder/diag/DiagActivity.kt`
- Test: `app/src/testDebug/kotlin/dev/smsforwarder/diag/DiagStoreTest.kt`

**Interfaces:**
- Consumes: `SenderFilter`, `SmsIdentity.bodyHash`, `joinParts`.
- Produces: nada para la release (todo en `src/debug`).

- [ ] **Step 1:** Test: `DiagStore` (fichero JSON en `filesDir/diag.json`, máximo 20 entradas, la más nueva primero). Guardar 21 y releer da 20, y la primera es la última guardada. Campos: `sender`, `pduTimestamp`, `bodyHash` (12 primeros caracteres), `bodyLength`, `parts`, `subId`, `capturedAt`. **Nunca** el cuerpo.
- [ ] **Step 2:** Implementar `DiagStore`, `DiagReceiver` (manifest de debug, `SMS_RECEIVED`, sin filtrar remitente; `subId` del extra `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX`, el mismo que usa el receptor real de la Task 11) y `DiagActivity`, con launcher propio "SMS Diag":
  - pide `RECEIVE_SMS` y `READ_SMS`;
  - lista las capturas del receptor;
  - lista las 20 filas más recientes de `content://sms/inbox` con `address`, `date`, `date_sent`, `sub_id`, hash y longitud del cuerpo;
  - marca en verde las parejas con igual `localKey` y en ámbar las que solo casan por `isSameSms`.
- [ ] **Step 3:** Push → `gh run watch` → verde. `gh run download -n app-debug`.
- [ ] **Step 4: Con el usuario**, paso a paso:
  1. Instala la APK debug. Por USB con `adb install`, o pasándola al teléfono.
  2. Concede los permisos.
  3. Espera o provoca un SMS de uno de tus remitentes (por ejemplo, consultar saldo).
  4. Si puede, que llegue uno multiparte.
  5. Abre "SMS Diag" y comunica, sin enviar capturas con datos reales: color de la pareja, `date_sent` ≠ 0, misma longitud de cuerpo.
- [ ] **Step 5:** Según el resultado:
  - Todo verde: anotar en la spec §5 "verificado en teléfono 2026-10-xx".
  - Ámbar o sin pareja: ajustar §5 y la Task 4 (por ejemplo, ventana mayor o normalizar `\r\n`) con el usuario antes de seguir.
  
  Commit `feat(debug): sms diagnostics screen`.

### Task 6: JSON, clasificación de respuestas y URL (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/IngestPayload.kt`, `SendOutcome.kt`, `UrlValidator.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/IngestPayloadTest.kt`, `SendOutcomeTest.kt`, `UrlValidatorTest.kt`

**Interfaces:**
- Produces:
  - `object IngestPayload { fun encode(sender: String, body: String, receivedAt: Long): String }`;
  - `sealed interface SendOutcome { data object Accepted; data class Retry(val httpCode: Int?, val reason: String); data class Rejected(val httpCode: Int, val errorCode: String?) }`;
  - `fun classify(httpCode: Int?, responseBody: String?): SendOutcome` (`httpCode == null` = error de red);
  - `enum class ConnectionTestResult { OK, BAD_TOKEN, UNREACHABLE }` + `fun interpretConnectionTest(httpCode: Int?): ConnectionTestResult`;
  - `object UrlValidator { fun isValid(url: String): Boolean }`.

- [ ] **Step 1: Tests que fallan.**

```kotlin
@Test fun encodesReceivedAtAsNumberAndEscapes() {
    val json = Json.parseToJsonElement(IngestPayload.encode("OPERADOR", "a \"b\"\nc", 1_792_260_900_000)).jsonObject
    assertEquals("OPERADOR", json["sender"]!!.jsonPrimitive.content)
    assertEquals("a \"b\"\nc", json["body"]!!.jsonPrimitive.content)
    assertFalse(json["received_at"]!!.jsonPrimitive.isString)
    assertEquals(1_792_260_900_000, json["received_at"]!!.jsonPrimitive.long)
}
@Test fun classify200and2xxAccepted() { assertEquals(SendOutcome.Accepted, classify(200, "")); assertEquals(SendOutcome.Accepted, classify(204, null)) }
@Test fun classify401Rejected() = assertEquals(SendOutcome.Rejected(401, "unauthorized"), classify(401, """{"error":"unauthorized"}"""))
@Test fun classify400ReadsErrorCode() = assertEquals(SendOutcome.Rejected(400, "invalid_request"), classify(400, """{"error":"invalid_request"}"""))
@Test fun classify400WithGarbageBody() = assertEquals(SendOutcome.Rejected(400, null), classify(400, "<html>"))
@Test fun networkAnd5xxAnd429Retry() {
    assertTrue(classify(null, null) is SendOutcome.Retry)
    assertTrue(classify(502, "<html>cloudflare</html>") is SendOutcome.Retry)
    assertTrue(classify(429, null) is SendOutcome.Retry)
    assertTrue(classify(404, null) is SendOutcome.Retry)
}
@Test fun connectionTest() {
    assertEquals(ConnectionTestResult.OK, interpretConnectionTest(400))
    assertEquals(ConnectionTestResult.BAD_TOKEN, interpretConnectionTest(401))
    assertEquals(ConnectionTestResult.UNREACHABLE, interpretConnectionTest(null))
    assertEquals(ConnectionTestResult.UNREACHABLE, interpretConnectionTest(200))
}
@Test fun urlOnlyHttpsWithHost() {
    assertTrue(UrlValidator.isValid("https://worker.example.dev/ingest/sms"))
    assertFalse(UrlValidator.isValid("http://worker.example.dev/ingest/sms"))
    assertFalse(UrlValidator.isValid("https://"))
    assertFalse(UrlValidator.isValid("worker.example.dev"))
}
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implementar:
  - JSON con `buildJsonObject`;
  - el código de error de un 400/401 sale del campo `error` del cuerpo, si es JSON válido;
  - `UrlValidator` usa `java.net.URI` y exige `scheme == "https"` y host no vacío.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): ingest payload, response classification and url validation`.

### Task 7: Reglas de cola y etiquetas (`:core`)

**Files:**
- Create: `core/src/main/kotlin/dev/smsforwarder/core/QueuePolicy.kt`
- Test: `core/src/test/kotlin/dev/smsforwarder/core/QueuePolicyTest.kt`

**Interfaces:**
- Produces:
  - `enum class SmsStatus { QUEUED, SENT, ERROR }`;
  - `enum class SmsOrigin { RECEIVER, SWEEP, MANUAL }`;
  - `object QueuePolicy`, con:
    - `const val STUCK_AFTER_MS = 6 * 3_600_000L`;
    - `const val RETENTION_MS = 90 * 86_400_000L`;
    - `const val SWEEP_OVERLAP_MS = 24 * 3_600_000L`;
    - `const val MANUAL_RECOVERY_MS = 7 * 86_400_000L`;
    - `fun isStuck(status: SmsStatus, lastHttpCode: Int?, firstSeenAt: Long, now: Long): Boolean`;
    - `fun retentionCutoff(now: Long): Long`;
    - `fun sweepFrom(cursor: Long, floor: Long): Long = max(cursor - SWEEP_OVERLAP_MS, floor)`.
  - `fun statusLabel(status: SmsStatus, origin: SmsOrigin, attempts: Int, lastHttpCode: Int?): String`.

- [ ] **Step 1: Tests que fallan.**

```kotlin
@Test fun stuckAfterSixHoursQueued() {
    assertFalse(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, QueuePolicy.STUCK_AFTER_MS))
    assertTrue(QueuePolicy.isStuck(SmsStatus.QUEUED, null, 0, QueuePolicy.STUCK_AFTER_MS + 1))
}
@Test fun error401IsStuckImmediately() = assertTrue(QueuePolicy.isStuck(SmsStatus.ERROR, 401, 1000, 1001))
@Test fun error400IsStuckImmediately() = assertTrue(QueuePolicy.isStuck(SmsStatus.ERROR, 400, 1000, 1001))
@Test fun sentIsNeverStuck() = assertFalse(QueuePolicy.isStuck(SmsStatus.SENT, 200, 0, Long.MAX_VALUE))
@Test fun sweepFromSubtracts24h() = assertEquals(100L, QueuePolicy.sweepFrom(100L + 86_400_000L, 0L))
@Test fun sweepFromNeverBelowFloor() = assertEquals(500L, QueuePolicy.sweepFrom(100L + 86_400_000L, 500L))
@Test fun labels() {
    assertEquals("En cola · 3 intentos", statusLabel(SmsStatus.QUEUED, SmsOrigin.RECEIVER, 3, null))
    assertEquals("Enviado 200", statusLabel(SmsStatus.SENT, SmsOrigin.RECEIVER, 1, 200))
    assertEquals("Recuperado · 200", statusLabel(SmsStatus.SENT, SmsOrigin.SWEEP, 1, 200))
    assertEquals("Manual · 200", statusLabel(SmsStatus.SENT, SmsOrigin.MANUAL, 1, 200))
    assertEquals("Error 401", statusLabel(SmsStatus.ERROR, SmsOrigin.RECEIVER, 2, 401))
    assertEquals("Error 400", statusLabel(SmsStatus.ERROR, SmsOrigin.SWEEP, 1, 400))
}
```

- [ ] **Step 2:** `./gradlew :core:test` → FAIL.
- [ ] **Step 3:** Implementar.  `isStuck` es true para `ERROR` con 400 o 401.
- [ ] **Step 4:** `./gradlew :core:test` → PASS.
- [ ] **Step 5:** Commit `feat(core): queue policy and status labels`.

### Task 8: Base de datos Room y repositorio de SMS

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/SmsEntity.kt`, `AttemptEntity.kt`, `SmsDao.kt`, `AppDatabase.kt`, `SmsRepository.kt`, `app/src/main/kotlin/dev/smsforwarder/AppGraph.kt`, `SmsForwarderApp.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`android:name=".SmsForwarderApp"`)
- Test: `app/src/test/kotlin/dev/smsforwarder/data/SmsRepositoryTest.kt`

**Interfaces:**
- Consumes: `SmsRecord`, `SmsIdentity`, `SmsStatus`, `SmsOrigin`, `QueuePolicy`, `Clock`.
- Produces:
  - `SmsEntity`, en la tabla `sms`, con:
    - `id: Long` (PK auto);
    - `localKey: String` (índice único);
    - `sender`, `senderNormalized`, `body: String`;
    - `receivedAt: Long` (índice), `phoneDate: Long?`, `subId: Int?`;
    - `origin: SmsOrigin`, `status: SmsStatus`;
    - `attempts: Int`, `lastHttpCode: Int?`, `lastError: String?`;
    - `firstSeenAt: Long`, `sentAt: Long?`, `stuckNotified: Boolean`.
    
    `toRecord(): SmsRecord`.
  - `AttemptEntity`, en la tabla `attempt`, con `id`, `smsId` (FK con cascada), `at`, `httpCode: Int?`, `error: String?`.
  - `sealed interface IngestResult { data class Inserted(val id: Long); data class AlreadyKnown(val id: Long) }`.
  - `class SmsRepository(db: AppDatabase, clock: Clock)`, con:
    - `suspend fun ingest(record: SmsRecord, origin: SmsOrigin): IngestResult`;
    - `suspend fun get(id: Long): SmsEntity?`;
    - `fun observeAll(): Flow<List<SmsEntity>>` (más reciente primero);
    - `fun observeAttempts(smsId: Long): Flow<List<AttemptEntity>>`;
    - `suspend fun recordAttempt(id: Long, outcome: SendOutcome)` (actualiza estado, `attempts`, `lastHttpCode`, `sentAt`, inserta `AttemptEntity`);
    - `suspend fun requeue(id: Long)`, que devuelve el SMS a `QUEUED` y pone `stuckNotified = false`;
    - `suspend fun queuedIds(): List<Long>` (todos los `QUEUED`);
    - `suspend fun idsWithError401(): List<Long>`;
    - `suspend fun purgeOld()`, que borra solo `SENT` con `receivedAt < retentionCutoff`;
    - `suspend fun stuckToNotify(): List<SmsEntity>` (excluye las filas 401: las cubre la notificación de token) y `suspend fun markStuckNotified(id: Long)`.
  - `class AppGraph(context: Context)`: `val db`, `val clock = SystemClock`, `val smsRepository` (se irán añadiendo campos en las tasks siguientes). `SmsForwarderApp.graph: AppGraph`.

- [ ] **Step 1: Tests Robolectric que fallan** (BD en memoria, `Clock` fijo):
  - `ingestTwiceSameRecordIsAlreadyKnown`
  - `ingestWithin2sSameBodyIsAlreadyKnownAndKeepsFirstReceivedAt`: inserta por `RECEIVER` con `receivedAt = T`, ingiere por `SWEEP` con `T + 1500` → `AlreadyKnown`, y `get(id).receivedAt == T`.
  - `recordAttemptRetryKeepsQueued`: `Retry(null, "io")` → `QUEUED`, `attempts == 1`.
  - `recordAttemptAcceptedSetsSent`
  - `recordAttempt401SetsErrorAndIsListedIn401`
  - `purgeOldKeepsQueuedAndErrorEvenIfOld`
- [ ] **Step 2:** Push → `gh run watch` → FAIL en `testDebugUnitTest`.
- [ ] **Step 3:** Implementar:
  - `ingest` en una transacción: busca por `localKey`; si no está, busca candidatos con mismo `senderNormalized` y `receivedAt` entre `T ± 2000` y comprueba con `isSameSms`; si no hay coincidencia, inserta.
  - `TypeConverter` para los enums.
  - `exportSchema = true` con `room.schemaLocation` en `app/schemas`.
- [ ] **Step 4:** Push → `gh run watch` → PASS.
- [ ] **Step 5:** Commit `feat(app): room queue with dedup-aware ingest`.

### Task 9: Cliente HTTP, credenciales cifradas y `SendWorker`

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/net/IngestClient.kt`, `data/SecretStore.kt`, `work/SendWorker.kt`, `work/WorkScheduler.kt`
- Modify: `AppGraph.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/net/IngestClientTest.kt`, `work/SendWorkerTest.kt`, `data/CredentialsTest.kt`

**Interfaces:**
- Consumes: `IngestPayload`, `classify`, `SendOutcome`, `SmsRepository`.
- Produces:
  - `data class Credentials(val url: String, val token: String)`, cuyo `toString()` devuelve `"Credentials(***)"`;
  - `interface SecretStore`, con `suspend fun get(): Credentials?` y `suspend fun set(c: Credentials)`;
    - `class KeystoreSecretStore(context)`: clave AES-256-GCM con alias `smsfwd-secrets` en `AndroidKeyStore`; texto cifrado + IV en base64 en DataStore (`secrets.preferences_pb`); `get()` captura `GeneralSecurityException` (incluye `AEADBadTagException` y `KeyPermanentlyInvalidatedException`) y la clave ausente: devuelve `null` y borra el blob guardado. Se prueba en JVM con un cifrador falso si es viable; si no, va a la lista manual de la Task 15.
  - `class IngestClient(http: OkHttpClient)`, con:
    - `suspend fun send(c: Credentials, sender: String, body: String, receivedAt: Long): SendOutcome`;
    - `suspend fun testConnection(c: Credentials): ConnectionTestResult` (POST `{}`).
    
    `fun defaultHttpClient(): OkHttpClient` (timeouts de Global Constraints).
  - `class SendWorker : CoroutineWorker`, con input `KEY_SMS_ID = "smsId"`.
  - `object WorkScheduler`, con:
    - `fun enqueueSend(context, smsId: Long, replace: Boolean = false)`: único `"send-$smsId"`, `KEEP` por defecto y `REPLACE` si `replace = true`, red `CONNECTED`, backoff exponencial 30 s, `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` (sin expedited en la ruta con `replace = true`);
    - `fun kickQueued(context, ids: List<Long>)`: `enqueueSend(context, id, replace = true)` para cada id;
    - `fun schedulePeriodicSweep(context)` y `fun sweepNow(context)`, rellenados en la Task 11.
  - Para los tests, `SendWorker` obtiene sus dependencias de `AppGraph`, con un `SecretStore` sustituible.

- [ ] **Step 1: Tests que fallan.**
  - `CredentialsTest.toStringRedacts`: `toString()` no contiene ni la URL ni el token.
  - `IngestClientTest` (MockWebServer):
    - `sendsBearerJsonAndNumberReceivedAt`: verifica la cabecera `Authorization: Bearer test-token`, el `Content-Type` y que el cuerpo tenga `received_at` numérico;
    - `maps401ToRejected`;
    - `disconnectIsRetry` (`SocketPolicy.DISCONNECT_AT_START`);
    - `testConnectionSendsEmptyObject`.
  - `SendWorkerTest` (`TestListenableWorkerBuilder`, `FakeSecretStore`, MockWebServer):
    - `ioErrorReturnsRetryAndStaysQueued`;
    - `200MarksSent`;
    - `401ReturnsSuccessAndMarksError`: el trabajo termina sin reintento;
    - `missingCredentialsReturnsRetry`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implementar. Si el SMS ya está `SENT`, `SendWorker` devuelve `success()`. `Retry` → `Result.retry()`. `Accepted`/`Rejected` → `Result.success()`. Ningún log con URL, token ni cuerpo.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): encrypted credentials, ingest client and send worker`.

### Task 10: Ajustes persistentes

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/data/SettingsRepository.kt`
- Modify: `AppGraph.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `ThemeMode`, `SecretStore`, `SmsRepository.idsWithError401`, `SmsRepository.queuedIds`, `WorkScheduler.enqueueSend`.
- Produces: `class SettingsRepository(context, secretStore, smsRepository, scheduler: (Long) -> Unit)`, con:
  - `val senders: Flow<List<String>>`, `suspend fun addSender(s: String)`, `suspend fun removeSender(s: String)` (compara valores normalizados);
  - `val themeMode: Flow<ThemeMode>`, `suspend fun setThemeMode(m: ThemeMode)`;
  - `suspend fun sweepCursor(): Long?`, `suspend fun setSweepCursor(t: Long)`;
  - `suspend fun sweepFloor(): Long?`, `suspend fun setSweepFloor(t: Long)`;
  - `val onboardingDone: Flow<Boolean>`, `suspend fun setOnboardingDone()`;
  - `suspend fun saveCredentials(c: Credentials)`, que guarda y después reencola todos los SMS de `idsWithError401()` y todos los `QUEUED` (los de antes de las credenciales estaban en backoff por `missingCredentialsReturnsRetry`);
  - `suspend fun lastNotifiedIssues(): Set<String>`, `suspend fun setLastNotifiedIssues(s: Set<String>)`.

- [ ] **Step 1: Tests que fallan:**
  - `sendersStartEmpty`: sin configuración previa, `senders` emite una lista vacía;
  - `addSenderIgnoresDuplicateAfterNormalization`: con `"OPERADOR"` ya guardado, añadir `" operador"` no crea una segunda entrada;
  - `saveCredentialsRequeues401AndKicksQueued`: con un SMS en `Error 401` y otro `QUEUED`, tras guardar el primero está `QUEUED` y el `scheduler` recibió los dos ids;
  - `removeSenderMatchesNormalized`: quitar `"operador "` elimina `"OPERADOR"`;
  - `themeDefaultsToSystem`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implementar con DataStore Preferences (`settings.preferences_pb`). Remitentes guardados tal como los escribe el usuario.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): settings repository with 401 requeue on token change`.

### Task 11: Receptor, lector de bandeja, barrido y arranque

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/sms/SmsReceiver.kt`, `sms/BootReceiver.kt`, `sms/InboxReader.kt`, `sms/Sweeper.kt`, `work/SweepWorker.kt`
- Modify: `AndroidManifest.xml` (permisos `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `INTERNET`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (lo usa el botón de batería con `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); receptores con `exported=true` y `android:permission="android.permission.BROADCAST_SMS"` en el de SMS), `WorkScheduler.kt`, `SmsForwarderApp.kt` (programar barrido periódico en `onCreate`)
- Test: `app/src/test/kotlin/dev/smsforwarder/sms/SweeperTest.kt`, `sms/SmsReceiverTest.kt`

**Interfaces:**
- Consumes: `SenderFilter`, `SmsIdentity`, `joinParts`, `SmsRepository.ingest`, `SettingsRepository`, `WorkScheduler.enqueueSend`/`kickQueued`, `SmsRepository.queuedIds`, `QueuePolicy.sweepFrom`.
- Produces:
  - `data class InboxRow(val address: String, val body: String, val date: Long, val dateSent: Long, val subId: Int?)`;
  - `interface InboxReader { fun readSince(fromDate: Long): List<InboxRow>?; fun hasAnyRow(): Boolean? }`: `readSince` devuelve `null` si el cursor es `null` o hay `SecurityException`; `hasAnyRow()` consulta toda la bandeja sin filtro de fecha con `LIMIT 1` y devuelve `null` si hay `SecurityException`;
    - `class ContentInboxReader(context)`;
  - `class Sweeper(reader, smsRepository, settings, clock, enqueue: (Long) -> Unit, onRecovered: (List<SmsEntity>) -> Unit)`, con `suspend fun sweep(): SweepResult`;
  - `data class SweepResult(val recovered: Int, val inboxUnreadable: Boolean)`: `inboxUnreadable` es true si `readSince` devuelve `null` o si `hasAnyRow() == false` con el permiso concedido;
  - `SweepWorker`: periódico `"sweep-periodic"` cada 15 min (`KEEP`) y puntual `"sweep-now"` (`REPLACE`), sin restricción de red. Ejecuta `sweep()`, después `purgeOld()`, después `WorkScheduler.kickQueued` con `queuedIds()`, y luego llama a `HealthChecker` y al aviso de atascados (enganchados en la Task 12).

- [ ] **Step 1: Tests que fallan** (`FakeInboxReader`):
  - `sweepBeforeOnboardingDoesNothing`: con `onboardingDone` falso no lee ni inserta nada.
  - `sweepNeverReadsBeforeFloor`: con cursor = suelo + 1 h, el `fromDate` pedido es el suelo. Sin cursor pero con suelo, lee desde el suelo.
  - `sweepIngestsMatchingSendersSinceCursorMinus24h`: comprobar el `fromDate` pedido (con el suelo muy anterior).
  - `sweepSkipsSmsAlreadyFromReceiverAndDoesNotNotify`.
  - `sweepRecoversMissingAndCallsOnRecovered`: `origin == SWEEP` y se llama a `enqueue`.
  - `sweepUsesDateWhenDateSentIsZero`.
  - `nullInboxReportsUnreadable`.
  - `emptyWholeInboxWithPermissionIsUnreadable`: `hasAnyRow() == false` con permiso concedido → `inboxUnreadable`.
  - `SmsReceiverTest.multipartFromFilteredSenderIsStoredOnceAndEnqueued`: construir el intent `SMS_RECEIVED` con dos PDU de prueba; helper de test que genere los PDU (GSM 7-bit, remitente alfanumérico `OPERADOR`).
  - `SmsReceiverTest.otherSenderIgnored`.
  - `SmsReceiverTest.ignoredBeforeOnboarding`: con `onboardingDone` falso no se guarda nada.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implementar:
  - **Receptor:** `Telephony.Sms.Intents.getMessagesFromIntent`, `joinParts`, `receivedAt = messages[0].timestampMillis`, `subId` del extra `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX`. Dentro de `goAsync()`, en `CoroutineScope(Dispatchers.IO)`, lee `onboardingDone` y, si es falso, termina sin `ingest`; si no, hace `ingest` y, si es `Inserted`, `enqueueSend`; luego `finish()`.
  - **Barrido:** si `onboardingDone` es falso, no hace nada. Lee desde `QueuePolicy.sweepFrom(cursor, floor)`; sin cursor pero con suelo, desde el suelo. El cursor avanza al máximo `date` leído o, si no hay filas, a `now`.
  - **Boot:** `sweepNow`.
  - **Apertura de la app:** `sweepNow` desde `MainActivity.onResume` (el barrido relanza los `QUEUED`).
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): sms receiver, inbox sweep and boot hook`.

### Task 12: Salud del sistema y notificaciones

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/health/HealthChecker.kt`, `health/MiuiIntents.kt`, `notify/Notifier.kt`
- Modify: `SweepWorker.kt`, `SendWorker.kt`, `SmsForwarderApp.kt` (crear canal), `AppGraph.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/health/HealthCheckerTest.kt`, `notify/NotifierTest.kt`

**Interfaces:**
- Consumes: `SweepResult.inboxUnreadable`, `SmsRepository.stuckToNotify`/`markStuckNotified`/`idsWithError401`, `SettingsRepository.lastNotifiedIssues`.
- Produces:
  - `enum class HealthIssue { SMS_PERMISSION, NOTIFICATION_PERMISSION, BATTERY_OPTIMIZED, BACKGROUND_RESTRICTED, AUTOSTART_OFF, AUTOSTART_UNKNOWN, MIUI_SMS_BLOCKED, TOKEN_REJECTED, NOT_CONFIGURED, NO_SENDERS }`, con `NO_SENDERS` cuando `SettingsRepository.senders` está vacía;
  - `class HealthChecker(context, smsRepository, secretStore)`, con `suspend fun check(inboxUnreadable: Boolean = false): Set<HealthIssue>`;
  - `object MiuiIntents`, con:
    - `fun autostartState(context): Boolean?`: best-effort por reflexión sobre `AppOpsManager.checkOpNoThrow` con op `10008`; `null` si no es MIUI o falla;
    - `fun autostart(context): Intent`: componente `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`;
    - `fun permissions(context): Intent`: `com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity` con extra `extra_pkgname`;
    - `fun battery(context): Intent`;
    - `fun forIssue(context, issue): Intent`.
    
    Cada intent cae a `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` si `resolveActivity` es `null`.
  - `class Notifier(context)`, con:
    - `fun recovered(items: List<SmsEntity>)`: id 1001;
    - `fun stuck(item: SmsEntity)`: id `2000 + smsId`;
    - `fun health(issues: Set<HealthIssue>)`: id 3001;
    - `fun tokenRejected(count: Int)`: id 3002, una sola notificación "Token rechazado: N SMS esperando".
    
    Canal `alerts`. Al tocar cualquiera se abre `MainActivity`.

- [ ] **Step 1: Tests que fallan:**
  - `emptySendersIsReportedAsNoSenders`;
  - `smsPermissionDeniedIsReported` (Robolectric `ShadowApplication.denyPermissions`);
  - `token401PresentIsReported`;
  - `inboxUnreadableWithPermissionIsMiuiSmsBlocked`;
  - `nonMiuiAutostartIsUnknownNotOff`;
  - `NotifierTest.tokenRejectedIsSingleNotification`;
  - `SendWorkerTest.401DoesNotNotifyPerSms`;
  - `NotifierTest.recoveredTextNamesSenderAndTime`: el texto contiene `"Recuperado 1 SMS de OPERADOR de las 11:42"` y `"revisa Inicio automático"`; zona horaria fija en el test.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implementar y enganchar en `SweepWorker`, en este orden:
  1. `onRecovered` llama a `Notifier.recovered`.
  2. Para cada `stuckToNotify()` (ya sin filas 401), llamar a `Notifier.stuck` y después a `markStuckNotified`.
  3. Calcular `issues` (incluye `NO_SENDERS`, que sí dispara notificación además de la tarjeta de la UI). Si difieren de `lastNotifiedIssues()` y no están vacíos, llamar a `Notifier.health`. En los dos casos, guardar `issues`.
  
  `AUTOSTART_UNKNOWN` no dispara notificación, solo se muestra en la UI.
  
  En `SendWorker`, un `Rejected(401)` llama a `Notifier.tokenRejected` con el número de filas 401 (`idsWithError401().size`) y no llama a `stuck`. Un `Rejected(400)` llama a `Notifier.stuck` y después a `markStuckNotified`. `HealthIssue.TOKEN_REJECTED` se mantiene para la tarjeta de la UI.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(app): health checks and alert notifications`.

### Task 13: UI: tema, navegación, pantalla principal y detalle

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/Nav.kt`, `ui/main/MainViewModel.kt`, `ui/main/MainScreen.kt`, `ui/detail/DetailViewModel.kt`, `ui/detail/DetailScreen.kt`
- Modify: `ui/MainActivity.kt`, `ui/theme/Theme.kt`, `data/SettingsRepository.kt`, `work/SweepWorker.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/MainScreenTest.kt`, `ui/DetailScreenTest.kt`

**Interfaces:**
- Consumes: `SmsRepository.observeAll/observeAttempts/requeue/get`, `statusLabel`, `HealthChecker`, `MiuiIntents.forIssue`, `WorkScheduler.sweepNow/enqueueSend`, `SettingsRepository.themeMode`.
- Produces:
  - rutas `onboarding`, `main`, `detail/{id}`, `settings`, `recovery`. `MainActivity` arranca en `onboarding` si `onboardingDone == false`.
  - `@Composable fun MainScreen(state: MainUiState, onOpenIssue: (HealthIssue) -> Unit, onSweepNow: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit)`;
  - `data class MainUiState(val issues: Set<HealthIssue>, val lastSweepAt: Long?, val queued: Int, val errors: Int, val rows: List<RowUi>)`;
  - `data class RowUi(val id: Long, val sender: String, val subId: Int?, val receivedAt: Long, val preview: String, val label: String)`.

- [ ] **Step 1: Tests Compose (Robolectric) que fallan:**
  - `showsWarningCardWithButtonForAutostartOff`: texto "Inicio automático desactivado" y botón "Abrir ajustes".
  - `showsStatusLineAndSweepButton`: "Barrer bandeja ahora".
  - `rowShowsLabel`: aparece "Recuperado · 200".
  - `DetailScreenTest.retryVisibleOnlyOnError`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implementar según el boceto A de la spec §7:
  - avisos arriba;
  - línea de estado con "Último barrido: hace N min · Cola: X";
  - `LazyColumn` del historial;
  - engranaje en la `TopAppBar`;
  - el detalle muestra texto completo, remitente, SIM, hora, origen e intentos.
  
  `lastSweepAt` sale de `SettingsRepository`: añadir `lastSweepAt()`/`setLastSweepAt()` ahí y escribirlo en `SweepWorker`. `SmsForwarderTheme` lee `themeMode`.
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(ui): main screen with alerts, status and history; sms detail`.

### Task 14: UI: primer arranque, recuperación manual y ajustes

**Files:**
- Create: `app/src/main/kotlin/dev/smsforwarder/ui/onboarding/OnboardingScreen.kt`, `ui/onboarding/OnboardingViewModel.kt`, `ui/recovery/RecoveryScreen.kt`, `ui/recovery/RecoveryViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`
- Modify: `Nav.kt`
- Test: `app/src/test/kotlin/dev/smsforwarder/ui/RecoveryViewModelTest.kt`, `ui/SettingsScreenTest.kt`, `ui/OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: `InboxReader`, `SenderFilter`, `SmsIdentity`, `SmsRepository.ingest`, `SettingsRepository` (`setSweepFloor`, `setSweepCursor`, `setOnboardingDone`), `IngestClient.testConnection`, `UrlValidator`, `WorkScheduler`, `QueuePolicy.MANUAL_RECOVERY_MS`.
- Produces:
  - `class RecoveryViewModel`, con `val candidates: StateFlow<List<RecoveryItem>>`, `fun toggle(key: String)` y `suspend fun sendSelected(): Int`;
  - `data class RecoveryItem(val key: String, val sender: String, val receivedAt: Long, val preview: String, val alreadyKnown: Boolean, val selected: Boolean)`.

- [ ] **Step 1: Tests que fallan:**
  - `recoveryListsLast7DaysOfAllowedSendersNoneSelected`.
  - `recoveryMarksAlreadyKnownAndDisablesThem`.
  - `sendSelectedIngestsAsManualAndEnqueues`: `origin == MANUAL`.
  - `onboardingRejectsHttpUrl`.
  - `onboardingRequiresAtLeastOneSender`: tras los permisos, el paso Remitentes no deja continuar con la lista vacía y sí con una entrada.
  - `onboarding400MeansOkAndShowsCutoverReminder`: el recordatorio con "desinstala o desactiva la app anterior" se muestra al inicio, antes de los permisos, y exige confirmarlo; tras un 400 en Probar conexión solo se continúa (recordatorio corto antes de la recuperación).
  - `settingsNeverShowsTokenValue`: tras guardar el token `test-token`, ningún nodo contiene `test-token`.
- [ ] **Step 2:** Push → FAIL.
- [ ] **Step 3:** Implementar:
  - **Primer arranque**, en este orden:
    1. Recordatorio de corte limpio (desinstalar o desactivar la app anterior), que se confirma.
    2. Permisos `RECEIVE_SMS`, `READ_SMS` y `POST_NOTIFICATIONS` (este último solo si SDK ≥ 33). Si se deniega `RECEIVE_SMS`/`READ_SMS` sin diálogo (`shouldShowRequestPermissionRationale == false` en la primera petición), mostrar el aviso de "ajustes restringidos": Información de la app → ⋮ → "Permitir ajustes restringidos".
    3. Botón para la optimización de batería.
    4. Aviso de revisar Inicio automático y los SMS en Seguridad de MIUI, con botones de `MiuiIntents`.
    5. **Remitentes**: añadir y quitar, sin valores por defecto; no se puede continuar con la lista vacía.
    6. URL y token, con Probar conexión.
    7. Pantalla de recuperación (con un recordatorio corto de la app anterior antes).
    8. `setSweepFloor(now)`, `setSweepCursor(now)` y `setOnboardingDone()`.
  - **Ajustes:**
    - URL y token se sustituyen con `saveCredentials`. Nunca se rellena el campo del token.
    - Probar conexión.
    - Lista de remitentes con añadir y quitar.
    - Tema: Sistema, Claro u Oscuro.
    - Entrada a "Recuperar SMS".
- [ ] **Step 4:** Push → PASS.
- [ ] **Step 5:** Commit `feat(ui): onboarding, manual recovery and settings`.

### Task 15: Release firmada, documentación y prueba real

**Files:**
- Create: `.github/workflows/release.yml`, `docs/signing.md`, `docs/manual-test-checklist.md`
- Modify: `app/build.gradle.kts` (`signingConfigs.release` desde env; `versionCode`/`versionName` desde la etiqueta), `README.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: secretos `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Asset `sms-forwarder-vX.Y.Z.apk` en la Release.

- [ ] **Step 1:** `app/build.gradle.kts`:
  - lee `GITHUB_REF_NAME`;
  - si casa `^v(\d+)\.(\d+)\.(\d+)$`, `versionCode = X*10000 + Y*100 + Z` y `versionName = "X.Y.Z"`; si no, `versionCode = 1` y `versionName = "0.0.0-dev"`;
  - `signingConfig` de release solo si existe la env `SIGNING_STORE_FILE`.
- [ ] **Step 2:** `release.yml`, en `push: tags: ['v*']`, con `permissions: contents: write`:
  1. Decodificar el secreto a `$RUNNER_TEMP/release.jks`.
  2. Ejecutar `./gradlew :core:test :app:testReleaseUnitTest :app:assembleRelease`.
  3. Renombrar la APK.
  4. El job define las env `SIGNING_STORE_FILE=$RUNNER_TEMP/release.jks`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` y `SIGNING_KEY_PASSWORD` desde los secretos, y `GH_TOKEN: ${{ github.token }}` para `gh release create`.
  5. Publicar con `gh release create "$GITHUB_REF_NAME" <apk> --generate-notes` (sin atribución a Claude en las notas).
  6. Borrar el `.jks` en un paso `if: always()`.
- [ ] **Step 3:** `docs/signing.md`, en español y paso a paso:
  1. `keytool -genkeypair -v -keystore sms-forwarder.jks -keyalg RSA -keysize 4096 -validity 36500 -alias sms-forwarder`.
  2. `base64 -w0 sms-forwarder.jks`.
  3. Crear los 4 secretos, por la web (Settings → Secrets and variables → Actions) o con `gh secret set`.
  4. Guardar el `.jks` y las contraseñas fuera de GitHub. Sin ellos no se puede actualizar la app.
  5. Desinstalar la build debug si molesta. No hace falta, porque tiene otro `applicationId`.
- [ ] **Step 4:** `docs/manual-test-checklist.md`, casos de la spec §12:
  - SMS normal;
  - SMS multiparte;
  - modo avión: el SMS queda en cola y se envía al volver la red;
  - forzar la detención y abrir: el barrido recupera y notifica;
  - token incorrecto: 401, notificación, corregir y reencolar;
  - reinicio del teléfono;
  - la app reenviadora anterior desactivada antes de configurar;
  - modo claro y oscuro;
  - quitar Inicio automático: aparece el aviso;
  - Android 15, ajustes restringidos: si no sale el diálogo de permiso, el primer arranque explica "Permitir ajustes restringidos";
  - clave perdida tras transferir a otro dispositivo: la app muestra `NOT_CONFIGURED`, no se cierra (y `KeystoreSecretStore.get()` devuelve `null` si no hay test JVM);
  - el primer barrido tras la configuración no envía nada anterior a la configuración;
  - un SMS que llega durante el primer arranque no se reenvía;
  - el primer arranque no deja continuar sin al menos un remitente;
  - vaciar la lista de remitentes en Ajustes muestra la tarjeta y la notificación de `NO_SENDERS`.
- [ ] **Step 5:** Commit `ci: signed release workflow; docs: signing and manual test checklist`. Push → CI verde.
- [ ] **Step 6: Con el usuario:**
  1. Crea la clave y los secretos siguiendo `docs/signing.md`.
  2. `git tag v1.0.0 && git push origin v1.0.0`.
  3. `gh run watch`. La Release aparece con la APK.
  4. Instala la APK en el teléfono y recorre la checklist.
  5. Apunta en el chat los casos fallidos.
  
  Los fallos se corrigen en tasks nuevas.
