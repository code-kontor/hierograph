import { describe, expect, it, vi } from "vitest";
import { page, userEvent } from "vitest/browser";

import { renderWithQueryClient } from "@/testing/render";

import { TreeSettingsMenu } from "./TreeSettingsMenu";

function noopControls() {
  return {
    setShowIndentGuides: vi.fn(),
    setAutoExpandSingleChildren: vi.fn(),
    setPreserveSelectionOnCollapse: vi.fn(),
    setLabelFormat: vi.fn(),
  };
}

describe("TreeSettingsMenu", () => {
  it("renders a brand-coloured check indicator for an enabled option", async () => {
    await renderWithQueryClient(
      <TreeSettingsMenu
        showIndentGuides
        autoExpandSingleChildren={false}
        preserveSelectionOnCollapse={false}
        labelFormat="full"
        {...noopControls()}
      />,
    );

    await userEvent.click(page.getByRole("button", { name: "Settings" }));

    const item = page.getByText("Indent guides").element().closest("[role]");
    expect(item?.getAttribute("data-state")).toBe("checked");

    // The check glyph must be tinted with the brand accent, not the near-white
    // shadcn "accent" hover colour — that regression made selection invisible.
    const svg = item?.querySelector("svg");
    expect(svg).not.toBeNull();
    const cls = svg?.getAttribute("class") ?? "";
    expect(cls).toContain("hg-accent");
    expect(cls).not.toMatch(/(^|\s)text-accent(\s|$)/);
  });
});
