// Maps GraphQL operation names to a human-readable "trigger" label for the
// dev query log — which view most likely fired the request. Best-effort:
// several views may share an operation, so this is not a strict mapping.

const OPERATION_TRIGGERS: Record<string, string> = {
  NodeAdjacencyMatrix: "DSM",
  NodesAdjacencyMatrix: "DSM",
  FilteredChildren: "Paths",
  FilteredDependencies: "Paths",
  DependencyEdges: "Paths",
  CrossReferencesUsedBy: "Cross-Reference",
  CrossReferencesUses: "Cross-Reference",
  CrossReferencesNodePredecessors: "Cross-Reference",
  CrossReferenceLeftChildren: "Cross-Reference",
  CrossReferenceRightChildren: "Cross-Reference",
  CrossReferenceCenterMarkedByLeft: "Cross-Reference",
  CrossReferenceCenterMarkedByRight: "Cross-Reference",
};

export function triggerForOperation(
  operationName: string | null | undefined,
): string {
  if (!operationName) {
    return "unknown";
  }
  return OPERATION_TRIGGERS[operationName] ?? operationName;
}

export function buildGraphiqlDeepLink(
  queryText: string,
  variables: unknown,
): string {
  const params = new URLSearchParams();
  params.set("query", queryText);
  params.set("variables", JSON.stringify(variables ?? {}, null, 2));
  return `/graphiql.html?${params.toString()}`;
}
