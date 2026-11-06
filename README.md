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

## Remote media performance

The app starts indexing the selected folder in the background after configuration
and when its process starts. It keeps the current catalogue in memory and persists
it in SQLite, so repeated thumbnail requests do not reload the whole library.
If no catalogue exists yet, the first request waits for one shared initial scan.

The app warms up to 12 recent thumbnails. Opening a visible preview cancels this
speculative work. Thumbnail downloads share work for the same resource, while
different resources can load concurrently. Cached thumbnails use size buckets;
a larger cached thumbnail can satisfy a smaller request. Older cached thumbnails
remain available while a bounded background queue refreshes them.

Video playback previews open the video directly. When an image provider cannot
supply a thumbnail, the foreground request opens the original directly without
first copying the entire file into the preview cache. Background warming skips
unavailable thumbnails. Remote providers may still need to download content before
returning a descriptor; device measurements are needed to assess that latency.

## Developer notes

Developer and release details live in [docs/development.md](docs/development.md).
