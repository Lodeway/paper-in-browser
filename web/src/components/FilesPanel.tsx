// The server-directory browser: a drawn card over the ops channel's file API. Disabled
// until Paper is running, because the Java side of the channel only polls once the
// launcher's ops thread is up.

import { useCallback, useEffect, useRef, useState } from "react";
import { unzipSync, zipSync, type Zippable } from "fflate";
import { files, type FileEntry } from "@/vm/paper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const EDIT_LIMIT = 256 * 1024;

function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes;
  let unit = "";
  for (const next of units) {
    value /= 1024;
    unit = next;
    if (value < 1024) break;
  }
  return `${value >= 10 ? Math.round(value) : value.toFixed(1)} ${unit}`;
}

function joinPath(segments: string[], name?: string): string {
  const parts = name ? [...segments, name] : segments;
  return parts.join("/");
}

export function FilesPanel({ active }: { active: boolean }) {
  const [segments, setSegments] = useState<string[]>([]);
  const [entries, setEntries] = useState<FileEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ path: string; text: string } | null>(null);
  const [transfer, setTransfer] = useState<string | null>(null);
  const [transferError, setTransferError] = useState<string | null>(null);
  const [importPending, setImportPending] = useState<File | null>(null);
  const uploadRef = useRef<HTMLInputElement>(null);
  const importRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(
    async (nextSegments?: string[]) => {
      if (!active) return;
      const at = nextSegments ?? segments;
      try {
        const listing = await files.list(joinPath(at));
        listing.sort((a, b) => Number(b.dir) - Number(a.dir) || a.name.localeCompare(b.name));
        setEntries(listing);
        setError(null);
        if (nextSegments) setSegments(nextSegments);
      } catch (err) {
        setError(String(err instanceof Error ? err.message : err));
      }
    },
    [active, segments],
  );

  // No refresh button: the listing follows the server on its own, at a pace slow enough to
  // stay out of the single cooperative thread's way while the server ticks.
  useEffect(() => {
    if (!active) return;
    void refresh();
    const timer = setInterval(() => {
      if (!editing && !transfer) void refresh();
    }, 4000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, segments, editing, transfer]);

  async function download(entry: FileEntry) {
    try {
      const bytes = await files.read(joinPath(segments, entry.name));
      const url = URL.createObjectURL(new Blob([bytes as BlobPart]));
      const a = document.createElement("a");
      a.href = url;
      a.download = entry.name;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    }
  }

  async function openEditor(entry: FileEntry) {
    try {
      const path = joinPath(segments, entry.name);
      const bytes = await files.read(path);
      setEditing({ path, text: new TextDecoder().decode(bytes) });
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    }
  }

  async function saveEditor() {
    if (!editing) return;
    try {
      await files.write(editing.path, new TextEncoder().encode(editing.text));
      setEditing(null);
      await refresh();
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    }
  }

  async function remove(entry: FileEntry) {
    if (!confirm(`Delete ${entry.name}${entry.dir ? " and everything in it" : ""}?`)) return;
    try {
      await files.remove(joinPath(segments, entry.name));
      await refresh();
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    }
  }

  async function upload(list: FileList | null) {
    if (!list) return;
    try {
      for (const file of Array.from(list)) {
        const bytes = new Uint8Array(await file.arrayBuffer());
        await files.write(joinPath(segments, file.name), bytes);
      }
      await refresh();
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    }
    if (uploadRef.current) uploadRef.current.value = "";
  }

  async function exportZip() {
    setTransfer("exporting…");
    setTransferError(null);
    try {
      const tree: Zippable = {};
      let count = 0;
      const collect = async (prefix: string[]) => {
        const listing = await files.list(joinPath(prefix));
        if (listing.length === 0 && prefix.length > 0) {
          tree[`${joinPath(prefix)}/`] = new Uint8Array(0);
          return;
        }
        for (const entry of listing) {
          if (entry.dir) {
            await collect([...prefix, entry.name]);
          } else {
            tree[joinPath(prefix, entry.name)] = await files.read(joinPath(prefix, entry.name));
            setTransfer(`exporting… ${++count} files`);
          }
        }
      };
      await collect([]);
      const zipped = zipSync(tree);
      const url = URL.createObjectURL(new Blob([zipped as BlobPart], { type: "application/zip" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = "paper-server.zip";
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setTransferError(`export failed: ${String(err instanceof Error ? err.message : err)}`);
    } finally {
      setTransfer(null);
    }
  }

  async function importZip(file: File) {
    setImportPending(null);
    setTransfer("importing…");
    setTransferError(null);
    try {
      const entries = unzipSync(new Uint8Array(await file.arrayBuffer()));
      for (const entry of await files.list("")) {
        await files.remove(entry.name);
      }
      let count = 0;
      for (const [path, data] of Object.entries(entries)) {
        if (path.endsWith("/")) {
          await files.mkdir(path.slice(0, -1));
        } else {
          await files.write(path, data);
          setTransfer(`importing… ${++count} files`);
        }
      }
      await refresh([]);
    } catch (err) {
      setTransferError(`import failed: ${String(err instanceof Error ? err.message : err)}`);
    } finally {
      setTransfer(null);
      if (importRef.current) importRef.current.value = "";
    }
  }

  async function newFolder() {
    const name = prompt("Folder name");
    if (!name?.trim()) return;
    try {
      await files.mkdir(joinPath(segments, name.trim()));
      await refresh();
    } catch (err) {
      setError(String(err instanceof Error ? err.message : err));
    }
  }

  return (
    <section className="border-border bg-card flex min-h-0 flex-col border-2 p-0 shadow-hard">
      <div className="border-rule flex flex-wrap items-center gap-2 border-b-2 px-4 py-2.5">
        <span className="font-display text-xs uppercase">files</span>
        <span className="text-muted-foreground truncate font-mono text-xs">
          /{joinPath(segments)}
        </span>
        <span className="ml-auto flex items-center gap-1.5">
          <Button variant="secondary" size="sm" disabled={!active} onClick={newFolder}>
            New folder
          </Button>
          <Button variant="secondary" size="sm" disabled={!active} onClick={() => uploadRef.current?.click()}>
            Upload
          </Button>
          <input
            ref={uploadRef}
            type="file"
            multiple
            className="hidden"
            aria-hidden="true"
            tabIndex={-1}
            onChange={e => void upload(e.target.files)}
          />
        </span>
      </div>

      {!active ? (
        <p className="text-muted-foreground px-4 py-10 text-center font-mono text-sm">
          start the server to browse its files
        </p>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto">
          {error ? (
            <p className="text-destructive border-rule border-b px-4 py-2 font-mono text-xs">{error}</p>
          ) : null}
          <ul className="font-mono text-sm">
            {segments.length > 0 ? (
              <li>
                <button
                  className="hover:bg-accent grid w-full grid-cols-[1rem_1fr] gap-2 px-4 py-1.5 text-left"
                  onClick={() => void refresh(segments.slice(0, -1))}
                >
                  <span aria-hidden="true">▸</span>
                  <span>..</span>
                </button>
              </li>
            ) : null}
            {entries.map(entry => (
              <li key={entry.name} className="group hover:bg-accent grid grid-cols-[1rem_minmax(0,1fr)_auto] items-center gap-2 px-4 py-1">
                <span className="text-muted-foreground" aria-hidden="true">
                  {entry.dir ? "▸" : "·"}
                </span>
                {entry.dir ? (
                  <button
                    className="truncate text-left hover:underline"
                    onClick={() => void refresh([...segments, entry.name])}
                  >
                    {entry.name}/
                  </button>
                ) : (
                  <span className="truncate">{entry.name}</span>
                )}
                <span className="flex items-center gap-2">
                  <span className="flex gap-1 opacity-0 transition-opacity group-focus-within:opacity-100 group-hover:opacity-100">
                    {!entry.dir ? (
                      <button
                        className="text-muted-foreground hover:text-foreground px-1 text-xs underline underline-offset-2"
                        aria-label={`Download ${entry.name}`}
                        onClick={() => void download(entry)}
                      >
                        get
                      </button>
                    ) : null}
                    {!entry.dir && entry.size < EDIT_LIMIT ? (
                      <button
                        className="text-muted-foreground hover:text-foreground px-1 text-xs underline underline-offset-2"
                        aria-label={`Edit ${entry.name}`}
                        onClick={() => void openEditor(entry)}
                      >
                        edit
                      </button>
                    ) : null}
                    <button
                      className="text-destructive px-1 text-xs underline underline-offset-2"
                      aria-label={`Delete ${entry.name}`}
                      onClick={() => void remove(entry)}
                    >
                      del
                    </button>
                  </span>
                  <span className="text-muted-foreground w-16 text-right text-xs tabular-nums">
                    {entry.dir ? "—" : humanSize(entry.size)}
                  </span>
                </span>
              </li>
            ))}
            {entries.length === 0 ? (
              <li className="text-muted-foreground px-4 py-3 text-xs">empty directory</li>
            ) : null}
          </ul>
        </div>
      )}

      <div className="border-rule flex flex-wrap items-center gap-1.5 border-t-2 px-4 py-2.5">
        <Button variant="secondary" size="sm" disabled={!active || !!transfer} onClick={() => void exportZip()}>
          Export filesystem
        </Button>
        <Button variant="secondary" size="sm" disabled={!active || !!transfer} onClick={() => importRef.current?.click()}>
          Import filesystem
        </Button>
        <input
          ref={importRef}
          type="file"
          accept=".zip,application/zip"
          className="hidden"
          aria-hidden="true"
          tabIndex={-1}
          onChange={e => {
            const file = e.target.files?.[0];
            if (file) setImportPending(file);
          }}
        />
        {transfer ? (
          <span className="text-muted-foreground font-mono text-xs" role="status">
            {transfer}
          </span>
        ) : transferError ? (
          <span className="text-destructive font-mono text-xs" role="alert">
            {transferError}
          </span>
        ) : null}
      </div>

      {importPending ? (
        <div
          className="bg-black/55 fixed inset-0 z-50 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-label="Confirm filesystem import"
        >
          <div className="border-border bg-background flex w-full max-w-md flex-col border-2 shadow-hard-lg">
            <p className="border-rule font-display border-b-2 px-4 py-2.5 text-xs uppercase">
              import filesystem
            </p>
            <p className="p-4 font-mono text-sm leading-relaxed">
              Importing <span className="break-all">{importPending.name}</span> will{" "}
              <strong>replace all current files</strong> on the server, including worlds and
              configs. This cannot be undone.
            </p>
            <div className="border-rule flex justify-end gap-2 border-t-2 px-4 py-2.5">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  setImportPending(null);
                  if (importRef.current) importRef.current.value = "";
                }}
              >
                Cancel
              </Button>
              <Button size="sm" onClick={() => void importZip(importPending)}>
                Replace files
              </Button>
            </div>
          </div>
        </div>
      ) : null}

      {editing ? (
        <div
          className="bg-black/55 fixed inset-0 z-50 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
          aria-label={`Editing ${editing.path}`}
        >
          <div className="border-border bg-background flex max-h-[80vh] w-full max-w-2xl flex-col border-2 shadow-hard-lg">
            <p className="border-rule font-display border-b-2 px-4 py-2.5 text-xs uppercase">
              edit <span className="font-mono normal-case">/{editing.path}</span>
            </p>
            <textarea
              className="min-h-64 flex-1 resize-none bg-transparent p-4 font-mono text-sm outline-none"
              value={editing.text}
              spellCheck={false}
              onChange={e => setEditing({ path: editing.path, text: e.target.value })}
            />
            <div className="border-rule flex justify-end gap-2 border-t-2 px-4 py-2.5">
              <Button variant="secondary" size="sm" onClick={() => setEditing(null)}>
                Cancel
              </Button>
              <Button size="sm" onClick={() => void saveEditor()}>
                Save
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  );
}
