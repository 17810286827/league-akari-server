# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Repository

- **Repository**: `17810286827/league-akari-server`
- **CLI**: `gh`（本机安装于 `%USERPROFILE%\tools\gh-cli\bin\gh.exe`，认证凭据在 `%APPDATA%\GitHub CLI\hosts.yml`）

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply / remove labels**: `gh issue edit <number> --add-label "..." / --remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

## Pull requests as a triage surface

**PRs as a request surface: no.** Set this to `yes` if this repo later treats external PRs as feature requests; `/triage` reads this flag.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The map is a single issue labelled `wayfinder:map`, holding the Notes / Decisions-so-far / Fog body. Child tickets use GitHub sub-issues where available, otherwise include `Part of #<map>` in the body. Use `wayfinder:<type>` labels for `research`, `prototype`, `grilling`, and `task`.

## 历史说明

本仓库早期使用本地 markdown 模式（`.scratch/<feature>/spec.md`），历史文件保留在 `.scratch/` 下（如 `op-score-scoring`）仅供参考；自 2026-08 起统一使用 GitHub Issues。
