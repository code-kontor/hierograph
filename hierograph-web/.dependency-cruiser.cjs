/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "no-module-cycles",
      severity: "error",
      from: {},
      to: { circular: true },
    },
    {
      name: "no-folder-cycles",
      severity: "error",
      scope: "folder",
      from: {},
      to: { circular: true },
    },
    {
      name: "no-orphans",
      severity: "warn",
      from: {
        orphan: true,
        pathNot: ["\\.d\\.ts$", "^src/(graphql/generated|testing)"],
      },
      to: {},
    },
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    // Exclude generated code, test files (they import across vertical boundaries by design)
    exclude:
      "^src/(graphql/generated|routeTree\\.gen)|\\.(?:test|browsertest)\\.[tj]sx?$",
    tsConfig: { fileName: "tsconfig.json" },
  },
};
