export type DsmColors = {
  empty: string;
  grid: string;
  diagonal: string;
  cycle: string;
  marker: string;
  outline: string;
  label: string;
};

export function resolveDsmColors(): DsmColors {
  const style = getComputedStyle(document.documentElement);
  const get = (token: string) => style.getPropertyValue(token).trim();
  return {
    empty: get("--hg-dsm-empty"),
    grid: get("--hg-dsm-grid"),
    diagonal: get("--hg-dsm-diagonal"),
    cycle: get("--hg-dsm-cycle"),
    marker: get("--hg-dsm-marker"),
    outline: get("--hg-dsm-outline"),
    label: get("--hg-dsm-label"),
  };
}
