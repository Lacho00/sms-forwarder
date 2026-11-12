# F-Droid and IzzyOnDroid

## Release procedure

The version lives as literals in `app/build.gradle.kts` so the F-Droid build server (which sets no
CI environment variables) and its `checkupdates` can read it.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
   `versionCode = x*10000 + y*100 + z` for `versionName = "x.y.z"` (2.1.1 is 20101).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and the `es-ES` one
   (500 characters at most each).
3. Commit, then tag `vX.Y.Z` on that commit and push the tag.
4. The release workflow fails early if the tag differs from `v` + `versionName`, or if `versionCode`
   does not follow the scheme, before building anything.

Store metadata (title, descriptions, icon, screenshots, changelogs) is in `fastlane/metadata/android/`.
Screenshots come from `docs/screenshots.md` (fake data). When the UI changes, render both locales and copy
`main-light`, `detail-light`, `rules-light`, `destinations-light` and `main-dark` as `1.png` to `5.png`
into `en-US/images/phoneScreenshots/` (from `en/`) and `es-ES/images/phoneScreenshots/` (from `es/`).

## F-Droid

F-Droid builds the app from source with reproducible builds enabled: it compares its unsigned APK
with the signed APK from the GitHub Release (`Binaries`) and, if they match byte for byte apart from
the signature, publishes our APK with our signature (`AllowedAPKSigningKeys`). So F-Droid, GitHub,
Obtainium, IzzyOnDroid and Uptodown all ship the same signature and updates work across channels. The
build disables `dependenciesInfo` so no encrypted dependency blob is embedded.

Keep releases reproducible: the release workflow builds with JDK 17 (Temurin), and
`META-INF/version-control-info.textproto` holds the commit hash, so it only matches a build made
from a real git clone (not a `git worktree`). To check a release locally, build the tag from a clean
clone with `./gradlew :app:assembleRelease` and no `SIGNING_*` variables, strip the APK Signing
Block from the release APK and compare the two files byte for byte (`apksigcopier compare` does both steps). If a release stops being
reproducible, F-Droid will not publish it.

Recipe in fdroiddata (`metadata/dev.smsforwarder.yml`), merge request
https://gitlab.com/fdroid/fdroiddata/-/merge_requests/56891:

```yaml
Categories:
  - Phone & SMS
  - Connectivity
License: MIT
AuthorName: Lacho00
SourceCode: https://github.com/Lacho00/sms-forwarder
IssueTracker: https://github.com/Lacho00/sms-forwarder/issues
Changelog: https://github.com/Lacho00/sms-forwarder/releases

AutoName: SMS Forwarder

RepoType: git
Repo: https://github.com/Lacho00/sms-forwarder.git
Binaries: https://github.com/Lacho00/sms-forwarder/releases/download/v%v/sms-forwarder-v%v.apk

Builds:
  - versionName: 2.1.3
    versionCode: 20103
    commit: e49cdfb4cac3c8be53c1ae2aefee565b43ce2e0f
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: fa54d7cbc42840e71840327ce6b6f7487438c22345197b94c064fe7c7bed98e5

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 2.1.3
CurrentVersionCode: 20103
```

`commit` must be the full hash of the tagged commit (`git rev-parse vX.Y.Z^{}`). After the first
merge, `AutoUpdateMode: Version` and `UpdateCheckMode: Tags` pick up new tags without another merge
request. Categories must exist in `config/categories.yml` of fdroiddata.

GitLab asks to verify the account (phone or card) before running pipelines on the fork. Following
the fdroiddata template, do not verify: leave a note in the merge request so a maintainer starts the
CI.

## IzzyOnDroid

IzzyOnDroid serves the APK from the GitHub Releases page, signed with our own key (see
`docs/signing.md`), so updates are compatible with the GitHub APK. Request inclusion by opening an
issue in the Codeberg issue tracker of
[IzzyOnDroid/repodata](https://codeberg.org/IzzyOnDroid/repodata/issues) with the "App Inclusion
Request" template, pointing to this repo. The template has a mandatory section on AI use; fill it
in. Each release must attach the signed APK (the workflow already does).

Our request: https://codeberg.org/IzzyOnDroid/repodata/issues/869.

## Uptodown

Uptodown takes the signed APK from the GitHub Release as is, so its updates are compatible with the
GitHub and IzzyOnDroid APKs. Upload it from the Uptodown developer console, using the texts and
screenshots in `fastlane/metadata/android/`. Each new release has to be uploaded there
again.
