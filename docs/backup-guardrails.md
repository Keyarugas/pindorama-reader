# Conventional backup guardrails (0.4.1)

The `.tachibk` payload remains GZIP-compressed Protobuf. Raw Protobuf remains readable.
No encryption, schema change or transactional restore is introduced.

## Export policy

`BackupCreator` obtains the execution snapshot, including read nonfavorites only when
selected. `ConventionalBackupPolicy` rejects it if library entries are selected and any
included work is private. The same creator handles manual and automatic jobs. It does
not consult the private session, filter works out, or change their private flags.
Settings-only exports and exports that do not include private works remain available.
The warning still applies: settings, categories and credentials can themselves be sensitive.

A blocked automatic job posts a fixed explanation without titles or private counts.
This version has no protected-backup configuration UI; the message explicitly says so.
It does not update the last-success timestamp, prune old copies or open a backup output.
The Android document picker may already have created an empty manual destination.

## Publication and retention

Output is closed and decoded successfully before success is recorded and retention runs.
Automatic names use a UUID suffix to avoid overwriting a same-minute predecessor.
Retention recognizes both historical and suffixed names, preserving the new copy and
the three newest other matching files. Nonmatching files are not pruned.
Nonempty manual destinations are rejected instead of being overwritten/deleted.

Cancellation is checked between writes, while reading, before validation completion and
before pruning each old file. Failure before validation attempts to delete only the new
output. A retention failure never deletes the validated new copy.

There is no atomic-publication guarantee across SAF providers. Process death, failed
deletion, deferred provider writes or revoked access may leave a partial file. Such a file
is not recorded as a successful backup. Prior copies are preserved until validation.
The app cannot guarantee provider durability or erase remote versions. Restore remains
incremental: canceling restoration can leave already restored data, as before.

## Diagnostics

Errors use a fixed operation/error vocabulary and localized generic UI descriptions.
No exception message, cause, preference key, URI, title or repository name is interpolated.
Restore reports contain timestamps and these codes in internal cache, shared only on
explicit request. Existing reports from older versions are not retrospectively sanitized.
All backup/restore notifications are classified as private, including incoming backups
on an empty local library, so progress/counts cannot precede local private classification.
Restore progress contains no titles. Missing-component warnings do not display strings
supplied by the backup. Actual backup/restored values are not sanitized or rewritten.

## Reading budgets and residual denial-of-service risk

The reader caps consumed input at 128 MiB and expanded Protobuf at 64 MiB, reading in
8 KiB chunks and checking cancellation. The input allowance accommodates compression
overhead; expanded bytes, not compression ratio, decide the main budget. At most one
extra byte is requested at a boundary to distinguish exact-size EOF from overflow.
Creation also checks serialized size before opening output, to avoid producing a file
that this reader would reject. This does not bound allocations during serialization.

64 MiB is an explicit initial resource budget, not a claimed universal library maximum:
the current decoder holds bytes and allocates an object graph, and converting the Okio
buffer adds another byte representation. Leaving that expansion unbounded is unsafe.
Tests cover exact boundaries, default-budget GZIP expansion overflow, malformed and
truncated input, raw legacy fixtures, and a synthetic library of 10,000 works / 200,000
chapters with descriptions (over 10 MiB serialized).

Legitimate files above the budget are rejected with a generic limit message; they are
not modified and must not be discarded. This budget does not guarantee safety against
all hostile object counts, large per-record allocations or low-memory devices. No real
user backup was collected to claim a universal threshold. Follow-up work should measure
peak heap on supported devices using synthetic distributions (especially chapter-heavy
libraries), then add record/allocation budgets and a reviewed streaming parser before
raising the ceiling. Do not automatically retry an oversized file without limits.

## Manual acceptance (synthetic data only)

- Public library: warning is visible, export succeeds, resulting `.tachibk` restores.
- Mark one included work private: manual export is blocked both locked and unlocked.
- Read private nonfavorite: included/blocked with read entries, excluded only by the
  explicit read-entries option; settings-only export remains possible.
- Automatic job: generic protection notice at normal/discreet/private notification
  levels, unchanged last-success timestamp and unchanged previous backups.
- Public automatic job: after successful validation, retain four matching copies.
- Revoke provider access / fill destination / stop creation: no older copy is removed
  before validation; no success notification for failure. Inspect provider leftovers.
- Select a nonempty output: reject it without writing or deleting it.
- Restore invalid, truncated and oversized synthetic files: generic error, no URI or
  embedded secrets in UI/logcat. Test old compressed and raw Protobuf imports.
- Cause a synthetic entry/preference failure: inspect internal error report and shared
  report, verifying fixed codes only. Restore progress must not show work titles.
- Repeat with a local Documents provider and a cloud provider; test process death.

Unit tests do not establish SAF durability or notification permission delivery on a
physical device. Those checks remain part of manual acceptance.

## Changed files

- `app/src/main/java/eu/kanade/presentation/more/settings/screen/data/CreateBackupScreen.kt`
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/data/RestoreBackupScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupDecoder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupError.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupFileValidator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/BackupNotifier.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/ConventionalBackupReader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreateJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupCreator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/BackupPublication.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/create/ConventionalBackupPolicy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestoreJob.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacy.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacyPolicy.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/BackupCreatorGuardrailsTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/BackupGuardrailsTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/backup/ConventionalBackupReaderTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/data/notification/NotificationPrivacyPolicyTest.kt`
- `docs/backup-guardrails.md`
- `i18n/src/commonMain/moko-resources/base/strings.xml`
- `i18n/src/commonMain/moko-resources/pt-rBR/strings.xml`
