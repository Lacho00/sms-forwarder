# SMS Forwarder

Aplicación Android que reenvía los SMS de los remitentes elegidos a un endpoint HTTPS
configurado. Guarda cada mensaje en una cola local y lo reintenta hasta recibir un 200;
además barre periódicamente la bandeja de entrada como red de seguridad.

## Compilación

La app se compila en GitHub Actions, no en local. En local solo se prueba el módulo
`:core` (Kotlin puro): `./gradlew :core:test` con JDK 17.

## Firma

Ver [docs/signing.md](docs/signing.md). Las versiones se publican con una etiqueta `vX.Y.Z`;
la Release incluye la APK firmada.

## Pruebas manuales

[docs/manual-test-checklist.md](docs/manual-test-checklist.md).
