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
// use-cases are shareable deep links and URLs built from outside the app (the
// agent hands out links into the UI).
//
// Each screen owns and validates its own params, so the codec cannot list
// them. Instead a naming convention marks id lists: every param whose name
// ends in `_ids` is a comma-separated id list (`string[]` to consumers, `"a,b"`
// in the URL). A param referencing a single node ends in `_id`.
const ID_LIST_SUFFIX = "_ids";

export function isIdListKey(key: string): boolean {
  return key.endsWith(ID_LIST_SUFFIX) && key.length > ID_LIST_SUFFIX.length;
}

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

// Router-level `parseSearch`: delegates the whole query string to the TanStack
// default, then rewrites exactly the id-list keys from their raw form
// (`"42,43"`, or a lone numeric id decoded as a number) into `string[]`. Every
// other key keeps the default's behaviour untouched.
export function routerParseSearch(searchStr: string): Record<string, unknown> {
  const parsed = defaultParseSearch(searchStr) as Record<string, unknown>;
  for (const key of Object.keys(parsed)) {
    if (!isIdListKey(key)) continue;
    const ids = parseIdList(parsed[key]);
    if (ids) {
      parsed[key] = ids;
    } else {
      delete parsed[key];
    }
  }
  return parsed;
}

// Router-level `stringifySearch`: converts the id-list keys from `string[]`
// back to `"a,b"` before the default serializer runs, then restores literal
// commas (`URLSearchParams` percent-encodes them) so the emitted URL reads
// `subject_ids=42,43` instead of `subject_ids=42%2C43`. Only the id lists ever
// contain a comma — every other value is a numeric id, a lowercase enum token
// or a hierarchy-view name (which never contains a comma) — so the blanket
// `%2C` → `,` rewrite is safe.
export function routerStringifySearch(search: Record<string, unknown>): string {
  const transformed: Record<string, unknown> = { ...search };
  for (const key of Object.keys(transformed)) {
    const value = transformed[key];
    if (isIdListKey(key) && Array.isArray(value)) {
      transformed[key] = stringifyIdList(value as string[]);
    }
  }
  return stringifySearch(transformed).replace(/%2C/g, ",");
}
