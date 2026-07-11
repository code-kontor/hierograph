export type GraphColors = {
  nodeFill: string;
  nodeBorder: string;
  nodeHoverBorder: string;
  nodeLabel: string;
  containerFill: string;
  containerBorder: string;
  containerHeaderLabel: string;
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
    // Container placeholders (see comment above): the container recedes behind
    // its leaf children (--hg-bg vs leaf --hg-panel) with a muted header label.
    // Follow-up design task will define dedicated `--hg-graph-container-*`.
    containerFill: get("--hg-bg"),
    containerBorder: get("--hg-border-strong"),
    containerHeaderLabel: get("--hg-fg-muted"),
    edge: get("--hg-fg-muted"),
    edgeBackward: get("--hg-status-error"),
    edgeLabel: get("--hg-fg-subtle"),
  };
}
