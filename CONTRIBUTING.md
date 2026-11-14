# Contributing

How to work in this repository. Building is described in [BUILD.md](BUILD.md), releasing in
[release/docs](release/docs/).

## Commit messages

A commit that belongs to a GitHub issue starts with the issue number in brackets:

```
[#<issue>] <subject>
```

### The rule

1. **A commit that works on an issue starts with `[#<issue>]`**, whatever kind of change it is: code,
   tests, specs, skills, and fixes.
2. **A commit without an issue starts with its kind** (see below): `<kind>: <subject>`.

Open an issue for every piece of work that changes the product. A commit without an issue is for small
changes that need no discussion, such as IDE files, a dependency bump or a typo.

```
[#42] Add the view document: format, selectors and resolution
[#42] Fix the cache race, new_cycles, define_view's description and view_groups ids
[#57] Fix DSM ordering for container nodes and stale upward dependencies
chore: Update IntelliJ compiler.xml from Maven reimport
build: Update Kotlin to 2.2.20
```

`git log --oneline --grep '^\[#42\]'` lists everything that worked on issue 42. GitHub links the
`#42` to the issue.

The number goes in brackets because git treats a line that starts with a bare `#` as a comment and
drops it when you write the message in the editor.

Earlier commits start with a feature name, such as `views:` or `levelized-map:`,
instead of an issue number.

A `commit-msg` hook checks the subject. Enable it once per clone:

```
git config core.hooksPath .githooks
```

`git commit --no-verify` bypasses it for a single commit. Merge, revert, `fixup!` and `squash!` commits
are not checked.

### Kinds

A commit without an issue starts with one of these kinds:

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
