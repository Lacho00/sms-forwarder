# Lista de comprobación manual

Se recorre en el teléfono real con la APK de la Release. Apunta los casos que fallen.

- [ ] SMS normal de un remitente configurado: llega al endpoint y aparece como enviado.
- [ ] SMS multiparte: se reenvía una sola vez, con el texto completo.
- [ ] Modo avión: el SMS queda en cola y se envía al volver la red.
- [ ] Forzar la detención y abrir la app: el barrido recupera el SMS perdido y notifica.
- [ ] Token incorrecto: respuesta 401, notificación; al corregir el token se reencola y se envía.
- [ ] Reinicio del teléfono: la app sigue activa y el barrido sigue programado.
- [ ] La app reenviadora anterior está desactivada antes de configurar.
- [ ] Modo claro y modo oscuro: la interfaz se ve bien en ambos.
- [ ] Quitar Inicio automático (MIUI): aparece el aviso.
- [ ] Android 15, ajustes restringidos: si no sale el diálogo de permiso, el primer arranque
      explica "Permitir ajustes restringidos".
- [ ] Clave perdida tras transferir a otro dispositivo: la app muestra `NOT_CONFIGURED` y no se cierra.
- [ ] El primer barrido tras la configuración no envía nada anterior a la configuración.
- [ ] Un SMS que llega durante el primer arranque no se reenvía.
- [ ] El primer arranque no deja continuar sin al menos un remitente.
- [ ] Vaciar la lista de remitentes en Ajustes muestra la tarjeta y la notificación de `NO_SENDERS`.

## v1.1.0

- [ ] Icono en el cajón de apps: se ve bien en modo claro, oscuro y con icono temático (monocromo).
- [ ] Cambiar el idioma (español/inglés) en Ajustes no pierde la pantalla en la que estás.
- [ ] Pausar el reenvío: los SMS nuevos no se envían; al reanudar se envían.
- [ ] Borrar de la app un SMS que sigue en la bandeja y esperar un barrido: no se reenvía.
- [ ] Intervalo de barrido: tras cambiarlo, `adb shell dumpsys jobscheduler | grep dev.smsforwarder`
      muestra el periodo nuevo.
- [ ] Apagar el color dinámico: la interfaz usa la paleta fija y se ve bien en claro y oscuro.
- [ ] Ocultar texto: Inicio y Detalle no muestran el contenido de los SMS.
- [ ] Inicio: la búsqueda y los filtros (todos, en cola, error) acotan la lista.
- [ ] Acerca de: muestra la versión correcta, el enlace al repositorio se abre y aparece la licencia MIT.
- [ ] Notificaciones por tipo (recuperado, atascado, permisos): al apagar cada una, ese aviso no sale.
- [ ] Un rechazo 400 sigue notificando aunque estén apagados los avisos opcionales.
- [ ] Retención: con un plazo corto, el historial enviado se purga; los SMS en cola o con error no se borran.

## Actualizar de 1.1 a 1.2

Se instala la APK de la Release de la rama sobre la 1.1.0 real, sin desinstalar (`adb install -r`).

- [ ] Inicio muestra el mismo historial que antes de actualizar.
- [ ] Ajustes muestra los mismos remitentes.
- [ ] La prueba de conexión funciona.
- [ ] Un SMS nuevo de un remitente configurado llega al endpoint con 200.
- [ ] La app no pide ningún permiso nuevo.

La APK de la Release no es depurable, así que no se puede copiar la base de datos antes de actualizar.
La red de seguridad son las pruebas de migración (T3) y de importación (T5). Quien instala confirma
antes que acepta ese riesgo.

## v1.3.0

- [ ] Crear un destino de cada plantilla (JSON estándar, Telegram, ntfy, personalizado) y pulsar Probar:
      el JSON estándar con cuerpo `{}` responde 400 (correcto); Telegram y ntfy reciben el SMS de prueba.
- [ ] El SMS de prueba no aparece en Inicio ni deja intentos.
- [ ] Un destino caído junto a otro que funciona: el SMS llega al que funciona y el caído no lo retrasa.
- [ ] Pausar un destino con SMS en cola: no se envían; al reanudar se envían enseguida y el otro
      destino, ya enviado, no repite.
- [ ] Pausar un destino que tiene entregas en error: siguen en error, no vuelven a "en cola".
- [ ] Borrar un destino con SMS en cola: el aviso indica el número de SMS, las entregas quedan
      canceladas y no hay aviso de atascado.
- [ ] Regla regex con `(?i)` y otra con filtro de texto "contiene".
- [ ] Regla con filtro "no contiene": un SMS que contiene el texto se descarta y otro del mismo
      remitente que no lo contiene se reenvía.
- [ ] Regla con SIM 2 en un teléfono de una SIM: no reenvía nada.
- [ ] Editar el token de un destino con 401: el SMS se reenvía.
- [ ] Reanudar un destino en pausa tras corregir su token (tenía 401/403): sus SMS vuelven a la cola
      y se envían.
- [ ] Detalle: "Reenviar a un destino" manda el SMS al destino elegido, también a uno que ya lo recibió.
- [ ] Detalle: el código HTTP solo aparece si procede de una entrega que falla; con un destino en 200 y
      otro en 403 no sale "Error 200".
- [ ] Inicio: los chips de destino filtran la lista y cada fila muestra el estado por destino.
- [ ] Cambiar el idioma con un editor abierto no pierde la pantalla.
- [ ] Actualizar de 1.2.0 a 1.3.0 (`adb install -r`) no pierde destinos, reglas ni historial.
