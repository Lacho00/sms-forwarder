# SMS Forwarder

Aplicación Android que reenvía los SMS de los remitentes elegidos a un endpoint HTTPS
configurado. Guarda cada mensaje en una cola local y lo reintenta hasta recibir un 200;
además barre periódicamente la bandeja de entrada como red de seguridad.

## Uso (1.1.0)

- Navegación inferior con Inicio y Ajustes; Acerca de en el menú.
- Inicio: búsqueda y filtros por estado, ocultar el texto de los SMS y borrado suave
  (un SMS borrado de la app no se reenvía aunque siga en la bandeja).
- Ajustes por grupos: pausa del reenvío, intervalo del barrido, retención del historial,
  avisos por tipo y color dinámico.
- Interfaz en español e inglés.
- Licencia MIT ([LICENSE](LICENSE)).

## Compilación

La app también compila en local con el SDK de Android y JDK 17 (`./gradlew :core:test`,
`:app:testDebugUnitTest`), pero la referencia es GitHub Actions. Capturas de las pantallas:
[docs/screenshots.md](docs/screenshots.md).

`scripts/e2e-emulator.sh` prueba el envío de punta a punta en un emulador con la compilación debug (siembra por broadcast, solo debug).

## Firma

Ver [docs/signing.md](docs/signing.md). Las versiones se publican con una etiqueta `vX.Y.Z`;
la Release incluye la APK firmada.

## Pruebas manuales

[docs/manual-test-checklist.md](docs/manual-test-checklist.md).
