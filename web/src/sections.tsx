// The page's structural pieces in the Pixel Survey shape: numbered eyebrows in the
// display face, drawn boxes with hard shadows, and 4x4 pixel glyphs kept as data.

import { cn } from "@/lib/utils";

export function Container({ className, ...rest }: React.ComponentProps<"div">) {
  return <div className={cn("mx-auto w-full max-w-[1160px] px-5 sm:px-8", className)} {...rest} />;
}

export function Eyebrow({ className, ...rest }: React.ComponentProps<"p">) {
  return (
    <p
      className={cn("font-display text-signal-ink text-xs tracking-[0.18em] uppercase", className)}
      {...rest}
    />
  );
}

type GlyphInk = "signal" | "ink" | "pale";

export interface GlyphCell {
  col: string;
  row: string;
  ink: GlyphInk;
}

const GLYPH_INK: Record<GlyphInk, string> = {
  signal: "bg-signal",
  ink: "bg-foreground",
  pale: "bg-rule",
};

export function PixelGlyph({ cells }: { cells: GlyphCell[] }) {
  return (
    <div aria-hidden="true" className="my-4 grid size-10 grid-cols-4 grid-rows-4 gap-0.5">
      {cells.map((cell, index) => (
        <span
          key={index}
          className={GLYPH_INK[cell.ink]}
          style={{ gridColumn: cell.col, gridRow: cell.row }}
        />
      ))}
    </div>
  );
}

const STEPS: { key: string; title: string; body: string; glyph: GlyphCell[] }[] = [
  {
    key: "01 / bytecode",
    title: "Paper, ported to Java 8",
    body: "Paper 26.2 is built for Java 25. JVMDowngrader rewrites each jar down to Java 8 bytecode ahead of time; Java 8 is the newest class format CheerpJ executes.",
    glyph: [
      { col: "1 / 3", row: "1 / 3", ink: "ink" },
      { col: "3", row: "2", ink: "pale" },
      { col: "2", row: "3", ink: "pale" },
      { col: "3 / 5", row: "3 / 5", ink: "signal" },
    ],
  },
  {
    key: "02 / runtime",
    title: "CheerpJ runs the JVM here",
    body: "CheerpJ compiles the JVM to WebAssembly and JavaScript, so the server runs inside this tab. The world is written to your browser's IndexedDB and survives a reload.",
    glyph: [
      { col: "1 / 5", row: "1 / 2", ink: "signal" },
      { col: "1", row: "2 / 5", ink: "ink" },
      { col: "4", row: "2 / 5", ink: "ink" },
      { col: "2 / 4", row: "3 / 4", ink: "pale" },
    ],
  },
  {
    key: "03 / tunnel",
    title: "A tunnel makes it joinable",
    body: "A browser tab cannot listen on a port, so a WebSocket tunnel gives every visitor their own address. Real Minecraft Java clients connect to it like any other server.",
    glyph: [
      { col: "1", row: "1 / 3", ink: "pale" },
      { col: "1 / 4", row: "3 / 4", ink: "signal" },
      { col: "2 / 5", row: "2 / 3", ink: "signal" },
      { col: "4", row: "3 / 5", ink: "ink" },
    ],
  },
];

export function HowItWorks() {
  return (
    <section id="how-it-works">
      <Container className="py-14 sm:py-16">
        <Eyebrow>02 / how it works</Eyebrow>
        <h2 className="font-display mt-4 mb-8 text-xl font-bold tracking-[-0.02em] sm:text-2xl">
          What runs where
        </h2>
        <ol className="grid gap-6 sm:grid-cols-3">
          {STEPS.map(step => (
            <li key={step.key} className="border-border bg-card flex flex-col border-2 p-6 shadow-hard">
              <span className="font-display text-muted-foreground/80 text-[0.7rem] tracking-[0.14em] uppercase">
                {step.key}
              </span>
              <PixelGlyph cells={step.glyph} />
              <h3 className="font-display text-base font-bold">{step.title}</h3>
              <p className="text-muted-foreground mt-3 text-sm leading-relaxed text-pretty">{step.body}</p>
            </li>
          ))}
        </ol>
      </Container>
    </section>
  );
}
