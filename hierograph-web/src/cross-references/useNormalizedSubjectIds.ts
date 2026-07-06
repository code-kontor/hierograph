import { useQueries } from "@tanstack/react-query";
import { useMemo } from "react";

import { normalizeSubjectIds } from "./normalizeSubjectIds";
import { crossReferencesNodePredecessorsQueryOptions } from "./queries";

/**
 * Returns a stable sorted comma-separated key representing the normalized
 * subject id set. E1: multi-selection drives the subject; single focus is
 * the fallback so existing single-subject paths are unchanged.
 *
 * Returning a string (not an array) ensures the caller only re-renders when
 * the normalized content genuinely changes — not when predecessor queries
 * resolve while the normalized result stays the same.
 */
export function useNormalizedSubjectKey(
  selectedIds: string[],
  focusedId: string | null,
): string {
  // E1: multi-selection drives the subject; single focus is the fallback so the
  // existing single-subject path (a plain HierarchyTree click) is unchanged.
  const rawSubjectIds = useMemo(() => {
    if (selectedIds.length > 0) return [...new Set(selectedIds)];
    return focusedId != null ? [focusedId] : [];
  }, [selectedIds, focusedId]);

  const rawKey = useMemo(
    () => [...rawSubjectIds].sort().join(","),
    [rawSubjectIds],
  );

  const results = useQueries({
    queries: rawSubjectIds.map((id) =>
      crossReferencesNodePredecessorsQueryOptions(id),
    ),
  });

  // Stable string key tracking which predecessor queries have resolved — avoids
  // re-running normalization on every render when only unrelated state changes.
  const resultsKey = results.map((r) => (r.data ? "1" : "0")).join("");

  return useMemo(() => {
    const predecessorsById = new Map<string, Set<string>>();
    results.forEach((r, i) => {
      const id = rawSubjectIds[i];
      const preds = r.data?.hierarchicalGraph?.node?.predecessors ?? null;
      if (preds) predecessorsById.set(id, new Set(preds.map((p) => p.id)));
    });
    return normalizeSubjectIds(rawSubjectIds, predecessorsById).join(",");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rawKey, resultsKey]);
}
