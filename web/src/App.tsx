// The control panel for the in-tab Paper server: one screen, one component's state.
//
// Load order: the terminal mounts immediately and prints its boot hints; fetchIdentity()
// runs at once so the address panel fills in; startServer() only ever runs on the user's
// Start click (and only once per page load: "Start again" is a reload on purpose).

import { useCallback, useEffect, useRef, useState } from "react";
import { sendCommand, startServer, stopServer, type VmStatus } from "@/vm/paper";
import { fetchIdentity, type Identity } from "@/vm/identity";
import { Container, Eyebrow, HowItWorks } from "@/sections";
import { ConsolePanel, type TermWriter } from "@/components/ConsolePanel";
import { FilesPanel } from "@/components/FilesPanel";
import { EulaDialog, eulaAccepted } from "@/components/EulaDialog";
import { Wordmark } from "@/components/Wordmark";
import { ThemeToggle } from "@/components/ThemeToggle";
import { Button } from "@/components/ui/button";

export function App() {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [identityFailed, setIdentityFailed] = useState(false);
  const [status, setStatus] = useState<VmStatus>("idle");
  const [started, setStarted] = useState(false);
  const [showEula, setShowEula] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [tunnelUp, setTunnelUp] = useState(false);
  const writerRef = useRef<TermWriter | null>(null);
  const identityRef = useRef<Identity | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchIdentity()
      .then(id => {
        if (cancelled) return;
        identityRef.current = id;
        setIdentity(id);
      })
      .catch(() => {
        if (!cancelled) setIdentityFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const onTerminalReady = useCallback((writer: TermWriter) => {
    writerRef.current = writer;
    writer.dim("[labs] paper 26.2 · java 8 bytecode · cheerpj 4.3");
    writer.dim("[labs] the world persists in this browser's storage");
    writer.dim("[labs] press start to boot the server in this tab");
  }, []);

  function doStart() {
    if (started) return;
    setStarted(true);
    const fresh = new URLSearchParams(location.search).get("fresh") === "1";
    const writer = writerRef.current;
    void startServer({
      // A null identity is fine: paper.ts only uses it for the tunnel, and its connect()
      // guard skips the tunnel entirely when no identity was minted. The console, files
      // and world all still work; only the public address is missing.
      identity: identityRef.current as unknown as Identity,
      freshWorld: fresh,
      events: {
        onLog: line => writerRef.current?.line(line),
        onStatus: (next, detail) => {
          setStatus(next);
          if (next === "stopped" || next === "failed") setTunnelUp(false);
          writerRef.current?.dim(`[labs] status: ${next}${detail ? `: ${detail}` : ""}`);
        },
        onTunnel: event => {
          const w = writerRef.current;
          switch (event.type) {
            case "registered":
              w?.green(`[labs] reachable at ${event.address}`);
              setTunnelUp(true);
              setNote(null);
              break;
            case "connection":
              w?.line(`[labs] player connecting from ${event.remote}`);
              break;
            case "replaced":
              w?.red("[labs] another tab took this address over");
              setTunnelUp(false);
              setNote("another tab took this address; reload to take it back");
              break;
            case "lost":
              w?.dim(`[labs] tunnel lost, retrying in ${Math.round(event.retryMs / 1000)}s`);
              setTunnelUp(false);
              break;
            case "invalid-token":
              w?.red("[labs] the tunnel rejected this identity; reload to mint a fresh one");
              setTunnelUp(false);
              setNote("identity rejected; reload the page to mint a fresh address");
              break;
          }
        },
      },
    }).catch(err => {
      writer?.red(`[labs] failed to start: ${String(err)}`);
      setStatus("failed");
    });
  }

  function onStartClick() {
    if (status === "stopped" || status === "failed") {
      location.reload();
      return;
    }
    if (!eulaAccepted()) {
      setShowEula(true);
      return;
    }
    doStart();
  }

  function copyAddress() {
    if (!identity) return;
    void navigator.clipboard.writeText(identity.address).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  const ended = status === "stopped" || status === "failed";
  const startLabel = ended
    ? "Start again"
    : status === "running"
      ? "Running"
      : started
        ? "Starting…"
        : "Start";

  return (
    <div className="bg-background text-foreground flex min-h-dvh flex-col">
      {/* Header */}
      <header className="bg-background sticky top-0 z-40">
        <Container className="border-border flex flex-wrap items-center justify-between gap-x-3 gap-y-1 border-b-2 py-4 sm:gap-x-4 sm:py-5">
          <Wordmark />
          <nav className="flex items-center gap-1 sm:gap-2" aria-label="Site">
            <a
              href="https://lodeway.app"
              className="text-muted-foreground hover:text-signal-ink px-2 py-1.5 text-sm transition-colors sm:px-3"
            >
              lodeway.app
            </a>
            <a
              href="https://github.com/lodeway/paper-in-browser"
              className="text-muted-foreground hover:text-signal-ink px-2 py-1.5 text-sm transition-colors sm:px-3"
            >
              github
            </a>
            <ThemeToggle />
          </nav>
        </Container>
      </header>

      <main className="flex-1">
        {/* Intro + address */}
        <Container className="py-10">
          <Eyebrow>lodeway labs / 01</Eyebrow>
          <h1 className="font-display mt-4 max-w-3xl text-2xl font-bold tracking-[-0.02em] text-balance sm:text-4xl">
            Run an extremely slow Minecraft server from your browser tab
          </h1>
          <p className="text-muted-foreground mt-4 max-w-2xl leading-relaxed text-pretty">
            This page runs Paper 26.2, rewritten to Java 8 bytecode and executed by
            CheerpJ, a JVM that runs in the browser. The world is stored in this
            browser&rsquo;s storage. A WebSocket tunnel forwards Minecraft clients into
            the tab, so the server has a joinable address.
          </p>
          <p className="text-muted-foreground mt-4 max-w-2xl leading-relaxed">
            Startup can take over 2 minutes. Expect your tab to momentarily freeze.
          </p>

          <div className="border-border bg-card mt-7 max-w-2xl border-2 p-5 shadow-hard">
            <p className="font-display text-muted-foreground text-[0.7rem] tracking-[0.14em] uppercase">
              your server address
            </p>
            {identity ? (
              <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2">
                <i
                  aria-hidden="true"
                  className={`size-2.5 shrink-0 rounded-full ${tunnelUp ? "bg-signal" : "bg-destructive"}`}
                  title={tunnelUp ? "reachable from Minecraft clients" : "not reachable yet"}
                />
                <span className="sr-only" role="status">
                  {tunnelUp ? "reachable from Minecraft clients" : "not reachable yet"}
                </span>
                <span className="font-mono text-lg font-semibold break-all sm:text-2xl">
                  {identity.address}
                </span>
                <Button variant="secondary" size="sm" onClick={copyAddress} aria-label="Copy the server address">
                  {copied ? "Copied" : "Copy"}
                </Button>
              </div>
            ) : identityFailed ? (
              <p className="text-muted-foreground mt-2 font-mono text-sm">
                could not mint an address. The console below still works, but the server
                has no public address
              </p>
            ) : (
              <p className="text-muted-foreground mt-2 font-mono text-lg" aria-live="polite">
                assigning an address…
              </p>
            )}
            <p className="text-muted-foreground mt-3 text-sm">
              Point a Minecraft Java 26.2 client at this address once the server is up.
            </p>
            {note ? <p className="text-destructive mt-2 font-mono text-xs">{note}</p> : null}
          </div>
        </Container>

        {/* Console + files */}
        <Container className="pb-4">
          <div className="grid gap-7 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
            <div className="min-w-0">
              <ConsolePanel
                status={status}
                canSend={status === "running"}
                onReady={onTerminalReady}
                onCommand={cmd => {
                  writerRef.current?.dim(`> ${cmd}`);
                  sendCommand(cmd).catch(err => writerRef.current?.red(`[labs] ${String(err)}`));
                }}
              />
              <div className="mt-5 flex flex-wrap items-center gap-4">
                <Button size="cta" disabled={started && !ended} onClick={onStartClick}>
                  {startLabel}
                </Button>
                <Button
                  variant="secondary"
                  size="cta"
                  disabled={status !== "running"}
                  onClick={() => void stopServer().catch(() => {})}
                >
                  Stop
                </Button>
                <p className="text-muted-foreground max-w-sm text-xs leading-relaxed">
                  The first start downloads ~110 MB of jars, and startup takes a few
                  minutes. Keep the tab focused: browsers throttle background tabs.
                </p>
              </div>
            </div>
            <FilesPanel active={status === "running"} />
          </div>
        </Container>

        <HowItWorks />
      </main>

      {/* Footer */}
      <footer className="border-border bg-muted border-t-2">
        <Container className="text-muted-foreground flex flex-col gap-4 py-10 text-sm sm:flex-row sm:items-start sm:justify-between">
          <div className="space-y-2">
            <Wordmark className="text-sm" />
            <p>Open-source experiments from Lodeway.</p>
            <nav aria-label="Links">
              <ul className="flex gap-4 text-xs">
                <li>
                  <a
                    href="https://lodeway.app"
                    className="hover:text-foreground underline underline-offset-2 transition-colors"
                  >
                    lodeway.app
                  </a>
                </li>
                <li>
                  <a
                    href="https://github.com/lodeway/paper-in-browser"
                    className="hover:text-foreground underline underline-offset-2 transition-colors"
                  >
                    github
                  </a>
                </li>
                <li>
                  <a
                    href="https://papermc.io"
                    className="hover:text-foreground underline underline-offset-2 transition-colors"
                  >
                    PaperMC
                  </a>
                </li>
              </ul>
            </nav>
          </div>
          <p className="max-w-xs text-xs leading-relaxed text-pretty">
            NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
            MICROSOFT. Paper belongs to the PaperMC project. This page runs a real server;
            it stops when the tab closes.
          </p>
        </Container>
      </footer>

      <EulaDialog
        open={showEula}
        onAgree={() => {
          setShowEula(false);
          doStart();
        }}
        onDecline={() => setShowEula(false)}
      />
    </div>
  );
}
