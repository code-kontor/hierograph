import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { page, userEvent } from "vitest/browser";
import { render } from "vitest-browser-react";

import { useLocalStorage } from "@/design-system/useLocalStorage";
import type { NodeLabelFormat } from "@/graph/nodeLabel";

import { DependencyDiagramOptionsMenu } from "./DependencyDiagram";
import { LABEL_FORMAT_STORAGE_KEY } from "./dependencyDiagramLabelSettings";

function PersistedOptionsMenu() {
  const [labelFormat, setLabelFormat] = useLocalStorage<NodeLabelFormat>(
    LABEL_FORMAT_STORAGE_KEY,
    "last-segment",
  );
  return (
    <DependencyDiagramOptionsMenu
      labelFormat={labelFormat}
      onLabelFormatChange={setLabelFormat}
    />
  );
}

describe("DependencyDiagramOptionsMenu", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  it("renders the three label-format options", async () => {
    await render(<PersistedOptionsMenu />);

    await userEvent.click(
      page.getByRole("button", { name: "Diagram options" }),
    );

    await expect.element(page.getByText("Full")).toBeInTheDocument();
    await expect
      .element(page.getByText("Abbreviated qualifier"))
      .toBeInTheDocument();
    await expect.element(page.getByText("Own name")).toBeInTheDocument();
  });

  it("selecting an option persists it under the diagram's storage key", async () => {
    await render(<PersistedOptionsMenu />);

    await userEvent.click(
      page.getByRole("button", { name: "Diagram options" }),
    );

    const fullOption = page.getByText("Full");
    await userEvent.click(fullOption);

    await expect
      .poll(() => localStorage.getItem(LABEL_FORMAT_STORAGE_KEY))
      .toBe(JSON.stringify("full"));

    const item = fullOption.element().closest("[role]");
    expect(item?.getAttribute("data-state")).toBe("checked");
  });
});
