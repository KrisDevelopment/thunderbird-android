# How to move accounts from Thunderbird for Android to Thunderbird Desktop

This covers exporting an encrypted archive on the phone, building and installing the Desktop importer, and
importing. Design background is in `migration-android-to-desktop.md`.

> Experimental fork feature, developed with AI assistance. The Android API/internal split and
> design-system UI migration remain deferred; this branch is not upstream-ready. Back up important data
> before use. The importer creates accounts, not a full mail-profile backup.

## 0. Build and install the Android app

Prerequisites: Git, Docker with permission to run containers, and Android Platform Tools (`adb`) for USB
installation. The build downloads Android tooling/dependencies and accepts SDK licenses inside the container.
No host Android SDK is required.

```sh
git clone --branch feat/android-desktop-migration https://github.com/KrisDevelopment/thunderbird-android.git
cd thunderbird-android
./build-android-apk.sh
```

The existing script builds the Docker image and runs `:app-thunderbird:assembleFullDebug`. In an interactive
terminal, answer `y` to deploy to an authorized USB device. Noninteractive runs build only; no deployment
occurs. To deploy an already-built APK interactively:

```sh
./build-android-apk.sh --deploy-only
```

APK: `app-thunderbird/build/outputs/apk/full/debug/app-thunderbird-full-debug.apk`.
Package: `net.thunderbird.android.debug`, installed alongside the official app.

Keep `.signing/android-home/.android/debug.keystore` private and persistent. It enables update-compatible
builds, is ignored by Git, and must never be shared. Gradle's container cache is under `.gradle/container-home`.
If updating fails, the script asks before uninstalling; uninstalling deletes the debug app's accounts/settings.
The official release app must not be uninstalled.

### Get accounts into the debug app

The official app does not accept the privately signed debug app as a trusted cross-app importer. Use the
official app's ordinary settings export, then choose **Import K-9 settings → Select file** during debug-app
onboarding (or import through Settings).

`.k9s` does **not** include passwords. Enter missing passwords on the phone or later on Desktop. For an
incoming password prompt, select the account → Sync account → close the navigation drawer → Update Server
Settings. OAuth accounts need provider authorization. Do not root the device, spoof signatures, or try to read
the release app's private data.

Keep `.k9s` files private too: they expose account addresses and server configuration even without passwords.

## 1. Export on Android

1. Open Thunderbird for Android (debug build: `net.thunderbird.android.debug`).
2. Enter the passwords for every account first. The archive carries the passwords that are stored on the
   phone; accounts without one will ask for it on Desktop.
3. Go to **Settings → Backups → Export for Thunderbird Desktop**.
4. Select the accounts, enter a passphrase twice, and tap **Export**. Keep the passphrase, it is required for import
   and cannot be recovered.
5. The file is saved as `thunderbird-migration.tbam` (for example in `Downloads`). Copy it to the computer, for example:

   ```sh
    mkdir -p "$HOME/Downloads/thunderbird-migration"
    chmod 700 "$HOME/Downloads/thunderbird-migration"
    adb pull /sdcard/Download/thunderbird-migration.tbam "$HOME/Downloads/thunderbird-migration/"
    chmod 600 "$HOME/Downloads/thunderbird-migration/thunderbird-migration.tbam"
   ```

Export never includes OAuth refresh tokens. Client certificates are referenced by alias only, and the
certificate itself does not move. Gmail-style accounts must be authorized again on Desktop (see step 5).

## 2. Build the Desktop importer

The extension is plain WebExtension files. Test it with the existing Docker setup (Node 22):

```sh
docker run --rm -v "$PWD/desktop-extension:/work:ro" -w /work node:22-slim \
  node --test test/archive.test.mjs
```

Package it as an `.xpi` (zip) with only the runtime files:

```sh
cd desktop-extension
zip ../tb-migration-importer.xpi manifest.json options.html options.js \
  experiments/migrationImporter/implementation.js experiments/migrationImporter/schema.json
```

## 3. Install it in a test profile first

Use a separate Thunderbird profile so your real mail profile is not touched while testing.

1. Create the profile once:

   ```sh
    thunderbird -CreateProfile "tb-migration-test"
   ```

2. Start Thunderbird with that profile, in its own process:

   ```sh
    thunderbird -no-remote -offline -P tb-migration-test
   ```

3. Open **about:debugging#/runtime/this-thunderbird** (Add-ons Manager → gear icon → **Debug Add-ons**).
4. Click **Load Temporary Add-on…** and select `desktop-extension/manifest.json`.

Temporary add-ons are removed when Thunderbird quits; load it again each session. For permanent installation,
use Add-ons Manager → gear menu → Install Add-on From File and select the `.xpi`. Review the full-access
permission prompt: this Experiment API can create accounts and access the password manager. Enterprise policy
or a particular Thunderbird distribution may restrict experiments; do not disable security checks to bypass it.

## 4. Import the archive

1. In that Thunderbird window, open **Add-ons and Themes**, find **Thunderbird Android Migration Importer**, and open its
   **Preferences**. This opens the import page in a tab.
2. Choose the `.tbam` file, enter the export passphrase, and click **Import accounts**.
3. The status box reports how many accounts were created and skipped, with a warning per account that needs
   attention.
4. Confirm the accounts in Account Settings, then switch off **File → Offline → Work Offline** when ready to
   connect and authorize them. Do not send test messages without consent.

What happens per account:

- Incoming and outgoing servers, ports, connection security and usernames are created as Desktop servers.
- Passwords from the archive are stored through Thunderbird's login manager.
- Accounts already present in the profile (same incoming username, host, protocol and port) are skipped.
- Client-certificate accounts are not imported; set them up manually.

## 5. Re-authorize OAuth2 accounts (Gmail, Google Workspace)

Refresh tokens are not transferable, so each OAuth2 account has to sign in once on Desktop:

1. Start the imported account (for example, select the account and check for mail). Thunderbird opens the
   provider sign-in page.
2. Sign in with the account and approve access.
3. Repeat for each OAuth2 account. The import warnings list them by address.

If sign-in does not start, open the account's **Account Settings → Server Settings** and check that the
authentication method is OAuth2, then try again.

## 6. Password accounts with no password in the archive

If the archive has no password for an account, Desktop asks for it on the first connection. Enter it there. The
warning list shows which accounts are affected.

## Troubleshooting

- **"Wrong passphrase, or the archive is corrupted"**: the passphrase does not match the export, or the file was
  truncated. Export again, or copy the file again.
- **"Not a Thunderbird migration archive"** or **"Unsupported archive version"**: the file is not a `.tbam` produced by
  this build.
- **Extension page does not open or the import fails immediately**: open the Browser Console
  (`Ctrl+Shift+J`) and look for errors from `migrationImporter`.
- **Authentication failed after import**: check the server host and port in Account Settings. Outgoing (SMTP) passwords
  can differ from incoming passwords, so they may need separate entry.

## Security

- The archive is encrypted with AES-256-GCM. The key is derived from the passphrase with PBKDF2-HMAC-SHA256 (600000
  iterations). Keep the passphrase separate from the file.
- Plaintext appears only in memory during import, and is written into Desktop's password store.
- Keep the archive in private local storage and out of chat, issue attachments, Git and cloud sync. After a
  verified migration, deliberately remove unneeded copies from both devices.
- Git ignores `.tbam`, `.k9s`, `.xpi`, signing material and build caches. Do not bypass those ignores with
  `git add -f`.
- Earlier local prototype exports could include OAuth state inside the encrypted archive. The published
  exporter removes it and OAuth password fields before encryption; re-export using the hardened build if
  retaining a backup. Desktop ignores these fields in older archives, but that does not remove them from the file.
- Memory wiping is best effort. Managed-runtime strings cannot be reliably erased; no plaintext temporary
  credential files are created by the migration feature.

## Verification record — 2026-11-07

- Live-tested the Experiment API in Thunderbird 153.6.0 with an isolated offline profile and synthetic
  archives; the real mail profile was not modified.
- Confirmed native creation of nine IMAP/POP3 accounts with SSL, password/OAuth2 authentication and SMTP
  identity associations. A repeat-import test exposed duplicate detection using an obsolete hostname
  property; switching to the native `findServer()` API correctly skipped all nine existing accounts.
- Fixed the `CreateInBackend` namespace import, used a native `AccountConfig` instance, and imported WebCrypto
  globals explicitly in the Experiment scope. OAuth password fields are ignored rather than transferred.
- Nine Node tests pass, executed in the existing `node:22-slim` Docker image.
- `./gradlew test lint detekt spotlessCheck` could not start: the repository Gradle `fileHashes.lock` is not
  writable by the local user. No Android code was changed in this verification pass.
- The user subsequently reported successful real-device Android export and Desktop import. No real archive,
  passphrase, account address, device identifier or profile contents are part of this repository publication.

## Copy-paste OpenCode prompts

Run `opencode` in this checkout and paste one prompt below. OpenCode V2 also supports `opencode run "..."`
for noninteractive prompts, but interactive mode is preferable for device prompts and user-entered secrets.
These are prompts, not automatic approval to delete data, bypass security or upload private information.

### Build and install Android

```text
Read AGENTS.md and docs/engineering/migration-howto.md. Build this fork's Android APK using the existing
build-android-apk.sh; do not introduce another build toolchain. Check ADB authorization and identify the
target device before deploying. Never uninstall either Thunderbird app or clear its data without my explicit
approval. Keep signing keys and caches local. Let me enter passwords and OAuth credentials on the phone;
never request, log, screenshot or upload them. Report exact build/test results and known experimental gaps.
```

### Export from the phone

```text
Help me export accounts using Settings → Backup → Export for Thunderbird Desktop. Confirm which app and
accounts I intend to export. I will type the passphrase directly on the phone. Copy only the encrypted .tbam
to private local storage outside the checkout, set restrictive permissions, and do not decrypt it in a shell,
inspect credentials, upload it, or write plaintext temporary files. Keep OAuth tokens non-transferable and
explain which passwords will require re-entry. Do not delete any existing backup without asking.
```

### Build the Desktop plugin and import

```text
Read desktop-extension/README.md and docs/engineering/migration-howto.md. Test the importer in Docker and
package only its five runtime files into an XPI. Verify compatibility with my installed Thunderbird version
using its native APIs. Start in an isolated offline test profile; ask before loading into my real profile or
importing accounts there. Use the plugin import UI, not prefs.js or password-file editing. I will select the
private .tbam and type its passphrase in Thunderbird. Verify imported/skipped counts without reading secrets
or message bodies, then let me reauthorize OAuth accounts and enter missing passwords. Do not send email.
```

### Diagnose safely

```text
Diagnose the failed migration without exporting private data. Start with the UI's account counts/status and
synthetic fixtures. Do not enable protocol debug logging or collect full logs, screenshots, archives, profile
files, email addresses, passwords or OAuth state. If logs are necessary, ask first and redact them locally;
never publish raw logs. Fix only the migration-related defect, add a regression test, and report exact tests
run, failures and risks. Do not change module structure without explicit approval.
```

## Publication disclosure

Both the Android exporter and Desktop importer were developed and reviewed with AI assistance. Successful
manual use is not a security audit. This fork intentionally keeps the original prototype module structure
pending approval for ADR-0009/design-system compliance; it must not be presented as upstream-ready.
See the verification record and limitations before building or distributing it.

### Publication checks — 2026-11-07

Exact Android command (inside `tb-android-build` with the project, private signing directory and Gradle cache
bind-mounted as in `build-android-apk.sh`):

```sh
./gradlew :feature:migration:export:testDebugUnitTest :feature:migration:export:lint \
  :feature:migration:export:detekt :feature:migration:export:spotlessCheck \
  :app-thunderbird:assembleFullDebug --continue --console=plain
```

- Android unit tests: **6 passed** (2 credential-privacy tests and 4 authenticated-encryption tests).
- The privacy regression failed before the credential-stripping fix, then passed after it.
- Android module lint and Spotless: **passed**; lint still reports warnings.
- Thunderbird full debug APK: **built successfully**. The timestamp code now supports the declared minimum SDK.
- Detekt: **1 remaining failure**, `MigrationExportScreen` is 68 lines against the 60-line limit. Further UI
  refactoring was deferred at the owner's request; the combined command therefore exits unsuccessfully.
- Formatting command: `./gradlew :feature:migration:export:spotlessApply` (passed after splitting an overlong line).
- Desktop: `docker run --rm -v "$PWD/desktop-extension:/work:ro" -w /work node:22-slim node --test test/archive.test.mjs`
  — **9 passed**.
- `bash -n build-android-apk.sh`, changed JSON/XML parsing, and whitespace checks passed.
- Repository-wide `./gradlew test lint detekt spotlessCheck` was attempted on the host and could not start
  because the Docker-owned Gradle lock is not writable by the local user. The full suite is not claimed to pass.
- Instrumentation tests and K-9 Mail builds were not run; publication validates the Thunderbird full debug
  target and JVM migration tests only, without reinstalling apps on the user's device.
- Reviewed the publication files and new commit metadata for known private identifiers, credential patterns,
  profiles, archives, logs and signing material. None were included; commits use a GitHub no-reply email.

This is an experimental source publication, not an upstream PR or signed binary release. No private archive,
key, profile, log, screenshot, credential or generated APK/XPI is uploaded with it. The scan is not a guarantee
against every possible leak or a substitute for an independent security audit.
