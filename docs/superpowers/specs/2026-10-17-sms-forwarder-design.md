# SMS Forwarder — diseño

Fecha: 2026-10-17. Estado: borrador para aprobación. Revisado 2026-10-23 (suelo de barrido, nada activo antes del primer arranque, minSdk 31, 400 notifica).

## 1. Qué es y por qué

App Android propia, de un solo usuario, que reenvía al Worker de Cloudflare ya existente los SMS
de remitentes concretos (los que el usuario configura; la lista empieza vacía). Esos SMS alimentan
un gestor de finanzas propio (otro repo).

Sustituye a la app reenviadora anterior, que pierde mensajes. El 2026-10-17 se perdió un SMS del
operador móvil: MIUI mató la app reenviadora y no la dejó arrancar al llegar el SMS. El Worker y el receptor
estaban bien.

**Objetivo número uno: no perder ningún SMS.** Todo lo demás se subordina a esto.

## 2. Objetivos, no objetivos y anti-objetivos

Objetivos:
- Cada SMS de un remitente de la lista acaba aceptado por el Worker (HTTP 200), aunque tarde horas.
- Si algo impide eso (permisos, MIUI, token), el usuario se entera por una notificación.
- Si el receptor no se ejecutó, un barrido de la bandeja recupera el SMS.

No objetivos:
- No es app de mensajes ni sustituye a Google Messages.
- No interpreta el contenido de los SMS (eso lo hace el Worker).
- No se integra en una futura APK del gestor de finanzas. App independiente (ver §10).
- No busca actualizaciones de sí misma.

Anti-objetivos (fallo aunque "funcione"):
- Descartar un SMS por un fallo de red o un error transitorio.
- Notificaciones por cada envío correcto (ruido que acaba ignorándose).
- Que el token, la URL del Worker, números de teléfono, tarjetas o SMS reales aparezcan en el repo,
  en los logs o en los tests.
- Notificación fija en la barra: **el usuario rechazó un servicio en primer plano**.

## 3. Restricciones

- Teléfono: Xiaomi con MIUI, Android 14, doble SIM. Google Play Services
  disponible. App de mensajes: Google Messages. Los SMS importantes llegan a la SIM 1.
- Red móvil lenta e intermitente, a veces con VPN.
- Kotlin + Jetpack Compose, solo Android, `minSdk 31`. Con 31, el trabajo acelerado de WorkManager (expedited) es un job de
  Android 12+, nunca un servicio en primer plano (en API 30 lo sería, con notificación, y exigiría
  `getForegroundInfo()`).
- Riesgo: Android 15 restringe los permisos de SMS a las apps instaladas fuera de la tienda
  ("ajustes restringidos"). Si el diálogo de permiso no aparece, el usuario debe ir a Información de
  la app → ⋮ → "Permitir ajustes restringidos". El primer arranque lo explica cuando el permiso se
  deniega sin diálogo.
- El usuario es nuevo en Kotlin/Android: el plan y el código explican paso a paso.
- No se instala Android Studio ni el SDK localmente: todo se compila en GitHub Actions.
- Repo **público** en GitHub.
- El contrato del Worker no se cambia (§4).

## 4. Contrato del Worker (fijo)

```
POST https://<host>/ingest/sms
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json

{"sender": "<1-64 chars>", "body": "<1-2000 chars>", "received_at": <ISO 8601 con desfase o Z | ms desde epoch, 13 dígitos>}
```

- `200`: aceptado (también si es duplicado).
- `400 invalid_json` / `invalid_request`: datos mal formados.
- `401 unauthorized`: token incorrecto.
- El Worker deduplica por `hash(sender + body + received_at)`. Reenviar el mismo SMS con el mismo
  `received_at` es seguro.
- El Worker ignora por su cuenta las promos del operador y guarda como "unknown" lo que no entiende.

## 5. Cómo llega un SMS a la app

Tres vías independientes, todas desembocan en la misma cola. Ninguna está activa hasta que termina
el primer arranque: mientras `onboardingDone` sea falso, el receptor descarta los SMS y el barrido
no hace nada. Motivo: un SMS que llegue con la app anterior aún activa entraría dos veces.

1. **Receptor `SMS_RECEIVED`** declarado en el manifiesto. Al recibir, une las partes de un SMS
   multiparte, aplica el filtro de remitente, guarda en la base de datos local (dentro de
   `goAsync()`) y encola un trabajo de envío. No envía nada por red desde el receptor.
2. **Barrido periódico** con WorkManager, cada 15 minutos (mínimo de Android), sin requisito de red.
3. **Barrido puntual** al abrir la app, al pulsar "Barrer bandeja ahora" y tras `BOOT_COMPLETED`.

**Verificado en teléfono 2026-10-29** (Android 14, MIUI): para SMS de una y de dos partes, el
receptor y la bandeja coinciden en remitente, hash y longitud del cuerpo, y `pduTimestamp` es igual
a `date_sent` (`localKey` idéntica). `date` llega varios segundos después de `date_sent`, así que
`date_sent` manda. `joinParts` reproduce el cuerpo de la bandeja, saltos de línea incluidos. Una app
que nunca se abrió no recibe `SMS_RECEIVED`: el primer arranque debe lanzarla.

### Barrido

- Lee `content://sms/inbox` (permiso `READ_SMS`) desde un cursor guardado, con **24 horas de
  solape** hacia atrás en cada barrido. El solape cubre relojes desajustados y SMS que Google
  Messages escribe con retraso.
- **Suelo de barrido (`sweepFloor`)**: al final del primer arranque se guarda ese instante y no se
  mueve nunca. Cada barrido lee desde `max(cursor − 24 h, sweepFloor)`. Sin el suelo, el primer
  barrido tras la configuración enviaría las 24 h anteriores de SMS que la app anterior ya envió con
  otra hora. Los SMS anteriores al suelo solo se envían por la recuperación manual.
- Filtra por remitente, y para cada SMS que no exista ya en la base local lo inserta con origen
  "barrido" y encola su envío.
- Si inserta alguno que el receptor no capturó, lanza la notificación "SMS recuperado" (§8).
- **Nada se reenvía automáticamente hacia atrás.** El cursor del barrido arranca en el momento de
  la instalación (o de la configuración inicial). Los SMS anteriores solo se envían por la
  recuperación manual (abajo).
- **Recuperación manual** (se abre al final del primer arranque, que se repite tras reinstalar porque
  los datos no se copian, y desde Ajustes; no se abre sola por tener la base vacía, porque saldría en
  cada arranque si el usuario no marca nada): la app lista los SMS de los remitentes de la lista de los últimos 7 días, **ninguno
  marcado**, y el usuario marca cuáles enviar. Los marcados entran en la cola con origen "manual".

Por qué: la app anterior enviaba `received_at` = la hora del teléfono, no la del
centro de servicio, así que el Worker no reconoce como duplicados los SMS que ya recibió de ella.
En el gestor de finanzas los pagos del banco se deduplican por número de transacción y no se
duplican, pero cada movimiento del operador usa como referencia la fila cruda del SMS: un reenvío con otra hora
**duplica el gasto**. Por eso, cualquier reenvío de algo que quizá ya llegó por otra vía lo decide el
usuario.

Corte limpio: la app anterior se desinstala o desactiva antes de configurar esta. Si ambas funcionan
a la vez, cada SMS del operador entra dos veces. El primer arranque lo recuerda, lo primero de todo.

### Identidad de un SMS (crítico)

Receptor y barrido deben producir **exactamente** los mismos `sender`, `body` y `received_at` para
el mismo SMS. Si no, el Worker lo guardaría dos veces y la app no sabría que ya lo tiene.

- `received_at` = marca de tiempo del centro de servicio, en milisegundos: en el receptor
  `SmsMessage.getTimestampMillis()`, en el barrido la columna `date_sent`. Se envía como entero
  de 13 dígitos.
- `body` = partes concatenadas en orden (receptor) / columna `body` (barrido).
- `sender` = dirección de origen tal cual llega (sin normalizar; la normalización solo se usa para
  filtrar y para la clave local).
- Clave de deduplicación local: `remitente normalizado + received_at + hash(body)`, con índice
  único en la base de datos.
- **El primero que ve un SMS fija su identidad.** Se guardan en la base local `date_sent` y `date`
  (o su equivalente del receptor). Si el barrido encuentra un SMS que coincide con uno ya guardado,
  no crea otro ni envía otro `received_at`: el valor guardado es el que se envía siempre.
- Coincidencia del barrido contra lo guardado: primero la clave exacta; si no, respaldo por mismo
  remitente normalizado, mismo cuerpo y `received_at` a ±2 s. Si `date_sent` vale 0 o falta, se usa
  `date` para ese SMS.
- **Primera tarea del plan: comprobación en el teléfono real.** Una pantalla de diagnóstico, solo
  en builds debug, muestra en el propio teléfono los valores crudos (remitente, `date`,
  `date_sent`, timestamp del PDU, hash del cuerpo) de receptor y bandeja para los últimos SMS. Nada
  de eso se sube al repo ni a logs. Con lo que muestre se confirma o se ajusta esta sección antes
  de construir la cola.

### Doble SIM

Se reenvían los SMS de ambas SIM si el remitente coincide; el filtro es el remitente, no la SIM.
Se guarda a qué SIM llegó (extra de suscripción en el receptor, `sub_id` en la bandeja) para
mostrarlo en el historial.

### Filtro de remitente

Lista editable en Ajustes. **Empieza vacía: no hay remitentes por defecto en el código**; el
usuario los introduce en el primer arranque (§7). Si la lista queda vacía, la app no reenvía nada y
lo avisa (`HealthIssue.NO_SENDERS`, tarjeta en la pantalla principal y notificación). Comparación con ambos lados
normalizados: sin espacios (en cualquier posición) y en mayúsculas. Coincidencia exacta tras
normalizar.

Quitar un remitente de la lista no cancela los SMS suyos que ya estén en cola: se envían igual.
Añadir un remitente no recupera nada anterior al suelo de barrido; sus SMS más antiguos solo entran
por la recuperación manual.

## 6. Cola y envío

- Base de datos local (Room). Cada SMS se guarda **antes** de cualquier intento de envío.
- Un `CoroutineWorker` por SMS (trabajo único por id), restricción `NetworkType.CONNECTED`,
  backoff exponencial. El receptor lo pide como trabajo acelerado (expedited) cuando se permita.
- El backoff puede llegar al tope de 5 h de WorkManager con red intermitente. Al abrir la app, en
  cada barrido (periódico o manual) y tras guardar credenciales, todo SMS `QUEUED` se vuelve a
  encolar con `REPLACE` para intentarlo ya (siempre con la restricción de red). Este reintento
  forzado no usa trabajo acelerado; el expedited queda para la vía del receptor.
- Solo HTTPS: la app rechaza URLs `http://` y no permite tráfico en claro. Sin certificate pinning
  (la VPN y posibles cambios de certificado de Cloudflare lo harían frágil).
- Timeouts HTTP generosos (orden de 30 s conexión, 60 s lectura) por la red lenta.
- Tratamiento de respuestas:
  - `200`: estado "Enviado".
  - Error de red, timeout, `5xx`, `429`: reintento con backoff, sin límite de intentos. Nunca se
    descarta.
  - `401`: estado "Error 401", sin reintento automático, notificación inmediata. Al guardar un token
    nuevo en Ajustes, todos los SMS en "Error 401" vuelven a la cola automáticamente.
  - `400`: estado "Error 400" con el código de error del cuerpo, sin reintento automático (reintentar
    no lo arregla), notificación inmediata (el usuario debe saberlo). Botón "Reintentar" manual.
  - Otros códigos: tratados como transitorios.
- Cada intento se registra (hora, código HTTP o tipo de error) para el detalle.
- Si un SMS lleva **más de 6 horas** sin recibir 200, notificación "SMS atascado" con el último error.

## 7. Pantallas

Material 3 con Compose. Modo **claro y oscuro**: por defecto sigue al sistema, con selector en
Ajustes (Sistema / Claro / Oscuro). Color dinámico cuando el Android del teléfono lo soporte.

Estructura elegida (opción A del boceto): **una pantalla principal + Ajustes detrás de un engranaje.**

Pantalla principal, de arriba abajo:
1. Avisos activos (si hay): Inicio automático, batería, permisos, token rechazado, sin remitentes. Cada uno con
   botón que abre la pantalla de ajustes correspondiente.
2. Línea de estado: hora del último barrido, número de SMS en cola y en error. Botón "Barrer bandeja
   ahora".
3. Historial, más reciente arriba. Cada fila: remitente, SIM, fecha y hora, inicio del texto y una
   etiqueta de estado: `En cola · N intentos`, `Enviado 200`, `Recuperado · 200` (llegó por
   barrido), `Error 401`, `Error 400`.

Detalle de un SMS (al tocar una fila): texto completo, remitente, SIM, hora, origen (receptor o
barrido), lista de intentos con su resultado. Botón "Reintentar" si está en error.

Ajustes:
- URL del Worker y token. Se muestran ocultos; el token **nunca se vuelve a mostrar**, solo se
  sustituye.
- Botón "Probar conexión": envía al Worker un POST con cuerpo `{}`. `400` = URL y token correctos
  (el Worker no guarda nada). `401` = token incorrecto. Otro resultado = error de red o URL.
- Lista de remitentes: añadir y quitar.
- Tema: Sistema / Claro / Oscuro.

Primer arranque: lo primero, el recordatorio de corte limpio (desinstalar o desactivar la app
anterior), que el usuario debe confirmar. Después, permisos (`RECEIVE_SMS`, `READ_SMS`, y
`POST_NOTIFICATIONS` si el Android lo exige), luego **Remitentes** (hay que añadir al menos uno
para continuar), luego URL y token, luego "Probar conexión". Si sale
bien: recordatorio corto de la app anterior y pantalla de recuperación manual (§5). Etiqueta de historial para estos: `Manual · 200`.

Retención: el historial se borra solo a los 90 días. Los SMS en cola o en error nunca se borran.

## 8. Avisos (notificaciones)

Silencio cuando todo va bien. Solo se notifica:

1. **SMS recuperado**: el barrido encontró uno que el receptor no vio. Texto del tipo "Recuperado
   1 SMS de OPERADOR de las 11:42. El receptor no se ejecutó: revisa Inicio automático."
2. **SMS atascado**: más de 6 h sin 200, o un `401`/`400` (inmediato). Un token rechazado produce
   UNA sola notificación "Token rechazado: N SMS esperando" (id fijo), no una por SMS. Un `400`
   produce una por SMS (es raro). Reencolar reinicia la marca de "ya notificado".
3. **Permisos perdidos**: comprobación periódica (en el mismo trabajo del barrido) de permisos SMS,
   optimización de batería (`PowerManager.isIgnoringBatteryOptimizations`), restricción en segundo
   plano (`ActivityManager.isBackgroundRestricted`) e Inicio automático de MIUI.

MIUI tiene además sus propios interruptores de SMS en la app Seguridad ("Leer SMS",
"SMS de notificación"), distintos del permiso de Android, que pueden bloquear el receptor o la
bandeja aunque Android diga "concedido". Se tratan igual que Inicio automático: detección
best-effort (con `READ_SMS` concedido, una consulta de toda la bandeja sin filtro de fecha y con
`LIMIT 1` que devuelve 0 filas se marca `MIUI_SMS_BLOCKED`; también si falla con el permiso
concedido), aviso con botón, y el primer arranque indica al usuario que los revise.

Inicio automático de MIUI no tiene API pública. Detección best-effort; si no se puede determinar,
la pantalla principal lo indica como "no comprobable" con el botón a la pantalla de MIUI, y no se
notifica en bucle. Los botones abren la actividad de MIUI correspondiente si existe y, si no, los
ajustes de la app.

Límite asumido: sin servicio en primer plano y con Inicio automático quitado, ninguna app garantiza
ejecutarse en MIUI. La garantía real es que el SMS sigue en la bandeja y el siguiente barrido que se
ejecute (como mínimo al abrir la app) lo recupera.

## 9. Secretos y privacidad

- URL y token se introducen una vez en la app y se guardan cifrados con una clave AES-GCM en
  Android Keystore. No usar `EncryptedSharedPreferences` (obsoleto).
- Nunca en el repo, ni en logs, ni en tests, ni en mensajes de excepción. Tampoco se registra el
  cuerpo de los SMS en logcat.
- Copias de seguridad de Android desactivadas para la base de datos y los ajustes
  (`allowBackup=false` o reglas de exclusión). `allowBackup=false` no impide la transferencia
  dispositivo a dispositivo de Android 12+: se añade `android:dataExtractionRules` que excluye base
  de datos, datastore y ficheros tanto en copia en la nube como en transferencia, y
  `fullBackupContent` que los excluye también.
- Si la clave del Keystore falta o es inválida (transferencia, restablecimiento), el fallo al
  descifrar se trata como "no configurado" (aviso `NOT_CONFIGURED`), nunca como un cierre de la app.
- Los remitentes reales y los valores de configuración personales viven en una carpeta local
  `private/`, ignorada por git, y nunca se commitean.
- Repo público, así que:
  - gitleaks en CI sobre cada push y PR;
  - `.gitignore` con `*.jks`, `*.keystore`, `local.properties`, `.superpowers/`, `.codebase-memory/`;
  - SMS de prueba inventados con formato real, por ejemplo: "OPERADOR: Se ha comprado un plan de
    datos de 2.5GB valido por 30 dias, se activa con el primer uso. Se desconto 360.00. Saldo
    actual 140.25". Sin números de teléfono ni tarjetas reales.

## 10. Relación con el gestor de finanzas

App **independiente**. El Worker es el punto de integración: el gestor de finanzas recibe los SMS por
`/ingest/sms` venga de donde venga. Una futura lectura nativa de SMS en su propia APK deja de hacer
falta; eso se anota en aquel repo, no en este.

Dos módulos Gradle (cambio hecho al planificar): `:core`, Kotlin puro sin Android (filtro,
identidad del SMS, JSON, clasificación de respuestas, reglas de cola), que se prueba en local solo
con el JDK; y `:app`, con todo lo de Android (receptor, barrido, Room, WorkManager, UI). No hay SDK
de Android en local, así que sin esta separación cada test exigiría pasar por GitHub Actions.

## 11. Compilación y publicación

- GitHub Actions:
  - En cada push y PR: compilación, tests unitarios de JVM, lint, gitleaks y un `assembleRelease`
    sin firmar, para que una rotura de R8/minify salga antes de etiquetar.
  - Al empujar una etiqueta `vX.Y.Z`: APK de release firmada, publicada en una Release de GitHub.
- Firma: una sola clave. El usuario la genera en su PC con `keytool` (el plan da los comandos paso a
  paso), la pasa a base64 y la guarda en los secretos del repo junto con contraseñas y alias. Debe
  guardar una copia fuera de GitHub: sin ella no se puede actualizar la app sin desinstalar (y
  desinstalar pierde la cola).
- `versionCode` derivado de la etiqueta (`vX.Y.Z` → `X*10000 + Y*100 + Z`) para que cada versión
  se instale encima de la anterior.
- Los secretos se suben por la web de GitHub (Settings → Secrets); el plan da también el comando
  `gh secret set` como alternativa.
- Instalación: el teléfono pedirá permitir "instalar apps desconocidas" al navegador y Play Protect
  puede avisar de app no verificada; ambos se aceptan una vez. Descargar la APK de la Release desde el teléfono e instalar encima de la anterior.

## 12. Pruebas

- Tests unitarios en JVM (en CI): normalización y filtro de remitentes, unión de multiparte,
  construcción de la clave local y del JSON, clasificación de respuestas HTTP (200/400/401/5xx/red),
  lógica de la cola y del barrido (cursor, solape, deduplicación), detección de "atascado".
- Sin tests de emulador en CI.
- Lista de comprobación manual en el teléfono real, incluida en el repo: SMS normal, multiparte,
  sin red y recuperación, app cerrada a la fuerza y barrido al abrir, token incorrecto, reinicio
  del teléfono, coincidencia receptor/barrido (§5).

## 13. Decisiones tomadas por mí (a revisar)

- Recuperación manual de 7 días, ninguno marcado (aprobado por el usuario: sin reenvío automático
  hacia atrás).
- Solape de 24 h en cada barrido.
- Ambas SIM, filtro por remitente; SIM guardada para mostrar.
- Umbral de "atascado": 6 h. `401` y `400` notifican al instante (un token rechazado, en una sola
  notificación agrupada).
- `400` sin reintento automático; `401` se reencola al cambiar el token.
- Suelo de barrido fijado al terminar el primer arranque: el barrido nunca lee antes de él.
- Nada activo (receptor ni barrido) hasta terminar el primer arranque, y el recordatorio de corte
  limpio va el primero.
- `minSdk 31` (el teléfono ya está en Android 14).
- Retención del historial: 90 días.
- "Probar conexión" con cuerpo `{}`.
- Tema: sigue al sistema por defecto, con selector; color dinámico si está disponible.
- Sin comprobación de actualizaciones en la app.
- Dos módulos Gradle (`:core` puro + `:app`), para poder probar la lógica en local sin SDK.
- Sin remitentes por defecto en el código: la lista empieza vacía, el primer arranque exige al menos
  uno y `HealthIssue.NO_SENDERS` avisa si queda vacía. Los reales viven en `private/` (git-ignorado).

## 14. Lo que queda al builder

- Nombre de paquete (genérico, sin datos personales), versiones de dependencias, librería HTTP.
- Esquema concreto de Room y migraciones.
- Cómo detectar Inicio automático en MIUI y qué intents exactos abren cada pantalla de ajustes.
- Diseño visual fino dentro de Material 3.
- Estructura del workflow de Actions y caché de Gradle.
