# FileExplorer capabilities

This guide separates features that work in the normal app from features that need another service, device capability, server, or configured build.

## Browsing and organization

| Capability | Availability | Notes |
|---|---|---|
| Local file browser | Ready | List and grid views, breadcrumbs, sorting, hidden files, column choices, and optional directory sizes |
| Tabs and dual-pane mode | Ready | Each pane keeps its own folder, selection, sort order, and tabs |
| Bookmarks and recent locations | Ready | Stored locally in Room |
| Tags and saved searches | Ready | Multiple tags can combine with filename or regular-expression search |
| Smart collections | Ready | Photos, videos, music, documents, downloads, and APKs use Android media indexes |
| USB OTG | Needs a connected drive | Android's folder picker grants persistent access to the selected USB tree |
| Root browser | Needs root | Supports protected paths, permission changes, SELinux context details, remount, and root module inspection |
| Android/data and Android/obb | Needs Shizuku or Sui | The separately started service must grant FileExplorer access |

## File work

| Capability | Availability | Notes |
|---|---|---|
| Copy, move, rename, create, Trash, restore | Ready | Controls adapt to the active provider's supported operations |
| Persistent transfer queue | Ready | Supports pause, resume, reorder, cancellation, throttling, and process-death recovery |
| Conflict handling | Ready | Shows metadata first, remembers each choice, and can create deterministic keep-both names |
| Batch rename | Ready | Counter, date, parent, and regular-expression capture tokens include a collision preview |
| Archive browser | Ready | ZIP, 7z, TAR, and RAR can open as virtual folders. RAR is read-only |
| Archive creation and extraction | Ready for local files | Extraction enforces path, entry-count, depth, and resource limits before committing output |
| Text editor | Ready | Line numbers, syntax highlighting, find and replace, undo, and redo |
| Document preview | Ready | Native PDF pages plus bounded DOCX, XLSX worksheet, and EPUB chapter previews |
| Hex editor | Ready | Editing is limited to files up to 64 MiB. Larger files remain available for inspection |
| Share and cast | Depends on receiver | Uses Android Sharesheet or a compatible Google Cast receiver |

## Analysis and security

| Capability | Availability | Notes |
|---|---|---|
| Storage Analyzer | Ready for local storage | Bounded scans, treemap drill-down, largest files, resumable metadata, and duplicate review before Trash |
| Checksums | Provider dependent | MD5, SHA-1, SHA-256, and SHA-512 appear when the current provider supports reading the file |
| Integrity Watch | Ready for local files | Stores SHA-256 fingerprints and reports changed or missing watched paths |
| Encrypted vault | Ready for local files | AES-256-GCM payloads, opaque names, authenticated index, and Android Keystore-backed access |
| File encryption | Ready for local files | Browser actions encrypt to `.encrypted`; decryption requires authentication |
| Secure delete | Best effort for supported local files | The app explains flash, snapshot, and remote-provider limitations before deletion |
| Encrypted volumes | Needs root and compatible binaries | Mounts existing gocryptfs or EncFS volumes when FUSE support is available |
| App Manager | Ready | User, system, and disabled filters with APK sharing, uninstall, and package details |
| APK Analyzer | Ready | Manifest, permissions, signing certificate SHA-256, shared UID, DEX method count, and archive breakdown |

## Network and cloud

| Provider | Availability | Notes |
|---|---|---|
| SMB | Ready with a server | Domain authentication, SMB3 support, and server-side copy with a streamed fallback |
| SFTP | Ready with a server | Password or private-key authentication, known-host verification, staged transfers, and verified rename |
| FTP/FTPS | Ready with a server | TLS is optional. Copy is reported as unsupported when the server cannot provide it |
| WebDAV | Ready with a server | HTTP or HTTPS with server-side copy and move when accepted |
| Local HTTP or FTP share server | Ready | Authenticated access, loopback default, bounded clients and uploads, and explicit plaintext LAN consent |
| Google Drive | Configured builds only | Uses Drive API v3 when a valid external OAuth client is supplied |
| Dropbox | Configured builds only | Uses Dropbox API v2 when an external app key is supplied |
| OneDrive | Configured builds only | Uses Microsoft Graph when an external application registration is supplied |

Remote recursive inspection stops at 100,000 entries and depth 64. Search returns at most 10,000 matches. Streamed checksums inspect files up to 2 GiB. Unsupported operations return a visible provider error instead of an empty result.

## Integrations

- Android's Storage Access Framework can browse, search, create, edit, and remove documents through FileExplorer's exported DocumentsProvider.
- Tasker, Automate, and similar tools can request copy, move, archive creation, or saved-network upload operations.
- Third-party APKs can add providers or tools through the versioned plugin contract. Plugins require explicit certificate-bound approval.
- Large windows use a three-pane places, files, and preview layout with mouse support.

See [DEVELOPMENT.md](DEVELOPMENT.md) for integration details and [SECURITY.md](../SECURITY.md) for permission and trust boundaries.
