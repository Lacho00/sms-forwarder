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

F-Droid builds from source and signs with its own key, so a user coming from the GitHub APK must
uninstall and reinstall (the signatures differ). Unless F-Droid verifies a reproducible build against
the developer-signed APK, the two channels are not interchangeable. The build disables
`dependenciesInfo` so no encrypted dependency blob is embedded.

Draft recipe for `metadata/dev.smsforwarder.yml` in fdroiddata:

```yaml
Categories:
  - Phone & SMS
  - Connectivity
License: MIT
SourceCode: https://github.com/Lacho00/sms-forwarder
IssueTracker: https://github.com/Lacho00/sms-forwarder/issues
Changelog: https://github.com/Lacho00/sms-forwarder/releases

AutoName: SMS Forwarder

RepoType: git
Repo: https://github.com/Lacho00/sms-forwarder.git

Builds:
  # v2.1.2 is the first tag with the literal version in app/build.gradle.kts; v2.1.1 and older
  # take it from GITHUB_REF_NAME and cannot be built by F-Droid.
  - versionName: 2.1.2
    versionCode: 20102
    commit: v2.1.2
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 2.1.2
CurrentVersionCode: 20102
```

To open the merge request: fork `fdroid/fdroiddata` on GitLab, add the recipe as
`metadata/dev.smsforwarder.yml` on a new branch of the fork, push it and open the merge request
against `fdroid/fdroiddata` with its "App inclusion" template. Categories must exist in
`config/categories.yml` of fdroiddata.

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
