// Two states, no hidden third one. Both icons ship in the markup and the `dark` class
// picks one, so the right icon is on screen before React decides anything.

import { Button } from "@/components/ui/button";
import { toggleTheme } from "@/theme";

const LABEL = "Switch between the light and dark theme";

function Sun() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function Moon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z" />
    </svg>
  );
}

export function ThemeToggle({ className }: { className?: string }) {
  return (
    <Button
      variant="ghost"
      size="icon-sm"
      className={className}
      aria-label={LABEL}
      title={LABEL}
      onClick={toggleTheme}
    >
      <span className="hidden dark:block">
        <Sun />
      </span>
      <span className="block dark:hidden">
        <Moon />
      </span>
    </Button>
  );
}
