# Thunderbird Desktop Migration Importer

Imports a `.tbam` archive exported by Thunderbird for Android into Thunderbird Desktop (140+).

Step-by-step instructions (export, build, test profile, import, OAuth re-authorization) are in
[`docs/engineering/migration-howto.md`](../docs/engineering/migration-howto.md).

## Layout

- `manifest.json`: WebExtension manifest; declares the `migrationImporter` Experiment API and the options page.
- `options.html`, `options.js`: import UI (file picker and passphrase).
- `experiments/migrationImporter/schema.json`: API schema exposed as `browser.migrationImporter`.
- `experiments/migrationImporter/implementation.js`: privileged parent code. Decrypts the archive and creates
  accounts through Thunderbird's `CreateInBackend` module.
- `test/archive.test.mjs`: Node tests for decryption and account mapping.

## Tests

```sh
node --test test/archive.test.mjs
```

## Limitations

- OAuth tokens are not transferred; OAuth2 accounts sign in again on Desktop.
- Client-certificate accounts are not imported.
- Signatures and folder name mappings are not imported; local mail is not migrated.
