// Mojang's EULA, put in front of the first start. Agreement is stored so the dialog
// appears once per browser; the flag maps to the launcher's --labs-eula-accepted.

import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";

export const EULA_STORAGE_KEY = "paper-labs.eula";

export function eulaAccepted(): boolean {
  try {
    return localStorage.getItem(EULA_STORAGE_KEY) === "accepted";
  } catch {
    return false;
  }
}

export function EulaDialog({
  open,
  onAgree,
  onDecline,
}: {
  open: boolean;
  onAgree: () => void;
  onDecline: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      onClose={onDecline}
      className="border-border bg-background text-foreground m-auto w-full max-w-md border-2 p-0 shadow-hard-lg backdrop:bg-black/55"
    >
      <p className="border-rule font-display border-b-2 px-5 py-3 text-xs uppercase">
        01 / before the first start
      </p>
      <div className="space-y-3 px-5 py-4 text-sm leading-relaxed">
        <p>
          Running a Minecraft server, even one inside a browser tab, means agreeing to{" "}
          <a
            href="https://aka.ms/MinecraftEULA"
            target="_blank"
            rel="noreferrer"
            className="text-signal-ink underline underline-offset-4"
          >
            Mojang&rsquo;s End User License Agreement
          </a>
          .
        </p>
        <p className="text-muted-foreground">
          Agreeing here writes <span className="font-mono">eula=true</span> for the server, the
          same as editing <span className="font-mono">eula.txt</span> on a normal install.
        </p>
      </div>
      <div className="border-rule flex justify-end gap-2 border-t-2 px-5 py-3">
        <Button
          variant="secondary"
          size="sm"
          onClick={() => {
            ref.current?.close();
          }}
        >
          Decline
        </Button>
        <Button
          size="sm"
          onClick={() => {
            try {
              localStorage.setItem(EULA_STORAGE_KEY, "accepted");
            } catch {
              /* private mode: accepted for this page view */
            }
            onAgree();
          }}
        >
          Agree and start
        </Button>
      </div>
    </dialog>
  );
}
