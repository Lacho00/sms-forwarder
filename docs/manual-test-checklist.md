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
