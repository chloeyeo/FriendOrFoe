# commit-message

Format Git commit messages with a one-line summary plus title-case `Summary` and `Changes` sections.

This skill gives coding agents a small, repeatable commit message convention. It keeps messages concise, avoids prefix tags, and asks the agent to explain why a change was made, not only what changed. It also keeps the agent from burning context on diffs it does not need to read.

## Install

Install just this skill:

```bash
npx skills add ujon/skills --skill commit-message
```

Manual install: copy `skills/commit-message` into `.agents/skills/commit-message` and reference `SKILL.md` from your project `AGENTS.md`.

## Requirements

- An AI coding agent that can load skill instructions from `SKILL.md`
- A Git repository with changes that need a commit message

## Use

Ask your coding agent to create, edit, or review a commit message:

```text
write a commit message for the staged changes
```

The agent should produce a one-line summary followed by `## Summary` and `## Changes`.

## Language

The default language for commit message content is English. It is set in the `## Settings` block at the top of `SKILL.md`:

```yaml
locale: en
```

Edit `locale` in your local copy to change the language used for the one-line summary, the body under `## Summary`, and the bullets under `## Changes`. The `## Summary` and `## Changes` headers stay in English.

Settings sit at the top of the body rather than in YAML frontmatter because skill loaders strip frontmatter before handing the file to the agent. Keeping them in the body means the agent already has the value and never opens `SKILL.md` to look it up.

## Context Budget

The skill tells the agent to learn why a change was made as cheaply as possible:

1. Changes made in the current session need no Git inspection at all, only `git status --short` to confirm what is staged.
2. Otherwise `git diff --staged --stat` comes first.
3. A full diff is opened one path at a time, and only for files whose intent the stat leaves unclear.

Lockfiles, generated output, and vendored paths are skipped.

## Format

```text
<one-line summary>

## Summary
<1-3 sentences on motivation and context>

## Changes
- <change 1>
- <change 2>
```

## Rules

- Use `locale` only for commit message content: the one-line summary, the body under `## Summary`, and the bullets under `## Changes`.
- Do not use prefix or scope tags such as `feat:`, `fix:`, or `chore:`.
- Keep `## Summary` and `## Changes` headers in English and title case.
- Explain why the change was made, not only what changed.
- Group related edits into one bullet by intent instead of enumerating every touched file, usually 2-6 bullets.

## What You Get

- A concise commit message in the configured language
- A consistent structure for summary and change details
- Change bullets focused on meaningful behavior or maintenance impact
- No conventional-commit prefix or scope tags
- No redundant file reads or full-diff dumps while gathering context

## License

MIT
