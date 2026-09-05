# Security and privacy

FileExplorer works with data that deserves plain, specific handling. This page explains what the app protects, what Android still controls, and where a guarantee would be misleading.

## Privacy

The app has no advertising SDK and no behavioral analytics SDK. Local file browsing does not require an account.

FileExplorer uses the network only when a selected feature needs it. Examples include opening a saved remote connection, signing in to a configured cloud provider, casting media, running the local share server, or executing the developer advisory scan.

## Secrets and encrypted data

- Saved network passwords are encrypted with keys held by Android Keystore before Room stores them.
- The local vault uses AES-256-GCM payload encryption, opaque payload names, and an authenticated encrypted index.
- File decryption and vault access can require biometric or device-credential authentication.
- Cloud tokens and network credentials are excluded from Android cloud backup.
- Vault payloads, security preferences, and the Room database are also excluded from backup.

The repository does not contain release keystores, network credentials, or production OAuth client secrets.

## Permission rationale

| Permission or surface | Why it exists | What happens without it |
|---|---|---|
| All Files Access | Gives a traditional file manager access to shared storage on Android 11+ | FileExplorer opens limited app storage and still supports Android's system picker |
| Legacy storage permissions | Keeps shared-storage access working on Android 8 through Android 10 | Modern Android uses the current storage path instead |
| Granular media permissions | Supports media actions on Android 13+ | App-scoped files and picker-granted files remain available |
| Installed package visibility | Lets App Manager inventory, search, and inspect installed apps | The list falls back to launcher-visible packages |
| Network access | Supports remote storage, configured cloud providers, casting, and local sharing | Local storage stays available and remote failures are shown as errors |
| Foreground services and notifications | Keeps active transfers and sharing visible and cancellable | Work remains visible inside the app if notifications are declined |
| DocumentsProvider | Lets Android's file picker use FileExplorer as a source | The main FileExplorer browser still works |

## Important boundaries

### Secure deletion

Overwriting a local file is best effort. Flash translation layers, snapshots, journaling, and copy-on-write storage can retain blocks outside an app's control. Remote providers may not expose any overwrite primitive. The interface reports these limits and does not claim forensic erasure.

### Root and Shizuku

Root and Shizuku expand what the app can reach. They also expand the impact of a mistake or a compromised device. Both paths are optional and remain disabled unless the user configures them.

### Remote servers

SMB, SFTP, FTP/FTPS, and WebDAV inherit the security properties of the chosen protocol and server. Prefer encrypted transport. SFTP uses host verification. FTP without TLS sends credentials and content without transport encryption.

### Plugins

Plugins run in a separate process and remain untrusted until approved. Approval is tied to the exact service component and SHA-256 signing certificate. The host checks declared capabilities, request sizes, response sizes, operation names, and time limits.

## Report a vulnerability

Please do not open a public issue for a security flaw. Use [GitHub's private vulnerability report](https://github.com/SysAdminDoc/FileExplorer/security/advisories/new) with reproduction steps, affected Android versions, and the smallest safe proof of concept you can provide.

You should receive an acknowledgement after the report is reviewed. Public disclosure can follow once a fix and signed release are available.
