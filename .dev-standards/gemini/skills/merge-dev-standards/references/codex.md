# Codex integration

Apply these rules to the Codex target regardless of which client is running the skill.

## Project paths

- Distribution: `.dev-standards/codex/skills/merge-dev-standards/`.
- Installed skill: `.agents/skills/merge-dev-standards/`.
- Shared instructions and entry point: repository-root `AGENTS.md`.

Merge shared-guide references into the matching H2 sections of root `AGENTS.md` using the common
procedure. Do not create `.codex/dev-standards.md` or put shared rules in
`.codex/rules/`, which holds command execution policies.

If root `AGENTS.override.md` exists, preserve its local instructions and merge a
plain instruction to read root `AGENTS.md` before modifying code. The override
must delegate to `AGENTS.md`, not replace it as the shared source of truth. Check
for cycles; never make `AGENTS.md` refer back to the override. Report conflicting
local override rules without silently removing them.

Codex discovers project skills under `.agents/skills/`, not `.codex/skills/`.
Preserve existing `.codex/config.toml`, execution policies, and legacy skills.
There is no need to create a `.codex/` directory for this integration.
