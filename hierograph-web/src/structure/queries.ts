import { queryOptions } from "@tanstack/react-query";

import { graphQueryKey, type GraphScope } from "@/frame/GraphScope";
import { execute } from "@/graphql/client";
import { graphql } from "@/graphql/generated";

import type { GivenEnd, GivenPair, ResolvedPair } from "./givenDependencies";

// The levelized structure map: the root of the hierarchy (in a view, or the
// scanned one), then, per container as it is expanded, its children in rows by
// level with their tangles and feedback dependencies (the server's
// levelization, #61).
export const StructureRootQuery = graphql(`
  query StructureRoot($view: String) {
    hierarchicalGraph(view: $view) {
      rootNode {
        id
      }
    }
  }
`);

export const StructureLevelQuery = graphql(`
  query StructureLevel($view: String, $id: ID!) {
    hierarchicalGraph(view: $view) {
      node(id: $id) {
        id
        children {
          levelization {
            levels {
              index
              nodes {
                id
                text
                type
                hasChildren
              }
            }
            tangles {
              level
              nodes {
                id
              }
              rows {
                index
                nodes {
                  id
                }
              }
            }
            feedbackDependencies {
              sourceNode {
                id
              }
              targetNode {
                id
              }
              weight
            }
          }
        }
      }
    }
  }
`);

export type StructureNode = {
  id: string;
  text: string;
  type: string;
  hasChildren: boolean;
};

// One container's children: the rows bottom first, the tangles (members
// dependents first, their inner rows bottom first) and the feedback
// dependencies heaviest first.
export type StructureLevel = {
  levels: StructureNode[][];
  tangles: { level: number; nodeIds: string[]; rows: string[][] }[];
  feedback: { sourceId: string; targetId: string; weight: number }[];
};

export function structureRootQueryOptions(scope: GraphScope) {
  return queryOptions({
    queryKey: graphQueryKey("structure", scope, "root"),
    async queryFn(): Promise<string | null> {
      const data = await execute(StructureRootQuery, {
        view: scope.view ?? null,
      });
      return data.hierarchicalGraph?.rootNode.id ?? null;
    },
  });
}

export function structureLevelQueryOptions(scope: GraphScope, id: string) {
  return queryOptions({
    queryKey: graphQueryKey("structure", scope, "level", id),
    async queryFn(): Promise<StructureLevel> {
      const data = await execute(StructureLevelQuery, {
        view: scope.view ?? null,
        id,
      });
      const levelization = data.hierarchicalGraph?.node?.children.levelization;
      return {
        levels: (levelization?.levels ?? []).map((level) => level.nodes),
        tangles: (levelization?.tangles ?? []).map((tangle) => ({
          level: tangle.level,
          nodeIds: tangle.nodes.map((node) => node.id),
          rows: tangle.rows.map((row) => row.nodes.map((node) => node.id)),
        })),
        feedback: (levelization?.feedbackDependencies ?? []).map((dep) => ({
          sourceId: dep.sourceNode.id,
          targetId: dep.targetNode.id,
          weight: dep.weight,
        })),
      };
    },
  });
}

// The dependencies between the visible boxes whose content isn't shown: the
// matrix over them, for what the selected box uses and what uses it.
export const StructureCellsQuery = graphql(`
  query StructureCells($view: String, $ids: [ID!]!) {
    hierarchicalGraph(view: $view) {
      nodes(ids: $ids) {
        orderedAdjacencyMatrix {
          orderedNodes {
            id
          }
          cells {
            row
            column
            value
          }
        }
      }
    }
  }
`);

export type StructureCell = { from: string; to: string; weight: number };

// `ids` sorted, so the same boxes share one cache entry whatever their order.
export function structureCellsQueryOptions(scope: GraphScope, ids: string[]) {
  const sorted = [...ids].sort();
  return queryOptions({
    queryKey: graphQueryKey("structure", scope, "cells", sorted.join(",")),
    async queryFn(): Promise<StructureCell[]> {
      const data = await execute(StructureCellsQuery, {
        view: scope.view ?? null,
        ids: sorted,
      });
      const matrix = data.hierarchicalGraph?.nodes?.orderedAdjacencyMatrix;
      if (!matrix) return [];
      const ordered = matrix.orderedNodes.map((node) => node.id);
      return matrix.cells
        .filter((cell) => cell.row !== cell.column)
        .map((cell) => ({
          from: ordered[cell.row],
          to: ordered[cell.column],
          weight: cell.value,
        }));
    },
  });
}

// The dependencies handed to the map (`dependency_ids`): each pair's ends
// with their ancestors in the view, and its weight. Ids that name no node
// come back as `null` ends, not as an error.
export const StructureGivenQuery = graphql(`
  query StructureGiven($view: String, $pairs: [NodePair!]!) {
    hierarchicalGraph(view: $view) {
      aggregatedDependencies(pairs: $pairs) {
        sourceId
        targetId
        weight
        sourceNode {
          id
          text
          type
          predecessors {
            id
            type
            hasChildren
          }
        }
        targetNode {
          id
          text
          type
          predecessors {
            id
            type
            hasChildren
          }
        }
      }
    }
  }
`);

export function structureGivenQueryOptions(
  scope: GraphScope,
  pairs: GivenPair[],
) {
  return queryOptions({
    queryKey: graphQueryKey(
      "structure",
      scope,
      "given",
      pairs.map((pair) => `${pair.sourceId}-${pair.targetId}`).join(","),
    ),
    async queryFn(): Promise<ResolvedPair[]> {
      const data = await execute(StructureGivenQuery, {
        view: scope.view ?? null,
        pairs,
      });
      type End = NonNullable<
        NonNullable<
          typeof data.hierarchicalGraph
        >["aggregatedDependencies"][number]["sourceNode"]
      >;
      const end = (node: End | null | undefined): GivenEnd | null =>
        node
          ? {
              id: node.id,
              text: node.text,
              type: node.type,
              ancestors: node.predecessors,
            }
          : null;
      return (data.hierarchicalGraph?.aggregatedDependencies ?? []).map(
        (pair) => ({
          sourceId: pair.sourceId,
          targetId: pair.targetId,
          source: end(pair.sourceNode),
          target: end(pair.targetNode),
          weight: pair.weight,
        }),
      );
    },
  });
}
