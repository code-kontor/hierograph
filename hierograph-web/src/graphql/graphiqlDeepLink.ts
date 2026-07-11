export function buildGraphiqlDeepLink(
  queryText: string,
  variables: unknown,
): string {
  const params = new URLSearchParams();
  params.set("query", queryText);
  params.set("variables", JSON.stringify(variables ?? {}, null, 2));
  return `/graphiql.html?${params.toString()}`;
}
