import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";

import App from "@/App.tsx";

test("renders placeholder page with title and button", () => {
  render(<App />);

  expect(
    screen.getByRole("heading", { name: "hierograph" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Get started" }),
  ).toBeInTheDocument();
});
