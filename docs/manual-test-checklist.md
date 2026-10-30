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
