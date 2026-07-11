import {
  defaultParseSearch,
  stringifySearchWith,
} from "@tanstack/react-router";

// Stringify built WITHOUT the re-parse step of `defaultStringifySearch`: the
// default re-serializes any JSON-parseable string value (so a lone numeric id
// like "360" would come out quoted as `%22360%22`). Dropping the parser keeps
// string values verbatim — exactly what the readable comma id lists and bare
// numeric ids need. Non-string structured values are still JSON-encoded, but
// our search only carries strings/numbers/enums, never nested objects.
const stringifySearch = stringifySearchWith(JSON.stringify);

// Search-param codec shared by the router and the route `validateSearch`
// functions. Node-referencing list params are comma-encoded
// (`subject_ids=42,43`) rather than TanStack's native JSON-array encoding, so
// URLs stay short, human-readable, and hand-constructable — the driving
// use-cases are shareable deep links and URLs built from outside the app.

// Keys whose values are comma-separated id lists: `string[]` to consumers,
// `"a,b"` in the URL. Consumed by the router parse/stringify wrappers (added in
// a later step) to intercept exactly these keys.
export const ID_LIST_KEYS = ["subject_ids", "center_ids"] as const;

// Inspector tab values for `/dsm`.
export const TABS = ["usages", "paths"] as const;
export type Tab = (typeof TABS)[number];

// Column-direction values for `/cross-reference-explorer`.
export const SIDES = ["uses", "used-by"] as const;
export type Side = (typeof SIDES)[number];

// Parse a comma-separated id list. Accepts a raw string (`"42,43"`), an array,
// or a lone primitive (a single numeric id is decoded as a number by the
// default search codec). Blank segments are dropped; an empty result collapses
// to `undefined` so the key disappears from the URL rather than lingering as
// an empty string.
export function parseIdList(value: unknown): string[] | undefined {
  const raw =
    typeof value === "string"
      ? value.split(",")
      : Array.isArray(value)
        ? value
        : value == null
          ? []
          : [value];
  const ids = raw
    .map((entry) => String(entry).trim())
    .filter((entry) => entry.length > 0);
  return ids.length > 0 ? ids : undefined;
}

// Serialize an id list back to `"a,b"`. Empty/undefined collapses to
// `undefined` so the key is omitted entirely.
export function stringifyIdList(ids: string[] | undefined): string | undefined {
  if (!ids || ids.length === 0) return undefined;
  return ids.join(",");
}

// Coerce a single node-id param to a string. A lone numeric id is decoded as a
// number by the default codec, so normalize it back; blank/other types drop to
// `undefined`.
export function parseSingleId(value: unknown): string | undefined {
  if (typeof value === "number") return String(value);
  if (typeof value === "string") return value.length > 0 ? value : undefined;
  return undefined;
}

// Whitelist guard for enum params: returns the value only if it is one of the
// allowed literals, otherwise `undefined` (unknown values are silently
// dropped, keeping hand-built URLs robust).
export function parseEnum<T extends string>(
  value: unknown,
  allowed: readonly T[],
): T | undefined {
  return typeof value === "string" &&
    (allowed as readonly string[]).includes(value)
    ? (value as T)
    : undefined;
}

// Leaf-route search shapes + their `validateSearch` cascades. Kept here (pure,
// component-free) so they are unit-testable in the node environment; the route
// files just wire them in. A level is dropped when its parent is absent, and
// id existence in the store is NOT checked (handled robustly downstream) so a
// hand-built URL with stale ids never crashes validation.
export type DsmSearch = {
  subject_ids?: string[];
  from_id?: string;
  to_id?: string;
  tab?: Tab;
};

export function validateDsmSearch(search: Record<string, unknown>): DsmSearch {
  const subject_ids = parseIdList(search.subject_ids);
  const hasSubjects = !!subject_ids?.length;
  const from_id = hasSubjects ? parseSingleId(search.from_id) : undefined;
  const to_id = hasSubjects ? parseSingleId(search.to_id) : undefined;
  const hasCell = from_id !== undefined && to_id !== undefined;
  const tab = hasCell ? parseEnum(search.tab, TABS) : undefined;
  return { subject_ids, from_id, to_id, tab };
}

export type CrossReferenceSearch = {
  center_ids?: string[];
  side?: Side;
  aggregated?: Side;
};

export function validateCrossReferenceSearch(
  search: Record<string, unknown>,
): CrossReferenceSearch {
  const center_ids = parseIdList(search.center_ids);
  const hasCenter = !!center_ids?.length;
  const side = hasCenter ? parseEnum(search.side, SIDES) : undefined;
  const aggregated = hasCenter
    ? parseEnum(search.aggregated, SIDES)
    : undefined;
  return { center_ids, side, aggregated };
}

// Router-level `parseSearch`: delegates the whole query string to the TanStack
// default, then rewrites exactly the id-list keys from their raw form
// (`"42,43"`, or a lone numeric id decoded as a number) into `string[]`. Every
// other key keeps the default's behaviour untouched.
export function routerParseSearch(searchStr: string): Record<string, unknown> {
  const parsed = defaultParseSearch(searchStr) as Record<string, unknown>;
  for (const key of ID_LIST_KEYS) {
    if (key in parsed) {
      const ids = parseIdList(parsed[key]);
      if (ids) {
        parsed[key] = ids;
      } else {
        delete parsed[key];
      }
    }
  }
  return parsed;
}

// Router-level `stringifySearch`: converts the id-list keys from `string[]`
// back to `"a,b"` before the default serializer runs, then restores literal
// commas (`URLSearchParams` percent-encodes them) so the emitted URL reads
// `subject_ids=42,43` instead of `subject_ids=42%2C43`. Only the id lists ever
// contain a comma — every other value is a numeric id or a lowercase enum
// token — so the blanket `%2C` → `,` rewrite is safe.
export function routerStringifySearch(search: Record<string, unknown>): string {
  const transformed: Record<string, unknown> = { ...search };
  for (const key of ID_LIST_KEYS) {
    const value = transformed[key];
    if (Array.isArray(value)) {
      transformed[key] = stringifyIdList(value as string[]);
    }
  }
  return stringifySearch(transformed).replace(/%2C/g, ",");
}
