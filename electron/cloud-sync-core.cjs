"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// mobile-bridge/portable/sync.ts
var sync_exports = {};
__export(sync_exports, {
  canonical: () => canonical,
  compactState: () => compactState,
  emptySync: () => emptySync,
  hash: () => hash,
  heads: () => heads,
  seal: () => seal,
  synchronize: () => synchronize,
  unseal: () => unseal
});
module.exports = __toCommonJS(sync_exports);
var emptySync = (device) => ({ version: 1, events: [], uploaded: [], baseline: {}, device });
function canonical(value) {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]";
  if (value && typeof value === "object") return "{" + Object.keys(value).sort().filter((k) => value[k] !== void 0).map((k) => JSON.stringify(k) + ":" + canonical(value[k])).join(",") + "}";
  return JSON.stringify(value);
}
var encoder = new TextEncoder();
async function hash(value) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value)))].map((n) => n.toString(16).padStart(2, "0")).join("");
}
function encode(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(s);
}
function decode(value) {
  return Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
}
async function aes(key) {
  const raw = decode(key);
  if (raw.length !== 32) throw new Error("Invalid sync encryption key");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}
async function seal(event, key) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const compressed = await new Response(new Blob([canonical(event)]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer();
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(`learnharness-sync-v1:${event.id}`) }, await aes(key), compressed);
  const blob = { version: 1, iv: encode(iv), data: encode(new Uint8Array(data)) };
  if (JSON.stringify(blob).length > 39e5) throw new Error("This session is too large to sync. The local copy is safe.");
  return blob;
}
async function unseal(id, blob, key) {
  if (blob?.version !== 1 || typeof blob.iv !== "string" || typeof blob.data !== "string" || blob.data.length > 39e5) throw new Error("Invalid encrypted revision");
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decode(blob.iv), additionalData: encoder.encode(`learnharness-sync-v1:${id}`) }, await aes(key), decode(blob.data));
  const reader = new Blob([plain]).stream().pipeThrough(new DecompressionStream("gzip")).getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 4e7) {
      await reader.cancel();
      throw new Error("Revision exceeds the expanded size limit");
    }
    chunks.push(value);
  }
  const event = JSON.parse(await new Blob(chunks.map((c) => new Uint8Array(c).buffer)).text());
  if (event.version !== 1 || event.id !== id || !/^[a-f0-9]{64}$/.test(id) || !/^(chat:[\w-]{1,160}|ledger:(nodes|chains|sessions|coverage))$/.test(event.entity) || !Array.isArray(event.parents) || event.parents.some((p) => typeof p !== "string" || !/^[a-f0-9]{64}$/.test(p)) || !event.payload) throw new Error("Invalid sync revision");
  return event;
}
function heads(events, entity) {
  const group = events.filter((e) => e.entity === entity);
  const parents = new Set(group.flatMap((e) => e.parents));
  return group.filter((e) => !parents.has(e.id)).sort((a, b) => a.id.localeCompare(b.id));
}
function compactState(state) {
  const parents = new Set(state.events.flatMap((e) => e.parents)), uploaded = new Set(state.uploaded);
  state.events = state.events.map((e) => parents.has(e.id) && uploaded.has(e.id) ? { ...e, payload: void 0 } : e);
  return state;
}
async function revision(entity, payload, parents, device, id) {
  return { version: 1, id: id ?? await hash(crypto.randomUUID()), entity, payload: structuredClone(payload), parents, device };
}
async function synchronize(documents, previous, key, host) {
  if (previous.version !== 1) throw new Error("Unsupported sync state");
  const state = structuredClone(previous);
  for (const entity of Object.keys(state.baseline)) if (entity.startsWith("chat:") && !(entity in documents) && heads(state.events, entity).some((e) => !e.payload._deleted)) {
    const payload = { _deleted: true, id: entity.slice(5) };
    state.events.push(await revision(entity, payload, heads(state.events, entity).map((e) => e.id), state.device));
    state.baseline[entity] = await hash(canonical(payload));
  }
  for (const [entity, payload] of Object.entries(documents)) {
    const fp = await hash(canonical(payload));
    if (fp !== state.baseline[entity]) {
      state.events.push(await revision(entity, payload, heads(state.events, entity).map((e) => e.id), state.device));
      state.baseline[entity] = fp;
    }
  }
  const save = () => host.saveState(compactState(state));
  await save();
  async function push() {
    const uploaded = new Set(state.uploaded);
    for (const event of state.events) if (!uploaded.has(event.id)) {
      await host.cloud({ op: "put", id: event.id, blob: await seal(event, key) });
      state.uploaded.push(event.id);
      uploaded.add(event.id);
      await save();
    }
  }
  await push();
  const known = new Set(state.events.map((e) => e.id));
  let cursor;
  do {
    const page = await host.cloud({ op: "list", ...cursor ? { cursor } : {} });
    if (!Array.isArray(page.ids) || page.ids.length > 1e3) throw new Error("Invalid sync index");
    for (const id of page.ids) if (!known.has(id)) {
      const { blob } = await host.cloud({ op: "get", id });
      const event = await unseal(id, blob, key);
      state.events.push(event);
      state.uploaded.push(id);
      known.add(id);
    }
    cursor = page.cursor;
  } while (cursor);
  const byId = new Map(state.events.map((e) => [e.id, e]));
  for (const e of state.events) for (const p of e.parents) if (!byId.has(p) || byId.get(p).entity !== e.entity) throw new Error("Sync history is incomplete. Retry later; local lessons were kept.");
  const merged = { ...documents };
  let conflicts = 0;
  for (const entity of new Set(state.events.map((e) => e.entity))) {
    const live = heads(state.events, entity);
    if (!live.length) throw new Error("Invalid revision history");
    const unique = live.filter((e, i) => live.findIndex((other) => canonical(other.payload) === canonical(e.payload)) === i).sort((a, b) => Number(Boolean(a.payload._deleted)) - Number(Boolean(b.payload._deleted)) || a.id.localeCompare(b.id));
    let payload = structuredClone(unique[0].payload);
    if (entity.startsWith("ledger:")) {
      payload = [...new Map(unique.flatMap((e) => e.payload).map((line) => [canonical(line), line])).values()];
    } else for (const conflict of unique.slice(1).filter((e) => !e.payload._deleted)) {
      const forkId = payload.id.slice(0, 80) + "_conflict_" + conflict.id.slice(0, 12);
      const forkEntity = "chat:" + forkId;
      if (!state.events.some((e) => e.entity === forkEntity)) {
        const fork = { ...structuredClone(conflict.payload), id: forkId, title: (conflict.payload.title + " (conflict copy)").slice(0, 500) };
        state.events.push(await revision(forkEntity, fork, [], conflict.device, await hash("fork:" + conflict.id)));
        merged[forkEntity] = fork;
        conflicts++;
      }
    }
    merged[entity] = payload;
    if (live.length > 1) state.events.push(await revision(entity, payload, live.map((e) => e.id), state.device));
  }
  await host.apply(merged);
  for (const [entity, payload] of Object.entries(merged)) state.baseline[entity] = await hash(canonical(payload));
  await save();
  await push();
  return { documents: merged, state, conflicts };
}
// Annotate the CommonJS export names for ESM import in node:
0 && (module.exports = {
  canonical,
  compactState,
  emptySync,
  hash,
  heads,
  seal,
  synchronize,
  unseal
});
