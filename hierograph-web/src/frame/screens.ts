import type { LinkProps } from "@tanstack/react-router";

// A screen the frame shows: its route, its label in the navigation, and whether
// it needs a loaded graph. The frame shows a message instead of a screen that
// needs a graph while none is loaded. The routes pass the list of screens to
// the frame, so the frame never imports a screen.
export type Screen = {
  path: NonNullable<LinkProps["to"]>;
  label: string;
  needsGraph: boolean;
};

// The screen `pathname` belongs to: the one with the longest matching path.
export function currentScreen(
  screens: readonly Screen[],
  pathname: string,
): Screen | undefined {
  const path = normalize(pathname);
  let best: Screen | undefined;
  for (const screen of screens) {
    const screenPath = normalize(String(screen.path));
    const matches =
      screenPath === "/"
        ? path === "/"
        : path === screenPath || path.startsWith(`${screenPath}/`);
    if (matches && (!best || screenPath.length > String(best.path).length)) {
      best = screen;
    }
  }
  return best;
}

function normalize(path: string): string {
  const trimmed = path.replace(/\/+$/, "");
  return trimmed === "" ? "/" : trimmed;
}
