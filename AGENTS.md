@/Users/karl/.codex/RTK.md

# FrameNest agent rules

Read `README.md`, `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, and the assigned task in
`tasks/TASKS.md` before editing.

## Scope

- Work on exactly one `FN-XX` task at a time.
- Respect the task's dependencies and owned paths.
- Do not add speculative abstractions, protocols, or providers.
- Do not put SMB credentials in URLs, logs, fixtures, screenshots, or commits.
- Do not silently change an accepted decision. Add or update a file under
  `docs/decisions/` and call the change out in the handoff.

## Collaboration

- Prefer one branch/worktree per task: `agent/FN-XX-short-name`.
- Do not edit another active task's owned paths.
- Shared composition files (`MainActivity`, navigation, dependency wiring, Room
  database registration, version catalog) belong to the current integration owner.
- If a shared-file change is unavoidable, describe the exact needed change in the
  handoff instead of making a conflicting edit.
- End every task with the handoff format in `tasks/HANDOFF.md`.

## Definition of done

- The task's acceptance checks pass on a phone-sized and, when UI is touched, a
  tablet-sized configuration.
- Add the smallest useful automated test for non-trivial logic.
- Run the relevant Gradle checks and record the commands/results in the handoff.
- No unrelated formatting, dependency upgrades, or cleanup.

