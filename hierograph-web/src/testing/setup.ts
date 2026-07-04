import { afterAll, afterEach, beforeAll } from "vitest";

import { worker } from "./msw/worker";

beforeAll(() => worker.start({ onUnhandledRequest: "error", quiet: true }));
afterEach(() => worker.resetHandlers());
afterAll(() => worker.stop());
