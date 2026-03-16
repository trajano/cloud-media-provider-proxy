# Agent notes

## Release workflow

- Use conventional commits for all changes.
- Expect pull requests to pass `Validate Commits` before merge.
- Expect pull requests to pass `Validate Code` before merge.
- Treat the release workflow as SemVer-driven from conventional commits on `master`.
- Expect the release workflow to fail if release-path changes reach `master` without a releasable conventional commit.

## Definition of done

- Before pushing to a pull request branch, assemble the APK locally with `.\gradlew.bat :app:assembleDebug`.
- Before pushing to a pull request branch, run the relevant local tests for the change.

## Version mapping

- `versionName` is derived from the release tag without the leading `v`.
- `versionCode` is derived as `MAJOR * 10000 + MINOR * 100 + PATCH`.

## Release signing

When working on release automation, these repository secrets are used for signing:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
