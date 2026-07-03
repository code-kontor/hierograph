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
