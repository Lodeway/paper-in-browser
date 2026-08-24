// java.net.NetworkInterface natives for the browser sandbox.
// The JDK's natives enumerate interfaces with socket ioctls, which CheerpJ refuses (EPERM), so
// NetworkInterface.getNetworkInterfaces() would throw SocketException. This JVM has exactly one
// interface it can honestly describe: the loopback (127.0.0.1 / ::1) the in-process local transport
// runs over. Up, not multicast-capable, no hardware address, 64 KiB MTU: what Linux reports for lo.

let loopback: unknown = null;

async function setField(cls: any, obj: unknown, name: string, value: unknown, setter?: string) {
  const f = await cls.getDeclaredField(name);
  await f.setAccessible(true);
  await f[setter || "set"](obj, value);
}

async function lo(lib: any) {
  if (loopback) return loopback;
  const Class = await lib.java.lang.Class;
  const Arr = await lib.java.lang.reflect.Array;
  const InetAddress = await lib.java.net.InetAddress;
  const NetworkInterface = await lib.java.net.NetworkInterface;
  const InterfaceAddress = await lib.java.net.InterfaceAddress;
  const niClass = await Class.forName("java.net.NetworkInterface");
  const iaClass = await Class.forName("java.net.InterfaceAddress");
  const v4 = await InetAddress.getByAddress("localhost", new Int8Array([127, 0, 0, 1]));
  const v6 = await InetAddress.getByAddress("localhost", new Int8Array([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]));
  const addrs = await Arr.newInstance(await Class.forName("java.net.InetAddress"), 2);
  await Arr.set(addrs, 0, v4);
  await Arr.set(addrs, 1, v6);
  const bindings = await Arr.newInstance(iaClass, 2);
  for (const [i, addr, prefix] of [[0, v4, 8], [1, v6, 128]] as const) {
    const b = await new InterfaceAddress();
    await setField(iaClass, b, "address", addr);
    await setField(iaClass, b, "maskLength", prefix, "setShort");
    await Arr.set(bindings, i, b);
  }
  const ni = await new NetworkInterface();
  await setField(niClass, ni, "name", "lo");
  await setField(niClass, ni, "displayName", "lo");
  await setField(niClass, ni, "index", 1, "setInt");
  await setField(niClass, ni, "addrs", addrs);
  await setField(niClass, ni, "bindings", bindings);
  await setField(niClass, ni, "childs", await Arr.newInstance(niClass, 0));
  return (loopback = ni);
}

const natives: Record<string, (...args: any[]) => Promise<unknown>> = {
  async Java_java_net_NetworkInterface_init() {},
  async Java_java_net_NetworkInterface_getAll(lib: any) {
    const Class = await lib.java.lang.Class;
    const Arr = await lib.java.lang.reflect.Array;
    const all = await Arr.newInstance(await Class.forName("java.net.NetworkInterface"), 1);
    await Arr.set(all, 0, await lo(lib));
    return all;
  },
  async Java_java_net_NetworkInterface_getByName0(lib: any, name: unknown) {
    return String(name) === "lo" ? lo(lib) : null;
  },
  async Java_java_net_NetworkInterface_getByIndex0(lib: any, index: number) {
    return index === 1 ? lo(lib) : null;
  },
  async Java_java_net_NetworkInterface_getByInetAddress0(lib: any, addr: any) {
    return (await addr.isLoopbackAddress()) ? lo(lib) : null;
  },
  async Java_java_net_NetworkInterface_isUp0(_lib: unknown, _name: unknown, index: number) {
    return index === 1;
  },
  async Java_java_net_NetworkInterface_isLoopback0(_lib: unknown, _name: unknown, index: number) {
    return index === 1;
  },
  async Java_java_net_NetworkInterface_supportsMulticast0() {
    return false;
  },
  async Java_java_net_NetworkInterface_isP2P0() {
    return false;
  },
  async Java_java_net_NetworkInterface_getMacAddr0() {
    return null;
  },
  async Java_java_net_NetworkInterface_getMTU0() {
    return 65536;
  },
};

// CheerpJ reports a rejected native as "Exception in native code, thread stopped" with no Java stack,
// so name the failing native on the console before rethrowing.
for (const k of Object.keys(natives)) {
  const f = natives[k];
  natives[k] = async (...a: unknown[]) => {
    try {
      return await f(...a);
    } catch (e) {
      console.error(`[net] ${k} failed:`, e);
      throw e;
    }
  };
}

export const networkInterfaceNatives = natives;
