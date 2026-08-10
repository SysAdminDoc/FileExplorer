# ROADMAP

Actionable work only. Historical and completed roadmap material is archived in CHANGELOG.md; blocked work is kept in Roadmap_Blocked.md.

## Actionable Items

- [ ] P1 — Make portable settings import bounded and transactional
  - Why: The existing export/import feature omits secrets, but import reads an unbounded stream, accepts unknown future versions, inserts without rollback, and has no duplicate policy.
  - Evidence: `core/data/.../BackupManager.kt`; Room/DataStore usage; Android backup/Keystore guidance; `RESEARCH.md` credential and backup analysis.
  - Touches: backup schema/versioning, stream parser and size limits, Room transaction, DataStore writes, preview/summary UI, backup tests and exclusions.
  - Acceptance: Import enforces byte/record/string limits, rejects unsupported versions, validates the whole payload before mutation, commits atomically or rolls back, defines duplicate behavior, preserves non-secret semantics, excludes credential/vault/share stores, and reports a deterministic summary.
  - Complexity: M

- [ ] P1 — Establish accessibility and localization quality gates
  - Why: Nine locale directories exist, but inline user strings and inconsistent `contentDescription`/Compose semantics remain. Missing labels, traversal order, RTL, and font-scaling checks make the broad UI unreliable for assistive-technology users.
  - Evidence: Compose accessibility and semantics guidance; scan of `feature/*` screens; `RESEARCH.md` accessibility section.
  - Touches: resource files, Compose screens/components, semantics helpers, locale key tooling, UI tests, large-screen/density settings.
  - Acceptance: User-facing strings are resource-backed; locale key parity is checked; actionable icons and custom actions have semantics while decorative icons remain hidden; tests cover TalkBack-relevant labels, progress/list roles, RTL, font scaling, compact/comfortable density, keyboard focus where supported, and minimum touch targets.
  - Complexity: M

- [ ] P1 — Add migration, adapter, archive, and DocumentsProvider regression coverage
  - Why: Room has explicit migrations and several security tests exist, but there is no complete migration matrix or cross-adapter contract suite; the current test baseline masks gaps.
  - Evidence: `core/database` schema 2–7 and `AppDatabase.kt`; existing 32 test files; `FileDocumentsProvider`; `ArchiveEntryPathPolicy`; Android DocumentsProvider contract; `RESEARCH.md` testability assessment.
  - Touches: `core:database`, `core:data`, `core:storage`, `core:network`, `core:cloud`, `app`/provider tests, CI reporting.
  - Acceptance: Migrations preserve representative rows through every version; repository routing/capability/error contracts are tested; archive policies cover all supported formats; DocumentsProvider covers grants, dynamic roots, search/recent, copy/move/rename and failures; tests run in CI with clear debug/release results.
  - Complexity: M

- [ ] P1 — Add dependency provenance and Android upgrade gates
  - Why: The app spans security/protocol/archive libraries and includes JitPack; Android 15/16 storage, FGS, package-visibility, and permission rules continue to change. A current version string alone is not a safety policy.
  - Evidence: `settings.gradle.kts`; dependency declarations; Commons Compress/SSHJ/Room/WorkManager/Kotlin release notes; OSV/NVD advisories; Android 15/16 guidance; `RESEARCH.md` distribution analysis.
  - Touches: version catalogs or centralized dependency definitions, repository allowlist, dependency locking/scanning, release scripts, API-level test matrix, upgrade documentation.
  - Acceptance: Production repositories and versions are explicit and reviewable; resolved dependencies are scanned against OSV/NVD; SSH/archive/crypto updates have release notes; Android 15/16 checks cover FGS timeout, all-files/package visibility, exported components, predictive back, and storage fallbacks; advisories are triaged against the actual graph.
  - Complexity: M

- [ ] P1 — Add an Android permission and distribution readiness matrix
  - Why: `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, media/storage, notifications, and exported integration components require a feature-level rationale and fallback plan even when publication itself is operator-gated.
  - Evidence: `app/src/main/AndroidManifest.xml`; Android all-files, package-visibility, scoped-storage, sharing, large-screen, and Play policy guidance; `RESEARCH.md` permissions analysis.
  - Touches: manifest, backup rules, permission rationale UI, app-manager fallback, DocumentsProvider/exported components, README/release checks.
  - Acceptance: A checked-in matrix maps each permission/component to purpose, API range, fallback, user explanation, backup implication, and distribution status; least-privilege package queries are used where possible; the app handles denied permissions without crashes; publication submission remains outside the item until owner access/decisions unblock it.
  - Complexity: M

- [ ] P2 — Complete high-value network capability semantics
  - Why: Remote create, recursive size, search, and checksum currently have provider-specific gaps or empty defaults, which makes the same browser operation behave differently without explanation.
  - Evidence: `NetworkRepoAdapter`; SMB/SFTP/FTP/WebDAV adapters; RFC HTTP/WebDAV/FTP semantics; competitor patterns in Material Files, MiXplorer, and X-plore; `RESEARCH.md` Product Map.
  - Touches: `core:network`, `core:data` capabilities, remote progress/cancellation, browser/search/analyzer UI, contract tests.
  - Acceptance: Each protocol advertises supported operations and consistency/cost; implemented operations report progress and errors; unsupported operations are explicit; recursive size/search/checksum have cancellation and limits; no adapter returns a success-shaped zero/empty placeholder.
  - Complexity: L

- [ ] P2 — Add crash-consistent staging for multi-file operations
  - Why: Recursive copy/move/delete and archive extraction can leave ambiguous partial state after process death, cancellation, low storage, or a filesystem error.
  - Evidence: transfer/repository implementations; Android storage guidance; fsync/data-loss research; `RESEARCH.md` architecture root causes.
  - Touches: `core:data`, `core:storage`, transfer queue, temp/staging metadata, recovery UI, low-storage/failure-injection tests.
  - Acceptance: Multi-file jobs record intended and committed entries, use atomic rename where supported, preserve or clean staging data deterministically, expose partial completion, and recover or roll back according to the selected operation policy.
  - Complexity: L

- [ ] P2 — Add conflict preview and idempotent operation decisions
  - Why: Power-user file managers compete on copy/move workflows, but durable recovery is unsafe without explicit overwrite/skip/rename/keep-both decisions and a way to reapply them.
  - Evidence: Solid Explorer, MiXplorer, X-plore, and Total Commander workflow patterns; transfer service construction; `RESEARCH.md` Competitive Landscape and reliability analysis.
  - Touches: transfer/domain model, browser selection flow, conflict UI, persisted queue schema, automation intents, tests.
  - Acceptance: Conflicts show source/destination metadata before mutation; policies are persisted per job; repeated delivery is idempotent; keep-both names are deterministic; automation can choose a policy; cancellation leaves a clear result.
  - Complexity: M

- [ ] P2 — Expand DocumentsProvider and persisted-URI conformance
  - Why: The provider already validates canonical roots and symlinks, but dynamic roots, persisted grants, missing removable volumes, flags, and failure behavior are integration-critical surfaces.
  - Evidence: `app/.../FileDocumentsProvider.kt`, `DocumentPathPolicy`; Android DocumentsProvider and SAF guidance; `RESEARCH.md` Android integration assessment.
  - Touches: provider queries/flags, URI grant lifecycle, USB/removable-root handling, external picker tests, diagnostics.
  - Acceptance: Provider tests cover root removal/reappearance, persisted grant revocation, read/write/create/delete/rename/move/copy, search/recent, MIME/size/last-modified metadata, symlink exclusion, and correct provider flags; failures return contract-appropriate errors.
  - Complexity: M

- [ ] P2 — Improve bounded duplicate and storage analysis
  - Why: Storage analysis is a recurring competitor and adjacent-project pattern, and duplicate/similar detection can deliver value without adding another remote provider if it is cancellable and privacy-preserving.
  - Evidence: `feature/apps` analyzer surfaces; CleanSweep; Solid Explorer/X-plore patterns; `RESEARCH.md` Product Map and Under Consideration analysis.
  - Touches: analyzer/index model, background scheduling, checksum sampling/full-hash policy, browser selection actions, storage/privacy tests.
  - Acceptance: Analysis is opt-in, bounded, cancellable, resumable, excludes inaccessible roots cleanly, avoids retaining file contents, explains hash/collision tradeoffs, and offers safe review-before-delete actions.
  - Complexity: M

- [ ] P2 — Add release smoke coverage for exported surfaces and large screens
  - Why: FileExplorer has many exported or system-facing paths—DocumentsProvider, tile, FileProvider, share intents, package queries, notifications, Cast—and a broad Compose layout surface.
  - Evidence: `AndroidManifest.xml`; Android large-screen/sharing/package-visibility guidance; recent large-screen, Quick Share, DocumentsProvider, and intent commits; `RESEARCH.md` testability assessment.
  - Touches: build/release scripts, manifest smoke tests, emulator/device matrix, Compose UI tests, artifact checks.
  - Acceptance: A headless/invisible smoke lane installs the debug artifact, resolves exported components with intended permissions, exercises share/picker/intent entry points, checks notification/FGS behavior, and runs representative phone/tablet/RTL/font-scale layouts without user-session interference.
  - Complexity: M

- [ ] P2 — Add a provider and feature capability matrix to the product surface
  - Why: Users need to know whether search, checksums, secure delete, cloud sign-in, archive extraction, and write operations are verified, expensive, unavailable, or configuration-gated for the current location.
  - Evidence: `FileRepositoryFactory`, `NetworkRepoAdapter`, cloud status flow, secure-delete scope, `RESEARCH.md` Product Map and Architecture Assessment; competitor breadth patterns.
  - Touches: `core:model`, repository adapters, browser/search/analyzer UI, settings/about, diagnostics and tests.
  - Acceptance: A single capability model drives action enablement, explanatory text, automation validation, and diagnostics; statuses are provider/location-specific; no UI or README surface claims a capability that the model marks unavailable.
  - Complexity: M
