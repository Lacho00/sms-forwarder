# Screenshots of the main screens

`ScreenshotsTest` renders Inicio, Reglas, Destinos and the SMS detail in light and dark with fake data
(AAA, TEST, BANK, `h.example`) and writes PNGs to `app/build/screenshots/`. It is skipped by the normal
`:app:testDebugUnitTest`; it only runs with `-Pscreenshots`:

```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest'
```

The test uses Robolectric native graphics, so it needs no emulator. Output files are named
`<screen>-light.png` and `<screen>-dark.png`; they are not committed.
