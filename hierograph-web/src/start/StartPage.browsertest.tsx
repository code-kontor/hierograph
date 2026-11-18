import { describe, expect, it } from "vitest";

import { renderWithQueryClient } from "@/testing/render";

import { StartPage } from "./StartPage";

describe("StartPage", () => {
  it("points to the agent session as the entry point", async () => {
    const screen = await renderWithQueryClient(<StartPage />);

    await expect
      .element(screen.getByRole("heading", { name: "Welcome to hierograph" }))
      .toBeVisible();
    await expect
      .element(screen.getByText("Screens are opened from your agent session."))
      .toBeVisible();
  });
});
