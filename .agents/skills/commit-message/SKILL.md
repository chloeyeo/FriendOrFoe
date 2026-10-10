---
name: commit-message
description: Format Git commit messages with a one-line summary plus title-case Summary and Changes sections. Use when creating, editing, or reviewing commit messages.
license: MIT
metadata:
  author: ujon
  version: 1.0.2
---

# Commit Message

## Settings

```yaml
locale: en
```

Skill loaders strip YAML frontmatter, so settings live here at the top of the body.
They are already in your context — never open this file to look them up. `locale` is
the single source of truth for the message language: never infer it from the
conversation, the user's messages, the repository, or prior commits.

## Context Budget

Spend the least context needed to explain *why* the change was made, then stop.

1. If you made the changes in this session you already know why — write the message
   directly. Use `git status --short` only to confirm what is actually staged.
2. Otherwise start with `git diff --staged --stat` (`git diff --stat` when nothing is
   staged) to see the shape of the change.
3. Open a real diff only for the files whose intent the stat leaves unclear, one path
   at a time: `git diff --staged -- <path>`.

Never dump a full diff you do not need, and skip lockfiles, generated output, and
vendored paths.

## Format

```
<one-line summary>

## Summary
<1-3 sentences on motivation and context>

## Changes
- <change 1>
- <change 2>
```

## Rules

- Write the one-line summary, the `## Summary` body, and the `## Changes` bullets in
  the configured `locale`.
- Keep the `## Summary` and `## Changes` headers in English and title case.
- Do not use prefix or scope tags such as `feat:`, `fix:`, or `chore:`.
- Explain why the change was made, not only what changed.
- Group related edits into one bullet by intent instead of enumerating every touched
  file. Keep `## Changes` to the bullets that carry meaning, usually 2-6.

## Example

```
Simplify login form validation logic

## Summary
Consolidated duplicated validation branches into one place to reduce maintenance overhead.

## Changes
- Merged email/password validation into a `validateLoginInput` helper
- Removed unused `legacyCheck` function
- Unified validation failure messages under the i18n dictionary
```
