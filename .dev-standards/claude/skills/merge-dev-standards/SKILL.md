---
name: merge-dev-standards
description: Merge this repository's shared development standards into Codex, Claude Code, and Gemini CLI project rules and skills, preserving local instructions and creating missing files. Use when adopting or refreshing dev-standards for any or all of these agents, regardless of the client running this skill.
---

# Merge development standards

The running client does not determine the targets. By default, integrate all three
targets: Codex, Claude Code, and Gemini CLI, including missing project directories.
If the user explicitly selects a subset (for example, "Gemini only"), change only
that subset and its shared `AGENTS.md` dependency. A mention of the running client ("I use Claude") does not narrow the
targets. Do not run another agent or require its CLI to be installed.

Read [the merge procedure](references/merge-rules.md), then the integration guide
for each selected target before writing:

- [Codex](references/codex.md): `.agents/skills/` and root `AGENTS.md`.
- [Claude Code](references/claude.md): `.claude/skills/` and root `CLAUDE.md`.
- [Gemini CLI](references/gemini.md): `.gemini/skills/` and root `GEMINI.md`.

Root `AGENTS.md` is the single source of truth for shared project instructions
and routes tasks to the relevant original guides in `.dev-standards/standards/`.
Use the bundled `assets/AGENTS.md` as the section layout for new documents.
Merge references into matching existing H2 sections, preserving their prose and
project-specific instructions. Keep a selective index of existing files with
when-to-read conditions; do not consolidate it into a separate duplicate table
or require reading the merged styleguide or all original documents.
Create missing root `CLAUDE.md`
and `GEMINI.md` for selected targets, importing `AGENTS.md` rather than copying
the rules. Do not create separate per-agent shared-rule documents.

All distributed copies contain this same cross-agent workflow and all references.
Install the complete `merge-dev-standards/` bundle at each selected target's native
skill path. Preserve local changes when merging an existing installed copy.
Create missing root `.worktreeinclude` from [the bundled template](assets/worktreeinclude);
preserve an existing file and its patterns. This setup step does not create a
branch or worktree or copy ignored local files.
