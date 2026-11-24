import { describe, expect, it } from "vitest";

import { execute } from "@/graphql/client";
import {
  GraphStatusDocument,
  HierarchyViewsDocument,
  ServerStatusDocument,
} from "@/graphql/generated/graphql";
import { handlersFor } from "@/testing/msw/handlers";
import { worker } from "@/testing/msw/worker";

describe("recorded GraphQL fixtures", () => {
  it("answer the server status from the fixture-app by default", async () => {
    const data = await execute(ServerStatusDocument);

    expect(data.server.workspace).toBe("/fixture-app");
    expect(data.server.graph.state).toBe("LOADED");
    expect(data.server.graph.counts?.modules).toBe(1);
    expect(data.server.artifacts?.notCompiled).toHaveLength(1);
    const { views } = await execute(HierarchyViewsDocument);
    expect(views.map((view) => [view.name, view.valid])).toEqual([
      ["broken", false],
      ["layers", true],
      ["locations", true],
    ]);
  });

  it("answer without a graph in the no-graph scenario", async () => {
    worker.use(...handlersFor("no-graph"));

    const data = await execute(GraphStatusDocument);

    expect(data.server.graph.state).toBe("UNAVAILABLE");
    expect(data.server.graph.generation).toBeNull();
  });

  it("answer with the updater busy in the updating scenario", async () => {
    worker.use(...handlersFor("updating"));

    const data = await execute(GraphStatusDocument);

    expect(data.server.graph.state).toBe("UPDATING");
    expect(data.server.graph.update).toBe("rescanning");
    expect(data.server.graph.generation).toBe("1"); // the graph in service is unchanged
  });
});
