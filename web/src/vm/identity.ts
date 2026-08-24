// Every browser gets its own randomly assigned server address (name.tun.lodeway.app), minted once by
// the labs server and kept in localStorage. The token is a signed claim on the name: the tunnel only
// registers a session whose token verifies, so nobody can take over an address they were not issued.

export interface Identity {
  name: string;
  address: string; // "<name>.<domain>"
  domain: string;
  token: string;
}

const STORAGE_KEY = "paper-labs.identity";

export async function fetchIdentity(): Promise<Identity> {
  const stored = readStored();
  const headers: Record<string, string> = {};
  if (stored) headers["Authorization"] = `Bearer ${stored.token}`;
  const res = await fetch("/api/identity", { method: "POST", headers });
  if (res.status === 401) {
    // the stored token no longer verifies (e.g. the server rotated its secret): mint a fresh one
    localStorage.removeItem(STORAGE_KEY);
    return fetchIdentity();
  }
  if (!res.ok) throw new Error(`identity request failed: ${res.status}`);
  const body = (await res.json()) as { name: string; address: string; token: string };
  const identity: Identity = {
    name: body.name,
    address: body.address,
    domain: body.address.slice(body.name.length + 1),
    token: body.token,
  };
  localStorage.setItem(STORAGE_KEY, JSON.stringify(identity));
  return identity;
}

export function resetIdentity(): void {
  localStorage.removeItem(STORAGE_KEY);
}

function readStored(): Identity | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Identity) : null;
  } catch {
    return null;
  }
}
