# Development notes

## Versioning and releases

This project uses SemVer releases derived from conventional commits on `master`.

- All commits are expected to follow conventional commit rules.
- Pull requests are expected to pass the `Validate Commits` check before merge.
- Pull requests are expected to pass the `Validate Code` build-and-test check before merge.
- Merge conventional commits into `master`, for example `feat:`, `fix:`, and `feat!:` or `BREAKING CHANGE:`.
- If a push changes release paths but the commit subjects are not conventional, the workflow fails instead of silently skipping the release.
- The release workflow calculates the next SemVer version, tags `HEAD` as `vMAJOR.MINOR.PATCH`, builds the signed release APK, and publishes the GitHub release.
- `versionName` is derived from the release version without the leading `v`.
- `versionCode` is derived as `MAJOR * 10000 + MINOR * 100 + PATCH`.
- If there are no releasable conventional commits since the previous tag, the workflow skips the release.

## Release signing secrets

The release workflow can sign the APK when these repository secrets are configured:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## App identity

The current scaffold uses these identifiers:

- Package name: `net.trajano.cloudmediaproviderproxy`
- Provider authority: `net.trajano.cloudmediaproviderproxy.cloud`
