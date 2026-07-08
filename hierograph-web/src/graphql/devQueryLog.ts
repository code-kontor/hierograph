// Dev-only execution log for GraphQL queries. Populated exclusively from
// `client.ts` under an `import.meta.env.DEV` guard — never touched in prod.

export type QueryLogEntry = {
  id: string;
  operationName: string;
  queryText: string;
  variables: unknown;
  trigger: string;
  timestamp: number;
};

const MAX_ENTRIES = 100;

let entries: QueryLogEntry[] = [];
let nextId = 0;
const listeners = new Set<() => void>();

export function recordQuery(
  entry: Omit<QueryLogEntry, "id" | "timestamp">,
): void {
  const nextEntries = [
    ...entries,
    { ...entry, id: String(nextId++), timestamp: Date.now() },
  ];
  entries =
    nextEntries.length > MAX_ENTRIES
      ? nextEntries.slice(nextEntries.length - MAX_ENTRIES)
      : nextEntries;
  listeners.forEach((listener) => listener());
}

export function clearQueryLog(): void {
  if (entries.length === 0) {
    return;
  }
  entries = [];
  listeners.forEach((listener) => listener());
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSnapshot(): readonly QueryLogEntry[] {
  return entries;
}
