import { describe, expect, it } from "vitest";
import { render } from "vitest-browser-react";

import { NodeIcon } from "./treeIcons";

describe("NodeIcon", () => {
  it.each([
    ["java.class", "C"],
    ["java.interface", "I"],
    ["java.enum", "E"],
    ["java.record", "R"],
    ["java.annotation", "@"],
  ])("marks a %s with %s", async (type, letter) => {
    const screen = await render(
      <NodeIcon data-testid="icon" type={type} text="a.b.C" />,
    );

    await expect.element(screen.getByTestId("icon")).toHaveTextContent(letter);
  });

  it("gives packages, modules and types outside the workspace their kind's icon", async () => {
    const screen = await render(
      <>
        <NodeIcon data-testid="package" type="java.package" text="a.b" />
        <NodeIcon data-testid="external" type="external.type" text="a.B" />
      </>,
    );

    await expect.element(screen.getByTestId("package")).toHaveClass("lucide");
    await expect.element(screen.getByTestId("package")).toHaveTextContent(/^$/);
    await expect.element(screen.getByTestId("external")).toHaveClass("lucide");
  });
});
