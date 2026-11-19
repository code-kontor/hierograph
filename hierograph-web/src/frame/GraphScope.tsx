import { createContext, type ReactNode, use } from "react";

// What a graph query is answered in: the graph build (`generation`) and the
// hierarchy view (`view`, from the URL), and with a view the version of the
// view documents (`viewsVersion`). Every query that reads the graph puts them
// into its key (`graphQueryKey`): within one generation the cache never goes
// stale (`staleTime: Infinity`), and a new generation refetches everything,
// with no manual invalidation; so does a changed view document for the queries
// in a view, e.g. after the agent redefined it.
export type GraphScope = {
  generation: string;
  view: string | undefined;
  viewsVersion: string | null;
};

const GraphScopeContext = createContext<GraphScope | undefined>(undefined);

type GraphScopeProviderProps = {
  scope: GraphScope | undefined;
  children: ReactNode;
};

export function GraphScopeProvider({
  scope,
  children,
}: GraphScopeProviderProps) {
  return <GraphScopeContext value={scope}>{children}</GraphScopeContext>;
}

// The scope graph queries run in; `undefined` while there is no graph to query
// (not loaded yet, unavailable, or the hierarchy view is unknown or invalid).
// A graph query waits until it is defined: `enabled: scope !== undefined`.
export function useGraphScope(): GraphScope | undefined {
  return use(GraphScopeContext);
}

// The query key of a graph query: the vertical, then the scope, then the
// query's own parts. The views' version counts only in a view: a query without
// one doesn't depend on any view document.
export function graphQueryKey(
  vertical: string,
  scope: GraphScope,
  ...parts: unknown[]
): unknown[] {
  return [
    vertical,
    "graph",
    scope.generation,
    scope.view ?? null,
    scope.view === undefined ? null : scope.viewsVersion,
    ...parts,
  ];
}
