// The terminal figure, the page's headline object, drawn like lodeway's SetupLog frame:
// a 2px ink border with the hard-lg shadow in the PAGE's theme, and an inner subtree that
// carries the `dark` class because a console is ink in both themes.
//
// The xterm instance lives here; the App writes into it through the small TermWriter the
// panel hands back on mount, so wiring VM events never has to know about ANSI codes.

import { useEffect, useRef, useState } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import type { VmStatus } from "@/vm/paper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface TermWriter {
  line(text: string): void;
  dim(text: string): void;
  green(text: string): void;
  red(text: string): void;
}

const STATUS_TONE: Record<VmStatus, { dot: string; text: string }> = {
  idle: { dot: "bg-muted-foreground", text: "text-muted-foreground" },
  booting: { dot: "bg-chart-4", text: "text-chart-4" },
  starting: { dot: "bg-chart-4", text: "text-chart-4" },
  running: { dot: "bg-signal", text: "text-signal-ink" },
  stopping: { dot: "bg-chart-4", text: "text-chart-4" },
  stopped: { dot: "bg-destructive", text: "text-destructive" },
  failed: { dot: "bg-destructive", text: "text-destructive" },
};

export function ConsolePanel({
  status,
  canSend,
  onReady,
  onCommand,
}: {
  status: VmStatus;
  canSend: boolean;
  onReady: (writer: TermWriter) => void;
  onCommand: (command: string) => void;
}) {
  const mountRef = useRef<HTMLDivElement>(null);
  const [input, setInput] = useState("");
  const history = useRef<string[]>([]);
  const historyAt = useRef(-1);

  useEffect(() => {
    const mount = mountRef.current;
    if (!mount) return;
    const term = new Terminal({
      convertEol: true,
      scrollback: 5000,
      disableStdin: true,
      cursorBlink: false,
      fontFamily: '"IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
      fontSize: 13,
      lineHeight: 1.25,
      theme: {
        // The dark theme's tokens, written out: xterm paints its own canvas.
        background: "#191613",
        foreground: "#ece7da",
        cursor: "#7fc86b",
        selectionBackground: "#2e7d3a",
        selectionForeground: "#ffffff",
      },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(mount);
    fit.fit();
    // The first fit can run against the fallback font's metrics; refit once the
    // webfont (IBM Plex Mono) has actually loaded, or the column count is garbage.
    void document.fonts?.ready.then(() => {
      try {
        fit.fit();
      } catch {
        /* disposed before the fonts settled */
      }
    });
    // Deferred a frame: fitting synchronously inside the observer callback re-triggers
    // layout and trips "ResizeObserver loop completed with undelivered notifications".
    let raf = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        try {
          fit.fit();
        } catch {
          /* mid-teardown resize */
        }
      });
    });
    observer.observe(mount);

    onReady({
      line: text => term.writeln(text),
      dim: text => term.writeln(`\x1b[2m${text}\x1b[0m`),
      green: text => term.writeln(`\x1b[32m${text}\x1b[0m`),
      red: text => term.writeln(`\x1b[31m${text}\x1b[0m`),
    });

    return () => {
      cancelAnimationFrame(raf);
      observer.disconnect();
      term.dispose();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function submit() {
    const command = input.trim();
    if (!command || !canSend) return;
    history.current.push(command);
    historyAt.current = history.current.length;
    onCommand(command);
    setInput("");
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter") {
      e.preventDefault();
      submit();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (historyAt.current > 0) {
        historyAt.current -= 1;
        setInput(history.current[historyAt.current] ?? "");
      }
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      if (historyAt.current < history.current.length - 1) {
        historyAt.current += 1;
        setInput(history.current[historyAt.current] ?? "");
      } else {
        historyAt.current = history.current.length;
        setInput("");
      }
    }
  }

  const tone = STATUS_TONE[status];

  return (
    <figure className="border-border border-2 shadow-hard-lg">
      <div className="dark bg-background text-foreground overflow-hidden">
        <figcaption className="border-rule text-muted-foreground flex items-center gap-3 border-b-2 px-4 py-2.5 text-xs">
          <span className="flex gap-2" aria-hidden="true">
            <i className="bg-signal size-2" />
            <i className="bg-muted-foreground size-2" />
            <i className="bg-border size-2" />
          </span>
          <span className="font-display truncate">console</span>
          <span
            className={cn("font-display ml-auto flex shrink-0 items-center gap-2 uppercase", tone.text)}
            role="status"
          >
            <i className={cn("size-2", tone.dot)} aria-hidden="true" />
            {status}
          </span>
        </figcaption>

        <div
          ref={mountRef}
          className="h-[26rem] min-w-0 overflow-hidden bg-[#191613] px-3 py-2 [&_.xterm]:h-full"
        />

        <div className="border-rule flex items-center gap-2 border-t-2 px-3 py-2">
          <span className="text-signal-ink font-mono text-sm" aria-hidden="true">
            &gt;
          </span>
          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            disabled={!canSend}
            placeholder={canSend ? "server command" : "commands unlock when the server is running"}
            aria-label="Server command"
            spellCheck={false}
            autoComplete="off"
            className="text-foreground placeholder:text-muted-foreground/60 min-w-0 flex-1 bg-transparent font-mono text-sm outline-none disabled:opacity-50"
          />
          <Button variant="secondary" size="sm" disabled={!canSend || !input.trim()} onClick={submit}>
            Send
          </Button>
        </div>
      </div>
    </figure>
  );
}
