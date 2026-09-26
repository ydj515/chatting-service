# Gemini integration

Apply these rules to the Gemini target regardless of which client is running the skill.

## Project paths

- Distribution: `.dev-standards/gemini/skills/merge-dev-standards/`.
- Installed skill: `.gemini/skills/merge-dev-standards/`.
- Entry point: repository-root `GEMINI.md`.
- Shared source of truth: repository-root `AGENTS.md`.

Ensure `AGENTS.md` contains the shared-rule reference using the common procedure.
If `GEMINI.md` is missing, create it with this content:

```markdown
@./AGENTS.md
```

If it exists, preserve local content and merge the import once, outside code
fences. Reuse an equivalent existing import and avoid cycles. Shared rules belong
in `AGENTS.md`; do not create `.gemini/dev-standards.md`.

Inspect existing `.gemini/settings.json` for a custom `context.fileName`. Always
maintain root `GEMINI.md` as above. If a different project context file is configured,
merge an import of root `AGENTS.md` into that active file as well, adjusting the
relative path from the importing file. Do not change settings to force the default.
If multiple configured files exist, choose the one already holding shared rules,
or report ambiguity before editing those custom files. If the configured entry
point is `AGENTS.md` itself, it already uses the source of truth: add no self-import.

Preserve settings, extensions, other skills, and `.gemini/styleguide.md` (the
separate Gemini Code Assist output). This skill targets Gemini CLI; it does not
change Gemini Code Assist configuration. After installation, `/skills reload`
refreshes discovery and `/memory refresh` reloads project context.
