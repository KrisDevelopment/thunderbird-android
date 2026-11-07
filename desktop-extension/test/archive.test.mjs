import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { webcrypto } from "node:crypto";

const source = readFileSync(
  new URL("../experiments/migrationImporter/implementation.js", import.meta.url),
  "utf8",
);
const sandbox = {
  module: { exports: {} },
  ExtensionAPI: class {},
  crypto: webcrypto,
  TextEncoder,
  TextDecoder,
};
vm.runInNewContext(source, sandbox);
const { decryptArchive, buildAccountConfig } = sandbox.module.exports;

const constants = {
  authMethod: { none: 1, passwordCleartext: 3, passwordEncrypted: 4, OAuth2: 10 },
  socketType: { plain: 0, SSL: 2, alwaysSTARTTLS: 3 },
};

// Builds a container in the same layout as MigrationArchiveEncryption.kt:
// "TBAM", version (BE int), salt(16), nonce(12), ciphertext length (BE int), ciphertext+tag.
async function encryptArchive(archive, passphrase) {
  const salt = webcrypto.getRandomValues(new Uint8Array(16));
  const nonce = webcrypto.getRandomValues(new Uint8Array(12));
  const baseKey = await webcrypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  const key = await webcrypto.subtle.deriveKey(
    { name: "PBKDF2", salt, iterations: 600000, hash: "SHA-256" },
    baseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt"],
  );
  const ciphertext = new Uint8Array(
    await webcrypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce },
      key,
      new TextEncoder().encode(JSON.stringify(archive)),
    ),
  );
  const header = new Uint8Array(4 + 4 + 16 + 12 + 4);
  header.set(new TextEncoder().encode("TBAM"), 0);
  new DataView(header.buffer).setUint32(4, 1);
  header.set(salt, 8);
  header.set(nonce, 24);
  new DataView(header.buffer).setUint32(36, ciphertext.length);
  const out = new Uint8Array(header.length + ciphertext.length);
  out.set(header, 0);
  out.set(ciphertext, header.length);
  return out;
}

const sampleArchive = {
  format: "thunderbird-android-to-desktop",
  version: 1,
  createdAtUtc: "2026-11-03T00:00:00Z",
  accounts: [
    {
      id: "uuid-1",
      name: "Work",
      email: "me@example.com",
      incomingServer: {
        type: "imap",
        host: "imap.example.com",
        port: 993,
        connectionSecurity: "SSL_TLS_REQUIRED",
        authenticationType: "PLAIN",
        username: "me@example.com",
        password: "secret-value",
      },
      outgoingServer: {
        type: "smtp",
        host: "smtp.example.com",
        port: 465,
        connectionSecurity: "SSL_TLS_REQUIRED",
        authenticationType: "PLAIN",
        username: "me@example.com",
      },
      identities: [{ name: "Me", email: "me@example.com" }],
    },
  ],
};

test("decrypts an archive produced with the documented container format", async () => {
  const container = await encryptArchive(sampleArchive, "correct horse");
  const archive = await decryptArchive(container, "correct horse");
  assert.equal(archive.accounts[0].email, "me@example.com");
});

test("rejects a wrong passphrase", async () => {
  const container = await encryptArchive(sampleArchive, "correct horse");
  await assert.rejects(decryptArchive(container, "wrong"), /Wrong passphrase/);
});

test("rejects data without the TBAM magic", async () => {
  const container = await encryptArchive(sampleArchive, "pw");
  container[0] = 0x58;
  await assert.rejects(decryptArchive(container, "pw"), /Not a Thunderbird migration archive/);
});

test("rejects a container whose length does not match the header", async () => {
  const container = await encryptArchive(sampleArchive, "pw");
  await assert.rejects(decryptArchive(container.subarray(0, container.length - 1), "pw"), /length does not match/);
});

test("maps a PLAIN SSL account onto Desktop auth and socket types", () => {
  const config = buildAccountConfig(sampleArchive.accounts[0], constants);
  assert.equal(config.incoming.type, "imap");
  assert.equal(config.incoming.auth, constants.authMethod.passwordCleartext);
  assert.equal(config.incoming.socketType, constants.socketType.SSL);
  assert.equal(config.incoming.password, "secret-value");
  assert.equal(config.outgoing.type, "smtp");
  assert.equal(config.outgoing.password, "");
  assert.equal(config.identity.emailAddress, "me@example.com");
});

test("maps OAuth2 accounts without carrying a token", () => {
  const account = structuredClone(sampleArchive.accounts[0]);
  account.incomingServer.authenticationType = "XOAUTH2";
  account.incomingServer.password = "non-transferable-token";
  account.outgoingServer.authenticationType = "XOAUTH2";
  account.outgoingServer.password = "non-transferable-token";
  const config = buildAccountConfig(account, constants);
  assert.equal(config.incoming.auth, constants.authMethod.OAuth2);
  assert.equal(config.incoming.password, "");
  assert.equal(config.outgoing.password, "");
});

test("refuses client certificate accounts instead of importing them broken", () => {
  const account = structuredClone(sampleArchive.accounts[0]);
  account.incomingServer.authenticationType = "EXTERNAL";
  assert.throws(() => buildAccountConfig(account, constants), /Client certificate/);
});

test("rejects unsupported JSON document versions before account creation", async () => {
  const container = await encryptArchive({ ...sampleArchive, version: 2 }, "pw");
  await assert.rejects(decryptArchive(container, "pw"), /Unsupported archive document/);
});

test("rejects invalid server ports and hosts", () => {
  const account = structuredClone(sampleArchive.accounts[0]);
  account.incomingServer.port = 0;
  assert.throws(() => buildAccountConfig(account, constants), /Invalid server/);
  account.incomingServer.port = 993;
  account.incomingServer.host = "imap.example.com/other";
  assert.throws(() => buildAccountConfig(account, constants), /Invalid server/);
});
