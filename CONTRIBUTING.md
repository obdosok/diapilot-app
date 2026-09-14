# Contributing

DiaPilot is a personal DIY project, built by one person for their own type 1
diabetes management and published for transparency and learning. Issues and
pull requests are welcome, but this repository is maintained on a
best-effort, spare-time basis — please be patient waiting for a review.

Before opening a large pull request, consider opening an issue first to
discuss the approach; it saves rework if the idea does not fit the project's
direction.

## Building and testing

```bash
export JAVA_HOME=<a JDK 21, e.g. the JetBrains Runtime bundled with Android Studio>
./gradlew :core:test :app:test :app:assembleDebug --console=plain
```

`:app:test` and `:app:assembleDebug` cover **both editions** (`oss` and
`store` — see [docs/editions.md](docs/editions.md)); a single one is
`:app:testOssDebugUnitTest` / `:app:assembleOssDebug`. Both must stay green:
the edition boundary is asserted by a test that runs on each flavor.

The Android SDK must be installed (Android Studio writes its path to
`local.properties`, or set `ANDROID_HOME`); CI does not need `local.properties`
at all. See the README's "Building" section for the phone-side setup needed
to run the app itself, and `:app:connectedOssDebugAndroidTest` for instrumented
tests, which need a device.

CI runs `:core:test`, `:app:test`, `:app:assembleDebug`,
`:app:assembleOssRelease` and `:app:lintOssDebug` on every push and pull
request — both editions — please keep these green. Lint aborts on errors and
has no baseline; if a change trips a check, fix it or, when the check is
wrong for this code, disable that single check in `app/build.gradle.kts` with
the reason written next to it (two are already there as examples).

## Releasing

Releases are cut by the maintainer from a `v*` tag; the procedure — the
release keystore, the four `DIAPILOT_*` environment variables /
`keystore.properties`, the version bump, and what
`.github/workflows/release.yml` does with the tag — is in the README's
"Building → Cutting a release" section, and the user-facing verification
steps in its "Install" section. Two rules for contributors:

- Never commit a keystore or its passwords. `keystore.properties`, `*.jks`
  and `*.keystore` are gitignored; `keystore.properties.example` is the only
  file of that shape that belongs in the tree.
- Do not publish a debug-signed APK anywhere. Locally, without a keystore,
  `:app:assembleOssRelease` prints `WARNING [audit S8]` and signs with the
  debug key; that build is for smoke tests only.

## Code style

- Comments, log messages and test/function names are in English, and
  impersonal — no first-person narration of the author's own data or dates
  tied to a real event.
- Follow the existing Kotlin formatting: one statement per line, spaces after
  commas and around operators, 4-space indentation.
- Add or update tests for any behaviour change. `:core` mirrors the
  `diapilot-reference/` Python reference's contracts where applicable — keep
  new numeric logic checked against it rather than only against itself.
- Never weaken or delete an existing test assertion to make a test pass.

## No personal or patient data

This is the hard rule of the project: no real glucose readings, doses, dates
tied to a real event, photos, names, or any other personal or patient data in
code, comments, tests, fixtures, or commit messages — synthetic data only.
Existing tests and the bundled model already use a synthetic example person;
follow that pattern for anything new. If you are ever unsure whether a value
is "real", treat it as real and use a made-up one instead.

## Translations

The UI supports System, English and Russian, with English as the default.
Strings live under `app/src/main/res/values/` (English, the fallback) and
`app/src/main/res/values-ru/` (Russian). To add another language, create a new
`values-xx/` directory with the same string keys — resource keys themselves
are language-neutral and live in `:core` (or reference `:core`'s stored
keys/labels), so translating is a matter of adding resource values, not
touching logic. Keep a translation's meaning and tone close to the English
source, and do not translate `translatable="false"` strings (placeholders,
technical identifiers).

## License and contributor agreement

DiaPilot is licensed under GPL-3.0 (see `LICENSE`).

Before a pull request is merged, its author agrees to the following:

> You confirm that you wrote the contribution, or otherwise have the right to
> submit it. You license it to the project under GPL-3.0, and you also grant
> the project maintainer a perpetual, worldwide, non-exclusive, royalty-free,
> irrevocable license to use, modify and relicense the contribution under other
> terms, including in versions of the software that are not open source. You
> keep the copyright in your contribution.

Code ported from other projects, such as the xDrip+ ports, keeps its original
license and cannot be relicensed. Mark it in the file header, as the existing
ports do.
