import { queryOptions } from "@tanstack/react-query";

import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

const XREF_TRIGGER = "Cross-Reference";

const crossReferenceExplorerLeftChildrenQuery = graphql(`
  query CrossReferenceExplorerLeftChildren(
    $parentNode: ID!
    $centerNodeIds: [ID!]!
  ) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencedNodes(referencedNodeIds: $centerNodeIds) {
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
`);

export function crossReferenceExplorerLeftChildrenQueryOptions(
  parentNodeId: string,
  centerNodeIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "left", parentNodeId, centerNodeIds],
    async queryFn() {
      return execute(
        crossReferenceExplorerLeftChildrenQuery,
        {
          parentNode: parentNodeId,
          centerNodeIds,
        },
        XREF_TRIGGER,
      );
    },
  });
}

const crossReferenceExplorerRightChildrenQuery = graphql(`
  query CrossReferenceExplorerRightChildren(
    $parentNode: ID!
    $centerNodeIds: [ID!]!
  ) {
    hierarchicalGraph {
      node(id: $parentNode) {
        childrenFilteredByReferencingNodes(referencingNodeIds: $centerNodeIds) {
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
`);

export function crossReferenceExplorerRightChildrenQueryOptions(
  parentNodeId: string,
  centerNodeIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "right", parentNodeId, centerNodeIds],
    async queryFn() {
      return execute(
        crossReferenceExplorerRightChildrenQuery,
        {
          parentNode: parentNodeId,
          centerNodeIds,
        },
        XREF_TRIGGER,
      );
    },
  });
}

// Related-node queries: the full (unbounded) set of center nodes related to a
// partner selection, over the whole graph — not limited to the currently
// loaded center rows. This is what makes hits inside not-yet-expanded branches
// discoverable (the count-badge / hint-bar feature).
//
// Both fields aggregate the selection over its own subtree (accumulatedOutgoing
// / accumulatedIncoming roll up successors), so a container selection reaches
// all its descendants' edges without an explicit nodesToConsider argument. The
// result is the raw endpoint set (leaf hits), matching the design: only the
// actual related nodes light up; their collapsed ancestor folders are surfaced
// separately via the predecessor batch + badges.
//
// Direction mirrors the Details pane pivot (dependencies-details-anbindung.md,
// Regel 3): selecting on the left ("Used by") pivots to "Everything the
// partner uses" — highlight what the selected partner uses (referencedNodes);
// selecting on the right ("Uses") pivots to "Everything that uses the
// partner" — highlight who uses the selected partner (referencingNodes).
const crossReferenceExplorerCenterRelatedByLeftQuery = graphql(`
  query CrossReferenceExplorerCenterRelatedByLeft($selectionIds: [ID!]!) {
    hierarchicalGraph {
      nodes(ids: $selectionIds) {
        referencedNodes {
          nodeIds
        }
      }
    }
  }
`);

export function crossReferenceExplorerCenterRelatedByLeftQueryOptions(
  selectionIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "relatedByLeft", selectionIds],
    async queryFn() {
      return execute(
        crossReferenceExplorerCenterRelatedByLeftQuery,
        {
          selectionIds,
        },
        XREF_TRIGGER,
      );
    },
  });
}

const crossReferenceExplorerCenterRelatedByRightQuery = graphql(`
  query CrossReferenceExplorerCenterRelatedByRight($selectionIds: [ID!]!) {
    hierarchicalGraph {
      nodes(ids: $selectionIds) {
        referencingNodes {
          nodeIds
        }
      }
    }
  }
`);

export function crossReferenceExplorerCenterRelatedByRightQueryOptions(
  selectionIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "relatedByRight", selectionIds],
    async queryFn() {
      return execute(
        crossReferenceExplorerCenterRelatedByRightQuery,
        {
          selectionIds,
        },
        XREF_TRIGGER,
      );
    },
  });
}

// Predecessor chains for the related hits, batched by id. `predecessors`
// returns each hit's full ancestor chain (nearest first); the mapping to
// collapsed ancestor rows is done purely by id, never by fqn splitting.
const crossReferenceExplorerCenterPredecessorsQuery = graphql(`
  query CrossReferenceExplorerCenterPredecessors($relatedIds: [ID!]!) {
    hierarchicalGraph {
      nodes(ids: $relatedIds) {
        nodes {
          id
          predecessors {
            id
          }
        }
      }
    }
  }
`);

export function crossReferenceExplorerCenterPredecessorsQueryOptions(
  relatedIds: string[],
) {
  return queryOptions({
    queryKey: ["crossReferenceExplorer", "predecessors", relatedIds],
    async queryFn() {
      return execute(
        crossReferenceExplorerCenterPredecessorsQuery,
        {
          relatedIds,
        },
        XREF_TRIGGER,
      );
    },
  });
}
