import { queryOptions } from "@tanstack/react-query";

import { graphql } from "@/generated/graphql";
import { execute } from "@/lib/graphql-client";

const rootNodeQuery = graphql(`
  query RootNode {
    hierarchicalGraph {
      rootNode {
        id
        text
        type
        hasChildren
      }
    }
  }
`);

export function rootNodeQueryOptions() {
  return queryOptions({
    queryKey: ["hierarchicalGraph", "rootNode"],
    async queryFn() {
      return execute(rootNodeQuery);
    },
  });
}

const nodeChildrenQuery = graphql(`
  query NodeChildren($id: ID!) {
    hierarchicalGraph {
      node(id: $id) {
        id
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
`);

export function nodeChildrenQueryOptions(id: string) {
  return queryOptions({
    queryKey: ["hierarchicalGraph", "node", id, "children"],
    async queryFn() {
      return execute(nodeChildrenQuery, { id });
    },
  });
}

const nodeBasicsQuery = graphql(`
  query NodeBasics($id: ID!) {
    hierarchicalGraph {
      node(id: $id) {
        id
        text
        type
        hasChildren
      }
    }
  }
`);

export function nodeBasicsQueryOptions(id: string) {
  return queryOptions({
    queryKey: ["hierarchicalGraph", "node", id, "basics"],
    async queryFn() {
      return execute(nodeBasicsQuery, { id });
    },
  });
}

const nodeDetailQuery = graphql(`
  query NodeDetail($id: ID!) {
    hierarchicalGraph {
      node(id: $id) {
        id
        text
        type
        properties {
          key
          value
        }
      }
    }
  }
`);

export function nodeDetailQueryOptions(id: string) {
  return queryOptions({
    queryKey: ["hierarchicalGraph", "node", id, "detail"],
    async queryFn() {
      return execute(nodeDetailQuery, { id });
    },
  });
}
