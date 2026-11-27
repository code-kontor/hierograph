import { describe, expect, it } from "vitest";

import { Route } from "./index";

const validate = (search: Record<string, unknown>) =>
  (
    Route.options.validateSearch as (
      search: Record<string, unknown>,
    ) => Record<string, unknown>
  )(search);

describe("the start route's search", () => {
  it("takes the selected rows as an id list", () => {
    expect(validate({ selected_ids: ["1", "2"] }).selected_ids).toEqual([
      "1",
      "2",
    ]);
  });

  it("reads an older link's single selected row as a list of one", () => {
    expect(validate({ selected_id: 42 }).selected_ids).toEqual(["42"]);
    expect(validate({ selected_id: 42 })).not.toHaveProperty("selected_id");
  });

  it("keeps the Selection tree's closed tops as an id list", () => {
    expect(
      validate({ selection_collapsed_ids: ["3"] }).selection_collapsed_ids,
    ).toEqual(["3"]);
  });
});
