import { useQuery } from "@tanstack/react-query";
import { createElement } from "react";

import { Message } from "@/design-system/ui/message";
import { getNodeIcon, nodeDetailQueryOptions } from "@/graph";
import { useSelection } from "@/selection";

import { NodePropertyRow } from "./NodePropertyRow";

const PRIORITY_KEYS = ["fqn", "sourceFileName", "valid", "visibility"] as const;

export function NodeDetails() {
  const { focusedId } = useSelection();

  if (focusedId == null) {
    return (
      <Message variant="empty" title="No node focused">
        Pick a row in the graph to see its details.
      </Message>
    );
  }

  return <NodeDetailsInner id={focusedId} />;
}

type NodeDetailsInnerProps = { id: string };

function NodeDetailsInner({ id }: NodeDetailsInnerProps) {
  const { data, isPending, isError } = useQuery(nodeDetailQueryOptions(id));

  if (isPending) {
    return <Message variant="loading">Loading node details…</Message>;
  }

  if (isError) {
    return (
      <Message variant="error" title="Could not load node details">
        Please retry.
      </Message>
    );
  }

  const node = data.hierarchicalGraph?.node;

  if (!node) {
    return <Message variant="empty" title="Node not found" />;
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
        <span className="text-fg font-mono text-[14px] font-semibold">
          {node.text}
        </span>
        <span className="border-border text-fg-subtle rounded-[20px] border px-[9px] py-px font-mono text-[11px] font-normal">
          {node.type}
        </span>
      </div>
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
    </div>
  );
}
