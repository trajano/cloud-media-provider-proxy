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
If no catalogue exists yet, picker requests return an empty snapshot immediately
while WorkManager schedules a shared background scan. Committed updates notify
the picker. Queued indexing and photo preparation survive normal app process
restarts and device reboots, subject to Android's background scheduling. Changing
the folder or compatibility option cancels outdated jobs.

Android also watches the selected SAF source's change notifications while the
app is closed. Notifications are batched before scheduling ordinary background
indexing, with no periodic polling or foreground service. The watcher waits when
the battery is low. This depends on the source app emitting SAF notifications;
server-only changes remain unknown until the source app discovers and announces
them. Opening the picker still requests a refresh when the index is stale.

The app warms up to 12 recent thumbnails. Opening a visible preview cancels this
speculative work. Thumbnail downloads share work for the same resource, while
different resources can load concurrently. Cached thumbnails use size buckets;
a larger cached thumbnail can satisfy a smaller request. Older cached thumbnails
remain available while a bounded background queue refreshes them.

Thumbnails use Coil's journaled disk cache with a 128 MiB limit and least recently
used eviction. Cache lookups use indexed keys instead of scanning the thumbnail
directory. The disk cache stays open once per directory, and readers acquire a
file descriptor before allowing an entry to be replaced or evicted.

Video playback previews open the video directly. When an image provider returns
an empty thumbnail or a thumbnail transfer fails, foreground requests generate
and cache a JPEG thumbnail from the original at the requested size. Background
warming skips these downloads. If thumbnail generation also fails, the foreground
request can still open the original directly. Remote providers may need to
download content before returning a descriptor.

## Photo compatibility

Google Contacts has a 10 MB image size limit. Enable **Serve compatible JPEG
copies** in the setup screen to convert PNG photos larger than 10 MB into JPEG
copies of at most 10 MB. The option is off by default and applies to all apps
using this provider, with any selected SAF folder. Original files stay unchanged.

Oversized PNGs are prepared by WorkManager in batches of up to four, newest first, and
appear progressively as their JPEG copies become ready. Folder scans read only
metadata and never wait for image downloads or encoding. Prepared byte counts are
retained separately, so reclaiming cached image files does not force a folder
scan to convert every photo again. Each batch saves its progress and schedules
the next batch; unavailable photos are retried with a delay. Continuations reuse
a persisted source snapshot instead of scanning SAF again. A newer source scan
supersedes older continuations. Index commits update only changed rows.

Converted photos and prepared originals are stored in the app's private cache.
Android may reclaim that cache; a later request downloads or prepares the file
again. JPEG conversion flattens transparency onto white and may reduce quality or
resolution to meet the limit.

The source app can also retain downloads in shared storage and make them visible
to Android's local photo library. A local original and a converted JPEG can then
appear separately in the system picker. The provider does not scan source files
into the gallery or modify the source app's downloads.

### Duplicate photos from source downloads

Keep the source app's downloads in private storage to prevent Android's local
photo library from listing originals alongside the provider's compatible copies.

In Nextcloud 35.0.2:

1. Open **Settings → Data storage location**.
2. Select **Internal storage** and turn off **Allow access from other apps**.
3. Confirm the change, wait for the storage migration to finish, then close and
   reopen the photo picker.

This setting selects Nextcloud's private download storage. Files remain available
through the SAF folder grant, and remote originals stay unchanged. It applies to
Nextcloud's downloaded files, not just the folder selected in this provider.

The setting is implemented by Nextcloud's [storage location dialog](https://github.com/nextcloud/android/blob/stable-35.0.2/app/src/main/java/com/nextcloud/ui/ChooseStorageLocationDialogFragment.kt)
and [private internal storage provider](https://github.com/nextcloud/android/blob/stable-35.0.2/app/src/main/java/com/owncloud/android/datastorage/providers/SystemDefaultStoragePointProvider.java).
Other SAF source apps need an equivalent private-download or gallery-visibility
setting. This provider cannot change another app's storage settings.

When a Nextcloud folder is selected, the setup screen shows these instructions
with an **Open Nextcloud** button when the source app can be launched. Nextcloud
does not expose this preference or export its settings activity, so the notice
cannot automatically disappear when the preference is off. It explains that no
action is needed if the setting is already off. The button opens Nextcloud;
follow the displayed steps to reach its storage settings.

## Developer notes

The app uses AndroidX Activity contracts, AppCompat views, Core extensions,
WorkManager, and SQLite. Database version 3 adds preparation checkpoints while
preserving the existing index. Cloud media provider and SAF integration
continue to use Android's framework contracts.

Developer and release details live in [docs/development.md](docs/development.md).
