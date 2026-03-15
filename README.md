# Cloud Media Provider proxy
This proxies the [CloudMediaProvider](https://developer.android.com/reference/android/provider/CloudMediaProvider) requests to the Storage Access Framework. It is an Android application with a small launcher UI used to select the SAF tree that backs the provider.

The main objective is to allow Nextcloud to be a source for full-screen contact images in Google Contacts.

## License
This project is licensed under the Eclipse Public License 2.0. See [LICENSE](/P:/cloud-media-provider-proxy/LICENSE).

## Versioning and releases
This project uses SemVer releases derived from conventional commits on `master`.

- Merge conventional commits into `master`, for example `feat:`, `fix:`, and `feat!:` or `BREAKING CHANGE:`.
- The release workflow calculates the next SemVer version, tags `HEAD` as `vMAJOR.MINOR.PATCH`, builds the signed release APK, and publishes the GitHub release.
- `versionName` is derived from the release version without the leading `v`.
- `versionCode` is derived as `MAJOR * 10000 + MINOR * 100 + PATCH`.
- If there are no releasable conventional commits since the previous tag, the workflow skips the release.

The release workflow can also sign the APK when these repository secrets are configured:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The manual `workflow_dispatch` trigger is still available, but the normal path is a push to `master`.

## Current app identity
The current scaffold uses these identifiers:

- Package name: `net.trajano.cloudmediaproviderproxy`
- Provider authority: `net.trajano.cloudmediaproviderproxy.cloud`

## Goal for the next stage
For the next stage, the app should be visible in the system's cloud media app settings so it can be selected as a cloud media source.

On current Android builds, that usually requires two things:

- The app must be installed and expose a valid `CloudMediaProvider` in the manifest.
- The package must be allowlisted for cloud media through `adb shell` DeviceConfig commands.

## Install the app
Build and install the debug APK:

```powershell
.\gradlew.bat installDebug
```

## Configure a SAF source
Open the app once after installing it and pick a folder tree from Nextcloud or another app that exposes a Storage Access Framework provider. The app stores the persisted read grant and uses that tree as the source for cloud media queries.

Current SAF behavior:

- Images and videos under the selected tree are surfaced as cloud media items.
- Media metadata is derived from generic SAF document columns.
- Deletes and albums are not yet modeled.

## Add the app to the cloud media allowlist
Do not overwrite the existing allowlist unless you mean to. Some emulator images already include other cloud media providers, such as Google Photos.

Enable the feature flags first:

```powershell
adb shell device_config put mediaprovider cloud_media_feature_enabled true
adb shell device_config put storage_native_boot cloud_media_feature_enabled true
```

Then append this package to the existing allowlist in both namespaces instead of replacing the whole value:

```powershell
$package = "net.trajano.cloudmediaproviderproxy"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$namespaces = @("mediaprovider", "storage_native_boot")

foreach ($namespace in $namespaces) {
    $current = (& $adb shell device_config get $namespace allowed_cloud_providers).Trim()
    if ($current -eq "null" -or [string]::IsNullOrWhiteSpace($current)) {
        $merged = $package
    } else {
        $merged = (($current -split ",") + $package | Where-Object { $_ } | Select-Object -Unique) -join ","
    }

    & $adb shell device_config put $namespace allowed_cloud_providers $merged
}
```

If DeviceConfig sync keeps overwriting local test values, disable sync until the next reboot:

```powershell
adb shell cmd device_config set_sync_disabled_for_tests until_reboot
```

## Verify that Android sees the provider
List all installed cloud media providers:

```powershell
adb shell /apex/com.android.mediaprovider/bin/media_provider cloud-provider list --all
```

List only allowlisted cloud media providers:

```powershell
adb shell /apex/com.android.mediaprovider/bin/media_provider cloud-provider list
```

Show the current selected cloud media provider:

```powershell
adb shell /apex/com.android.mediaprovider/bin/media_provider cloud-provider info
```

Select this provider explicitly by authority:

```powershell
adb shell /apex/com.android.mediaprovider/bin/media_provider cloud-provider set net.trajano.cloudmediaproviderproxy.cloud
```

Unset the current provider:

```powershell
adb shell /apex/com.android.mediaprovider/bin/media_provider cloud-provider unset
```

## Remove the allowlist entry or disable cloud media
Remove only this package from both namespaces and keep any other providers that were already present:

```powershell
$package = "net.trajano.cloudmediaproviderproxy"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$namespaces = @("mediaprovider", "storage_native_boot")

foreach ($namespace in $namespaces) {
    $current = (& $adb shell device_config get $namespace allowed_cloud_providers).Trim()
    if ($current -eq "null" -or [string]::IsNullOrWhiteSpace($current)) {
        continue
    }

    $remaining = ($current -split "," | Where-Object { $_ -and $_ -ne $package } | Select-Object -Unique) -join ","

    if ([string]::IsNullOrWhiteSpace($remaining)) {
        & $adb shell device_config delete $namespace allowed_cloud_providers
    } else {
        & $adb shell device_config put $namespace allowed_cloud_providers $remaining
    }
}
```

Disable the cloud media feature in both namespaces:

```powershell
adb shell device_config put mediaprovider cloud_media_feature_enabled false
adb shell device_config put storage_native_boot cloud_media_feature_enabled false
```

## Expected result
After install and allowlisting, the app should appear in the cloud media app settings UI. If it does not, verify these in order:

- The package is installed.
- The provider authority is `net.trajano.cloudmediaproviderproxy.cloud`.
- `media_provider cloud-provider list --all` shows the provider.
- `allowed_cloud_providers` contains `net.trajano.cloudmediaproviderproxy`.
- `cloud_media_feature_enabled` is `true` in the relevant namespace.
