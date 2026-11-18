import { createContext, type ReactNode, use } from "react";

// What a graph query is answered in: the graph build (`generation`) and the
// hierarchy view (`view`, from the URL). Every query that reads the graph puts
// both into its key (`graphQueryKey`): within one generation the cache never
// goes stale (`staleTime: Infinity`), and a new generation refetches
// everything, with no manual invalidation.
export type GraphScope = {
  generation: string;
  view: string | undefined;
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
// query's own parts.
export function graphQueryKey(
  vertical: string,
  scope: GraphScope,
  ...parts: unknown[]
): unknown[] {
  return [vertical, "graph", scope.generation, scope.view ?? null, ...parts];
}
