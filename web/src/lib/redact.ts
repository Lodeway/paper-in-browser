// The console is the page's headline object, so it is what ends up in screenshots and screen
// shares. Player connections carry real public addresses (the tunnel hands the browser the
// remote address, and the PROXY header puts it into Paper's own join/kick/ban log lines), so
// every line is scrubbed on its way to the terminal.
//
// Only routable addresses are hidden: loopback, link-local, and the RFC1918 / unspecified
// addresses a local server legitimately prints ("Starting Minecraft server on *:25565",
// "0.0.0.0", "127.0.0.1") stay readable, because censoring those loses information without
// protecting anyone.

const HIDDEN = "[hidden]";

// Four dotted octets, not glued to a longer number/word (so "1.21.4-R0.1-SNAPSHOT" is safe).
const IPV4 = /(?<![\w.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![\w.])/g;
// A run of hex groups with at least two colons: enough to catch "2001:db8::1" and "::ffff:1.2.3.4"
// without matching a bare "host:port" or a log timestamp ("12:04:31").
const IPV6 = /(?<![\w:])(?=[0-9a-fA-F:]*::|(?:[0-9a-fA-F]{1,4}:){7})[0-9a-fA-F:]{2,}(?:\.\d{1,3}){0,3}(?![\w:])/g;

function isPrivateV4(octets: number[]): boolean {
  const [a, b] = octets;
  if (octets.some(o => o > 255)) return true; // not an address at all; leave it alone
  return (
    a === 0 || // unspecified / "this network"
    a === 10 ||
    a === 127 ||
    (a === 100 && b >= 64 && b <= 127) || // CGNAT
    (a === 169 && b === 254) || // link-local
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    a >= 224 // multicast / reserved / broadcast
  );
}

function isPrivateV6(text: string): boolean {
  const addr = text.toLowerCase();
  if (addr === "::" || addr === "::1") return true;
  if (addr.startsWith("fe80") || addr.startsWith("fc") || addr.startsWith("fd")) return true;
  return false;
}

/** Replace every routable IP literal in a line with a placeholder. */
export function redactIps(text: string): string {
  return text
    .replace(IPV6, match => {
      if (isPrivateV6(match)) return match;
      // "::ffff:203.0.113.9" — the v4-mapped tail is an address too, so hide the whole thing.
      return HIDDEN;
    })
    .replace(IPV4, (match, ...groups) => {
      const octets = (groups.slice(0, 4) as string[]).map(Number);
      return isPrivateV4(octets) ? match : HIDDEN;
    });
}
