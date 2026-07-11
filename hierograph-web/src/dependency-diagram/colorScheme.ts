export type GraphColors = {
  nodeFill: string;
  nodeBorder: string;
  nodeHoverBorder: string;
  nodeLabel: string;
  edge: string;
  edgeBackward: string;
  edgeLabel: string;
};

// No dedicated graph/node-link design tokens exist yet (see docs/features/
// dependency-diagram — follow-up design task); mapped onto existing tokens as
// a placeholder until Claude Design defines `--hg-graph-*` tokens. The hover
// accent is mapped onto --hg-accent, falling back to --hg-fg.
export function resolveGraphColors(): GraphColors {
  const style = getComputedStyle(document.documentElement);
  const get = (token: string) => style.getPropertyValue(token).trim();
  return {
    nodeFill: get("--hg-panel"),
    nodeBorder: get("--hg-border-strong"),
    nodeHoverBorder: get("--hg-accent") || get("--hg-fg"),
    nodeLabel: get("--hg-fg"),
    edge: get("--hg-fg-muted"),
    edgeBackward: get("--hg-status-error"),
    edgeLabel: get("--hg-fg-subtle"),
  };
}
