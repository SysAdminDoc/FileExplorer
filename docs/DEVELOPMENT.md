# Development guide

## Prerequisites

- Windows, macOS, or Linux with Android SDK 36
- JDK 21 to run Gradle. The app targets JVM 17 bytecode
- An Android 8.0+ device or emulator
- ADB access for the release smoke check

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

Release builds need an ignored `signing.properties` file:

```properties
STORE_FILE=fileexplorer.jks
STORE_PASSWORD=replace-with-local-secret
KEY_ALIAS=fileexplorer
KEY_PASSWORD=replace-with-local-secret
```

The two passwords can instead come from `FILEEXPLORER_STORE_PASSWORD` and `FILEEXPLORER_KEY_PASSWORD`.

## Local verification

```powershell
.\gradlew.bat --no-daemon --console=plain test
.\gradlew.bat --no-daemon --console=plain verifyAccessibilityLocalization
.\gradlew.bat --no-daemon --console=plain :core:ui:compileDebugAndroidTestKotlin
.\gradlew.bat --no-daemon --console=plain verifyAndroidUpgradeReadiness
.\gradlew.bat --no-daemon --console=plain verifyDependencyProvenance
.\gradlew.bat --no-daemon --console=plain scanDependencyAdvisories
.\gradlew.bat --no-daemon --console=plain releaseSmoke
```

`verifyAccessibilityLocalization` checks resource keys, format placeholders, Android fallback strings, and new inline user-facing strings. The UI instrumentation contract covers file-row actions, descriptions, touch targets, right-to-left layout, and large text.

`verifyAndroidUpgradeReadiness` checks compile and target SDK levels, manifest exports, backup rules, storage fallbacks, predictive back behavior, package visibility, and foreground-service timeout handling.

`scanDependencyAdvisories` resolves the release runtime graph and queries OSV. An advisory should be investigated and fixed before release. The optional `-PosvAllowlist=OSV-ID,...` input is for a documented owner decision, not a way to silence an unexplained result.

`releaseSmoke` builds and installs the debug APK on a connected emulator. It checks picker, share, DocumentsProvider, Quick Settings, exported components, and activity recreation.

## Project layout

FileExplorer has 19 Gradle modules. `:app` owns the application, navigation, storage permission flow, and exported Android surfaces.

Feature modules contain browser, search, transfer, network, cloud, editor, app-management, security, and settings screens. Core modules hold provider contracts, storage access, network and cloud clients, Room data, models, reusable UI, and the design system.

`FileRepositoryFactory` routes each path or provider URI to the matching local, root, USB, plugin, network, cloud, or archive implementation. Screens read provider capabilities before exposing a destructive or expensive action.

## Automation intents

Tasker, Automate, and similar tools can send broadcasts to `AutomationReceiver`.

| Action | Inputs |
|---|---|
| `com.explorer.fileexplorer.action.COPY` | `source` or `sources`, `destination`, optional `conflict`, optional `idempotency_key` |
| `com.explorer.fileexplorer.action.MOVE` | `source` or `sources`, `destination`, optional `conflict`, optional `idempotency_key` |
| `com.explorer.fileexplorer.action.ZIP` | `source` or `sources`, `destination`, optional `format` |
| `com.explorer.fileexplorer.action.UPLOAD` | Local source, remote destination, and positive saved `connection_id` |

Conflict values are `rename`, `overwrite`, `skip`, or `keep-both`. Archive formats are `zip`, `7z`, or `tar.gz`.

Requests receive `RESULT_OK` when the foreground transfer is accepted. Add a unique `request_id` to receive a `com.explorer.fileexplorer.action.TRANSFER_RESULT` completion broadcast with `completed`, `failed`, or `cancelled` status.

## Plugin contract

The `:core:plugin` module defines protocol version 1 and the `IFileExplorerPlugin` AIDL service. A plugin advertises its ID, display name, version, URI schemes, and capabilities through manifest metadata.

FileExplorer validates the exact exported service, signing certificate, protocol version, declared capability, operation name, path, and message size. Approval remains local and can be revoked in Settings. Calls are limited to four concurrent requests, 15 seconds, 256 KiB requests, and 512 KiB responses.

Filesystem providers can implement list, info, exists, copy, move, delete, create directory, create file, rename, size, search, and checksum operations. Unknown or unsupported operations fail closed.

## Cloud configuration

The default build includes provider clients but no production OAuth identity. A configured build must supply its own provider registration through an external secret mechanism.

- Google Drive needs an Android OAuth client that matches the package and signing certificate.
- Dropbox needs an app registration and external app key.
- OneDrive needs a Microsoft application registration with the required file and offline-access permissions.

The Cloud Storage screen distinguishes unavailable, configuration-required, ready-to-sign-in, and signed-in states. It does not enable a connect action unless a real authentication intent exists.

## Marketing assets

- `assets/screenshots/` holds the uncropped 1080x2400 app captures used in the README.
- `assets/marketing/hero-source.svg` defines the 1280x640 GitHub hero layout. `hero.png` is the composed output with real product screens.
- `assets/marketing/source/` holds the feature-graphic and store-poster layouts.
- `store-assets/` contains the 1024x500 feature graphic and five 1080x1920 store images.

Recapture every affected screen after a UI change. Use the signed release APK on an isolated emulator, keep the seeded data free of personal information, and confirm each final PNG at full resolution.

## Release checklist

1. Update the version name, version code, README badge, changelog, and internal project notes.
2. Run the full local verification set on a clean tree.
3. Remove previous build output and assemble a signed release APK.
4. Verify the APK signature, SHA-256 checksum, certificate fingerprint, and 16 KiB zip alignment.
5. Install the signed APK on an isolated emulator and exercise the main workflows.
6. Commit, push, tag, and attach the verified files to the GitHub release.
