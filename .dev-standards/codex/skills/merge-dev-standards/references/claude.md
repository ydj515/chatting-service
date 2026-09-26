# Claude integration

Apply these rules to the Claude target regardless of which client is running the skill.

## Project paths

- Distribution: `.dev-standards/claude/skills/merge-dev-standards/`.
- Installed skill: `.claude/skills/merge-dev-standards/`.
- Entry point: repository-root `CLAUDE.md`.
- Shared source of truth: repository-root `AGENTS.md`.

Ensure `AGENTS.md` contains the shared-rule reference using the common procedure.
If `CLAUDE.md` is missing, create it with this content:

```markdown
@AGENTS.md
```

If it exists, preserve local content and merge the import once, outside code
fences. Reuse an equivalent existing import. Shared rules belong in `AGENTS.md`;
do not copy them into `CLAUDE.md` or create `.claude/rules/dev-standards.md`.

Preserve existing `.claude/CLAUDE.md`, other rule files, skills, settings, hooks,
and permissions. For earlier dev-standards-managed rule files, follow the common
migration procedure without deleting files or discarding local additions.
