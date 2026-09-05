# FileExplorer Research Refresh

Research date: 2026-08-08
Repository state reviewed: `main` at `06cd99b` (`chore: release v1.5.0`)
Scope: exhaustive repository and external research for the next roadmap slice. This pass is research-only: it does not implement code, change product behavior, commit, or push.

The repository is the source of truth for implementation status. Claims inherited from the older roadmap or research file were rechecked against the current checkout and are not repeated when the code no longer supports them.

## Executive Summary

FileExplorer v1.5.0 is an unusually broad Android file-management shell: 19 Gradle modules cover local and SAF storage, root access, four network protocols, three cloud adapters, archives, previews, an editor, app inspection, tags and collections, a transfer service, a DocumentsProvider, a LAN share server, security features, plugins, large-screen layouts, and Android intent surfaces. Its strongest strategic position is a modern Compose/Material 3 architecture with a permissive license and a broad local-to-remote feature map.

The next work should be safety- and truthfulness-led rather than another feature sweep. The largest risks are concentrated at boundaries where a plausible UI currently exposes a weaker implementation:

- Archive extraction now applies one canonical path, entry-count, depth, and uncompressed-byte policy to ZIP, 7z, TAR, and RAR paths, with staged output and fail-closed commit behavior. The shared policy must remain covered as additional archive formats or selection behaviors are added.
- SFTP copy and move now use SSHJ-native transfers/rename, literal remote path segments, bounded socket timeouts, cancellation checks, staging cleanup, and post-operation type/size verification. The shared provider contract now carries capabilities plus typed, redacted error context across adapters.
- The browser's secure-delete setting now queries a repository capability before permanent deletion. Local Java-file deletion is labeled best effort, while root, SAF, network, USB, and plugin providers default to delete-only semantics instead of implying overwrite guarantees.
- Cloud UI and README claims now reflect the live authentication boundary. All built-in providers are registered, the screen has an Activity Result sign-in/cancel/failure path, and the UI labels unconfigured providers instead of launching placeholder intents. Provider-specific auth intents and production credential/OAuth configuration remain owner-gated.
- The vault is now an end-to-end local workflow: browser selection requires biometric/device-credential authentication, payloads use opaque IDs and AES-GCM with a separate Keystore alias, the encrypted index hides filenames, sessions gate listing/restore/delete, operations use staged atomic replacement, and `.vault/` remains excluded from backup. The supported scope is regular local files; provider-backed paths remain outside the vault contract.
- Transfer queue state is now Room-backed with startup recovery and an explicit foreground monitor; the separate automation service remains `START_NOT_STICKY` by design and is still a one-shot path.
- Network/cloud failures no longer use success-shaped empty, zero, or checksum defaults for the covered adapters. Capability declarations, typed provider/operation/status/retryability errors, UI failure handling, and bounded redacted diagnostics are now in place; protocol-specific failure-injection coverage remains part of the later regression work.
- The share server now stores its password through the Keystore-backed credential cipher, binds to loopback by default with an explicit plaintext-LAN acknowledgement, and enforces bounded connections, uploads, temporary storage, headers, listings, and request rates. HTTP Basic and FTP credentials remain plaintext on the wire until a future TLS mode exists.
- Plugin discovery now exposes a visible Settings trust state. Approval is bound to the exact service component and current SHA-256 signing certificate; revocation, declared-capability checks, fail-closed protocol negotiation, bounded IPC, binder-death recovery, and path-free audit events keep unapproved or misbehaving services out of the repository path. A future catalog would still need separate provenance and update policy.
- The initial JVM baseline failure was a stale `ConnectionManagerTest` constructor call that omitted the required `diagnosticLog` parameter. The logger no longer carries an unused `Context` dependency, the tests now supply the real logger, and the full JVM suite passes with the documented Android Studio JBR; migration, native RAR fixture, and adapter-contract coverage remain open.

The recommended sequence is therefore:

1. Keep the green, trustworthy verification baseline and close the remaining destructive-delete safety gap.
2. Make cloud, vault, network capabilities, diagnostics, transfers, sharing, plugins, and imports truthful and durable.
3. Add accessibility/localization, migration, DocumentsProvider, dependency, and Android policy quality gates.
4. Only then invest in optional offline indexing, multi-user semantics, local discovery, duplicate analysis, and broader provider breadth.

The harvest produced 112 candidate ideas across 11 themes. After deduplication against the live roadmap and `Roadmap_Blocked.md`, they reduce to 0 Now/P0 items, 11 Next/P1 items, 7 Later/P2 items, 4 P3 discovery items, 8 under-consideration ideas, and 8 rejected ideas. Existing completed work includes URI routing, search streaming, predictive back, FGS timeout handling, signing-secret externalization, explicit Room migrations, secure credential storage, host-key verification, cloud streaming, diagnostics export, compact density, and settings/bookmarks import/export. It is not re-added as unfinished work.

## Product Map

### User and product surface

| Surface | Current shape | Verified status | Main gap |
|---|---|---|---|
| Local, SAF, USB, and root browsing | Repository factory resolves local/root/SAF/USB and provider-backed paths; browser supports tabs, dual-pane/large-screen layouts, recent locations, tags, collections, trash, previews, and intents | Strong foundation with shared capability/error contracts | Root metadata is approximate and some operations remain shell-based; full boundary coverage is still open |
| SMB, SFTP, FTP, and WebDAV | Network adapters are present and registered at app startup | Protocol code, per-provider capabilities, typed failures, known-host verification, and native staged SFTP copy/move are implemented | Protocol-specific failure injection and advanced remote operation semantics remain open |
| Google Drive, Dropbox, and OneDrive | Provider implementations contain list/read/upload/delete/rename/quota paths; Dropbox and OneDrive upload bodies stream | Partial and configuration-gated | Provider registry has no production registration call site, auth intents are placeholders, and the UI has no completed sign-in result flow |
| Archives and document/media previews | ZIP/7z/TAR/GZ/BZ2/XZ/Zstandard/RAR-related paths plus EPUB, PDF, APK, hex, and text/editor surfaces | Broad feature coverage | Extraction containment, symlink behavior, entry/byte/path caps, and cancellation need one policy across formats |
| Transfer and automation | Compose transfer UI, Room-backed `TransferQueueManager`, foreground queue monitor, `TransferService`, automation intents, progress and timeout handling | Durable local queue plus separate automation path | Automation service remains one-shot; one extraction operation remains unsupported; its display task source resolution is simplified |
| Security | Biometric app lock, encrypted connection credentials, SFTP known hosts, secure-delete utility, file encryption manager, biometric-gated local vault, and Keystore-backed share credentials | Vault local-file flow is verified; secure-delete guarantees remain provider-dependent, share-server wire transport is plaintext unless kept on loopback, and feature labels need verified/partial states |
| Sharing and Android integration | HTTP/FTP share server, Quick Share, `DocumentsProvider`, FileProvider, tile, package/intent integrations, Cast | Share server has explicit loopback/LAN scope and resource limits; DocumentsProvider needs conformance tests; permission and distribution posture needs an executable matrix |
| Plugins | AIDL/Bundle protocol v1, explicit component binding, certificate-bound consent, declared capabilities, bounded IPC, and path-free audit events | Verified local trust boundary; unapproved plugins remain inert | A future catalog, update provenance, and richer per-capability consent need a separate product/release decision |
| Settings and portable state | Room v7 with explicit migrations and exported schemas; DataStore; settings/bookmarks/connections export/import | Database migration foundation is good; export/import exists | Import reads an unbounded stream, accepts unknown future versions, has no transaction/rollback or duplicate policy, and backup boundaries need review |
| UI quality | Compose/Material 3, theme flow, localized resource directories for 9 locales, large-screen and dual-pane work | Visual foundation is strong | Inline user strings, null content descriptions, missing semantics coverage, RTL/scalable-text checks, and a broken JVM test contract reduce confidence |

### Module map and trust boundaries

The 19-module split is coherent: `:app` composes `core:model`, `core:plugin`, `core:data`, `core:storage`, `core:database`, `core:ui`, `core:designsystem`, `core:network`, and `core:cloud`, while feature modules own browser, transfer, settings, search, network, cloud, security, editor, and apps. Hilt registration in `App.onCreate()` wires network and plugin adapters into the repository factory. This is a good seam for capability and error contracts.

The important data flows are:

```text
Compose feature screens
        |
        v
ViewModels / feature policies ----> Room, DataStore, app-private files
        |
        v
FileRepositoryFactory
  |       |        |       |
local   SAF/root  network cloud/plugin
        |
        +--> transfer queue/service --> notification + long-running I/O
        +--> DocumentsProvider / share server / Android intents
```

The high-risk boundaries are not isolated to one module:

- A path enters from UI, URI routing, an archive entry, a network adapter, a plugin, or an automation intent and eventually reaches a repository or a server-side command.
- Credentials enter through network/cloud/share screens and can be persisted by different stores with different backup behavior.
- Long-running work can be started by a UI action, an intent, or a service restart, but state ownership is not yet durable.
- A provider can legally lack an operation, but the current contract often encodes that as an empty result rather than a typed capability or an actionable error.

## Competitive Landscape

The comparison below is intentionally limited to eight products so it remains decision-useful. It combines direct source inspection with current product pages and community discussions; it is not a claim that every competitor has the same implementation quality on every device.

| Product | Segment | Strong pattern to borrow | FileExplorer position / caution |
|---|---|---|---|
| Material Files | OSS | NIO/native file operations, root support, archive/network breadth, explicit privacy/security posture, reliable error handling | FileExplorer has a broader feature map and newer Compose shell, but should match its boundary discipline before adding more breadth |
| Amaze File Manager | OSS | Hybrid file abstraction, tabs, root, archive readers, encryption, plugin/cloud seams, translations | Good benchmark for modular capability breadth; its long feature history is a reminder to test every storage path |
| Fossify File Manager | OSS | Offline-first, no-ad/no-analytics posture, lock/fingerprint, storage analysis, simple permissions | FileExplorer is broader; Fossify is the benchmark for minimal data collection and a clear permission story |
| Ghost Commander | OSS | Dual-panel workflow and protocol functionality behind plugins | FileExplorer already has dual-pane and direct adapters; plugin trust and discoverability must be stronger than a raw service hook |
| Solid Explorer | Commercial | Dual-pane, collections, cloud integrations, encrypted archives, extensions, Chromecast, storage analyzer | Feature parity is not enough; FileExplorer can differentiate on open source, verifiable states, and local privacy |
| MiXplorer Silver | Commercial | Tabs, dual panels, broad provider/archive support, import/export, editor/EPUB, server, customizable power-user workflows | Demonstrates demand for depth, but its provider count is not a reason to ship unverified credentials or unsupported adapters |
| X-plore | Commercial | Permanent dual-pane tree, disk map, LAN/DLNA/FTP/SFTP/cloud/server, vault and keyboard workflows | Validates advanced workflows and disk visualization; FileExplorer should first make operation recovery and capability reporting dependable |
| Total Commander | Commercial | Long-term maintenance, keyboard/regex/search workflows, Android 15/16 awareness, plugin model | Demonstrates the value of release discipline and compatibility notes rather than only feature count |

Cross-product patterns that recur across OSS and commercial sources are dual-pane or fast multi-location movement, reliable conflict handling, storage analysis, broad but optional remote providers, portable settings, transparent privacy, and a clear split between simple and power-user workflows. The most transferable pattern is not another provider; it is an explicit operation model with progress, cancellation, conflict decisions, recovery, and truthful support status.

## Security/Privacy/Reliability

### Credential and secret handling

The current implementation has several real strengths that supersede stale findings:

- `SecureCredentialCipher` uses Android Keystore AES-GCM with the `keystore-aes-gcm:v1` payload prefix and the `fileexplorer_secure_credentials_v1` alias. `ConnectionManager` migrates legacy/plain saved passwords when they are read or saved.
- `SftpKnownHostsStore` uses `noBackupFilesDir`, presents fingerprints for first trust, and rejects unknown or changed host keys instead of silently accepting them.
- Release signing now reads ignored `signing.properties` or `FILEEXPLORER_STORE_PASSWORD`/`FILEEXPLORER_KEY_PASSWORD`; tracked Gradle files no longer contain the formerly reported hardcoded passwords.
- Cloud account tokens are encrypted before persistence when `staySignedIn` is enabled, while non-persistent sessions remain memory-only.
- `BackupManager` intentionally omits connection passwords and private keys from the portable JSON payload.

The remaining secret surfaces are materially different:

- `ShareServerSettingsStore` now stores the share-server password through the shared Keystore-backed credential cipher. The UI distinguishes loopback-only sharing from an explicit plaintext LAN opt-in; TLS remains a future transport option.
- Cloud account preferences are encrypted at the application layer, but the backup exclusion posture is not explicit for the cloud store. The backup contract should exclude cloud/share secret stores and document what can and cannot be restored.
- `BackupManager.importFromStream()` calls `readText()` on the entire input, accepts any version greater than or equal to 1, inserts without a transaction/rollback policy, and does not define duplicate behavior. Portable data is not automatically safe merely because it omits passwords.
- Plugin metadata and remote-provider errors can contain identifiers or paths. Diagnostic export must redact passwords, bearer tokens, refresh tokens, private keys, and sensitive path components consistently.

### Path, archive, and command boundaries

The root repository's shared shell-escape helper wraps arguments in single quotes and escapes embedded quotes; `RootShellEscapeTest` covers the hard cases. That is a current strength, although parsing `ls -l`, hardcoded readable/writable flags, `lastModified = 0L`, and a bounded `find ... | head -500` still limit correctness.

SFTP copy and move now use SSHJ-native transfers and rename operations rather than remote shell commands. Copy stages files and directory trees through SFTP, verifies local and remote sizes/types, commits with SFTP rename, cleans partial staging, rejects remote symlinks/special files, and uses connection/read timeouts plus cancellation checks. The shared adapter contract now preserves authentication, timeout, unsupported, conflict, and partial-operation context instead of collapsing it into empty results.

Archive extraction now applies one policy object before any format library writes a file. ZIP, 7z, TAR, and RAR extraction normalize names through path-segment containment and write into a staging directory before commit. The policy:

- resolve destination and entry paths with path-segment containment, not a raw string prefix;
- reject absolute paths, `..` traversal, invalid/overlong names, and symlink/hardlink escapes;
- enforces maximum entry count, total uncompressed bytes, per-entry bytes, nesting depth, and path length before or during writes;
- writes into a staging directory, handles cancellation, and commits only the validated result;
- rejects skipped/unsafe entries with an actionable failure instead of silently presenting a successful extraction;
- has ZIP/7z/TAR regression fixtures, while RAR uses the same staged and budgeted adapter path and still needs a native fixture for format-specific regression coverage.

The app depends on Apache Commons Compress 1.28.0, which is newer than the documented 1.26 security fixes for CVE-2024-25710 and CVE-2024-26308 and the 1.24 fix for CVE-2023-42503. Dependency freshness reduces library exposure; it does not replace application-level output containment and resource caps.

Secure deletion has an honest boundary. Overwriting a regular local Java `File` before deletion is a best-effort operation. Android SAF providers, root-backed paths, network shares, flash translation layers, snapshots, and copy-on-write storage cannot all promise physical erasure. The browser now exposes a provider capability, confirms the limitation before permanent deletion, and never labels a delete-only provider as securely overwritten. The remaining gap is broader typed error/status semantics across adapters and the Trash maintenance path.

### Share-server exposure

The HTTP and FTP servers now bind to loopback by default, require an explicit acknowledgement before binding all local IPv4 interfaces, and bind passive FTP sockets to the same configured scope. HTTP Basic authentication and FTP credentials remain plaintext on the wire because TLS is not yet implemented, so the UI warns and constrains LAN exposure. The path resolver's canonical-root, temporary-file exclusion, and symlink/traversal checks are retained.

The implemented hardening bundle uses a loopback/LAN-explicit bind choice, visible network exposure and plaintext warnings, Keystore-backed credentials, eight concurrent sessions, per-client request rate limits, bounded request headers and listings, 256 MiB uploads, 512 MiB active temporary storage, idle timeouts, stale-upload cleanup, and structured startup failure diagnostics. TLS or another secure transport should be an explicit future mode rather than an implied guarantee. HTTP status and FTP reply handling now report oversized, unavailable, and partial transfers with protocol-appropriate failures.

### Plugin trust and extension safety

`PluginManager` discovers services from metadata, verifies the installed signing certificate, and exposes every discovered component in Settings as untrusted until explicitly approved. Approval is stored for the package/service/certificate tuple and declared capabilities; revocation immediately removes the plugin from URI routing. Calls negotiate the supported protocol version, require the filesystem capability, check all requested paths, and use four concurrent permits, a 15-second interruptible timeout, 256 KiB request and 512 KiB response budgets, and a path-free audit log. Binding death and malformed, oversized, or failed responses become bounded provider failures; the next request rebinds instead of reusing a dead binder.

This is a local trust boundary, not a marketplace: explicit user-installed components are approved against their current signing certificate, and unknown or changed identities fail closed. A future catalog still needs independent provenance, compatibility, update, rollback, and supply-chain decisions. Plugin failure is isolated from the browser process and represented as an unavailable capability, not a crash or a hung transfer.

### Reliability and observability

`TransferQueueManager` now owns a Room-backed queue projected through a singleton `StateFlow`. Task identity, idempotency key, URI/path payload, order, conflict policy, progress checkpoint, retry count/cause, cancellation state, and timestamps are persisted; startup rehydrates the queue and converts interrupted execution back to queued work. The separate automation path in `TransferService` remains a one-shot `START_NOT_STICKY` service with a simplified `sources = emptyList()` display model, while the recoverable queue has an explicit `START_STICKY` foreground notification monitor and keeps execution in the durable manager.

The durable queue now persists task identity, source/destination URIs, provider-derived URI schemes, conflict policy, progress checkpoints, retry count/cause, cancellation state, and an idempotency key. Room rehydration plus application startup recovers bounded work after process death; a foreground service is reserved for active user-visible queue notification and cancellation ownership. Resume is idempotent at the task/checkpoint boundary and repository resolution revalidates permissions and URI grants before I/O. WorkManager remains a possible future scheduler if background execution needs to outlive the foreground service policy.

The current `DiagnosticLog` is a useful singleton with a 200-entry cap and redaction for password/Bearer/token patterns, and Settings can export diagnostics. Repository errors now carry provider, operation, kind, retryability, and optional status context, and browser/network/cloud flows preserve operational failures rather than emitting empty success-shaped results. Remaining work is broader failure-injection and lifecycle coverage so “empty directory,” “not supported,” “authentication failed,” and “timeout” stay distinct across every exported surface.

### Permissions, backup, and distribution

The manifest requests broad storage, media, internet/network-state, foreground data-sync, notification, and package-visibility capabilities, including `MANAGE_EXTERNAL_STORAGE` and `QUERY_ALL_PACKAGES`. Android guidance recognizes file managers, backup/document management, on-device search, and disk encryption as potential all-files use cases, but Play policy still requires a narrow primary purpose and declaration. The product should maintain a machine-readable permission matrix connecting each permission to a feature, API level, fallback, user explanation, and distribution status.

`DocumentsProvider` is a strong integration choice: its root and child paths use canonical containment, symlink filtering, and parent/source/target validation. It needs conformance tests for persisted URI grants, root changes, missing roots, search/recent results, rename/move/copy failures, and provider flags. Android's provider contract treats roots as dynamic (for example, accounts and USB devices), so hard-coded assumptions should not leak into the UI.

The repository's `settings.gradle.kts` includes JitPack in addition to standard repositories. Dependency provenance should be centralized, pinned, scanned, and reviewed. The current dependency set includes Kotlin 2.2.21, AGP 8.13.0, Gradle 8.14.4, Room 2.8.4, Compose BOM 2026.06.00, Hilt 2.58, Commons Compress 1.28.0, Zip4j 2.11.6, sshj 0.40.0, SMBJ 0.13.0, Bouncy Castle 1.84, and other protocol/security libraries. A refresh policy should track upstream release notes and OSV/NVD advisories without claiming a vulnerability is present until the resolved dependency graph proves it.

### Accessibility, internationalization, and testability

Localized resource directories currently cover English plus German, Spanish, French, Japanese, Korean, Brazilian Portuguese, Russian, and Simplified Chinese. A scan found approximately 16 inline user-facing string candidates and many `contentDescription = null` sites. Null descriptions are correct for decorative icons, but the project lacks a systematic rule and Compose semantics test coverage for actionable icons, traversal order, progress, list roles, custom actions, live regions, and large text.

The quality gate should compare resource keys across locales, exercise RTL, font scaling, compact/comfortable density, large screens, keyboard navigation where supported, and TalkBack-relevant semantics. UI tests should assert labels and actions rather than pixels. This is also a reliability measure: a user who cannot distinguish an unsupported provider from an empty directory has an accessibility and observability failure at the same time.

## Architecture Assessment

### Strengths to preserve

| Area | Evidence in the current checkout | Assessment |
|---|---|---|
| Modular boundaries | 19 Gradle modules split core contracts, storage, database, network, cloud, UI, and features | Good substrate for incremental hardening; avoid a broad rewrite |
| Modern Android shell | Compose, Material 3, theme flow, large-screen layouts, predictive-back and FGS timeout work already landed | Strong platform trajectory |
| Data durability foundation | Room v7, `exportSchema = true`, explicit migrations 2→3→4→5→6→7, schema JSONs 2 through 7 | Migration tests are the missing confidence layer, not migration design |
| Credential protection | Keystore AES-GCM cipher, legacy migration, SFTP host-key fingerprint checks, ignored release signing properties | Keep the same primitives and extend them to share/vault/backup boundaries |
| Repository routing | Scheme resolver handles local/root/SAF, Shizuku, USB, network, and plugin paths; app registers adapters | Correct place to add capabilities, typed errors, and URI-grant validation |
| Extensibility | Cloud provider abstraction, plugin AIDL/Bundle protocol, network adapter registration | Needs readiness/trust semantics before more providers are added |
| Android integration | DocumentsProvider, share intents, Quick Share, tile, FileProvider, Cast, app analyzer | Broad integration value; each exported surface needs contract tests and permission review |

### Root causes rather than isolated bugs

1. **The repository contract was too weak and is now being strengthened.** The shared model now declares operation capabilities and typed `Unsupported`, `Auth`, `Permission`, `Transport`, `Conflict`, `Cancelled`, `NotFound`, and `CorruptData`-equivalent error kinds; remaining gaps are adapter-specific coverage and richer user-action fields.
2. **Policy is implemented beside format/provider code.** Archive containment, secure deletion, backup exclusion, upload limits, and share-server boundaries need central policy objects and tests. A policy that exists but is bypassed by one format is not a security boundary.
3. **Lifecycle ownership is split between UI, singletons, and services.** Transfer state, diagnostic state, cloud account state, and plugin binding need explicit persistence/lifecycle owners. Room/WorkManager should own recoverable work; in-memory flows should be views over durable state.
4. **Readiness is not a first-class state.** Cloud providers, vault, plugins, network capabilities, and optional distribution features need statuses such as `Verified`, `Partial`, `RequiresConfiguration`, `Unsupported`, and `BlockedByEnvironment`. The UI and README should consume the same status model.
5. **Tests are not aligned to trust boundaries.** The full JVM test task now passes, and the repository has root shell, share path, archive policy, and security tests, but there is no migration/format-matrix/contract suite covering all adapters.
6. **Dependency and platform drift is not yet a release gate.** The app targets SDK 35 and compiles against 36, while Android 15/16 behavior, foreground-service limits, storage policy, package visibility, and library advisories continue to move. A small matrix and automated checks will cost less than reactive release repair.

### Recommended target shape

```text
Feature status registry
       |
Capability-aware repository contract ---- typed error + redacted diagnostic
       |                 |
       |                 +--> UI / automation / DocumentsProvider / share server
       v
Policy services: path containment, archive limits, secure-delete scope,
                 import bounds, upload/session limits, plugin budgets
       |
Durable state: Room queue + WorkManager recovery + encrypted secret stores
       |
Adapters: local / SAF / root / USB / SMB / SFTP / FTP / WebDAV / cloud / plugin
```

This shape keeps the current modules and moves shared semantics downward. The provider contract now makes future adapters declare capabilities and typed errors rather than silently inventing defaults.

### Gap analysis and tiering

#### Now

- Keep the full JVM verification baseline green, then add migration and boundary tests.
- Keep the shared bounded extraction policy covered by archive-format and malformed-input tests.
- Keep destructive deletion capability-aware and honest about secure-delete limits as new providers and Trash maintenance paths are added.

#### Next

- Make cloud readiness and auth launchability truthful without assuming owner credentials.
- Keep the local vault workflow covered as the payload/index format evolves; provider-backed vault imports remain out of scope until their URI and streaming semantics are defined.
- Persist and recover transfer work across process death.
- Extend capability/error contract tests and failure injection across providers and exported surfaces.
- Harden the share server's credentials, bind scope, transport warnings, and resource limits.
- Keep the local plugin trust boundary covered as the protocol and repository adapters evolve; a future catalog remains a separate decision.
- Make portable settings import bounded, versioned, transactional, and non-secret by design.
- Add accessibility/i18n quality gates and align feature documentation with verified behavior.
- Add migration, adapter, DocumentsProvider, and security regression coverage.
- Add dependency provenance, advisory, and Android 15/16 upgrade gates.

#### Later

- Complete high-value network capabilities such as remote search, size, checksums, and create with explicit support states.
- Add crash-consistent file-operation staging and recovery for multi-file jobs.
- Add conflict preview and resumable/idempotent operation decisions for power users.
- Improve duplicate/similar-file analysis and storage visualization using bounded background work.
- Expand DocumentsProvider conformance and persisted-URI-grant behavior across removable/dynamic roots.
- Add release smoke coverage for APK install, exported components, permission fallbacks, and large-screen layouts.
- Add a provider/feature status matrix to the app and release documentation.

#### Under Consideration

- An offline index and operation journal, if the team accepts the storage, invalidation, privacy, and battery cost.
- Multi-user or shared-workspace semantics, which need an identity, encryption, conflict, and ownership model; Android device profiles alone do not define an app product.
- Local discovery and secure cross-device transfer, potentially inspired by LocalSend, after choosing discovery, trust, and transport boundaries.
- A richer disk map/duplicate workflow after the current analyzer's correctness and cancellation behavior are measured.
- A constrained plugin catalog after the local plugin trust model is complete.
- Desktop/Kotlin Multiplatform, terminal, NFS, Syncthing, rclone, and additional cloud providers only after their external/runtime blockers are resolved.

#### Rejected

- A blanket promise of physical secure deletion on Android flash, SAF, network, or copy-on-write storage.
- Shipping every cloud provider named by competitors before authentication, maintenance ownership, and test accounts exist.
- Embedding an rclone runtime without a defined Android process/runtime boundary.
- Android Auto file-manager UI, because the platform category restrictions do not support it.
- Full Brotli archive support as a standalone feature, because Brotli is a codec rather than a multi-entry container.
- Custom icon-pack support in the next release, because it adds ecosystem/design maintenance without addressing current reliability gaps.
- A KMP scaffold that shares no usable storage/runtime behavior.
- An embedded terminal before the project chooses PTY, root, package, and command-isolation semantics.

## Rejected Ideas

The following ideas were evaluated during the harvest and are intentionally not active roadmap work. Some are already represented in `Roadmap_Blocked.md`; they are recorded here so future research does not re-add them as if they were unexamined.

| Idea | Decision | Reason |
|---|---|---|
| “Add all competitor cloud providers” | Rejected for the current cycle; blocked for any concrete provider batch | OAuth credentials, service accounts, redirect URIs, test accounts, API policy, and maintenance ownership are external inputs. More adapters would amplify the current readiness problem |
| “Import rclone.conf and browse every remote” | Rejected until runtime boundary is selected | A config file is not an Android execution engine; importing credentials without a supported backend would create a misleading and risky half-feature |
| “Guarantee secure erase everywhere” | Rejected as a product promise | Flash translation, snapshots, SAF providers, network servers, and copy-on-write filesystems make physical erasure unprovable from this app |
| “Build a terminal now” | Blocked/under consideration, not active implementation | Embedded PTY and Termux integration have different security and lifecycle models; the project owner must select one |
| “Start KMP with a shared scaffolding module” | Rejected for now | The current implementation is tightly coupled to Android storage, Room, libsu, Compose, and Android networking; a scaffold would add migration cost without a usable target |
| “Add Android Auto file browsing” | Rejected | Android Auto's supported app categories do not provide a general file-manager display surface |
| “Add a standalone Brotli archive browser” | Rejected | Brotli compresses a stream; it does not define a multi-file archive container |
| “Add custom icon packs before reliability work” | Rejected for the next cycle | Low impact relative to security, lifecycle, accessibility, and verification gaps; it would also fragment the established Material 3 visual language |

## Sources

The source list is grouped by class and intentionally contains URLs only. Repository symbols and findings in the sections above are the primary evidence for implementation status; these external sources support comparative, platform, standards, engineering, and advisory conclusions.

### Direct open-source competitors

https://github.com/zhanghai/MaterialFiles
https://github.com/TeamAmaze/AmazeFileManager
https://github.com/FossifyOrg/File-Manager
https://github.com/Raival-e/Prism-File-Explorer
https://github.com/1hakr/AnExplorer
https://f-droid.org/packages/com.ghostsq.commander/
https://veniosg.github.io/Dir/
https://github.com/SerZhyAle/AndroidFolderExplorer
https://github.com/Senzme/NFile
https://f-droid.org/en/packages/app.fluffy/
https://gitlab.com/axet/android-file-manager/-/tree/master
https://github.com/loopotto/cleansweep

### Commercial competitors

https://neatbytes.com/solidexplorer/
https://mixplorer.com/
https://www.lonelycatgames.com/apps/xplore
https://www.ghisler.com/android.htm
https://www.nextapp.com/fx/
https://play.google.com/store/apps/details?id=com.ghisler.android.TotalCommander

### Adjacent projects and patterns

https://github.com/syncthing/syncthing-android
https://github.com/rclone/rclone
https://github.com/termux/termux-app
https://github.com/KDE/dolphin
https://gitlab.gnome.org/GNOME/nautilus
https://github.com/TagSpaces/tagspaces
https://github.com/RikkaApps/Shizuku
https://github.com/LocalSend/localsend

### Curated lists and community

https://github.com/Heapy/awesome-kotlin
https://github.com/offa/android-foss
https://f-droid.org/en/categories/system/
https://www.reddit.com/r/fossdroid/
https://www.reddit.com/r/androidapps/
https://news.ycombinator.com/item?id=38992689
https://xdaforums.com/t/vote-for-your-favorite-file-manager-mixplorer-vs-solid-explorer-vs-anything-else.4751063/

### Android platform and official guidance

https://developer.android.com/training/data-storage/manage-all-files
https://developer.android.com/guide/topics/providers/document-provider
https://developer.android.com/reference/android/provider/DocumentsProvider
https://developer.android.com/training/data-storage/shared/documents-files
https://developer.android.com/develop/background-work/services/fgs/service-types
https://developer.android.com/about/versions/15/behavior-changes-15
https://developer.android.com/about/versions/16/behavior-changes-16
https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
https://developer.android.com/develop/ui/compose/accessibility
https://developer.android.com/develop/ui/compose/accessibility/semantics
https://developer.android.com/privacy-and-security/keystore
https://developer.android.com/training/package-visibility/declaring
https://developer.android.com/topic/libraries/architecture/workmanager
https://developer.android.com/identity/credential-manager
https://developer.android.com/guide/topics/large-screens
https://support.google.com/googleplay/android-developer/answer/10467955
https://developer.android.com/training/sharing/send
https://source.android.com/docs/core/storage/scoped
https://developer.android.com/develop/ui/compose/bom/bom-mapping

### Protocol standards

https://www.rfc-editor.org/rfc/rfc959
https://www.rfc-editor.org/rfc/rfc4253
https://datatracker.ietf.org/doc/html/rfc4918
https://www.rfc-editor.org/rfc/rfc5531
https://www.rfc-editor.org/rfc/rfc6762
https://datatracker.ietf.org/doc/html/rfc8446
https://www.rfc-editor.org/rfc/rfc9112
https://www.rfc-editor.org/rfc/rfc3986

### Academic and engineering research

https://arxiv.org/abs/2007.03905
https://arxiv.org/abs/1407.5410
https://arxiv.org/abs/2507.07927
https://www.usenix.org/conference/atc15/technical-session/presentation/agrawal
https://www.usenix.org/conference/osdi12/technical-sessions/presentation/tang
https://www.usenix.org/conference/usenixsecurity24/presentation/dong-zikan
https://www.usenix.org/conference/usenixsecurity24/presentation/arkalakis
https://www.usenix.org/conference/atc20/presentation/rebello
https://www.usenix.org/conference/usenixsecurity12/technical-sessions/presentation/reardon
https://www.pure.ed.ac.uk/ws/portalfiles/portal/294753517/CAT_PENG_DOA14062021_AFV.pdf
https://chao-peng.github.io/publication/icsme21/icsme21.pdf

### Dependency releases and documentation

https://github.com/hierynomus/sshj/releases
https://github.com/hierynomus/sshj/security/advisories
https://github.com/hierynomus/smbj/releases
https://github.com/apache/commons-compress
https://commons.apache.org/proper/commons-compress/changes.html
https://commons.apache.org/proper/commons-compress/security.html
https://commons.apache.org/proper/commons-compress/limitations.html
https://github.com/srikanth-lingala/zip4j/releases
https://github.com/coil-kt/coil/releases
https://coil-kt.github.io/coil/upgrading_to_coil3/
https://developer.android.com/jetpack/androidx/releases/room
https://developer.android.com/jetpack/androidx/releases/work
https://github.com/JetBrains/kotlin/releases
https://github.com/google/dagger/releases
https://www.bouncycastle.org/latest_releases.html

### Security advisories and verification references

https://osv.dev/vulnerability/CVE-2024-25710
https://osv.dev/vulnerability/CVE-2024-26308
https://osv.dev/vulnerability/CVE-2023-42503
https://osv.dev/vulnerability/GHSA-45x7-px36-x8w8
https://nvd.nist.gov/vuln/detail/CVE-2020-36843
https://github.com/hierynomus/sshj/issues/916
https://github.com/hierynomus/sshj/issues/1000
https://mas.owasp.org/MASVS/

## Open Questions

These questions are intentionally left for product or operator decisions rather than silently answered in the roadmap:

1. Which cloud providers, redirect URIs, OAuth clients, and test accounts will the owner provision, and is the intended contract “optional provider configured by the user” or “first-party sign-in ready”?
2. Should the share server default to loopback, an explicitly selected interface, or LAN binding with a prominent warning? Is TLS required for the supported release, or is plaintext restricted to a clearly labeled local/LAN mode?
3. The implemented vault is a biometric/device-credential-gated encrypted container in app-private storage. Remaining product decisions are whether provider-backed files should be supported and what migration policy should apply to contents after uninstall, device migration, or biometric enrollment change.
4. What exact wording is acceptable for secure delete on flash and remote providers: “best effort overwrite,” “permanent delete,” or a provider-specific capability label?
5. Is there an app-level multi-user requirement beyond Android work profiles/device users? If yes, what are the identity, ownership, encryption, sharing, and conflict semantics?
6. Should offline mode mean cached browsing, queued mutations, a full local index, or a sync engine? Each choice has different battery, privacy, and conflict costs.
7. If a plugin catalog is ever added, what provenance, update, rollback, compatibility, and supply-chain policy should govern it beyond the current local certificate-bound approval?
8. Which distribution lanes are targets for v1.7.0: sideload/GitHub, F-Droid, Play, or multiple flavors? The F-Droid and Play entries in `Roadmap_Blocked.md` require owner access and product decisions.
9. Should the minimum Android API remain 26 while the app targets 35/compiles 36, and which Android 15/16 behaviors must be release-blocking rather than informational?
10. Is JitPack required for a current dependency, or can all production dependencies move to verified Maven repositories with centralized versions and dependency locking?
11. What is the supported semantic level for remote `search`, `calculateSize`, checksums, and create operations when a protocol cannot offer them efficiently? `Unsupported` is preferable to a false empty or zero result, but the UI wording needs agreement.
12. What device matrix is available for SAF providers, root/Shizuku, removable USB, large screens, TalkBack, RTL, flash storage, and network failure injection? The answer determines which checks can be automated in CI and which need a documented device lane.
