# Cloud Media Provider proxy

This app proxies [CloudMediaProvider](https://developer.android.com/reference/android/provider/CloudMediaProvider) requests to the Storage Access Framework. Use it to expose a folder from Nextcloud or another SAF-capable app as a cloud media source.

## Install

Build and install the debug APK:

```powershell
.\gradlew.bat installDebug
```

## Configure the folder

Open the app once after installing it and choose the folder tree that should back the provider.

The app stores a persisted read grant for that tree and uses it as the cloud media source.

## Add the proxy to DeviceConfig

Append `net.trajano.cloudmediaproviderproxy` to `allowed_cloud_providers` in both namespaces. Do not replace the full value.

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

## Expected result

After the folder is configured and the package is appended to DeviceConfig, the app should appear in the cloud media app settings UI.

## Developer notes

Developer and release details live in [docs/development.md](/P:/cloud-media-provider-proxy/docs/development.md).
