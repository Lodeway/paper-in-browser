// Pixel Survey button: a drawn box. 2px ink border, hard offset shadow, and the active
// state is the button translating into its own shadow. Mirrors lodeway's button.tsx,
// minus the radix Slot (this page never renders a button as a link).

import { cva, type VariantProps } from "class-variance-authority";
import * as React from "react";

import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-all disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg:not([class*='size-'])]:size-4 shrink-0 [&_svg]:shrink-0 outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground border-2 border-border shadow-hard-sm hover:bg-primary/90 active:translate-x-[3px] active:translate-y-[3px] active:shadow-hard-xs",
        destructive:
          "bg-destructive text-white border-2 border-border shadow-hard-sm hover:bg-destructive/90 active:translate-x-[3px] active:translate-y-[3px] active:shadow-hard-xs",
        secondary:
          "bg-background text-foreground border-2 border-border shadow-hard-sm hover:bg-accent active:translate-x-[3px] active:translate-y-[3px] active:shadow-hard-xs",
        ghost: "hover:bg-accent hover:text-accent-foreground dark:hover:bg-accent/50",
      },
      size: {
        default: "h-9 px-4 py-2",
        sm: "h-8 gap-1.5 px-3",
        cta: "font-display h-11 px-6 text-sm font-bold uppercase tracking-wide",
        icon: "size-9",
        "icon-sm": "size-8",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

function Button({
  className,
  variant,
  size,
  ...props
}: React.ComponentProps<"button"> & VariantProps<typeof buttonVariants>) {
  return <button className={cn(buttonVariants({ variant, size, className }))} {...props} />;
}

export { Button, buttonVariants };
