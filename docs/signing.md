# Firma de la release

La APK de la Release la firma GitHub Actions con una clave que creas una vez. Sin esa clave no
puedes publicar actualizaciones que se instalen encima de la anterior.

1. Genera la clave:

   ```
   keytool -genkeypair -v -keystore sms-forwarder.jks -keyalg RSA -keysize 4096 -validity 36500 -alias sms-forwarder
   ```

2. Codifica el fichero en base64:

   ```
   base64 -w0 sms-forwarder.jks
   ```

3. Crea cuatro secretos en el repo, por la web (Settings → Secrets and variables → Actions) o con
   `gh secret set`:

   | Secreto | Valor |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | salida del paso 2 |
   | `SIGNING_STORE_PASSWORD` | contraseña del almacén |
   | `SIGNING_KEY_ALIAS` | `sms-forwarder` |
   | `SIGNING_KEY_PASSWORD` | contraseña de la clave |

   Con `gh`: `base64 -w0 sms-forwarder.jks | gh secret set SIGNING_KEYSTORE_BASE64` y
   `gh secret set SIGNING_STORE_PASSWORD` (y así con los demás).

4. Guarda el `.jks` y las contraseñas fuera de GitHub (gestor de contraseñas). Si los pierdes, la
   app no se puede actualizar: habría que desinstalarla e instalar otra firmada con una clave nueva.

5. La build debug no hace falta desinstalarla: tiene otro `applicationId`
   (`dev.smsforwarder.debug`).

## Publicar una versión

```
git tag v1.0.0 && git push origin v1.0.0
gh run watch
```

La Release aparece con `sms-forwarder-v1.0.0.apk`. `versionCode` = X·10000 + Y·100 + Z.
