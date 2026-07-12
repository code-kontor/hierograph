import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import {
  ArrowRightFromLine,
  ArrowRightToLine,
  ChevronsDown,
  Search,
} from "lucide-react";
import {
  type RefObject,
  useEffect,
  useEffectEvent,
  useRef,
  useState,
} from "react";
import { twMerge } from "tailwind-merge";

import { Pane } from "@/design-system/layout/Pane";
import { HelpPopoverButton } from "@/design-system/ui/help-popover";
import { Message } from "@/design-system/ui/message";
import {
  nodeChildrenQueryOptions,
  rootNodeQueryOptions,
} from "@/graph/queries";
import { useSelection } from "@/selection/SelectionContext";
import { AsyncTree, type AsyncTreeHandle } from "@/tree/AsyncTree";
import { TreeSettingsMenu } from "@/tree/TreeSettingsMenu";
import type {
  TreeSettings,
  TreeSettingsControls,
} from "@/tree/useTreeSettings";

import {
  crossReferenceExplorerCenterPredecessorsQueryOptions,
  crossReferenceExplorerCenterRelatedByLeftQueryOptions,
  crossReferenceExplorerCenterRelatedByRightQueryOptions,
  crossReferenceExplorerLeftChildrenQueryOptions,
  crossReferenceExplorerRightChildrenQueryOptions,
} from "./queries";
import { useNodeLabel } from "./useNodeLabel";

export type CrossReferenceExplorerViewProps = {
  settings: TreeSettings;
  centerTreeRef: RefObject<AsyncTreeHandle | null>;
} & TreeSettingsControls;

type ColumnInspectButtonProps = {
  label: string;
  active: boolean;
  onClick: () => void;
};

// Explicit affordance to send the aggregated Center↔column relationship to
// the Dependencies Details pane (see docs/dependency-details-wiring.md —
// Aggregate pinning) — only rendered once a center node is selected.
function ColumnInspectButton({
  label,
  active,
  onClick,
}: ColumnInspectButtonProps) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={twMerge(
        "border-border-strong bg-panel flex size-[20px] shrink-0 items-center justify-center rounded-[4px] border",
        active && "bg-[var(--hg-accent)]",
      )}
    >
      <Search
        className={twMerge(
          "size-[12px] text-[var(--hg-accent)]",
          active && "text-[var(--hg-accent-fg)]",
        )}
      />
    </button>
  );
}

type ColumnInspectHintProps = {
  message: string;
};

// Inline hint shown in a column header when Inspect is clicked while the
// center selection has more than one node — the aggregate needs a single
// anchor. Rendered as a plain text line (like the column sub-labels) rather
// than the full Message component, which doesn't fit the narrow header.
function ColumnInspectHint({ message }: ColumnInspectHintProps) {
  return (
    <div
      className={twMerge(
        "mt-0.5 truncate text-[10.5px] text-[var(--hg-accent)]",
      )}
    >
      {message}
    </div>
  );
}

const CROSS_REFERENCE_HELP_LABEL = "About the Cross-Reference Explorer";

function CrossReferenceHelpContent() {
  return (
    <>
      <p>
        The Cross-Reference Explorer answers <em>who depends on what</em>: pick
        any type or package and see, side by side, what uses it and what it uses
        — a fast way to gauge the ripple of a planned refactoring before you
        make it.
      </p>
      <p>
        Center is the anchor. <em>Used by</em> (left, incoming) lists everything
        that uses the center; <em>Uses</em> (right, outgoing) lists everything
        the center uses. Click a node in either partner column to pivot: it
        becomes the subject and the center highlights its own dependencies. The
        inspect buttons (🔍) open the aggregated Center↔column relationship in
        Dependencies Details.
      </p>
    </>
  );
}

export function CrossReferenceExplorerView({
  settings,
  centerTreeRef,
  setShowIndentGuides,
  setAutoExpandSingleChildren,
  setPreserveSelectionOnCollapse,
  setLabelFormat,
}: CrossReferenceExplorerViewProps) {
  const { setCellSelection, setFocusedId, setFocusedName } = useSelection();
  const queryClient = useQueryClient();

  const setCell = (source: string | undefined, target: string | undefined) =>
    setCellSelection(
      source !== undefined && target !== undefined
        ? { sourceNodeId: source, targetNodeId: target }
        : null,
    );

  const {
    data: rootData,
    isPending: rootPending,
    isError: rootError,
  } = useQuery(rootNodeQueryOptions());
  const rootNode = rootData?.hierarchicalGraph?.rootNode ?? null;

  // URL-backed view state (source of truth): the center selection and the
  // pinned aggregate direction live in the search params. Writes go through
  // `navigate`; no mirror `useState`, no URL→state→navigate effect.
  const search = useSearch({ from: "/cross-reference-explorer" });
  const navigate = useNavigate({ from: "/cross-reference-explorer" });
  const centerSelectedIds = search.center_ids ?? [];
  // Which column's "Everything Center uses/is used by" aggregate is pinned to
  // the Dependencies Details pane (see docs/dependency-details-wiring.md —
  // Aggregate pinning). Derived from the `aggregated` param (used-by↔left,
  // uses↔right); dropped from the URL whenever the center changes or a partner
  // takes over (Precedence & reset).
  const aggregateSide: "left" | "right" | null = search.aggregated
    ? search.aggregated === "used-by"
      ? "left"
      : "right"
    : null;

  // Partner-column selection stays deliberately local (never serialized): the
  // partner nodes have no URL representation, so a reload has nothing to
  // re-select (see task #0123 — "bewusst nicht in die URL").
  const [leftSelectedIds, setLeftSelectedIds] = useState<string[]>([]);
  const [rightSelectedIds, setRightSelectedIds] = useState<string[]>([]);
  const [lastActiveSide, setLastActiveSide] = useState<"left" | "right" | null>(
    null,
  );

  // Drop the pinned aggregate (and active side) from the URL — used when a
  // partner selection takes over. Replace, not push: a partner click is a
  // transient, non-serialized interaction, so it must not add a history entry.
  const clearAggregateFromUrl = async () => {
    if (search.side === undefined && search.aggregated === undefined) return;
    await navigate({
      search: (prev) => ({ ...prev, side: undefined, aggregated: undefined }),
      replace: true,
    });
  };
  // Which column shows the "select exactly one center node" inline hint
  // after an Inspect click while the center selection has more than one
  // node. Reset whenever the center selection changes.
  const [inspectHintSide, setInspectHintSide] = useState<
    "left" | "right" | null
  >(null);
  const [hiddenHighlightTotal, setHiddenHighlightTotal] = useState(0);
  const leftTreeRef = useRef<AsyncTreeHandle>(null);
  const rightTreeRef = useRef<AsyncTreeHandle>(null);

  const centerSelectionKey = [...centerSelectedIds].sort().join(",");

  const loadCenterChildren = async (parentId: string) => {
    const result = await queryClient.ensureQueryData(
      nodeChildrenQueryOptions(parentId),
    );
    return result.hierarchicalGraph?.node?.children.nodes ?? [];
  };

  const loadLeftChildren = async (parentId: string) => {
    const result = await queryClient.ensureQueryData(
      crossReferenceExplorerLeftChildrenQueryOptions(
        parentId,
        centerSelectedIds,
      ),
    );
    return (
      result.hierarchicalGraph?.node?.childrenFilteredByReferencedNodes.nodes ??
      []
    );
  };

  const loadRightChildren = async (parentId: string) => {
    const result = await queryClient.ensureQueryData(
      crossReferenceExplorerRightChildrenQueryOptions(
        parentId,
        centerSelectedIds,
      ),
    );
    return (
      result.hierarchicalGraph?.node?.childrenFilteredByReferencingNodes
        .nodes ?? []
    );
  };

  const handleCenterSelectedIdsChange = async (ids: string[]) => {
    // Center change pushes a history entry and drops side/aggregated (cascade).
    await navigate({
      search: (prev) => ({
        ...prev,
        center_ids: ids.length > 0 ? ids : undefined,
        side: undefined,
        aggregated: undefined,
      }),
    });
    // The Left/Right trees are remounted via key on a center change and no
    // longer emit an initial onSelectedIdsChange([]), so their (local, non-URL)
    // selections and the active side are reset explicitly here.
    setLeftSelectedIds([]);
    setRightSelectedIds([]);
    setLastActiveSide(null);
    setInspectHintSide(null);
    setCell(undefined, undefined);
  };

  const handleInspect = async (side: "left" | "right") => {
    if (centerSelectedIds.length === 1) {
      setInspectHintSide(null);
      // Inspect always wins: clear any active partner selection on both sides so
      // aggregateSide becomes the single source driving the details pane.
      leftTreeRef.current?.clearSelection();
      rightTreeRef.current?.clearSelection();
      setLeftSelectedIds([]);
      setRightSelectedIds([]);
      setLastActiveSide(null);
      // Pin the aggregate direction in the URL (replace: a view toggle, not a
      // navigation step). used-by↔left, uses↔right.
      const dir = side === "left" ? "used-by" : "uses";
      await navigate({
        search: (prev) => ({ ...prev, side: dir, aggregated: dir }),
        replace: true,
      });
      setCell(
        side === "left" ? rootId : center,
        side === "left" ? center : rootId,
      );
    } else {
      setInspectHintSide(side);
    }
  };

  const handleLeftSelectedIdsChange = async (ids: string[]) => {
    setLeftSelectedIds(ids);
    if (ids.length > 0) {
      rightTreeRef.current?.clearSelection();
      setLastActiveSide("left");
      await clearAggregateFromUrl();
      setCell(ids[0], rootId);
    } else {
      setLastActiveSide((prev) => (prev === "left" ? null : prev));
      setCell(undefined, undefined);
    }
  };

  const handleRightSelectedIdsChange = async (ids: string[]) => {
    setRightSelectedIds(ids);
    if (ids.length > 0) {
      leftTreeRef.current?.clearSelection();
      setLastActiveSide("right");
      await clearAggregateFromUrl();
      setCell(rootId, ids[0]);
    } else {
      setLastActiveSide((prev) => (prev === "right" ? null : prev));
      setCell(undefined, undefined);
    }
  };

  const handleCenterFocusedIdChange = (
    id: string | null,
    name: string | null,
  ) => {
    setFocusedId(id);
    setFocusedName(name);
  };

  // Reveal the URL center selection in the center tree (deep-link reload / Back
  // button): expand each center's ancestor folders and scroll it into view.
  // Never navigates — the single allowed URL-reading effect (no sync loop),
  // analogous to the DSM tree reveal. Gated on `rootNode` so it fires only once
  // the center tree is mounted (the ref would be null while the root query is
  // still pending, which is why this lives in the view, not the page).
  const centerRevealKey = centerSelectedIds.join(",");
  const revealCenters = useEffectEvent((ids: string[]) => {
    queryClient
      .ensureQueryData(
        crossReferenceExplorerCenterPredecessorsQueryOptions(ids),
      )
      .then((data) => {
        const nodes = data.hierarchicalGraph?.nodes.nodes ?? [];
        const ancestorsById = new Map(
          nodes.map((node) => [node.id, node.predecessors.map((p) => p.id)]),
        );
        for (const id of ids) {
          centerTreeRef.current?.revealNode(id, ancestorsById.get(id) ?? []);
        }
      })
      .catch(console.error);
  });
  const lastRevealedCenterKeyRef = useRef<string | null>(null);
  const rootReady = rootNode !== null;
  useEffect(() => {
    if (!rootReady) return;
    if (lastRevealedCenterKeyRef.current === centerRevealKey) return;
    lastRevealedCenterKeyRef.current = centerRevealKey;
    if (centerRevealKey.length === 0) return;
    revealCenters(centerRevealKey.split(","));
  }, [centerRevealKey, rootReady]);

  // Related queries — always called, gated by enabled. The unbounded fields
  // return the full related set over the graph, so no candidate set is needed.
  const leftMarkingEnabled =
    lastActiveSide === "left" && leftSelectedIds.length > 0;
  const rightMarkingEnabled =
    lastActiveSide === "right" && rightSelectedIds.length > 0;

  const { data: leftMarkingData } = useQuery({
    ...crossReferenceExplorerCenterRelatedByLeftQueryOptions(leftSelectedIds),
    enabled: leftMarkingEnabled,
  });

  const { data: rightMarkingData } = useQuery({
    ...crossReferenceExplorerCenterRelatedByRightQueryOptions(rightSelectedIds),
    enabled: rightMarkingEnabled,
  });

  const relatedCenterIds: string[] = leftMarkingEnabled
    ? (leftMarkingData?.hierarchicalGraph?.nodes.referencedNodes.nodeIds ?? [])
    : rightMarkingEnabled
      ? (rightMarkingData?.hierarchicalGraph?.nodes.referencingNodes.nodeIds ??
        [])
      : [];

  // Ancestor chains for the related hits, so hidden hits inside collapsed
  // branches can be counted per collapsed ancestor and revealed on demand.
  const { data: predecessorsData } = useQuery({
    ...crossReferenceExplorerCenterPredecessorsQueryOptions(relatedCenterIds),
    enabled: relatedCenterIds.length > 0,
  });

  // hit id → ancestor ids (nearest first); the mapping is by id only, never by
  // fqn. Derived from predecessorsData alone — the React Compiler memoizes it
  // on that input, so the object stays content-stable and AsyncTree does not
  // churn on every render.
  const predecessorNodes =
    predecessorsData?.hierarchicalGraph?.nodes.nodes ?? [];
  const highlightedAncestors: Record<string, string[]> = {};
  for (const node of predecessorNodes) {
    highlightedAncestors[node.id] = node.predecessors.map((p) => p.id);
  }

  // The cell shown in the Dependencies Details pane is set via `setCell` at
  // the interaction events that change it (see
  // docs/dependency-details-wiring.md; handleInspect,
  // handleLeft/RightSelectedIdsChange,
  // handleCenterSelectedIdsChange above), not derived on every render. Only
  // first-selected ids are used; DependencyDetailsPane takes a single
  // directed pair.
  // - Active Used-by partner P (left) → pivot (P, root), "Everything P uses".
  // - Active Uses partner Q (right) → pivot (root, Q), "Everything that uses Q".
  // - aggregateSide "left" → (root, C), "Everything that uses C".
  // - aggregateSide "right" → (C, root), "Everything C uses".
  // - Otherwise (center-only, nothing, or no aggregate chosen) → null.
  const rootId = rootNode?.id;
  const center = centerSelectedIds[0];

  // Names for the dynamic labels/tooltips below. Both hooks are called
  // unconditionally — gating happens via `enabled` inside useNodeLabel.
  const centerLabel = useNodeLabel(center, settings.labelFormat);
  const centerDisplayLabel =
    centerSelectedIds.length === 1
      ? centerLabel
      : `${centerSelectedIds.length} nodes`;
  // Center as the subject of the "Uses" direction (analogous to the DSM's
  // references/referencing verb pair).
  const centerUsesVerb = centerSelectedIds.length === 1 ? "uses" : "use";
  const partnerId =
    lastActiveSide === "left"
      ? leftSelectedIds[0]
      : lastActiveSide === "right"
        ? rightSelectedIds[0]
        : undefined;
  const partnerLabel = useNodeLabel(partnerId, settings.labelFormat);

  if (rootPending) {
    return (
      <Pane
        title="Cross-Reference Explorer"
        bodyClassName="overflow-hidden p-0"
      >
        <div className="p-4">
          <Message variant="loading" title="Loading hierarchy" />
        </div>
      </Pane>
    );
  }

  if (rootError || !rootNode) {
    return (
      <Pane
        title="Cross-Reference Explorer"
        bodyClassName="overflow-hidden p-0"
      >
        <div className="p-4">
          <Message variant="error" title="Could not load hierarchy root" />
        </div>
      </Pane>
    );
  }

  // Center sub-label: one line per state — no center, center-only (anchor),
  // or an active partner pivot — the partner becomes the subject.
  function centerSubLabel(): string {
    if (centerSelectedIds.length === 0) {
      return "select a node to explore";
    }
    if (lastActiveSide === "left" && leftSelectedIds[0] !== undefined) {
      return `Highlighting what ${partnerLabel} uses`;
    }
    if (lastActiveSide === "right" && rightSelectedIds[0] !== undefined) {
      return `Highlighting what uses ${partnerLabel}`;
    }
    if (aggregateSide === "left") {
      return `Inspecting everything that uses ${centerDisplayLabel}`;
    }
    if (aggregateSide === "right") {
      return `Inspecting everything ${centerDisplayLabel} ${centerUsesVerb}`;
    }
    return `Anchor · ${centerDisplayLabel}`;
  }

  const isSingleCenter = centerSelectedIds.length === 1;
  const leftInspectLabel = isSingleCenter
    ? `Inspect everything that uses ${centerDisplayLabel}`
    : `Inspect works on a single anchor — select exactly one center node (${centerDisplayLabel})`;
  const rightInspectLabel = isSingleCenter
    ? `Inspect everything ${centerDisplayLabel} ${centerUsesVerb}`
    : `Inspect works on a single anchor — select exactly one center node (${centerDisplayLabel})`;
  const inspectHintMessage =
    "Inspect works on a single anchor — select exactly one center node.";

  return (
    <Pane
      title="Cross-Reference Explorer"
      bodyClassName="overflow-hidden p-0"
      toolbar={
        <div className="flex items-center gap-1">
          <HelpPopoverButton label={CROSS_REFERENCE_HELP_LABEL}>
            <CrossReferenceHelpContent />
          </HelpPopoverButton>
          <TreeSettingsMenu
            {...settings}
            setShowIndentGuides={setShowIndentGuides}
            setAutoExpandSingleChildren={setAutoExpandSingleChildren}
            setPreserveSelectionOnCollapse={setPreserveSelectionOnCollapse}
            setLabelFormat={setLabelFormat}
          />
        </div>
      }
    >
      <div className="grid h-full min-h-0 flex-1 grid-cols-3 overflow-hidden">
        {/* Left column */}
        <div className="border-border flex min-w-0 flex-col overflow-auto border-r">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            <div className="flex items-center justify-between gap-2">
              <span
                className="flex min-w-0 items-center gap-1.5 truncate"
                title={
                  centerSelectedIds.length > 0
                    ? `Incoming dependencies — everything that uses ${centerDisplayLabel}. Click a node to pivot to it: the center highlights what that node uses.`
                    : undefined
                }
              >
                <ArrowRightToLine className="size-[13px] shrink-0" />
                Used by
              </span>
              {centerSelectedIds.length > 0 && (
                <ColumnInspectButton
                  label={leftInspectLabel}
                  active={aggregateSide === "left"}
                  onClick={() => handleInspect("left")}
                />
              )}
            </div>
            {centerSelectedIds.length > 0 && (
              <div
                className="text-fg-muted mt-0.5 truncate text-[10.5px]"
                title={`what uses ${centerDisplayLabel}`}
              >
                what uses {centerDisplayLabel}
              </div>
            )}
            {inspectHintSide === "left" && centerSelectedIds.length > 1 && (
              <ColumnInspectHint message={inspectHintMessage} />
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            {centerSelectedIds.length === 0 ? (
              <Message variant="empty" title="No node selected">
                Pick a node in the center tree to see what uses it.
              </Message>
            ) : (
              <AsyncTree
                key={`left-${centerSelectionKey}`}
                ref={leftTreeRef}
                rootNode={rootNode}
                loadChildren={loadLeftChildren}
                onSelectedIdsChange={handleLeftSelectedIdsChange}
                autoExpandOnLoad="all"
                selectionTone="secondary"
                selectionMode="single"
                label="XrefLeft"
                settings={settings}
              />
            )}
          </div>
        </div>
        {/* Center column */}
        <div className="border-border @container flex min-w-0 flex-col overflow-auto border-r">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            <div>Center</div>
            <div
              className="text-fg-muted mt-0.5 truncate text-[10.5px]"
              title={centerSubLabel()}
            >
              {centerSubLabel()}
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            <AsyncTree
              ref={centerTreeRef}
              rootNode={rootNode}
              loadChildren={loadCenterChildren}
              onSelectedIdsChange={handleCenterSelectedIdsChange}
              onFocusedIdChange={handleCenterFocusedIdChange}
              highlightedIds={relatedCenterIds}
              highlightedAncestors={highlightedAncestors}
              onHiddenHighlightCountChange={setHiddenHighlightTotal}
              label="XrefCenter"
              settings={settings}
            />
          </div>
          {hiddenHighlightTotal > 0 && (
            <div className="bg-state-highlighted-bg m-[9px_4px_2px] flex shrink-0 items-center gap-2 rounded-[6px] border border-dashed border-[var(--hl-badge-border)] px-2 py-1">
              <span className="flex h-4 min-w-[17px] shrink-0 items-center justify-center rounded-[8px] border border-[var(--hl-badge-border)] bg-[var(--hl-badge-bg)] px-[5px] font-mono text-[10px] font-semibold text-[var(--hl-badge-fg)] tabular-nums">
                {hiddenHighlightTotal}
              </span>
              <span className="text-fg-muted min-w-0 flex-1 truncate font-mono text-[11.5px] font-medium">
                highlighted nodes in collapsed branches
              </span>
              <button
                type="button"
                title="Expand all hits"
                onClick={() => centerTreeRef.current?.revealHighlighted()}
                className="border-border-strong bg-panel flex h-[26px] min-w-[22px] shrink-0 items-center justify-center gap-1 rounded-[6px] border px-[9px] py-[3px] text-[var(--hg-accent)]"
              >
                <ChevronsDown className="size-[14px]" />
                <span className="hidden text-[11.5px] font-medium @[20rem]:inline">
                  Expand
                </span>
              </button>
            </div>
          )}
        </div>
        {/* Right column */}
        <div className="flex min-w-0 flex-col overflow-auto">
          <div className="border-border text-fg-subtle shrink-0 border-b px-[14px] py-2 font-mono text-[11px]">
            <div className="flex items-center justify-between gap-2">
              <span
                className="flex min-w-0 items-center gap-1.5 truncate"
                title={
                  centerSelectedIds.length > 0
                    ? `Outgoing dependencies — everything ${centerDisplayLabel} ${centerUsesVerb}. Click a node to pivot to it: the center highlights what uses that node.`
                    : undefined
                }
              >
                <ArrowRightFromLine className="size-[13px] shrink-0" />
                Uses
              </span>
              {centerSelectedIds.length > 0 && (
                <ColumnInspectButton
                  label={rightInspectLabel}
                  active={aggregateSide === "right"}
                  onClick={() => handleInspect("right")}
                />
              )}
            </div>
            {centerSelectedIds.length > 0 && (
              <div
                className="text-fg-muted mt-0.5 truncate text-[10.5px]"
                title={`what ${centerDisplayLabel} ${centerUsesVerb}`}
              >
                what {centerDisplayLabel} {centerUsesVerb}
              </div>
            )}
            {inspectHintSide === "right" && centerSelectedIds.length > 1 && (
              <ColumnInspectHint message={inspectHintMessage} />
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto p-1.5">
            {centerSelectedIds.length === 0 ? (
              <Message variant="empty" title="No node selected">
                Pick a node in the center tree to see what it uses.
              </Message>
            ) : (
              <AsyncTree
                key={`right-${centerSelectionKey}`}
                ref={rightTreeRef}
                rootNode={rootNode}
                loadChildren={loadRightChildren}
                onSelectedIdsChange={handleRightSelectedIdsChange}
                autoExpandOnLoad="all"
                selectionTone="secondary"
                selectionMode="single"
                label="XrefRight"
                settings={settings}
              />
            )}
          </div>
        </div>
      </div>
    </Pane>
  );
}
