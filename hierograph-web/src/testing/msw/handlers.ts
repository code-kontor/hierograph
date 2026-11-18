import type { RequestHandler } from "msw";

// GraphQL handlers answering from recorded fixtures (`src/testing/fixtures/`).
// Empty until the first queries exist; the fixtures and the recorder come with
// the start screen. Every unhandled request fails the test
// (`onUnhandledRequest: "error"` in `setup.ts`).
export const handlers: RequestHandler[] = [];
