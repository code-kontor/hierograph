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
    // Exclude generated code (never authored/reviewed by hand)
    exclude: "^src/(graphql/generated|routeTree\\.gen)",
    tsConfig: { fileName: "tsconfig.json" },
  },
};
