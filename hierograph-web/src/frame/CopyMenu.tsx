import { useQuery } from "@tanstack/react-query";
import { Copy } from "lucide-react";

import { MiddleTruncated } from "@/design-system/graph/MiddleTruncated";
import {
  ContextMenuItem,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
} from "@/design-system/ui/context-menu";
import { useCopyToClipboard } from "@/design-system/useCopyToClipboard";
import {
  type CopyEntry,
  type CopyNode,
  dependencyCopyEntries,
  needsSourceLocation,
  nodeCopyEntries,
} from "@/frame/copyEntries";
import { type GraphScope, useGraphScope } from "@/frame/GraphScope";
import { nodeSourceLocationQueryOptions } from "@/frame/queries";

// The Copy submenu of a graph element's context menu (#88), as IntelliJ
// IDEA's "Copy Path/Reference…": each entry with a preview of what it copies,
// only those that make sense for the element (see `copyEntries.ts`).

type CopyNodeSubmenuProps = {
  node: CopyNode & { id: string };
};

// A node's Copy submenu. A type's paths come from the server, asked for when
// the submenu opens; its names are there at once.
export function CopyNodeSubmenu({ node }: CopyNodeSubmenuProps) {
  return (
    <ContextMenuSub>
      <ContextMenuSubTrigger>
        <Copy />
        Copy
      </ContextMenuSubTrigger>
      <ContextMenuSubContent aria-label="Copy">
        <CopyNodeItems node={node} />
      </ContextMenuSubContent>
    </ContextMenuSub>
  );
}

function CopyNodeItems({ node }: CopyNodeSubmenuProps) {
  const scope = useGraphScope();
  if (!needsSourceLocation(node) || scope === undefined) {
    return <CopyItems entries={nodeCopyEntries(node, undefined, undefined)} />;
  }
  return <CopyTypeItems scope={scope} node={node} />;
}

type CopyTypeItemsProps = CopyNodeSubmenuProps & {
  scope: GraphScope;
};

// A type's entries: its names, and its paths once the server located its
// source file (none, if it couldn't).
function CopyTypeItems({ scope, node }: CopyTypeItemsProps) {
  const location = useQuery(nodeSourceLocationQueryOptions(scope, node.id));
  return (
    <>
      <CopyItems
        entries={nodeCopyEntries(
          node,
          location.data?.source,
          location.data?.workspace,
        )}
      />
      {location.isPending && (
        <ContextMenuItem disabled>Looking for the source file…</ContextMenuItem>
      )}
    </>
  );
}

type CopyItemsProps = {
  entries: CopyEntry[];
};

function CopyItems({ entries }: CopyItemsProps) {
  const items = entries.map((entry) => (
    <CopyItem key={entry.label} entry={entry} />
  ));
  return <>{items}</>;
}

type CopyDependencySubmenuProps = {
  source: CopyNode;
  target: CopyNode;
};

// A dependency's Copy submenu: `source → target`, and each end.
export function CopyDependencySubmenu({
  source,
  target,
}: CopyDependencySubmenuProps) {
  return (
    <ContextMenuSub>
      <ContextMenuSubTrigger>
        <Copy />
        Copy
      </ContextMenuSubTrigger>
      <ContextMenuSubContent aria-label="Copy">
        <CopyItems entries={dependencyCopyEntries(source, target)} />
      </ContextMenuSubContent>
    </ContextMenuSub>
  );
}

type CopyItemProps = {
  entry: CopyEntry;
};

// One entry: what it is, and a preview of the value, cut in the middle when
// long. The value is its accessible description.
function CopyItem({ entry }: CopyItemProps) {
  const { copy } = useCopyToClipboard();
  return (
    <ContextMenuItem
      aria-description={entry.value}
      onSelect={() => copy(entry.value)}
    >
      <span className="shrink-0">{entry.label}</span>
      <MiddleTruncated
        aria-hidden
        text={entry.value}
        className="text-fg-subtle ml-auto max-w-[360px] min-w-0 pl-4 font-mono text-[12px]"
      />
    </ContextMenuItem>
  );
}
