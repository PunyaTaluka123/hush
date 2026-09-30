// PINs (SPEC §7, I7). Stored only as argon2id(pin, per-user salt), labelled
// real/duress in a server-only doc. The phone never sends a PIN again after
// setPins: it sends proof = HMAC-SHA256(key = session nonce,
// msg = argon2id(pin, salt) | idempotency_key | purpose | incident_id), which
// is fixed-size whatever was typed. The idempotency key is single-use, so a
// captured proof cannot be replayed.
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { argon2id } from "hash-wasm";
import type { PinResult } from "./engine";

// OWASP minimum for argon2id. The phone must use the same parameters (app-android PinCrypto).
export const ARGON = { iterations: 2, memorySize: 19456, parallelism: 1, hashLength: 32 };

export interface PinDoc { salt: Buffer; real: Buffer; duress: Buffer; nonce: Buffer }

export const validPins = (real: unknown, duress: unknown) =>
  typeof real === "string" && typeof duress === "string" && /^\d{4,6}$/.test(real) && /^\d{4,6}$/.test(duress) &&
  real.length === duress.length && real !== duress;

export async function hashPin(pin: string, salt: Buffer): Promise<Buffer> {
  return Buffer.from(await argon2id({ password: pin, salt, ...ARGON, outputType: "binary" }));
}

export async function makePins(real: string, duress: string): Promise<PinDoc> {
  const salt = randomBytes(16);
  return { salt, real: await hashPin(real, salt), duress: await hashPin(duress, salt), nonce: randomBytes(16) };
}

export const proofMsg = (key: string, purpose: string, incident: string) => `|${key}|${purpose}|${incident}`;

export function proof(hash: Buffer, nonce: Buffer, msg: string): Buffer {
  return createHmac("sha256", nonce).update(hash).update(msg).digest();
}

// Constant time, and both comparisons always run.
export function verify(p: PinDoc, got: Buffer, msg: string): PinResult {
  if (got.length !== 32) return "wrong";
  const r = timingSafeEqual(proof(Buffer.from(p.real), Buffer.from(p.nonce), msg), got);
  const d = timingSafeEqual(proof(Buffer.from(p.duress), Buffer.from(p.nonce), msg), got);
  return r ? "real" : d ? "duress" : "wrong";
}
