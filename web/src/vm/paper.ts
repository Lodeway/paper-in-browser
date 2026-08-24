// Everything that talks to the JVM lives here: CheerpJ boot, the JS-implemented natives, the ops
// channel (file manager + console input), and the WebSocket tunnel that makes the in-page server
// reachable from real Minecraft clients.
//
// One constraint shapes all of it: while the main JVM runs, JS cannot call into Java. Java threads
// therefore *poll* for work through natives that return promises (tunnelPoll, opsPoll), and every
// JS-initiated operation is a frame pushed into a queue that the matching Java thread drains.

import { networkInterfaceNatives } from "./net-natives";
import type { Identity } from "./identity";

declare global {
  // injected by https://cjrtnc.leaningtech.com/4.3/loader.js
  function cheerpjInit(opts: unknown): Promise<void>;
  function cheerpjRunMain(cls: string, cp: string, ...args: string[]): Promise<number>;
}

export type VmStatus =
  | "idle"
  | "booting"     // CheerpJ runtime + jar loading
  | "starting"    // Paper is booting
  | "running"     // "Done (…s)!" seen
  | "stopping"
  | "stopped"     // server exited; a reload starts it again (the world persists)
  | "failed";

export type TunnelEvent =
  | { type: "registered"; address: string }
  | { type: "connection"; remote: string }
  | { type: "lost"; retryMs: number }
  | { type: "replaced" }        // another tab took the address over
  | { type: "invalid-token" };  // identity re-mint required

export interface VmEvents {
  onLog(line: string): void;          // Paper's own log lines only
  onStatus(status: VmStatus, detail?: string): void;
  onTunnel(event: TunnelEvent): void;
}

export interface FileEntry {
  name: string;
  size: number;
  dir: boolean;
  mtime: number;
}

const PAPER_LOG = /^\[\d\d:\d\d:\d\d/; // "[12:34:56 INFO]: ..."; everything else is runtime noise

let events: VmEvents | null = null;
let status: VmStatus = "idle";
let identity: Identity | null = null;

function setStatus(next: VmStatus, detail?: string) {
  status = next;
  events?.onStatus(next, detail);
}

export function vmStatus(): VmStatus {
  return status;
}

// ---- ops channel ------------------------------------------------------------------------------------
// Request frame: [id u32 BE][op u8][pathLen u16 BE][path utf8][payload]; reply via the opsReply native.

const enum Op {
  List = 1,
  Read = 2,
  Write = 3,
  Delete = 4,
  Mkdir = 5,
  Command = 6,
  Stop = 7,
}

type OpWaiter = { resolve(payload: Uint8Array): void; reject(err: Error): void };

const ops = {
  nextId: 1,
  queue: [] as Uint8Array[],
  poller: null as ((frame: Uint8Array) => void) | null,
  pending: new Map<number, OpWaiter>(),

  request(op: Op, path: string, payload?: Uint8Array): Promise<Uint8Array> {
    // File ops work whenever the JVM is up: the ops thread outlives the server thread, so the
    // file manager keeps working after "stop". Before the first start there is nothing to ask.
    if (status === "idle" || status === "failed") {
      return Promise.reject(new Error("the server has not started"));
    }
    const id = this.nextId++;
    const pathBytes = new TextEncoder().encode(path);
    const body = payload ?? new Uint8Array(0);
    const frame = new Uint8Array(7 + pathBytes.length + body.length);
    const view = new DataView(frame.buffer);
    view.setUint32(0, id);
    frame[4] = op;
    view.setUint16(5, pathBytes.length);
    frame.set(pathBytes, 7);
    frame.set(body, 7 + pathBytes.length);
    const result = new Promise<Uint8Array>((resolve, reject) => this.pending.set(id, { resolve, reject }));
    if (this.poller) {
      const p = this.poller;
      this.poller = null;
      p(frame);
    } else {
      this.queue.push(frame);
    }
    return result;
  },

  nextFrame(): Promise<Uint8Array> {
    const queued = this.queue.shift();
    if (queued) return Promise.resolve(queued);
    return new Promise((resolve) => (this.poller = resolve));
  },

  reply(id: number, ok: boolean, payload: Uint8Array) {
    const waiter = this.pending.get(id);
    if (!waiter) return;
    this.pending.delete(id);
    if (ok) waiter.resolve(payload);
    else waiter.reject(new Error(new TextDecoder().decode(payload) || "operation failed"));
  },
};

export const files = {
  async list(path: string): Promise<FileEntry[]> {
    const payload = await ops.request(Op.List, path);
    return JSON.parse(new TextDecoder().decode(payload)) as FileEntry[];
  },
  async read(path: string): Promise<Uint8Array> {
    return ops.request(Op.Read, path);
  },
  async write(path: string, data: Uint8Array): Promise<void> {
    await ops.request(Op.Write, path, data);
  },
  async remove(path: string): Promise<void> {
    await ops.request(Op.Delete, path);
  },
  async mkdir(path: string): Promise<void> {
    await ops.request(Op.Mkdir, path);
  },
};

export async function sendCommand(command: string): Promise<void> {
  await ops.request(Op.Command, "", new TextEncoder().encode(command));
}

export async function stopServer(): Promise<void> {
  setStatus("stopping");
  await ops.request(Op.Stop, "");
}

// ---- tunnel client ----------------------------------------------------------------------------------
// Frames: [type u8][conn id u32 BE][payload]; 1 OPEN (server→page), 2 DATA, 3 CLOSE. See server/tunnel.go.

const tunnel = {
  ws: null as WebSocket | null,
  backoff: 2000,
  stopped: false,
  queue: [] as ArrayBuffer[],
  waiter: null as ((frame: ArrayBuffer) => void) | null,

  send(type: number, id: number, payload: Uint8Array | null) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const body = payload ?? new Uint8Array(0);
    const frame = new Uint8Array(5 + body.length);
    frame[0] = type;
    new DataView(frame.buffer).setUint32(1, id);
    frame.set(body, 5);
    this.ws.send(frame);
  },

  nextFrame(): Promise<ArrayBuffer> {
    const queued = this.queue.shift();
    if (queued) return Promise.resolve(queued);
    return new Promise((resolve) => (this.waiter = resolve));
  },

  onFrame(buf: ArrayBuffer) {
    if (new DataView(buf).getUint8(0) === 1) {
      events?.onTunnel({ type: "connection", remote: new TextDecoder().decode(new Uint8Array(buf, 5)) });
    }
    if (this.waiter) {
      const w = this.waiter;
      this.waiter = null;
      w(buf);
    } else {
      this.queue.push(buf);
    }
  },

  url(): string {
    const params = new URLSearchParams(location.search);
    const override = params.get("tunnel"); // e.g. ?tunnel=ws://localhost:8090/tunnel for local dev
    const base = override ?? `wss://${identity!.name}.${identity!.domain}/tunnel`;
    const sep = base.includes("?") ? "&" : "?";
    return `${base}${sep}name=${encodeURIComponent(identity!.name)}&token=${encodeURIComponent(identity!.token)}`;
  },

  connect() {
    if (this.stopped || !identity) return;
    const ws = new WebSocket(this.url());
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    ws.onopen = () => {
      this.backoff = 2000;
      events?.onTunnel({ type: "registered", address: identity!.address });
    };
    ws.onmessage = (e) => this.onFrame(e.data as ArrayBuffer);
    ws.onclose = (e) => {
      this.ws = null;
      if (this.stopped) return;
      if (e.code === 4001) {
        events?.onTunnel({ type: "replaced" });
        return; // reload the page to take the address back
      }
      if (e.code === 4003) {
        events?.onTunnel({ type: "invalid-token" });
        return;
      }
      events?.onTunnel({ type: "lost", retryMs: this.backoff });
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 2, 30_000);
    };
    ws.onerror = () => {};
  },
};

// ---- boot -------------------------------------------------------------------------------------------

let started = false;

export interface StartOptions {
  identity: Identity;
  freshWorld?: boolean;
  events: VmEvents;
}

export async function startServer(opts: StartOptions): Promise<void> {
  if (started) throw new Error("the server can only be started once per page load");
  started = true;
  events = opts.events;
  identity = opts.identity;
  setStatus("booting", "loading the CheerpJ runtime");

  await loadScript("https://cjrtnc.leaningtech.com/4.3/loader.js");
  // A missing classpath.txt would otherwise turn the server's 404 body into jar paths, and the
  // only symptom would be "Could not find or load main class BrowserMain" much later.
  const classpathRes = await fetch("/classpath.txt");
  if (!classpathRes.ok) throw new Error(`classpath.txt is not being served (HTTP ${classpathRes.status})`);
  const classpath = (await classpathRes.text())
    .trim()
    .split("\n")
    .map((p) => "/app/" + p)
    .join(":");

  await cheerpjInit({
    version: 8,
    status: "none",
    natives: {
      // Output bridge: every line any Java thread prints lands here. Paper's own log lines go to the
      // terminal; the rest ([labs] diagnostics, stack traces) go to the browser console for debugging.
      async Java_BrowserMain_consoleLine(_lib: unknown, line: string) {
        if (PAPER_LOG.test(line)) {
          onPaperLine(line);
        } else {
          console.log(line);
        }
      },
      async Java_BrowserMain_tunnelSend(_lib: unknown, id: number, data: Uint8Array) {
        tunnel.send(2, id, data);
      },
      async Java_BrowserMain_tunnelClosed(_lib: unknown, id: number) {
        tunnel.send(3, id, null);
      },
      async Java_BrowserMain_tunnelPoll(_lib: unknown) {
        return new Int8Array(await tunnel.nextFrame());
      },
      async Java_BrowserMain_opsPoll(_lib: unknown) {
        const frame = await ops.nextFrame();
        return new Int8Array(frame.buffer, frame.byteOffset, frame.byteLength);
      },
      async Java_BrowserMain_opsReply(_lib: unknown, id: number, ok: boolean, payload: Int8Array | null) {
        ops.reply(id, ok, payload ? new Uint8Array(payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength)) : new Uint8Array(0));
      },
      ...networkInterfaceNatives,
      ...managementNatives,
    },
    javaProperties: javaProperties(),
  });

  setStatus("starting", "starting Paper (loading ~110 MB of jars on first visit)");
  const args = ["nogui", "--labs-eula-accepted", ...(opts.freshWorld ? ["--labs-fresh-world"] : [])];
  cheerpjRunMain("BrowserMain", classpath, ...args)
    .then((code) => {
      if (status === "booting" || status === "starting") setStatus("failed", `launcher exited with code ${code}`);
    })
    .catch((err) => setStatus("failed", String(err)));
}

function onPaperLine(line: string) {
  events?.onLog(line);
  if (/Done \(/.test(line)) {
    setStatus("running");
    if (!tunnel.ws) tunnel.connect();
  } else if (/Stopping server|Closing Server/.test(line)) {
    tunnel.stopped = true;
    tunnel.ws?.close();
    setStatus("stopped");
  } else if (status === "starting" && /Starting minecraft server|Preparing level|Preparing start region/.test(line)) {
    setStatus("starting", line.replace(/^\[[^\]]*\]:?\s*/, ""));
  }
}

function javaProperties(): string[] {
  return [
    "user.dir=/files/",
    // CheerpJ reports itself as 1.8.0_492-internal
    "Paper.IgnoreJavaVersion=true",
    // no sockets in a browser: the server listens on netty's in-JVM LocalServerChannel
    "paper.network.transport=local",
    "io.netty.noUnsafe=true",
    // no network interfaces to probe for a MAC address
    "io.netty.machineId=02:42:ac:11:00:02:00:00",
    // the IndexedDB filesystem has no chown/chmod
    "paper.fileAttributeCopy=false",
    // Mojang API hosts are proxied same-origin (the browser blocks cross-origin requests)
    `minecraft.api.services.host=${location.origin}/proxy/services`,
    `minecraft.api.session.host=${location.origin}/proxy/session`,
    `minecraft.api.profiles.host=${location.origin}/proxy/profiles`,
    "java.awt.headless=true",
    "terminal.jline=false",
    "terminal.ansi=false",
    "log4j.skipJansi=true",
    "log4j2.skipJansi=true",
    // CheerpJ's single cooperative thread blocks for seconds during the join chunk burst; the watchdog
    // would read that as a hung server and halt it. The stall is slowness, not a deadlock.
    "disable.watchdog=true",
    // keepalives from the netty event loop, and generous client keepalive deadlines: a slow tick is
    // normal on this runtime, not a sign of a dead client
    "paper.keepAliveOffMain=true",
    "paper.playerconnection.keepalive=120",
    // one JS thread means zero real parallelism: extra chunk workers only add scheduling churn
    "Paper.WorkerThreadCount=1",
    "paper.disableOldApiSupport=true",
  ];
}

function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = src;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error(`failed to load ${src}`));
    document.head.appendChild(s);
  });
}

// java.lang.management natives CheerpJ leaves unimplemented; the server queries them at startup and
// for /tps-style commands. Report what the browser can honestly know; -1 is the documented "not
// available" value throughout the management API.
const managementNatives: Record<string, (...args: never[]) => unknown> = {
  async Java_java_lang_reflect_Field_getTypeAnnotationBytes0() {
    return null;
  },
  async Java_java_lang_reflect_Executable_getTypeAnnotationBytes0() {
    return null;
  },
  async Java_sun_management_MemoryImpl_getMemoryUsage0(lib: any, _self: unknown, heap: boolean) {
    const pm = (performance as any).memory;
    const rt = await lib.java.lang.Runtime;
    const r = await rt.getRuntime();
    const max = Number(await r.maxMemory());
    const total = Number(await r.totalMemory());
    const free = Number(await r.freeMemory());
    let init: number, used: number, committed: number, limit: number;
    if (heap) {
      init = total;
      used = pm ? pm.usedJSHeapSize : total - free;
      committed = pm ? pm.totalJSHeapSize : total;
      limit = pm ? pm.jsHeapSizeLimit : max;
    } else {
      init = 0; used = 0; committed = 0; limit = -1;
    }
    const MemoryUsage = await lib.java.lang.management.MemoryUsage;
    return await new MemoryUsage(BigInt(init), BigInt(used), BigInt(committed), BigInt(limit));
  },
  async Java_sun_management_OperatingSystemImpl_initialize0() {},
  async Java_sun_management_OperatingSystemImpl_getSystemCpuLoad0() { return -1.0; },
  async Java_sun_management_OperatingSystemImpl_getProcessCpuLoad0() { return -1.0; },
  async Java_sun_management_OperatingSystemImpl_getSingleCpuLoad0() { return -1.0; },
  async Java_sun_management_OperatingSystemImpl_getProcessCpuTime0() { return -1n; },
  async Java_sun_management_OperatingSystemImpl_getHostTotalCpuTicks0() { return -1n; },
  async Java_sun_management_OperatingSystemImpl_getHostConfiguredCpuCount0() { return navigator.hardwareConcurrency || 1; },
  async Java_sun_management_OperatingSystemImpl_getHostOnlineCpuCount0() { return navigator.hardwareConcurrency || 1; },
  async Java_sun_management_OperatingSystemImpl_getTotalPhysicalMemorySize0() {
    return BigInt(Math.round(((navigator as any).deviceMemory || 4) * 1073741824));
  },
  async Java_sun_management_OperatingSystemImpl_getFreePhysicalMemorySize0() {
    const pm = (performance as any).memory;
    return pm ? BigInt(pm.jsHeapSizeLimit - pm.usedJSHeapSize) : -1n;
  },
  async Java_sun_management_OperatingSystemImpl_getCommittedVirtualMemorySize0() {
    const pm = (performance as any).memory;
    return pm ? BigInt(pm.totalJSHeapSize) : -1n;
  },
  async Java_sun_management_OperatingSystemImpl_getTotalSwapSpaceSize0() { return 0n; },
  async Java_sun_management_OperatingSystemImpl_getFreeSwapSpaceSize0() { return 0n; },
  async Java_sun_management_OperatingSystemImpl_getOpenFileDescriptorCount0() { return -1n; },
  async Java_sun_management_OperatingSystemImpl_getMaxFileDescriptorCount0() { return -1n; },
  // statvfs for the IndexedDB filesystem: derive block counts from the browser's storage quota estimate
  async Java_sun_nio_fs_UnixNativeDispatcher_statvfs0(_lib: unknown, _path: unknown, attrs: any) {
    let quota = 1073741824, usage = 0;
    try {
      const est = await navigator.storage.estimate();
      if (est.quota) quota = est.quota;
      usage = est.usage || 0;
    } catch {}
    const free = BigInt(Math.max(0, Math.floor((quota - usage) / 4096)));
    attrs.f_frsize = 4096n;
    attrs.f_blocks = BigInt(Math.floor(quota / 4096));
    attrs.f_bfree = free;
    attrs.f_bavail = free;
  },
  // this JVM exposes no memory pools/managers (honest empty arrays)
  async Java_sun_management_MemoryImpl_getMemoryPools0(lib: any) {
    const Class = await lib.java.lang.Class, Arr = await lib.java.lang.reflect.Array;
    return await Arr.newInstance(await Class.forName("java.lang.management.MemoryPoolMXBean"), 0);
  },
  async Java_sun_management_MemoryImpl_getMemoryManagers0(lib: any) {
    const Class = await lib.java.lang.Class, Arr = await lib.java.lang.reflect.Array;
    return await Arr.newInstance(await Class.forName("java.lang.management.MemoryManagerMXBean"), 0);
  },
  // thread CPU/contention/allocation accounting is not available on this JVM
  async Java_sun_management_VMManagementImpl_isThreadCpuTimeEnabled() { return false; },
  async Java_sun_management_VMManagementImpl_isThreadContentionMonitoringEnabled() { return false; },
  async Java_sun_management_VMManagementImpl_isThreadAllocatedMemoryEnabled() { return false; },
  async Java_sun_management_VMManagementImpl_getVerboseGC() { return false; },
  async Java_sun_management_VMManagementImpl_getVerboseClass() { return false; },
  // fsync on IndexedDB: writes are durable once the transaction commits; nothing to flush
  async Java_sun_nio_ch_FileDispatcherImpl_force0() { return 0; },
  // the browser sandbox has no OS user; report a regular (non-root) one
  async Java_com_sun_security_auth_module_UnixSystem_getUnixInfo(_lib: unknown, self: any) {
    self.uid = 1000n;
    self.gid = 1000n;
    self.username = "browser";
    self.groups = null;
  },
};
