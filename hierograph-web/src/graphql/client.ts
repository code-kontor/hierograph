import type { TypedDocumentNode } from "@graphql-typed-document-node/core";
import { GraphQLClient } from "graphql-request";

// graphql-request v7 passes the endpoint to `new URL(...)` — a relative path
// like "/graphql" is not a valid URL without a base. So resolve it against the
// current origin here. The path stays relative in intent: the server serves
// the UI and `/graphql` from the same origin, and the dev server proxies it.
const graphqlClient = new GraphQLClient(
  new URL("/graphql", window.location.origin).toString(),
);

export async function execute<TResult, TVariables extends object>(
  document: TypedDocumentNode<TResult, TVariables>,
  ...[variables]: TVariables extends Record<string, never>
    ? [variables?: undefined]
    : [variables: TVariables]
): Promise<TResult> {
  return graphqlClient.request(
    document as TypedDocumentNode<TResult, object>,
    variables as object,
  );
}
