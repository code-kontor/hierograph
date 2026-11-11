# Contributing

How to work in this repository. Building is described in [BUILD.md](BUILD.md), releasing in
[release/docs](release/docs/).

## Commit messages

Every commit subject starts with a prefix that tells what the commit belongs to:

```
<prefix>: <subject>
```

### The rule

1. **A commit that builds a feature is prefixed with the feature's name**, whatever kind of change it is:
   code, tests, specs, skills, and fixes made before the feature is released.
2. **Every other commit is prefixed with its kind** (see below).
3. **Kind words are reserved:** no feature is named `fix`, `perf`, `refactor`, `test`, `docs`, `build` or `chore`.

A feature name marks the construction of a feature. Once the feature is released, a fix to it is a
`fix:` commit. A later extension that is a piece of work of its own gets a feature name of its own.

```
views: Add the view document: format, selectors and resolution
views: Fix the cache race, new_cycles, define_view's description and view_groups ids
fix: Fix DSM ordering for container nodes and stale upward dependencies
perf: Scan class files and signature walks in parallel across all modules
chore: Update IntelliJ compiler.xml from Maven reimport
```

`git log --oneline --grep '^views:'` lists everything that built a feature, and `--grep '^fix:'` lists
the fixes outside features.

### Kinds

| Kind | Explanation |
|---|---|
| `fix` | Corrects wrong behaviour; the code now does what it was meant to do |
| `perf` | Makes something faster or leaner without changing its behaviour |
| `refactor` | Changes the structure of the code without changing its behaviour |
| `test` | Adds or changes tests only |
| `docs` | Changes documentation only: specs, guides, release docs, comments |
| `build` | Changes the build: Maven, dependencies, versions, packaging, Docker |
| `chore` | Housekeeping with no effect on the product, such as IDE files, helper scripts or cleanup |

A commit that would need two kinds is usually two commits.

### Feature names

Feature names are short and lower-case: one word, or kebab-case. A name is chosen when the work starts,
added to this list in the feature's first commit, and kept for the rest of the feature.

| Feature | Description | Version |
|---|---|---|
| `views` | Named regroupings of the hierarchy (multi-modules, layers, contexts) and restructuring views that project module splits and merges; the `view` parameter on the tools, the view tools, and their use in the skills | 0.3.0 |
