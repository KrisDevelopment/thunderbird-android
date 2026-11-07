/* eslint-disable no-undef */
"use strict";

if (typeof Components !== "undefined") {
  Components.utils.importGlobalProperties(["crypto", "TextEncoder", "TextDecoder", "atob"]);
}

// Privileged Experiment API (parent process) that imports a Thunderbird for Android migration
// archive (.tbam) into Thunderbird Desktop. Accounts are created through Thunderbird's own
// account creation code (CreateInBackend), so servers, identities and default account handling
// follow the same path as the Account Hub.

const TBAM_MAGIC = "TBAM";
const TBAM_VERSION = 1;
const TBAM_FORMAT = "thunderbird-android-to-desktop";
const SALT_SIZE = 16;
const NONCE_SIZE = 12;
const HEADER_SIZE = 4 + 4 + SALT_SIZE + NONCE_SIZE + 4;
const PBKDF2_ITERATIONS = 600000;

function readUint32(bytes, offset) {
  return ((bytes[offset] << 24) >>> 0) + (bytes[offset + 1] << 16) + (bytes[offset + 2] << 8) + bytes[offset + 3];
}

async function decryptArchive(containerBytes, passphrase) {
  const bytes = new Uint8Array(containerBytes);
  if (bytes.length < HEADER_SIZE) {
    throw new Error("Archive is too short");
  }
  for (let i = 0; i < TBAM_MAGIC.length; i++) {
    if (bytes[i] !== TBAM_MAGIC.charCodeAt(i)) {
      throw new Error("Not a Thunderbird migration archive");
    }
  }
  const version = readUint32(bytes, 4);
  if (version !== TBAM_VERSION) {
    throw new Error("Unsupported archive version: " + version);
  }
  let offset = 8;
  const salt = bytes.slice(offset, offset + SALT_SIZE);
  offset += SALT_SIZE;
  const nonce = bytes.slice(offset, offset + NONCE_SIZE);
  offset += NONCE_SIZE;
  const ciphertextLength = readUint32(bytes, offset);
  offset += 4;
  if (ciphertextLength < 16 || ciphertextLength !== bytes.length - offset) {
    throw new Error("Archive length does not match its header");
  }
  const ciphertext = bytes.subarray(offset);

  const passphraseKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(passphrase),
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  const key = await crypto.subtle.deriveKey(
    { name: "PBKDF2", salt, iterations: PBKDF2_ITERATIONS, hash: "SHA-256" },
    passphraseKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
  let plaintext;
  try {
    plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce }, key, ciphertext);
  } catch (e) {
    throw new Error("Wrong passphrase, or the archive is corrupted");
  }
  let archive;
  try {
    archive = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
  } finally {
    new Uint8Array(plaintext).fill(0);
  }
  if (archive.format !== TBAM_FORMAT) {
    throw new Error("Unexpected archive format: " + archive.format);
  }
  if (archive.version !== TBAM_VERSION || !Array.isArray(archive.accounts)) {
    throw new Error("Unsupported archive document version or account list");
  }
  return archive;
}

function mapAuthType(authenticationType, authMethod) {
  switch (authenticationType) {
    case "NONE": return authMethod.none;
    case "PLAIN": return authMethod.passwordCleartext;
    case "CRAM_MD5": return authMethod.passwordEncrypted;
    case "XOAUTH2": return authMethod.OAuth2;
    default: return null;
  }
}

function mapSocketType(connectionSecurity, socketType) {
  switch (connectionSecurity) {
    case "NONE": return socketType.plain;
    case "STARTTLS_REQUIRED": return socketType.alwaysSTARTTLS;
    case "SSL_TLS_REQUIRED": return socketType.SSL;
    default: return null;
  }
}

// Translates one archive account into the config shape used by CreateInBackend.
// Throws when the account uses something that cannot be carried over (e.g. client certificates).
function buildAccountConfig(account, constants) {
  const incoming = account.incomingServer;
  const outgoing = account.outgoingServer;
  if (!incoming || !outgoing || outgoing.type !== "smtp") {
    throw new Error("Incoming and SMTP server settings are required");
  }
  for (const server of [incoming, outgoing]) {
    if (typeof server.host !== "string" || !server.host || /[\s/\\\x00-\x1f]/.test(server.host) ||
        typeof server.username !== "string" || !Number.isInteger(server.port) ||
        server.port < 1 || server.port > 65535) {
      throw new Error("Invalid server host, username or port");
    }
  }
  if (incoming.type !== "imap" && incoming.type !== "pop3") {
    throw new Error("Unsupported incoming server type: " + incoming.type);
  }
  const inAuth = mapAuthType(incoming.authenticationType, constants.authMethod);
  const outAuth = mapAuthType(outgoing.authenticationType, constants.authMethod);
  const inSocket = mapSocketType(incoming.connectionSecurity, constants.socketType);
  const outSocket = mapSocketType(outgoing.connectionSecurity, constants.socketType);
  if (inAuth === null || outAuth === null) {
    throw new Error("Client certificate sign-in cannot be imported; set up this account manually");
  }
  if (inSocket === null || outSocket === null) {
    throw new Error("Unsupported connection security setting");
  }
  const firstIdentity = (account.identities || [])[0] || {};

  return {
    displayName: account.name || account.email,
    rememberPassword: true,
    identity: {
      realname: firstIdentity.name || account.name || "",
      emailAddress: account.email,
    },
    incoming: {
      type: incoming.type,
      hostname: incoming.host,
      port: incoming.port,
      username: incoming.username,
      password: incoming.authenticationType === "XOAUTH2" ? "" : incoming.password || "",
      auth: inAuth,
      socketType: inSocket,
      checkInterval: 10,
      loginAtStartup: true,
      leaveMessagesOnServer: true,
      daysToLeaveMessagesOnServer: 14,
      deleteOnServerWhenLocalDelete: false,
      deleteByAgeFromServer: false,
      downloadOnBiff: false,
    },
    outgoing: {
      type: "smtp",
      hostname: outgoing.host,
      port: outgoing.port,
      username: outgoing.username,
      password: outgoing.authenticationType === "XOAUTH2" ? "" : outgoing.password || "",
      auth: outAuth,
      socketType: outSocket,
      addThisServer: true,
      useGlobalPreferredServer: false,
    },
  };
}

function needsPassword(authenticationType) {
  return authenticationType === "PLAIN" || authenticationType === "CRAM_MD5";
}

async function importArchive(base64Archive, passphrase) {
  const { MailServices } = ChromeUtils.importESModule("resource:///modules/MailServices.sys.mjs");
  const { CreateInBackend } = ChromeUtils.importESModule(
    "resource:///modules/accountcreation/CreateInBackend.sys.mjs",
  );
  const { AccountConfig } = ChromeUtils.importESModule(
    "resource:///modules/accountcreation/AccountConfig.sys.mjs",
  );
  const constants = {
    authMethod: {
      none: Components.interfaces.nsMsgAuthMethod.none,
      passwordCleartext: Components.interfaces.nsMsgAuthMethod.passwordCleartext,
      passwordEncrypted: Components.interfaces.nsMsgAuthMethod.passwordEncrypted,
      OAuth2: Components.interfaces.nsMsgAuthMethod.OAuth2,
    },
    socketType: {
      plain: Components.interfaces.nsMsgSocketType.plain,
      SSL: Components.interfaces.nsMsgSocketType.SSL,
      alwaysSTARTTLS: Components.interfaces.nsMsgSocketType.alwaysSTARTTLS,
    },
  };

  const binary = atob(base64Archive);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  const archive = await decryptArchive(bytes, passphrase);

  const result = { importedAccounts: 0, skippedAccounts: 0, warnings: [] };
  for (const account of archive.accounts || []) {
    const label = account.email || "(unknown account)";
    try {
      const incoming = account.incomingServer;
      const exists = MailServices.accounts.findServer(
        incoming.username, incoming.host, incoming.type, incoming.port,
      );
      if (exists) {
        result.skippedAccounts++;
        result.warnings.push(label + ": already exists in Thunderbird Desktop, skipped.");
        continue;
      }

      const config = Object.assign(new AccountConfig(), buildAccountConfig(account, constants));
      const created = await CreateInBackend.createAccountInBackend(config);
      result.importedAccounts++;

      const identities = (account.identities || []).slice(1);
      for (const ident of identities) {
        const identity = MailServices.accounts.createIdentity();
        identity.fullName = ident.name || "";
        identity.email = ident.email || account.email;
        identity.replyTo = ident.replyTo || "";
        identity.valid = true;
        created.addIdentity(identity);
      }
      if (identities.length > 0) {
        result.warnings.push(label + ": extra identities imported without signatures.");
      }

      if (incoming.authenticationType === "XOAUTH2") {
        result.warnings.push(label + ": sign in again when Thunderbird Desktop asks, OAuth tokens are not transferred.");
      } else if (needsPassword(incoming.authenticationType) && !incoming.password) {
        result.warnings.push(label + ": no password in the archive, enter it on first connection.");
      }
    } catch (e) {
      result.warnings.push(label + ": " + e.message);
    }
  }
  return result;
}

this.migrationImporter = class extends ExtensionAPI {
  getAPI(context) {
    return {
      migrationImporter: {
        importArchive(base64Archive, passphrase) {
          return importArchive(base64Archive, passphrase);
        },
      },
    };
  }
};

if (typeof module !== "undefined") {
  module.exports = { decryptArchive, buildAccountConfig, mapAuthType, mapSocketType };
}
