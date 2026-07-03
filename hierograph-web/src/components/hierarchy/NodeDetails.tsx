import { useQuery } from "@tanstack/react-query";
import { createElement } from "react";

import { nodeDetailQueryOptions } from "@/queries/hierarchical-graph";

import { getNodeIcon } from "./nodeIcon";
import { useSelection } from "./SelectionContext";

export function NodeDetails() {
  const { focusedId } = useSelection();

  if (focusedId == null) {
    return <p className="text-muted-foreground text-sm">No node focused.</p>;
  }

  return <NodeDetailsInner id={focusedId} />;
}

type NodeDetailsInnerProps = { id: string };

function NodeDetailsInner({ id }: NodeDetailsInnerProps) {
  const { data, isPending, isError } = useQuery(nodeDetailQueryOptions(id));

  if (isPending) {
    return (
      <p className="text-muted-foreground text-sm">Loading node details…</p>
    );
  }

  if (isError) {
    return (
      <p className="text-destructive text-sm">Could not load node details.</p>
    );
  }

  const node = data.hierarchicalGraph?.node;

  if (!node) {
    return <p className="text-muted-foreground text-sm">Node not found.</p>;
  }

  return (
    <div className="flex flex-col gap-3 text-sm">
      <div className="flex items-center gap-2">
        {createElement(getNodeIcon(node.type), {
          className: "text-muted-foreground h-5 w-5 shrink-0",
        })}
        <span className="font-semibold">{node.text}</span>
        <span className="text-muted-foreground">{node.type}</span>
      </div>
      {node.properties.length > 0 ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
          {node.properties.map((entry) => (
            <>
              <dt
                key={`k-${entry.key}`}
                className="text-muted-foreground font-mono text-xs"
              >
                {entry.key}
              </dt>
              <dd key={`v-${entry.key}`} className="text-xs break-all">
                {entry.value ?? "—"}
              </dd>
            </>
          ))}
        </dl>
      ) : (
        <p className="text-muted-foreground text-xs">No properties.</p>
      )}
    </div>
  );
}
