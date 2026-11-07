# Thunderbird Android → Desktop Migration

This document describes the research, architecture, migration archive format and
implementation status of the Android→Desktop migration feature. It is an experimental, AI-assisted fork
feature, not an upstream-ready contribution.

## 1. Android platform research

### Account configuration storage

Accounts are represented by `net.thunderbird.core.android.account.LegacyAccount` and managed
through `LegacyAccountManager` (`core/android/account`). Persistence is handled by
`LegacyAccountStorageHandler` (`feature/account/storage/legacy`) which serializes accounts to the
K-9/Thunderbird settings XML store (`StorageProvider`). Each account has a UUID (`AccountId`).

### IMAP/SMTP configuration

`LegacyAccount.incomingServerSettings` and `outgoingServerSettings` are `com.fsck.k9.mail.ServerSettings`
containing `type`, `host`, `port`, `connectionSecurity`, `authenticationType`, `username`,
`password`, `clientCertificateAlias`, `extra` (protocol-specific extras such as IMAP prefix, etc.).

### Identities and aliases

`LegacyAccount.identities: List<Identity>` (`core/android/account/Identity`). Identity settings
(name, email, signature, etc.) are stored under per-identity preference keys. `SettingsExporter`
writes them under the `identities` element.

### Password storage and encryption

Passwords are available **in plaintext** through `ServerSettings.password` and the
`StorageProvider` inside Android's app-private storage. The ordinary `.k9s` settings export excludes
passwords; the migration `.tbam` encrypts available passwords with the chosen passphrase.
There is no Android Keystore wrapping of account passwords in the
current codebase; `clientCertificateAlias` references a KeyStore entry for client certificates.

### OAuth tokens

`LegacyAccount.oAuthState: String?` holds the serialized OAuth state (refresh/access tokens as
produced by AppAuth). `AuthType.XOAUTH2` is used for IMAP/SMTP. Refresh tokens are bound to the
Android app's OAuth client registration (client id/secret) and are therefore **not** directly
transferable to Thunderbird Desktop's OAuth client registration. The migration marks OAuth
credentials as non-transferable and requires reauthorization on Desktop. The published exporter strips
serialized OAuth state, access/refresh tokens, and OAuth password fields before encryption.

### Android Keystore dependencies

Only client certificates (`clientCertificateAlias`) depend on the Android KeyStore. Account
passwords are not Keystore-protected. Exporting Keystore-protected material is not possible
without the private key; aliases are carried as references only.

### Certificate storage

Client certificate aliases are stored in `ServerSettings.clientCertificateAlias`. The actual
certificates live in the Android KeyStore / user certificate store and are not exported.

### Folder configuration and sync settings

Per-account folder settings (integrate, sync, push, notifications, visibility) are stored via
`FolderSettingsProvider` and exported by `SettingsExporter` under the `folders` element.

### Local mail storage

Local messages and attachments live in the per-account mail store (SQLite + attachment files)
managed by `com.fsck.k9.mailstore` (legacy). Exporting them is optional and large; the migration
archive supports an `includeLocalMail` flag but full message export is left as a follow-up.

### Address books

Not part of account configuration; out of scope for this phase.

## 2. Desktop platform research

### Account creation APIs

Thunderbird Desktop exposes account management through `MailServices.accounts`
(`nsIMsgAccountManager`): `createAccount()`, `createIncomingServer(...)`, `createIdentity()`.
These are XPCOM APIs available only to privileged (chrome) code, i.e. an Experiment API, not to
plain MailExtension content scripts.

### Password manager

Passwords are stored in Thunderbird's Login Manager (`nsILoginManager`). An Experiment API can
call `Services.logins.addLogin(...)`.

### OAuth handling

Thunderbird Desktop manages OAuth internally per account (`oauth2`) and requires its own client
registration. Tokens from Android cannot be reused; reauthorization is required.

### Certificate handling

Client certificates are imported into Thunderbird's certificate database via `nsIX509CertDB`;
out of scope for v1.

### Preferences

Account/server preferences are set through the account objects; global prefs via
`Services.prefs`.

### Conclusion on extension vs fork

A MailExtension alone cannot create accounts. An Experiment API (privileged) is required. No
Desktop fork is needed; the Experiment API provides sufficient access.

## 3. Migration archive format

- Encrypted container (TBAM) wrapping a JSON document (see `MigrationArchive`).
- Encryption: AES-256-GCM, key derived via PBKDF2-HMAC-SHA256 (600000 iterations).
- Passphrase provided by the user; never stored.
- OAuth tokens are never transferred (marked non-transferable); reauthorization required.
- Keystore-protected credentials (client certs) are carried as aliases only.

Archive model: `feature/migration/export/.../MigrationArchive.kt`.
Inner JSON schema: `schemas/migration-archive.schema.json`.
The Desktop Experiment API schema in `desktop-extension/experiments/.../schema.json` describes API calls,
not the archive format.

## 4. Android implementation

New module `feature/migration/export` provides:
- `MigrationArchive` / related serializable models.
- `MigrationArchiveEncryption` (AES-GCM + PBKDF2).
- `DefaultMigrationArchiveExporter` (reads accounts, builds archive, encrypts).
- `MigrationExportViewModel` + `MigrationExportScreen` (Compose, MVI) with SAF save.

Module is wired into `featureMigrationModule` for Thunderbird builds.

## 5. Desktop implementation

`desktop-extension/` is a Thunderbird WebExtension (options page as the import UI) that uses an
Experiment API (`migrationImporter`) to:

- decrypt the archive in the privileged parent process (WebCrypto, same parameters as the Kotlin exporter),
- map each account to the config shape used by Thunderbird's `CreateInBackend` module, which creates the
  incoming server, outgoing server, identity and account exactly as the Account Hub does.

Mapping: `PLAIN` → cleartext password, `CRAM_MD5` → encrypted password, `XOAUTH2` → OAuth2 (no token),
`NONE` → no auth, `EXTERNAL` (client certificate) → refused with a message. Connection security maps
`NONE`/`STARTTLS_REQUIRED`/`SSL_TLS_REQUIRED` to plain/STARTTLS/SSL.

Targets Thunderbird 140 (`strict_min_version` 140.0). How-to: `docs/engineering/migration-howto.md`.

## 6. Security notes

- No plaintext credentials written to temp files.
- Passphrase held in memory only; wiped best-effort after export.
- OAuth tokens not transferred.
- Android Keystore aliases carried as references only.

## 7. Known limitations

- Local Android SDK is not installed; use `Dockerfile.android` and the existing `build-android-apk.sh`.
  Exact verification results are recorded in the [how-to](migration-howto.md).
- Local mail/attachments export not implemented (optional).
- Account settings map not yet populated from preference store (follow-up).
- Client certificates not exported.
- The user reported successful real-device export and Desktop import. This is a manual result, not a
  comprehensive compatibility or security audit; the new publication privacy hardening needs fresh export
  verification.
- Signatures and folder name mappings are not imported; extra identities are created without signatures.
- Local mail is not migrated.
- The Android feature has not adopted ADR-0009's API/internal split and still uses raw Material components
  instead of design-system components. Module restructuring was explicitly deferred by the fork owner.
  These are known `AGENTS.md` compliance gaps, not exceptions approved for upstream contributions.
- Imports are not transactional: a native backend failure may leave partial account/server configuration.
- This is not a signed Mozilla release or an audited password-backup product.
