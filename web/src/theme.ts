// Light or dark, and how that decision survives a reload. Light is the brand default;
// the class name is `dark` because that is what globals.css's @custom-variant matches.
// Mirrors lodeway's theme.ts, with this experiment's own storage key.

export type Theme = "light" | "dark";

export const THEME_STORAGE_KEY = "paper-labs.theme";

export function currentTheme(): Theme {
  return document.documentElement.classList.contains("dark") ? "dark" : "light";
}

export function toggleTheme(): void {
  const next: Theme = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.classList.toggle("dark", next === "dark");
  try {
    localStorage.setItem(THEME_STORAGE_KEY, next);
  } catch {
    /* private mode: the choice lasts this page view */
  }
}
