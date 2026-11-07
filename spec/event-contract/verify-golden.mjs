import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const doc = JSON.parse(readFileSync(new URL("./golden-vectors.json", import.meta.url), "utf8"));
const anchorDomain = Buffer.from("UAV-EVENT-ANCHOR-v1\n", "utf8");
const packetDomain = Buffer.from("UAV-EVENT-PACKET-v1\n", "utf8");

function sha(...parts) {
  const hash = createHash("sha256");
  for (const part of parts) hash.update(part);
  return hash.digest("hex");
}

const identity = Buffer.from(doc.identityCanonicalUtf8, "utf8");
const anchor = sha(anchorDomain, identity);
if (anchor !== doc.anchorHex) throw new Error("anchor " + anchor);

for (const vector of doc.vectors) {
  const input = Buffer.from(vector.inputUtf8Hex, "hex");
  const canonical = Buffer.from(vector.canonicalUtf8Hex, "hex");
  const without = Buffer.from(vector.withoutChainHashHex, "hex");
  if (sha(canonical) !== vector.contentDigestHex) throw new Error(vector.name + " contentDigest");
  if (sha(input) !== vector.rawSha256Hex) throw new Error(vector.name + " rawSha256");
  const prev = canonical.toString("utf8").match(/"prevChainHash":"([0-9a-f]{64})"/);
  if (!prev) throw new Error(vector.name + " prev");
  const chain = sha(packetDomain, Buffer.from(prev[1], "hex"), without);
  if (chain !== vector.chainHashHex) throw new Error(vector.name + " chainHash " + chain);
  if (vector.anchorHex !== doc.anchorHex && vector.name !== "other-airframe-product-serial") {
    throw new Error(vector.name + " anchor");
  }
}
const spaced = doc.vectors.find((vector) => vector.name === "height1-same-canonical-different-bytes");
if (spaced.rawSha256Hex === spaced.contentDigestHex) throw new Error("raw digest was mixed into contentDigest");

function canonicalString(text) {
  let out = "\"";
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    if (ch === "\"") out += "\\\"";
    else if (ch === "\\") out += "\\\\";
    else if (ch === "\b") out += "\\b";
    else if (ch === "\f") out += "\\f";
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (cp < 0x20) out += "\\u" + cp.toString(16).padStart(4, "0");
    else out += ch;
  }
  return out + "\"";
}

function canonicalValue(value) {
  if (value === null) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) throw new Error("number");
    return String(value);
  }
  if (typeof value === "string") return canonicalString(value);
  if (Array.isArray(value)) return "[" + value.map(canonicalValue).join(",") + "]";
  const keys = Object.keys(value).sort();
  return "{" + keys.map((key) => canonicalString(key) + ":" + canonicalValue(value[key])).join(",") + "}";
}

if (!Array.isArray(doc.canonicalFixtures) || doc.canonicalFixtures.length === 0) {
  throw new Error("missing non-ASCII canonical fixtures");
}
for (const fixture of doc.canonicalFixtures) {
  const input = Buffer.from(fixture.inputUtf8Hex, "hex");
  const expected = Buffer.from(fixture.canonicalUtf8Hex, "hex");
  const produced = Buffer.from(canonicalValue(JSON.parse(input.toString("utf8"))), "utf8");
  if (!produced.equals(expected)) throw new Error(fixture.name + " node canonical mismatch");
  if (!expected.includes(Buffer.from([0xe2, 0x80, 0xa8])) || !expected.includes(Buffer.from([0xe2, 0x80, 0xa9]))) {
    throw new Error(fixture.name + " lost U+2028/U+2029");
  }
  if (expected.includes(Buffer.from("\\u2028")) || expected.includes(Buffer.from("\\u2029"))) {
    throw new Error(fixture.name + " escaped a line separator");
  }
}
console.log("node verified " + doc.vectors.length + " draft vectors and "
    + doc.canonicalFixtures.length + " non-ASCII fixtures; an offline vector match is not HTTP interop, and this contract is not frozen");
