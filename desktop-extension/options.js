/* global browser */
"use strict";

const statusElement = document.getElementById("status");
const archiveInput = document.getElementById("archive");
const passphraseInput = document.getElementById("passphrase");
const importButton = document.getElementById("import");

function showStatus(text) {
  statusElement.textContent = text;
}

function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

importButton.addEventListener("click", async () => {
  const file = archiveInput.files[0];
  if (!file) {
    showStatus("Choose a .tbam file first.");
    return;
  }
  if (!passphraseInput.value) {
    showStatus("Enter the passphrase used for the export.");
    return;
  }

  showStatus("Importing...");
  importButton.disabled = true;
  try {
    const base64Archive = arrayBufferToBase64(await file.arrayBuffer());
    const result = await browser.migrationImporter.importArchive(base64Archive, passphraseInput.value);
    const lines = [
      "Imported " + result.importedAccounts + " account(s), skipped " + result.skippedAccounts + ".",
      ...result.warnings.map((warning) => "- " + warning),
    ];
    showStatus(lines.join("\n"));
  } catch (e) {
    showStatus("Import failed: " + e.message);
  } finally {
    passphraseInput.value = "";
    importButton.disabled = false;
  }
});
