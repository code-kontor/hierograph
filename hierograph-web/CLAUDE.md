# hierograph-web

React + Vite + TypeScript (strict) + Tailwind CSS v4 + shadcn/ui. Alle Befehle
aus diesem Verzeichnis (`hierograph-web/`) heraus ausführen. Package Manager:
pnpm (via Corepack, `packageManager`-Feld in `package.json`).

shadcn/ui-Komponenten liegen in `src/components/ui/` (generiert via
`pnpm dlx shadcn@latest add <component>`).

## Code Style

- Als Props für React-Komponenten immer ein Objekt mit eigenem benannten Typ
  verwenden, nie einen Inline-Typ
  - EIGENER TYP: `type MyComponentProps = { label: string }; function MyComponent({label}: MyComponentProps) { /* ... */ }`
  - NICHT: `function MyComponent({label}: {label: string}) { /* ... */ }`
- Für Typ-Deklarationen `type` statt `interface` verwenden
- Kein `void` vor einem Funktionsaufruf
  - NICHT: `void queryClient.invalidateQueries({ queryKey: ["posts"] });`
- Statt zusammengesetzter CSS-Klassennamen mit Template Strings `twMerge`
  verwenden
  - NICHT: ``className={`btn-submit ${isSuccess ? "success" : ""}`}``
  - MIT TWMERGE: `className={twMerge("btn-submit", isSuccess && "success")}`
- Kein inline-Array mit `.map()` in JSX — Komponenten explizit ausschreiben
  statt dynamisch erzeugen
  - NICHT: `{(['a', 'b'] as const).map(x => <Item key={x} value={x} />)}`
  - STATTDESSEN: `<Item value="a" /><Item value="b" />`

## Überprüfen vom Code und Code Style

**Nach dem Erzeugen und Ändern von Code:** `pnpm check` ausführen — formatiert
mit Prettier, fixt ESLint-Probleme und prüft anschließend Typ-Fehler (tsc).
Achtung: verändert Dateien (Auto-Fix).

**Verfügbare Einzel-Skripte (nur prüfend, ohne Auto-Fix):**

- `check:ts`: prüft nur Typ-Fehler (tsc)
- `check:prettier`: prüft nur Formatierungsfehler (Prettier)
- `check:lint`: prüft nur Linting-Regeln (ESLint)

## Wichtige Patterns: TanStack Query

(Gilt ab der GraphQL-Anbindung — TanStack Query ist Teil des entschiedenen
Stacks.)

`queryFn` in `queryOptions` als **Methode** schreiben, nicht als
Pfeil-Funktion-Property:

```ts
// ✅ richtig
queryOptions({
  queryKey: ["posts", "list"],
  async queryFn() {
    // ...
  },
});

// ❌ falsch
queryOptions({
  queryKey: ["posts", "list"],
  queryFn: async () => {
    // ...
  },
});
```

In `onSuccess` von `useMutation` den Query Client über den Context
(4. Methodenparameter) holen, nicht über `useQueryClient`. Das Promise von
`invalidateQueries` aus `onSuccess` zurückgeben; bei mehreren Aufrufen die
Promises mit `Promise.all` zusammenfassen:

```ts
onSuccess: (_data, _vars, _result, context) => {
  return Promise.all([
    context.client.invalidateQueries({ queryKey: ["posts"] }),
    context.client.invalidateQueries({ queryKey: ["stats"] }),
  ]);
};
```

## GraphQL

- Endpoint: fest relativ `/graphql` (Dev-Proxy auf den MCP-Server, Ziel per
  `VITE_GRAPHQL_PROXY_TARGET` übersteuerbar — siehe `vite.config.ts`).
- GraphQL-Dokumente inline mit `graphql()` aus `@/generated/graphql` schreiben;
  keine separaten `.graphql`-Dateien.
- Query-Module unter `src/queries/`: Dokument + `queryOptions`-Factory im
  selben Modul; Komponenten importieren nur die Factory
  (`useQuery(rootNodeQueryOptions())`). Variablen als Factory-Parameter, sie
  gehören in den `queryKey`. Requests über `execute()` aus
  `src/lib/graphql-client.ts`.
- Generierter Code liegt in `src/generated/graphql/` (committed; von ESLint/Prettier
  ausgenommen, von tsc geprüft) — **nie von Hand editieren**. Nach jedem
  neuen/geänderten Dokument: erst `pnpm codegen`, dann Typecheck
  (`pnpm codegen:watch` für laufende Arbeit; Schema-Änderungen erfordern
  einen Watch-Neustart).
