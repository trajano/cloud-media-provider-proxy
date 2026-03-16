# Development notes

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
