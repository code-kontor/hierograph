import { describe, expect, it } from "vitest";
import { render } from "vitest-browser-react";

import { MiddleTruncated } from "./MiddleTruncated";

describe("MiddleTruncated", () => {
  it("keeps the end of a long name visible when it doesn't fit", async () => {
    const screen = await render(
      <div style={{ width: "90px", display: "flex" }}>
        <MiddleTruncated
          data-testid="a"
          text="limitierte-vermarktung-api"
          style={{ whiteSpace: "nowrap" }}
        />
      </div>,
    );
    const label = screen.getByTestId("a");

    await expect
      .element(label)
      .toHaveAttribute("aria-label", "limitierte-vermarktung-api");
    // The tail is whole; the head is what gives way.
    await expect.element(label).toHaveTextContent(/arktung-api$/);
    expect(label.element().lastElementChild?.textContent).toBe("tung-api");
  });

  it("leaves a short name in one piece", async () => {
    const screen = await render(
      <MiddleTruncated data-testid="b" text="orders" />,
    );

    await expect.element(screen.getByTestId("b")).toHaveTextContent("orders");
    expect(screen.getByTestId("b").element().children).toHaveLength(0);
  });
});
