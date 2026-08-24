// The Lodeway cube mark plus "lodeway labs / paper" in the display voice.
// The cube restates favicon.svg's geometry: the survey green shaded by the iso engine's
// three-face model (top 1 / south 0.8 / east 0.6), identical in both themes.

import { cn } from "@/lib/utils";

export function CubeMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 41.569 48"
      className={cn("w-[1.15em] shrink-0", className)}
      aria-hidden="true"
    >
      <polygon points="20.785,0 41.569,12 20.785,24 0,12" fill="#2e7d3a" />
      <polygon points="0,12 20.785,24 20.785,48 0,36" fill="#25642e" />
      <polygon points="41.569,12 20.785,24 20.785,48 41.569,36" fill="#1c4b23" />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        "font-display text-foreground inline-flex items-center gap-2 text-base font-bold tracking-[-0.05em]",
        className,
      )}
    >
      <CubeMark />
      <span>lodeway labs</span>
      <span className="text-muted-foreground font-normal">/ paper</span>
    </span>
  );
}
