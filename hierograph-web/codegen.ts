import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import type { CodegenConfig } from "@graphql-codegen/cli";

const SCHEMA_DIR =
  "../hierograph-mcp/io.hierograph.graphql/src/main/resources/graphql";

// graphql-js rejects empty field blocks like `type HierarchicalGraph {}` —
// graphql-java in the backend accepts them. Empty `{ }` blocks are stripped so
// `type HierarchicalGraph` (without braces) is valid SDL; fields come via
// `extend type` from the other schema files.
function loadSchema(): string {
  return readdirSync(SCHEMA_DIR)
    .filter((file) => file.endsWith(".graphqls"))
    .sort()
    .map((file) => readFileSync(join(SCHEMA_DIR, file), "utf8"))
    .join("\n")
    .replace(/\{\s*\}/g, "");
}

const config: CodegenConfig = {
  schema: loadSchema(),
  documents: ["src/**/*.{ts,tsx}", "!src/generated/graphql/**"],
  ignoreNoDocuments: true,
  generates: {
    "./src/generated/graphql/": {
      preset: "client",
      config: {
        useTypeImports: true,
      },
    },
  },
};

export default config;
