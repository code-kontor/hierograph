import { CancelledError } from "@tanstack/react-query";
import { afterAll, afterEach, beforeAll } from "vitest";

import { worker } from "./msw/worker";

beforeAll(() => worker.start({ onUnhandledRequest: "error", quiet: true }));
afterEach(() => worker.resetHandlers());
afterAll(() => worker.stop());

// renderWithQueryClient cancels in-flight queries on test teardown (see
// testing/render.tsx). Cancellation rejects the pending ensureQueryData
// promises in the tree loaders with a CancelledError that has no catch
// handler, surfacing as an unhandled rejection that can fail the run with
// exit code 1 even though every test passes. This is expected teardown
// noise, so swallow exactly that error and let all other rejections through.
window.addEventListener(
  "unhandledrejection",
  (event) => {
    const reason = event.reason;
    if (
      reason instanceof CancelledError ||
      (reason as { name?: string } | null)?.name === "CancelledError"
    ) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  },
  { capture: true },
);

// Vitest's browser error-catcher registers its own unhandledrejection listener
// directly on window before this file runs, so it always observes the
// CancelledError first regardless of the capture-phase listener above.
// Detecting our extra listener, it downgrades from reporting a test failure
// to a plain console.error instead — filter exactly that residual log line.
const originalConsoleError = console.error;
console.error = (...args: unknown[]) => {
  const isCancelledErrorLog = args.some(
    (arg) =>
      arg instanceof CancelledError ||
      (arg instanceof Error && arg.name === "CancelledError") ||
      (typeof arg === "string" && arg.includes("CancelledError")),
  );
  if (isCancelledErrorLog) {
    return;
  }
  originalConsoleError(...args);
};
