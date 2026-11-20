# SMS Forwarder

Android app that forwards SMS from the senders you choose to a configured HTTPS endpoint.
It stores each message in a local queue and retries until it gets a 200; it also sweeps the
inbox periodically as a safety net.

## Installation

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png"
alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/Lacho00/sms-forwarder)

- [Obtainium](https://obtainium.imranr.dev/): installs and updates from GitHub Releases.
- [GitHub Releases](https://github.com/Lacho00/sms-forwarder/releases/latest): signed APK.

Both channels use the same signature, so you can switch between them without uninstalling.

## Features

- Bottom navigation with Home, Rules, Destinations and Settings; About, Export and Import in the
  overflow menu.
- Home: search and status filters, hide SMS text, and soft delete
  (an SMS deleted from the app is not forwarded again even if it is still in the inbox).
- Rules: choose which senders (and, optionally, which message bodies) go to which destinations.
- Destinations: several endpoints, each with its own method, headers, body template and
  connection test. Secrets are stored in the Android Keystore.
- Export and import of destinations and rules as a JSON file; secrets are left out unless you choose
  to include them.
- Settings by group: forwarding pause, sweep interval, history retention,
  notifications by type, and dynamic color.
- Diagnostic report without personal data, from About.
- Interface in English and Spanish.
- No Google services, no trackers.
- MIT license ([LICENSE](LICENSE)).

## Building

The app also builds locally with the Android SDK and JDK 17 (`./gradlew :core:test`,
`:app:testDebugUnitTest`), but GitHub Actions is the reference. Screenshots:
[docs/screenshots.md](docs/screenshots.md).

`scripts/e2e-emulator.sh` tests delivery end to end on an emulator with the debug build (seeding by broadcast, debug only).

## Signing

See [docs/signing.md](docs/signing.md). Releases are published with a `vX.Y.Z` tag;
the Release includes the signed APK.

## Manual testing

[docs/manual-test-checklist.md](docs/manual-test-checklist.md).
