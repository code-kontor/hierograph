import { useQuery } from "@tanstack/react-query";
import {
  Check,
  ChevronUp,
  Copy,
  ExternalLink,
  GripVertical,
  X,
} from "lucide-react";
import { createElement, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { cn } from "@/design-system/cn";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/design-system/ui/tabs";
import { useLocalStorage } from "@/design-system/useLocalStorage";
import { getNodeIcon } from "@/graph/nodeIcon";
import { nodeDetailQueryOptions } from "@/graph/queries";
import { buildGraphiqlDeepLink } from "@/graphql/queryTriggerLabels";
import {
  type NodeDetailsTab,
  useNodeDetailsWidget,
  useSelection,
} from "@/selection/SelectionContext";

import { NodePropertyRow } from "./NodePropertyRow";
import { QueryLogPanel } from "./QueryLogPanel";

const WIDGET_WIDTH = 384;
// Keep at least this many px of the widget on screen so title/tabs/footer stay reachable
const MIN_VISIBLE_HEIGHT = 160;
const PRIORITY_KEYS = ["fqn", "sourceFileName", "valid", "visibility"] as const;

// A ready-to-run exploration query for the "Open in GraphiQL" link: the node's
// own details plus a first level of children — a useful starting point to keep
// drilling from inside GraphiQL, not just a copy of what this tab already shows.
const GRAPHIQL_EXPLORE_QUERY = `query NodeExplore($id: ID!) {
  hierarchicalGraph {
    node(id: $id) {
      id
      text
      type
      properties {
        key
        value
      }
      children {
        nodes {
          id
          text
          type
          hasChildren
        }
      }
    }
  }
}
`;

type NodeDetailsWidgetBodyProps = { id: string | null };

type NodeDetailsWidgetInnerProps = { id: string };

type NodeIdLineProps = { id: string };

function NodeIdLine({ id }: NodeIdLineProps) {
  const [copied, setCopied] = useState(false);

  function handleCopy() {
    navigator.clipboard.writeText(id).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      title="Copy node id"
      className="text-fg-subtle hover:text-fg -mt-1 flex w-fit max-w-full items-center gap-1.5 font-mono text-[11px]"
    >
      <span className="text-fg-muted shrink-0">id</span>
      <span className="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">
        {id}
      </span>
      {copied ? (
        <Check className="size-3 shrink-0" />
      ) : (
        <Copy className="size-3 shrink-0" />
      )}
    </button>
  );
}

function NodeDetailsWidgetInner({ id }: NodeDetailsWidgetInnerProps) {
  const { data, isPending, isError } = useQuery(nodeDetailQueryOptions(id));

  if (isPending) {
    return (
      <div className="flex flex-col gap-3 px-4 py-3.5 text-sm">
        <div className="mb-1 flex items-center gap-[9px]">
          <span className="text-fg font-mono text-[14px] font-semibold">—</span>
        </div>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col gap-3 px-4 py-3.5 text-sm">
        <p className="text-fg-muted font-mono text-xs">Failed to load.</p>
      </div>
    );
  }

  const node = data.hierarchicalGraph?.node;

  if (!node) {
    return (
      <div className="flex flex-col gap-3 px-4 py-3.5 text-sm">
        <div className="mb-1 flex items-center gap-[9px]">
          <span className="text-fg font-mono text-[14px] font-semibold">—</span>
        </div>
      </div>
    );
  }

  const filtered = node.properties.filter((e) => e.key !== "name");
  const prioritized = PRIORITY_KEYS.flatMap((k) => {
    const entry = filtered.find((e) => e.key === k);
    return entry ? [entry] : [];
  });
  const rest = filtered.filter(
    (e) => !(PRIORITY_KEYS as readonly string[]).includes(e.key),
  );
  const orderedRows = [...prioritized, ...rest];

  return (
    <div className="flex flex-col gap-3 px-4 py-3.5 text-sm">
      <div className="mb-1 flex items-center gap-[9px]">
        {createElement(getNodeIcon(node.type), {
          className: "h-[15px] w-[15px] shrink-0 text-fg-subtle",
        })}
        {/* Intentionally shows the full node text — not subject to the label-format setting (#47). */}
        <span className="text-fg min-w-0 flex-1 overflow-hidden font-mono text-[14px] font-semibold text-ellipsis whitespace-nowrap">
          {node.text}
        </span>
        <span className="border-border text-fg-subtle shrink-0 rounded-[20px] border px-[9px] py-px font-mono text-[11px] font-normal">
          {node.type}
        </span>
      </div>
      <NodeIdLine id={node.id} />
      {orderedRows.length > 0 ? (
        <div className="border-border overflow-hidden rounded-[7px] border">
          {orderedRows.map((entry) => (
            <NodePropertyRow
              key={entry.key}
              propertyKey={entry.key}
              value={entry.value}
            />
          ))}
        </div>
      ) : (
        <p className="text-fg-muted text-xs">No properties.</p>
      )}
      <a
        href={buildGraphiqlDeepLink(GRAPHIQL_EXPLORE_QUERY, { id: node.id })}
        target="_blank"
        rel="noreferrer"
        className="text-fg-subtle hover:text-fg flex w-fit items-center gap-1 text-xs underline"
      >
        <ExternalLink className="size-[13px]" />
        Open in GraphiQL
      </a>
    </div>
  );
}

function NodeDetailsWidgetBody({ id }: NodeDetailsWidgetBodyProps) {
  if (id == null) {
    return (
      <div className="flex flex-col gap-3 px-4 py-3.5 text-sm">
        <div className="mb-1 flex items-center gap-[9px]">
          <span className="text-fg font-mono text-[14px] font-semibold">—</span>
        </div>
        <div className="border-border overflow-hidden rounded-[7px] border" />
      </div>
    );
  }

  return <NodeDetailsWidgetInner id={id} />;
}

export function NodeDetailsWidget() {
  const { focusedId } = useSelection();
  const { open, setOpen, tab, setTab } = useNodeDetailsWidget();
  const [collapsed, setCollapsed] = useLocalStorage(
    "hg.nodeDetailsWidget.collapsed",
    false,
  );

  const defaultPos = {
    x: window.innerWidth - WIDGET_WIDTH - 24,
    y: window.innerHeight - 400 - 24,
  };
  const [pos, setPos] = useLocalStorage("hg.nodeDetailsWidget.pos", defaultPos);

  const dragState = useRef<{
    startX: number;
    startY: number;
    origX: number;
    origY: number;
  } | null>(null);

  useEffect(() => {
    function handleMouseMove(e: MouseEvent) {
      if (!dragState.current) return;
      const dx = e.clientX - dragState.current.startX;
      const dy = e.clientY - dragState.current.startY;
      const newX = Math.max(
        6,
        Math.min(
          dragState.current.origX + dx,
          window.innerWidth - WIDGET_WIDTH - 6,
        ),
      );
      const newY = Math.max(
        6,
        Math.min(
          dragState.current.origY + dy,
          window.innerHeight - MIN_VISIBLE_HEIGHT,
        ),
      );
      setPos({ x: newX, y: newY });
    }

    function handleMouseUp() {
      dragState.current = null;
    }

    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);
    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
    };
  }, [setPos]);

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [setOpen]);

  if (!open) return null;

  function handleTitlebarMouseDown(e: React.MouseEvent) {
    dragState.current = {
      startX: e.clientX,
      startY: e.clientY,
      origX: pos.x,
      origY: pos.y,
    };
  }

  function handleButtonMouseDown(e: React.MouseEvent) {
    e.stopPropagation();
  }

  return createPortal(
    <div
      aria-label="NodeDetailsWidget"
      className="border-border-strong bg-panel flex flex-col overflow-hidden rounded-[8px] border shadow-[var(--hg-shadow-float)]"
      style={{
        position: "fixed",
        left: pos.x,
        top: pos.y,
        width: WIDGET_WIDTH,
        maxHeight: `calc(100vh - ${pos.y}px - 12px)`,
      }}
    >
      {/* Titlebar */}
      <div
        className="bg-panel-header border-border flex h-[34px] shrink-0 cursor-move items-center gap-[6px] border-b px-[10px] select-none"
        onMouseDown={handleTitlebarMouseDown}
      >
        <GripVertical className="text-fg-subtle h-[13px] w-[13px] shrink-0" />
        <span className="text-fg-muted font-mono text-[11px] tracking-[0.06em] uppercase">
          NODE DETAILS
        </span>
        <span className="border-border-strong rounded-[20px] border px-[7px] py-px font-mono text-[9.5px] tracking-[0.06em] uppercase">
          dev
        </span>
        <div className="flex-1" />
        <button
          type="button"
          className={cn(
            "flex h-6 w-6 items-center justify-center rounded ring-inset",
            "hover:bg-panel focus-visible:ring-state-focus-ring focus-visible:ring-2",
          )}
          onMouseDown={handleButtonMouseDown}
          onClick={() => setCollapsed(!collapsed)}
          aria-label={collapsed ? "Expand" : "Collapse"}
        >
          <ChevronUp
            className={cn(
              "h-[14px] w-[14px] transition-transform",
              collapsed && "rotate-180",
            )}
          />
        </button>
        <button
          type="button"
          className={cn(
            "flex h-6 w-6 items-center justify-center rounded ring-inset",
            "hover:bg-panel focus-visible:ring-state-focus-ring focus-visible:ring-2",
          )}
          onMouseDown={handleButtonMouseDown}
          onClick={() => setOpen(false)}
          aria-label="Close"
        >
          <X className="h-[14px] w-[14px]" />
        </button>
      </div>
      {/* Content */}
      {!collapsed && (
        <Tabs
          value={tab}
          onValueChange={(value) => setTab(value as NodeDetailsTab)}
          className="min-h-0 flex-1"
        >
          <TabsList className="border-border shrink-0 border-b">
            <TabsTrigger value="details">Details</TabsTrigger>
            <TabsTrigger value="queries">Queries</TabsTrigger>
          </TabsList>
          <TabsContent
            value="details"
            className="min-h-0 flex-1 overflow-y-auto"
          >
            <NodeDetailsWidgetBody id={focusedId} />
          </TabsContent>
          <TabsContent
            value="queries"
            className="min-h-0 flex-1 overflow-y-auto"
          >
            <QueryLogPanel />
          </TabsContent>
          <div className="text-fg-subtle border-border shrink-0 border-t px-4 py-2 font-mono text-[10.5px]">
            Dev-only · reflects tree focus · reopen from the navbar
          </div>
        </Tabs>
      )}
    </div>,
    document.body,
  );
}
