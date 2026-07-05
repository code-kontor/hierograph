import type { TypedDocumentNode } from "@graphql-typed-document-node/core";
import { GraphQLClient } from "graphql-request";

// graphql-request v7 passes the endpoint to `new URL(...)` — a relative path
// like "/graphql" is not a valid URL without a base. So resolve it against the
// current origin here; the path stays relative in intent (dev proxy).
const graphqlClient = new GraphQLClient(
  new URL("/graphql", window.location.origin).toString(),
);

export async function execute<TResult, TVariables extends object>(
  document: TypedDocumentNode<TResult, TVariables>,
  ...[variables]: TVariables extends Record<string, never> ? [] : [TVariables]
): Promise<TResult> {
  return graphqlClient.request(
    document as TypedDocumentNode<TResult, object>,
    variables as object,
  );
}
