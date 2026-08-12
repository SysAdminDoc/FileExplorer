# FileExplorer

![Version](https://img.shields.io/badge/version-1.5.0-blue)
![License](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Android%208.0+-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Status](https://img.shields.io/badge/status-active-success)

> Full-featured Android file manager with root access, archive support, network protocols, cloud storage, built-in editor, and app manager. OLED dark theme. Zero-config.


## Quick Start

```bash
git clone https://github.com/SysAdminDoc/FileExplorer.git
cd FileExplorer
```

1. Open in **Android Studio Ladybug** (2024.2.1+)
2. Sync Gradle
3. Run on device or emulator (API 26+)
4. Grant **All Files Access** when prompted

## Verification

Use the Android Studio JBR when the inherited `JAVA_HOME` is stale, and run Gradle serially on memory-constrained machines:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat --no-daemon --console=plain test
.\gradlew.bat --no-daemon --console=plain verifyAccessibilityLocalization
.\gradlew.bat --no-daemon --console=plain :core:ui:compileDebugAndroidTestKotlin
```

`verifyAccessibilityLocalization` checks localized resource keys and format placeholders, reports Android fallback keys, and rejects new inline user-facing strings in Kotlin UI sources. The Compose instrumentation contract covers file-row actions, descriptions, touch targets, RTL, and large-text density.

## Features

| Feature | Description | Status |
|---------|-------------|--------|
| File Browsing | NIO2 backend, breadcrumb nav, per-directory grid/list, sort, and column visibility | Complete |
| Dual-pane Browsing | Independent left/right folders with long-press drag-and-drop copy or move | Complete |
| Tabbed Browsing | Multiple tabs per pane with add, select, reorder, and swipe-to-close controls | Complete |
| Collections | MediaStore-backed Photos, Videos, Music, Documents, Downloads, and APK smart categories | Complete |
| Storage Analyzer | Recursive size treemap, SHA-256 duplicate groups, and largest-file list | Complete |
| Batch Rename | Regex capture groups, counter/date/parent tokens, collision checks, and live preview | Complete |
| Transfer Queue | Room-backed pausable and reorderable copy, move, and delete queue with process-death recovery, retry checkpoints, throttling, metadata conflict previews, persisted decisions, deterministic keep-both names, and text diff preview | Verified (local recovery) |
| DocumentsProvider | SAF access to the local storage root with browsing, search, recent files, document mutations, dynamic-root handling, and symlink-safe path validation | Verified (SAF contract) |
| USB OTG | UsbManager mass-storage detection, persistent SAF tree access, and DocumentFile read/write browsing | Requires configuration |
| Quick Share | Selection menu action backed by the Android Sharesheet for nearby-device delivery | Requires Quick Share or another compatible receiver |
| Media casting | Cast a selected local or SAF-backed photo, video, or audio file through Chromecast's Cast dialog | Requires Chromecast / Google Cast services |
| Localization | Shared Android string resources with English plus Spanish, Brazilian Portuguese, German, French, Japanese, Korean, Simplified Chinese, Russian, and Arabic locale variants | Core visible surfaces localized |
| Thumbnail cache | Configurable 32–1024 MB Coil disk-cache cap, internal or external app-cache location, and one-tap purge controls in Settings | Complete |
| Directory sizes | Optional coroutine-backed recursive folder sizes in list view with a bounded per-directory LRU cache | Complete |
| Saved searches | Named regex and path filters persisted in Room and pinned to the navigation drawer | Complete |
| Share Server | Authenticated HTTP web access and passive FTP sharing with Keystore-encrypted credentials, loopback default, explicit plaintext-LAN opt-in, and bounded client/upload/request resources | Complete |
| File Encryption | AES-256-GCM encryption with an Android Keystore key and biometric-gated decryption from the browser | Complete |
| Shizuku Android/data and obb | Optional UserService backend for scoped browsing and file operations under Android/data and Android/obb | Optional |
| Large-screen layout | Adaptive places, file list, and preview panes with keyboard shortcuts and mouse context menus | Complete |
| File Operations | Copy, move, trash, restore, permanent delete, rename, create. Foreground service with progress notification | Complete |
| Trash Bin | `.FileExplorer-Trash/` per storage volume, 30-day default purge, configurable TTL, restore and empty-trash screen | Complete |
| Search | Streaming results via Coroutine Flow, regex support, search history | Complete |
| Bookmarks | Bookmark any directory, persisted in Room DB, accessible from drawer | Complete |
| Recent Files | Track opened files, quick access from drawer | Complete |
| Recent Locations | Automatically track recently visited directories in recency order | Complete |
| Root Access | libsu 6.0.0, browse /data /system /vendor, SELinux context, chmod/chown, remount | Complete |
| Root Module Browser | List Magisk, KernelSU, and APatch modules, toggle disable markers, and install trusted ZIPs through the detected manager | Complete |
| Archives | Browse ZIP/7z/TAR/RAR as virtual folders. RAR extraction is read-only; ZIP supports AES-256 passwords | Complete |
| SMB/CIFS | Windows network shares via smbj 0.13.0 with domain auth and SMB3 server-side copy fallback | Complete |
| SFTP | SSH file transfer via sshj 0.40.0 with known_hosts verification, password + private key auth | Complete |
| FTP/FTPS | File transfer via Apache Commons Net 3.13.0 with TLS toggle | Complete |
| WebDAV | HTTP/HTTPS via sardine-android 0.9 with server-side copy/move | Complete |
| Connection Manager | Save, edit, test network connections with Android Keystore-encrypted passwords. Remote file browser | Complete |
| Provider Contracts | Shared capabilities and typed unsupported/auth/permission/transport/conflict/corrupt errors across local, root, USB, plugin, network, and cloud adapters, with bounded redacted diagnostics | Complete |
| Plugin Trust | Explicit certificate-bound approval and revocation, declared-capability checks, bounded isolated IPC, and path-free audit events | Verified (local trust boundary) |
| Google Drive | REST API v3. Browse, upload, download, delete, rename, quota display | Requires configuration |
| Dropbox | HTTP API v2. Browse, upload, download, folder operations | Requires configuration |
| OneDrive | Microsoft Graph API. Full file operations, quota tracking | Requires configuration |
| Biometric Lock | Fingerprint/face/device credential via AndroidX Biometric | Complete |
| Encrypted Vault | Opaque-ID AES-256-GCM storage with an encrypted index, Android Keystore key, and biometric/device-credential gate | Verified (local files) |
| Secure Delete | DoD 5220.22-M 3-pass overwrite before deletion | Complete |
| Checksum Verify | MD5, SHA-1, SHA-256, SHA-512 via java.security.MessageDigest | Complete |
| Integrity Watch | Room-backed SHA-256 watches for files and directory trees, with periodic drift notifications | Complete |
| File Tags | Room-backed tags with multi-tag AND search and browser assignment | Complete |
| Encrypted Volumes | Root-only mount and unmount workflow for existing gocryptfs or EncFS volumes | Requires configuration |
| Text Editor | Built-in editor with syntax highlighting, line numbers, find/replace, undo/redo | Complete |
| App Manager | List all apps, filter user/system/disabled, search, sort, share APK, uninstall | Complete |
| APK Analyzer | Manifest, permissions, signing certificate SHA-256, shared UID, DEX method count, and ZIP directory size breakdown | Complete |
| PDF / Document Preview | Native paginated PDF rendering plus bounded DOCX, XLSX worksheet, and EPUB chapter previews | Complete |
| Hex Editor | Paginated hexadecimal view with byte editing up to 64 MiB and read-only larger-file inspection | Complete |
| Gesture Actions | Configurable swipe-left/right actions for delete, share, compress, and cut-to-move | Complete |
| Automation Intents | Exported Tasker / Automate actions for copy, move, archive creation, and network upload | Complete |

## Language support

The app follows the Android system language. English is the fallback, with bundled
resources for Spanish, Brazilian Portuguese, German, French, Japanese, Korean,
Simplified Chinese, Russian, and Arabic. Android handles right-to-left layout for
Arabic through the existing RTL manifest support.

## Automation intents

Tasker, Automate, and other automation tools can send broadcasts to the exported
`AutomationReceiver`. Every request accepts `source` (one path) or `sources` (a
string array), plus the `destination` extra. Use these action names:

| Action | Additional extras |
|--------|-------------------|
| `com.explorer.fileexplorer.action.COPY` | Optional `conflict`: `rename` (default), `overwrite`, `skip`, or `keep-both`; optional `idempotency_key` |
| `com.explorer.fileexplorer.action.MOVE` | Optional `conflict`: `rename` (default), `overwrite`, `skip`, or `keep-both`; optional `idempotency_key` |
| `com.explorer.fileexplorer.action.ZIP` | Optional `format`: `zip` (default), `7z`, or `tar.gz` |
| `com.explorer.fileexplorer.action.UPLOAD` | Required positive `connection_id` for a saved Network connection; `destination` is the remote path |

Actions return `RESULT_OK` when the foreground transfer is accepted. Add a
unique `request_id` to receive a completion broadcast with action
`com.explorer.fileexplorer.action.TRANSFER_RESULT`; its `status` is
`completed`, `failed`, or `cancelled`, and failures include `error`.
`idempotency_key` is used to make repeated deliveries resolve to the same
keep-both artifact; when omitted, copy and move requests derive a stable key
from their operation, sources, and destination. The queue shows source and
destination size/modified-time metadata before mutation, persists each
per-source choice, and records cancellation as an explicit result.
Upload sources must be local files. A saved network connection is connected
automatically for the action and disconnected afterward if it was not already active.

## Architecture

```
                           ┌─────────────────────────────────────────────┐
                           │                  :app                       │
                           │  Application, Navigation (11 routes),       │
                           │  Permission flow, MainActivity              │
                           └──────┬──────┬──────┬──────┬──────┬─────────┘
                                  │      │      │      │      │
              ┌───────────────────┼──────┼──────┼──────┼──────┼───────────────┐
              │                   │      │      │      │      │               │
     ┌────────▼──┐  ┌─────▼──┐ ┌─▼────┐ │ ┌────▼──┐ ┌▼─────┐│  ┌──────┐ ┌──▼────┐
     │ :feature: │  │:feature│ │:feat: │ │ │:feat: │ │:feat:││  │:feat:│ │:feat: │
     │  browser  │  │:search │ │transf │ │ │:netw  │ │cloud ││  │:edit │ │:apps  │
     │           │  │        │ │       │ │ │       │ │      ││  │      │ │       │
     └──┬──┬─────┘  └───┬────┘ └──┬────┘ │ └──┬────┘ └──┬───┘│  └──┬───┘ └──┬────┘
        │  │             │        │      │    │         │    │     │        │
     ┌──▼──▼─────────────▼────────▼──────▼────▼─────────▼────▼─────▼────────▼──┐
     │                        Core Layer                                        │
     │  :core:data        FileRepositoryFactory → Local / Root / Archive        │
     │                    UsbFileRepository + EncryptedVolumeManager          │
     │  :core:plugin      Versioned AIDL plugin discovery and URI repository     │
     │  :core:storage     PermissionHelper, StorageVolumeHelper, RootHelper     │
     │  :core:network     SMB / SFTP / FTP / WebDAV + ConnectionManager         │
     │  :core:cloud       Google Drive / Dropbox / OneDrive + AccountManager    │
     │  :core:database    Room (bookmarks, recents, history, connections, views, integrity, tags) │
     │  :core:model       FileItem, SortOrder, TransferTask, ClipboardContent   │
     │  :core:ui          FileIcon, BreadcrumbBar, FileListItem                 │
     │  :core:designsystem  OLED dark theme, Material 3 colors                 │
     └─────────────────────────────────────────────────────────────────────────┘
```

19 Gradle modules. MVVM + Clean Architecture. `FileRepositoryFactory` routes file operations to the correct backend based on path type, device capabilities, and installed plugin URI schemes.

## Technology Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Kotlin | 2.2 |
| UI | Jetpack Compose + Material 3 | BOM 2026.06 |
| DI | Hilt | 2.58 |
| Async | Kotlin Coroutines + Flow | 1.9.0 |
| Persistence | Room + DataStore | 2.8.4 |
| File I/O | java.nio.file (NIO2) | JDK 17 |
| Root | libsu | 6.0.0 |
| Archives | Apache Commons Compress + zip4j + Junrar | 1.28.0 / 2.11.6 / 7.6.0 |
| SMB | smbj | 0.13.0 |
| SFTP | sshj + BouncyCastle | 0.40.0 / 1.84 |
| FTP | Apache Commons Net | 3.13.0 |
| WebDAV | sardine-android | 0.9 |
| HTTP | OkHttp | 4.12.0 |
| Security | AndroidX Biometric + Security-Crypto | 1.2.0 / 1.1.0 |
| Images | Coil | 3.3.0 |
| Navigation | Jetpack Navigation Compose | 2.8.5 |

## Plugin / Add-on API

Third-party providers can ship as separate APKs without running plugin code inside FileExplorer. The `:core:plugin` library defines protocol version 1, manifest metadata, and the `IFileExplorerPlugin` AIDL service. Filesystem plugins advertise URI schemes such as `sftp2` and are routed through `FileRepositoryFactory`; archive and tool plugins can use the same operation envelope without claiming a URI scheme.

A plugin declares one exported service and the protocol metadata in its manifest:

```xml
<service
    android:name=".FileExplorerPluginService"
    android:exported="true"
    android:process=":plugin">
    <intent-filter>
        <action android:name="com.explorer.fileexplorer.action.PLUGIN" />
    </intent-filter>
    <meta-data android:name="com.explorer.fileexplorer.plugin.PROTOCOL_VERSION" android:value="1" />
    <meta-data android:name="com.explorer.fileexplorer.plugin.ID" android:value="com.example.sftp" />
    <meta-data android:name="com.explorer.fileexplorer.plugin.DISPLAY_NAME" android:value="SFTP add-on" />
    <meta-data android:name="com.explorer.fileexplorer.plugin.VERSION_NAME" android:value="1.0.0" />
    <meta-data android:name="com.explorer.fileexplorer.plugin.SCHEMES" android:value="sftp2" />
    <meta-data android:name="com.explorer.fileexplorer.plugin.CAPABILITIES" android:value="filesystem" />
</service>
```

The host validates the metadata, binds only to the declared component, and keeps each discovered plugin untrusted until it is approved in Settings. Approval is tied to the exact service component and SHA-256 signing-certificate digest, can be revoked, and grants only the declared capabilities. The host negotiates protocol version 1, rejects unknown operations, checks every requested path through the plugin, and sends `list`, `info`, `exists`, `copy`, `move`, `delete`, `create_directory`, `create_file`, `rename`, `size`, `search`, and `checksum` requests as `Bundle` messages. Calls are limited to four concurrent requests, 15 seconds, 256 KiB requests, and 512 KiB responses; binder death and protocol/resource failures are isolated as unavailable provider errors. Each response must set `ok=true`; file entries use `PluginFileCodec` bundles. The binder boundary keeps failures and plugin dependencies out of the host process.

## Cloud Setup

Cloud providers are optional. The Cloud Storage screen reports `Requires OAuth configuration`, `Unavailable in this build`, `Ready for sign-in`, or `Signed in` and keeps the connect action disabled until a real provider auth intent is available. The default build does not contain production OAuth credentials or client secrets; credentials must remain external to the repository.

**Google Drive:** For a configured build, create an OAuth 2.0 client in [Google Cloud Console](https://console.cloud.google.com/), add your app's package name and SHA-1 fingerprint, and provide the client configuration through the approved external secret/build mechanism.

**Dropbox:** For a configured build, register an app at [Dropbox App Console](https://www.dropbox.com/developers/apps) and provide the App Key through the approved external secret/build mechanism.

**OneDrive:** For a configured build, register an app in [Azure AD Portal](https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps), add `Files.ReadWrite.All offline_access`, and provide the client configuration through the approved external secret/build mechanism.

## Configuration

| Setting | Location | Description |
|---------|----------|-------------|
| Show hidden files | Settings / Drawer | Toggle visibility of dotfiles |
| Folders first | Settings | Pin folders above files in listings |
| Directory view | Browser / More | List or Grid plus per-directory size, modified, and type columns |
| Sort order | Browser / top bar | Per-directory name, size, date, or type with ascending/descending direction |
| Trash auto-purge | Settings | Retain trash for 7, 14, 30, 60, or 90 days |
| Root mode | Drawer toggle | Enable/disable root shell access |
| App lock | Security screen | Biometric requirement on launch |
| Secure delete | Security screen | 3-pass overwrite before deletion |
| Vault | Browser selection → More → Add to Vault; Security screen | Authenticate, move local regular files into opaque encrypted storage, inspect entries only while unlocked, restore to Downloads, or delete atomically |
| File encryption | Browser selection → More | Encrypt files to `.encrypted`; biometric authentication is required to decrypt |
| Shizuku access | Shizuku Access screen / drawer | Optional Android/data and Android/obb backend; requires a separately started Shizuku or Sui service and granted permission |
| Large-screen layout | Automatic on windows at least 840dp wide | Three-pane places, files, and preview workspace with Ctrl+A, Delete, F5, Escape, and Up shortcuts |
| Share server | Share Server screen / Quick Settings | Authenticated HTTP or FTP access to a selected local folder |
| Integrity watch | Security screen or Browser selection → More → Watch for changes | Recompute watched SHA-256 fingerprints every 15 minutes and notify on drift |
| File tags | Browser selection → More → Set tags; drawer → Tags; Search → tag chips | Apply normalized tags and combine multiple tags with filename or regex search |
| USB OTG | Drawer → USB OTG → Choose USB folder | Connect a mass-storage device, choose its SAF folder, then browse and mutate it with persisted access |
| Quick Share | Select files → More → Send with Quick Share | Opens the Android Sharesheet with read grants so Quick Share can send one or many files |
| Media casting | Top-bar Cast button → select receiver; select one media file → More → Cast media | Streams the selected local/SAF item over a temporary LAN URL and loads it in the Cast receiver |
| Encrypted volumes | Security → Encrypted volumes | Requires root, an installed gocryptfs or EncFS binary, FUSE support, and existing cipher directories |

## Theme

Theme mode is selectable in Settings: System, Light, Dark, OLED / True Black, or Material You on Android 12+.

| Element | Color |
|---------|-------|
| Background | `#0D0D0D` |
| Surface | `#161616` |
| Primary accent | `#00BCD4` (Cyan) |
| Root indicators | `#FF9800` (Orange) |
| Error | `#CF6679` |

OLED remains available as the high-contrast AMOLED option, while System follows the device theme.

## Requirements

- Android Studio Ladybug (2024.2.1+)
- Android SDK 36, JDK 17
- Device or emulator running Android 8.0+ (API 26)
- Rooted device for root features (optional)
- Shizuku or Sui for optional Android/data and Android/obb access (optional)

## Release and upgrade gates

The build uses Google Maven, Maven Central, and a JitPack allowlist for the two
GitHub-hosted groups that still require it. Fixed versions live in
`gradle/libs.versions.toml`.

Run the local policy checks before a release:

```powershell
.\gradlew.bat verifyAndroidUpgradeReadiness verifyDependencyProvenance
.\gradlew.bat scanDependencyAdvisories
```

The first command checks Android 15/16 SDK, manifest, backup, storage, predictive-back,
and foreground-service timeout requirements. The advisory task resolves the release
runtime graph and queries OSV; use `-PosvAllowlist=OSV-ID,...` only for an explicitly
triaged release decision.

## Remote capability semantics

Remote repositories expose operation support and the expected consistency/cost to the
browser. Recursive inspection is cancellable and bounded to 100,000 entries and depth
64; search returns at most 10,000 matches, and streamed checksums inspect at most 2 GiB.

| Provider | Copy/move | Recursive size | Search | Checksum | Consistency and cost |
|---|---|---|---|---|---|
| SMB | Server-side copy with streamed fallback | Bounded, cancellable | Bounded, cancellable | Streamed, bounded | Mutations are strong; inspection is point-in-time/high cost |
| SFTP | Staged stream and verified rename | Bounded, cancellable | Bounded, cancellable | Streamed, bounded | Mutations are strong; inspection is point-in-time/high cost |
| FTP/FTPS | Copy is explicit unsupported; move uses verified rename | Bounded, cancellable | Bounded, cancellable | Streamed, bounded | Mutations are strong; inspection is point-in-time/high cost |
| WebDAV | Server-side copy/move when accepted | Bounded, cancellable | Bounded, cancellable | Streamed, bounded | Mutations are strong; inspection is point-in-time/high cost |

Unsupported operations return typed provider errors. Network transfers and mutating
operations report progress and verify completion rather than presenting an empty or
zero-valued success as a completed operation.

Local multi-file copies, moves, deletes, archive extraction, and archive creation use
an atomic manifest under `.fileexplorer-staging/`. The manifest records planned,
staged, committed, and backup entries; interrupted journals are rolled back or
cleaned before the next operation. The transfer queue persists its intended and
committed entries in Room, so cancellation and process death show partial completion
instead of silently retrying an unknown filesystem state.

When a queued conflict is resolved with **Keep both**, the target name includes a
stable `copy-<hash>` suffix derived from the task idempotency key and source/target
paths. A retry first compares the existing deterministic artifact with the source
(checksum when the provider supports it, otherwise type and size) and records it as
committed without invoking the provider again. If an unrelated artifact already has
that name, providers fall back to a numbered variant.

## Permissions

The following matrix is the release-readiness record for every privileged permission
and exported or system-facing component. “Owner review” means the build is ready for
an operator to make the distribution decision; this repository does not submit a
store listing or request restricted-policy approval.

| Permission or component | Purpose | API range | Denial or fallback | User explanation | Backup implication | Distribution status |
|---|---|---|---|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Browse and mutate shared storage, including folders outside the app sandbox | Android 11+ (API 30+) | App opens Android’s All Files Access settings; limited mode opens app-scoped external files | “Needed to browse and organize shared storage.” | Permission state is not backed up; excluded data remains excluded | Restricted permission; owner review required before Play submission |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | Legacy shared-storage access for older devices | Read API 26–32; write API 26–29 | App-scoped storage and the system picker remain available | “Allows file access on older Android versions.” | Permission state is not backed up | Legacy compatibility only; no new runtime request on modern Android |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` | Granular media fallback for Android 13+ integrations | Android 13+ (API 33+) | Media actions use app-scoped files or a system picker when not granted | “Allow only the media types you want File Explorer to open.” | Permission state is not backed up; media content is never copied by backup rules | Runtime, optional, least-privilege fallback |
| `QUERY_ALL_PACKAGES` | Complete App Manager inventory for search, sort, APK analysis, and sharing | Android 11+ package visibility | App Manager falls back to launcher-visible packages through a declared `<queries>` intent | “Used only by App Manager to show installed applications.” | Installed-package metadata and APKs are not included in app backup rules | Restricted permission; owner review required before Play submission |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Remote repositories, cloud status, and local share-server connectivity checks | All supported API levels | Offline locations remain usable; network failures are surfaced as provider errors | “Network access is used only when a remote feature is selected.” | Credentials and network state are excluded from cloud backup | Normal permissions; release-ready |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Long-running transfers and the local share server | All supported API levels; Android 14+ type declaration | Work can be cancelled; Android 15 timeout calls stop the service cleanly | “Keeps an active transfer or share server visible while it runs.” | Queue metadata follows app backup policy; credentials remain excluded | Normal/type permissions; release-ready with timeout gate |
| `POST_NOTIFICATIONS` | Transfer progress, completion, and share-server status notifications | Android 13+ (API 33+) | Operations continue without notifications and remain visible in the app | “Allow notifications to see transfer progress.” | Permission state is not backed up | Runtime, optional; release-ready |
| `MainActivity` (`exported=true`, launcher) | Normal launcher entry point | All supported API levels | None; the activity validates storage state before opening the browser | “Start File Explorer.” | No activity state or permission grant is backed up | Required launcher surface; release-ready |
| `TransferService` / `ShareServerService` (`exported=false`) | Internal transfer and share-server workers | All supported API levels | Explicit start failures are reported; services stop on cancellation and timeout | Described by the active transfer/share action | Service state is not restored from backup | Internal-only; release-ready |
| `ShareServerTileService` (`exported=true`, `BIND_QUICK_SETTINGS_TILE`) | Optional Quick Settings control for the local share server | Android 7+ (API 24+) | Share server remains controllable from the app | “Quick Settings can start or stop local sharing.” | Share-server settings and credentials are excluded | System-bound export; release-ready |
| `FileProvider` (`exported=false`, URI grants) | Temporary APK/file sharing through Android’s chooser | All supported API levels | Share action reports a recoverable error; no public provider access | “The selected file is shared through a temporary Android URI.” | Shared files are not backup data | Internal provider; release-ready |
| `FileDocumentsProvider` (`exported=true`, `MANAGE_DOCUMENTS`, URI grants) | Android Storage Access Framework browsing, creation, and editing | Android 4.4+ (API 19+; app min API 26) | Contract errors are returned for inaccessible, removed, or invalid roots | “Android’s file picker can use File Explorer as a document source.” | Provider roots and grants are not restored by cloud backup | Required SAF integration; release-ready; external submission remains owner-gated |
| Application backup rules (`allowBackup`, `backup_rules.xml`, `data_extraction_rules.xml`) | Preserve safe portable settings while excluding secrets and private vault data | Android 6+ cloud backup; Android 12+ data extraction rules | Restore omits database, security preferences, credentials, and vault files | “Safe settings can be backed up; secrets and vault data stay on-device.” | Room database, security preferences, and `.vault/` are explicitly excluded | Release gate required; policy is checked in |

Backup rules exclude the Room database (contains Keystore-encrypted network credentials), security preferences, and vault files from Android cloud backup.

## FAQ

**Q: Why does it need All Files Access?**
A: Android requires `MANAGE_EXTERNAL_STORAGE` to browse outside app-specific directories. If it is denied, File Explorer stays usable in limited mode and opens its app-scoped storage instead of crashing.

**Q: Is root required?**
A: No. Root features are optional and auto-detected. The app works as a standard file manager without root.

**Q: Which archive formats are supported?**
A: ZIP (with AES-256 encryption), 7z, TAR, GZ, BZ2, XZ, Zstandard, and read-only RAR. Archives can be browsed as virtual folders without extracting.

**Q: Are my cloud credentials stored securely?**
A: Network passwords are encrypted before database storage with Android Keystore-backed AES-GCM. Cloud OAuth tokens stay in memory unless you choose Stay signed in, which stores account tokens in Keystore-encrypted app preferences.

## Contributing

Issues and PRs welcome. This project follows the "maximum feature density" philosophy — if it belongs in a file manager, it should be here.

## License

[MIT](LICENSE)
