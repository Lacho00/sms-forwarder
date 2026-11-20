# Release signing

GitHub Actions signs the Release APK with a key you create once. Without that key you cannot
publish updates that install over the previous version.

1. Generate the key:

   ```
   keytool -genkeypair -v -keystore sms-forwarder.jks -keyalg RSA -keysize 4096 -validity 36500 -alias sms-forwarder
   ```

2. Encode the file in base64:

   ```
   base64 -w0 sms-forwarder.jks
   ```

3. Create four secrets in the repo, through the web UI (Settings → Secrets and variables → Actions)
   or with `gh secret set`:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEYSTORE_BASE64` | output of step 2 |
   | `SIGNING_STORE_PASSWORD` | keystore password |
   | `SIGNING_KEY_ALIAS` | `sms-forwarder` |
   | `SIGNING_KEY_PASSWORD` | key password |

   With `gh`: `base64 -w0 sms-forwarder.jks | gh secret set SIGNING_KEYSTORE_BASE64` and
   `gh secret set SIGNING_STORE_PASSWORD` (and likewise for the others).

4. Keep the `.jks` and the passwords outside GitHub (password manager). If you lose them, the
   app cannot be updated: you would have to uninstall it and install another build signed with a new key.

5. The debug build does not need to be uninstalled: it has a different `applicationId`
   (`dev.smsforwarder.debug`).

## Publishing a release

```
git tag v1.0.0 && git push origin v1.0.0
gh run watch
```

The Release appears with `sms-forwarder-v1.0.0.apk`. `versionCode` = X·10000 + Y·100 + Z.
