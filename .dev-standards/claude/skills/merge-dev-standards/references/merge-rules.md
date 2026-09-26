# Merge shared development rules

## Establish the project and scope

1. Locate the user's target repository and its `.dev-standards/standards/` directory.
   Resolve all project paths below from that root, not from the skill directory.
   If the target is ambiguous, ask which repository to use before writing.
2. List the original Markdown files under that directory. Read
   `.dev-standards/config.yml` when present, existing project instructions, and
   every selected target's existing rule documents and skill. Use paths and
   headings to identify guides; read a guide's body only to resolve uncertain
   scope or conflicts. Do not read the merged styleguide or all guide bodies to
   build the index. Do not infer technologies that were not selected.
3. Locate a complete current distribution bundle at
   `.dev-standards/{codex,claude,gemini}/skills/merge-dev-standards/`. These bundles
   are identical; any one can supply all targets. Require `SKILL.md` and all four
   references (`merge-rules.md`, `codex.md`, `claude.md`, `gemini.md`). If multiple
   distribution copies differ, report the inconsistent composition instead of
   silently choosing one. Never use an installed copy as the update source.
   Also require `assets/worktreeinclude` and `assets/AGENTS.md` in the bundle.
4. If the standards directory or a complete distribution bundle is absent, stop and
   explain that dev-standards composition/synchronization must run first. Do not
   invent rules or automatically fetch another release.
   An existing empty standards directory is valid when no standards were selected;
   record that no shared guides are currently selected without inventing links.

## Merge the shared source of truth and selected entry points

Create missing parent directories and files only for the selected targets' documented
paths and their shared root `AGENTS.md` dependency. Changing this shared document
also affects unselected clients that already reference it; report that shared
impact without editing those clients' files. For all-target runs, create or merge
all three root files: `AGENTS.md`, `CLAUDE.md`, and `GEMINI.md`.

Preserve existing
unrelated content, frontmatter, imports, local edits,
unselected agents, and all configuration values. Do not follow symlinks or write
outside the target repository; report affected paths instead. Do not replace a
file that is a directory or change an existing rule's scope.

Read the bundled `assets/AGENTS.md` before editing the root guide. Compose copies
this asset from the same `templates/agents/AGENTS.md` used by bootstrap; do not
maintain another contributor-guide template in this skill.

If root `AGENTS.md` is missing, start with that asset, retaining its title,
all six H2 sections, and their explanatory prose. Specialize only the guide
references to selected files; never invent repository paths, commands, or tools.
If it exists, preserve its title, existing H2 headings, prose, and local rules.
Reuse equivalent sections (including translated headings); add only missing
sections from the asset. Do not replace the document or move unrelated prose.

Distribute concise when-to-read bullets across these sections:

| H2 section | References |
| --- | --- |
| Shared Development Standards | `base.md`, `workflows/branch.md`, `workflows/worktree.md`; selective-reading and precedence instructions |
| Project Structure & Module Organization | Applicable `architectures/` guides |
| Build, Test, and Development Commands | Applicable `build-tools/` and `runtime/` guides |
| Coding Style & Naming Conventions | Applicable `languages/`, `frameworks/`, and non-test `tools/` guides |
| Testing Guidelines | Selected test-runner and coverage guides under `tools/`, when present |
| Commit & Pull Request Guidelines | `workflows/commit.md`, `workflows/pr.md` |

This table describes placement, not an output table to append. Keep each guide's
reference once in the appropriate section. For example, place
“When modifying Java code, read `.dev-standards/standards/languages/java.md`”
under Coding Style & Naming Conventions. Use repository-relative paths for new
references. Preserve an existing relative-path convention only when its base is
explicit and applies across all sections. Keep Testing Guidelines and its prose
even if no testing guide was selected; do not invent a testing document.

Recognize bootstrap category bullets as existing references: refine them into
selected file references in place, rather than keeping both forms. Keep one
fallback instruction to check the relevant category for newly synchronized files.
For a legacy single-table index or old whole-styleguide instruction clearly
owned by dev-standards, migrate only its shared references to the matching H2
sections. Preserve local additions and explain any ownership conflicts.

Include existing files from `workflows/`, `languages/`, `architectures/`, `frameworks/`,
`build-tools/`, `tools/`, and `runtime/` with their when-to-read conditions. Retain
qualified tool paths such as `tools/frameworks/react-ts/vitest.md`. Architecture
guides apply to the matching module, not automatically to every module in a
polyglot repository. If the module mapping is unclear, instruct readers to check
the affected module's manifests and local instructions rather than invent a mapping.
Do not list missing files or unselected categories; `base.md` is optional too.
Contribution guides are included with `base` by default and omitted when
`base: false`. Route commit-message work to `workflows/commit.md` and PR-title or
description work to `workflows/pr.md`, only when those files exist. Do not read
these guides for unrelated implementation tasks or draft messages during this
merge unless the user requests that work.
Also route branch selection and worktree operations to `workflows/branch.md` and
`workflows/worktree.md` when present. These guides belong to the same base selection.
On repeat runs, refresh the managed index for added or removed guides while
preserving local additions.
Also direct readers to inspect the relevant category when a new guide is synced
but the index has not yet been refreshed. Do not embed guide bodies or use `@`
imports for the guides: the index must support selective reading, not eager loading.

Replace a known dev-standards instruction requiring the whole merged styleguide
with the selective index, including an old bootstrap section. Preserve unrelated
additions. If ownership or intent is ambiguous, report the conflict rather than
silently removing a local requirement.
Do not convert guidance into command permissions, hooks, or tool configuration.

Create missing `CLAUDE.md` with `@AGENTS.md` and missing `GEMINI.md` with
`@./AGENTS.md`. For existing entry points, preserve local instructions and merge
the same import as an active standalone line, reusing equivalent existing imports.
Do not import `CLAUDE.md` or `GEMINI.md` from `AGENTS.md`; check the existing
reference graph before adding edges and report any cycle instead of adding it.

For root `AGENTS.md`, use section placement above rather than appending a new
Shared Development Standards block. Reuse equivalent active references; examples
inside code fences do not count. If a legacy `dev-standards:begin/end` block exists,
inspect it before migrating. Move only identifiable shared index entries to the
appropriate sections and retain markers and any local prose in place. Do not
leave duplicate shared references inside the old block. Preserve all unrelated
bytes outside it, except the specific section additions or reference refinements
required by this merge. Do not wrap the whole document in a managed block.

For Claude/Gemini and other entry points, reuse equivalent active imports or
reading instructions. Otherwise update a single well-formed managed block or
append one `dev-standards:begin/end` block, preserving local content.
Unmatched, nested, or repeated marker pairs are ambiguous: report the conflict
and leave that file unchanged. Preserve conflicting local rules; never silently
override the project's choice or claim the conflict was resolved.

Do not create `.codex/dev-standards.md`, `.claude/rules/dev-standards.md`, or
`.gemini/dev-standards.md`. If previous integrations created these files, preserve
the files and any local additions. For selected targets only, redirect clearly
identified dev-standards-managed references to root `AGENTS.md` after ensuring
`AGENTS.md` no longer points back to them. Use a plain reading instruction with
the correct repository-relative path in legacy rule documents; imports belong in
the root entry points. Do not delete files or move ambiguous local rules; report
unresolved duplicates and conflicts. Common new rules belong only in `AGENTS.md`.

## Ensure root worktree configuration

Create root `.worktreeinclude` from the bundle's `assets/worktreeinclude` only if
it is absent. This project-wide setup also applies to subset runs. Preserve an
existing file byte-for-byte, including comments and patterns; do not follow a
symlink or replace a directory. Report those conflicts. Do not add inferred env
paths, copy ignored files, or create worktrees as part of merging the skill.

## Install or update this skill

Copy the complete distribution skill directory into each selected target's
documented native project skill path, including all four references and both assets. A copy is needed
because `.dev-standards/<agent>/skills/` is a distribution location, not an
automatically discovered project skill path. Use the same name,
`merge-dev-standards`, everywhere. Keep the distributed workflow identical across
targets because Gemini may discover `.agents/skills/` ahead of `.gemini/skills/`.
If installed copies have divergent local overrides, preserve them and report the
discovery precedence issue rather than silently overwriting either version.

Create missing files and leave identical files untouched. For existing differing
files, read both versions and merge the integration instructions while preserving
project-specific additions and supported frontmatter. Report irreconcilable
conflicts without overwriting them. Do not replace the entire native skills
directory, delete other skills, install globally, or alter invocation policies.
Always use the current distribution bundle when updating an installed copy.
If legacy `apply-dev-standards-*` skills exist, leave them in place and report that
they still have separate invocation names; do not delete them during this merge.

## Verify and report

- Verify the six template sections (or existing equivalent headings) remain,
  with their explanatory prose and local additions. Ensure each shared guide
  reference occurs only in its matching section, including after legacy migration.
- Read back the changed files and inspect the diff; check that unrelated content
  and configuration remain unchanged, each marker is paired, and each reference
  resolves to a file inside the target repository.
- Trace every selected target's entry point through root `AGENTS.md` to the
  task-specific original guides. Verify every indexed path exists and every
  selected guide has an appropriate when-to-read condition. Missing categories
  and `base: false` must not introduce nonexistent references. Verify that no
  newly generated instruction requires reading the entire styleguide or every guide.
  For an all-target run verify that all three root entry files exist, that the
  Claude and Gemini imports resolve to `AGENTS.md`, and that no import cycle exists.
  Check that installed `SKILL.md` has matching `name` and directory name, a
  meaningful `description`, and an existing relative reference document.
- Evaluate the merge again without writing: the same request and inputs should
  require no further changes. Repair duplicate additions introduced by this run.
- Report created, updated, unchanged, and conflicted paths. Distinguish filesystem
  verification from actual client discovery; do not claim a client loaded the
  skill unless verified. Group results by target and identify incomplete targets
  when conflicts remain. Suggest a reload/new session when discovery is untested.

Commit, push, dependency installation, and changes to application code are outside
this workflow unless separately requested by the user.
