# FileExplorer

![Version](https://img.shields.io/badge/version-1.4.0-blue)
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

## Features

| Feature | Description | Status |
|---------|-------------|--------|
| File Browsing | NIO2 backend, breadcrumb nav, grid/list, sort by name/size/date/type | Complete |
| Dual-pane Browsing | Independent left/right folders with long-press drag-and-drop copy or move | Complete |
| Tabbed Browsing | Multiple tabs per pane with add, select, reorder, and swipe-to-close controls | Complete |
| Storage Analyzer | Recursive size treemap, SHA-256 duplicate groups, and largest-file list | Complete |
| Batch Rename | Regex capture groups, counter/date/parent tokens, collision checks, and live preview | Complete |
| Transfer Queue | Pausable and reorderable copy, move, and delete queue with throttling, conflict choices, and text diff preview | Complete |
| DocumentsProvider | SAF access to the local storage root with browsing, search, recent files, and document mutations | Complete |
| Share Server | Authenticated HTTP web access and passive FTP sharing with foreground service and Quick Settings start/stop | Complete |
| File Operations | Copy, move, trash, restore, permanent delete, rename, create. Foreground service with progress notification | Complete |
| Trash Bin | `.FileExplorer-Trash/` per storage volume, 30-day default purge, configurable TTL, restore and empty-trash screen | Complete |
| Search | Streaming results via Coroutine Flow, regex support, search history | Complete |
| Bookmarks | Bookmark any directory, persisted in Room DB, accessible from drawer | Complete |
| Recent Files | Track opened files, quick access from drawer | Complete |
| Root Access | libsu 6.0.0, browse /data /system /vendor, SELinux context, chmod/chown, remount | Complete |
| Archives | Browse ZIP/7z/TAR as virtual folders. Extract/create with AES-256 passwords | Complete |
| SMB/CIFS | Windows network shares via smbj 0.13.0 with domain auth | Complete |
| SFTP | SSH file transfer via sshj 0.40.0 with known_hosts verification, password + private key auth | Complete |
| FTP/FTPS | File transfer via Apache Commons Net 3.13.0 with TLS toggle | Complete |
| WebDAV | HTTP/HTTPS via sardine-android 0.9 with server-side copy/move | Complete |
| Connection Manager | Save, edit, test network connections with Android Keystore-encrypted passwords. Remote file browser | Complete |
| Google Drive | REST API v3. Browse, upload, download, delete, rename, quota display | Requires configuration |
| Dropbox | HTTP API v2. Browse, upload, download, folder operations | Requires configuration |
| OneDrive | Microsoft Graph API. Full file operations, quota tracking | Requires configuration |
| Biometric Lock | Fingerprint/face/device credential via AndroidX Biometric | Complete |
| Encrypted Vault | AES-256-GCM encrypted storage with Android Keystore-backed keys | Complete |
| Secure Delete | DoD 5220.22-M 3-pass overwrite before deletion | Complete |
| Checksum Verify | MD5, SHA-1, SHA-256, SHA-512 via java.security.MessageDigest | Complete |
| Text Editor | Built-in editor with syntax highlighting, line numbers, find/replace, undo/redo | Complete |
| App Manager | List all apps, filter user/system/disabled, search, sort, share APK, uninstall | Complete |

## Architecture

```
                           ┌─────────────────────────────────────────────┐
                           │                  :app                       │
                           │  Application, Navigation (8 routes),        │
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
     │  :core:storage     PermissionHelper, StorageVolumeHelper, RootHelper     │
     │  :core:network     SMB / SFTP / FTP / WebDAV + ConnectionManager         │
     │  :core:cloud       Google Drive / Dropbox / OneDrive + AccountManager    │
     │  :core:database    Room (bookmarks, recents, history, connections)        │
     │  :core:model       FileItem, SortOrder, TransferTask, ClipboardContent   │
     │  :core:ui          FileIcon, BreadcrumbBar, FileListItem                 │
     │  :core:designsystem  OLED dark theme, Material 3 colors                 │
     └─────────────────────────────────────────────────────────────────────────┘
```

18 Gradle modules. MVVM + Clean Architecture. `FileRepositoryFactory` routes file operations to the correct backend based on path type and device capabilities.

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
| Archives | Apache Commons Compress + zip4j | 1.28.0 / 2.11.6 |
| SMB | smbj | 0.13.0 |
| SFTP | sshj + BouncyCastle | 0.40.0 / 1.84 |
| FTP | Apache Commons Net | 3.13.0 |
| WebDAV | sardine-android | 0.9 |
| HTTP | OkHttp | 4.12.0 |
| Security | AndroidX Biometric + Security-Crypto | 1.2.0 / 1.1.0 |
| Images | Coil | 3.3.0 |
| Navigation | Jetpack Navigation Compose | 2.8.5 |

## Cloud Setup

Cloud providers require OAuth configuration. Each is optional and the app works without them.

**Google Drive:** Create OAuth 2.0 client in [Google Cloud Console](https://console.cloud.google.com/), add your app's package name and SHA-1 fingerprint.

**Dropbox:** Register an app at [Dropbox App Console](https://www.dropbox.com/developers/apps), copy the App Key.

**OneDrive:** Register an app in [Azure AD Portal](https://portal.azure.com/#blade/Microsoft_AAD_RegisteredApps), add `Files.ReadWrite.All offline_access` scope.

## Configuration

| Setting | Location | Description |
|---------|----------|-------------|
| Show hidden files | Settings / Drawer | Toggle visibility of dotfiles |
| Folders first | Settings | Pin folders above files in listings |
| Default view | Settings | List or Grid |
| Sort order | Settings / Top bar | Name, size, date, type |
| Trash auto-purge | Settings | Retain trash for 7, 14, 30, 60, or 90 days |
| Root mode | Drawer toggle | Enable/disable root shell access |
| App lock | Security screen | Biometric requirement on launch |
| Secure delete | Security screen | 3-pass overwrite before deletion |
| Vault | Security screen | Protected private storage area |
| Share server | Share Server screen / Quick Settings | Authenticated HTTP or FTP access to a selected local folder |

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

## Permissions

| Permission | Why |
|-----------|-----|
| `MANAGE_EXTERNAL_STORAGE` | Browse files outside app-private directories — core function of a file manager |
| `QUERY_ALL_PACKAGES` | App Manager: list, search, sort, and share APKs for all installed apps |
| `INTERNET` + `ACCESS_NETWORK_STATE` | SMB/SFTP/FTP/WebDAV and cloud provider connectivity |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Long-running file operations and local share server notification |
| `POST_NOTIFICATIONS` | Transfer progress and completion notifications |
| `READ_MEDIA_*` | Android 13+ granular media access |

Backup rules exclude the Room database (contains Keystore-encrypted network credentials), security preferences, and vault files from Android cloud backup.

## FAQ

**Q: Why does it need All Files Access?**
A: Android requires `MANAGE_EXTERNAL_STORAGE` to browse outside app-specific directories. Without it, you can only see your own app's files.

**Q: Is root required?**
A: No. Root features are optional and auto-detected. The app works as a standard file manager without root.

**Q: Which archive formats are supported?**
A: ZIP (with AES-256 encryption), 7z, TAR, GZ, BZ2, XZ, and Zstandard. Archives can be browsed as virtual folders without extracting.

**Q: Are my cloud credentials stored securely?**
A: Network passwords are encrypted before database storage with Android Keystore-backed AES-GCM. Cloud OAuth tokens stay in memory unless you choose Stay signed in, which stores account tokens in Keystore-encrypted app preferences.

## Contributing

Issues and PRs welcome. This project follows the "maximum feature density" philosophy — if it belongs in a file manager, it should be here.

## License

[MIT](LICENSE)
