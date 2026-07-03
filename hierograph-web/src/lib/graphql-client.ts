import type { TypedDocumentNode } from "@graphql-typed-document-node/core";
import { GraphQLClient } from "graphql-request";

// graphql-request v7 gibt den Endpoint an `new URL(...)` — ein relativer Pfad
// wie "/graphql" ist ohne Basis keine gültige URL. Deshalb hier gegen den
// aktuellen Origin auflösen; der Pfad bleibt relativ gemeint (Dev-Proxy).
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
