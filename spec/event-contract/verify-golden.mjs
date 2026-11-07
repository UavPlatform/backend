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
console.log("node verified " + doc.vectors.length + " draft vectors; Kotlin has not verified this contract");
