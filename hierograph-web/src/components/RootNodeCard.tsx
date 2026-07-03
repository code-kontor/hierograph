import { useQuery } from "@tanstack/react-query";

import { rootNodeQueryOptions } from "@/queries/hierarchical-graph";

export function RootNodeCard() {
  const { data, isPending, isError, error } = useQuery(rootNodeQueryOptions());

  if (isPending) {
    return <p className="text-muted-foreground text-sm">Loading root node…</p>;
  }

  if (isError || !data.hierarchicalGraph) {
    console.log(error);

    return (
      <div className="border-destructive/50 max-w-md rounded-lg border p-4 text-sm">
        <p className="text-destructive font-medium">
          Could not load the root node.
        </p>
        <p className="text-muted-foreground mt-1">
          Make sure the hierograph MCP server is running on
          http://localhost:8080 and is serving a store.
        </p>
      </div>
    );
  }

  const { id, text, type } = data.hierarchicalGraph.rootNode;

  return (
    <dl className="grid min-w-72 grid-cols-[auto_1fr] gap-x-4 gap-y-1 rounded-lg border p-4 text-left text-sm">
      <dt className="text-muted-foreground font-medium">id</dt>
      <dd>{id}</dd>
      <dt className="text-muted-foreground font-medium">text</dt>
      <dd>{text}</dd>
      <dt className="text-muted-foreground font-medium">type</dt>
      <dd>{type}</dd>
    </dl>
  );
}
