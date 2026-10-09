# Selective CI

PR checks keep their existing job names and always report a result. Each job checks out
history and runs `tools/ci_changes.py` before installing toolchains or building anything.
Unrelated changes skip expensive steps inside the job; a selector failure fails the job.
This avoids pending required checks caused by workflow-level path filtering. Repository
branch-protection settings are managed separately; this change does not modify them.

The selector uses exact filenames and directory prefixes, not GitHub glob syntax.
The dependency map is in `SUITES`, with shared Gradle and Rust inputs added explicitly.
Update it whenever a module starts consuming another module, fixture, generator or asset.

| Changed input | Expensive automatic work |
| --- | --- |
| Root README or `docs/` | None; lightweight check jobs still report |
| App UI / main resources | F-Droid build and Kotlin coverage |
| App unit tests | Kotlin coverage |
| JNI wrappers | F-Droid, Kotlin coverage and native Rust coverage |
| Native Rust implementation / lockfile | F-Droid, Kotlin coverage and native Rust coverage |
| Server implementation / templates / embedded static assets | Server Rust coverage and Kotlin client/server integration + coverage |
| Server tests / traffic simulator | Server Rust coverage |
| Core source or fixtures | F-Droid, Kotlin/native coverage and routing fixture |
| Shared Gradle configuration | F-Droid, Kotlin/native coverage and routing fixture |
| Map pack tools | Python tooling tests and map fixture |
| Coverage script | Python tooling tests and its corresponding coverage suite(s) |
| Selector or its tests | All selected suites, so changes to the dependency map are exercised |

Server Docker smoke builds retain their `main` path filters and manual dispatch. Native
formal verification and full regional pack generation remain manual. No release is published
by these workflows. F-Droid tags continue to build regardless of path selection.

## Diff and failure semantics

- PR selection compares the merge base of the event's base/head with the PR head, including
  earlier commits even when the latest commit only changes documentation.
- Main pushes compare the event's `before` and `after` commits, including force pushes.
- Renames are represented as deletion plus addition, so both dependency paths are considered.
  Filenames are NUL-separated and there is no 300-file API/native-filter cutoff.
- Missing history, new-branch zero SHAs, manual runs, tags and merge queues select all work
  conservatively. Malformed event JSON or a script error fails the selector step.
- Selected compilation/test failures remain failures; no `continue-on-error` is used.

## Resource controls

Superseded PR/merge-queue checks and `main` validation runs are cancelled within the same
workflow/event/ref. Manual runs have independent groups; active tag builds are not cancelled.
Native and server coverage have independent selection, and PostgreSQL starts only for selected
server coverage. Cargo registry/git downloads are cached by OS, architecture and lockfiles;
instrumented build targets are not shared. Existing Gradle caches remain enabled. F-Droid no
longer runs `clean` before every build and has a 60-minute timeout.

Python tooling tests run in the Kotlin workflow without installing Android/Java/Rust when
only tooling changes. Their previous duplicate executions in both Rust matrix entries were
removed. Manual native formal verification retains its own tooling validation.

The lightweight jobs and full-history checkout still have a cost, including on docs-only PRs.
The intended savings are avoided builds, setup, service containers and obsolete runs; no
runner-minute percentage has been measured. The two Rust matrix entries remain present to
preserve check names even when one has no relevant work.

## Validation

Run `python3 -m unittest discover -s tools/tests -v`. Selector regressions use real temporary
git repositories for multi-commit PRs, divergent targets/force pushes, renames, deletions and
diffs exceeding 300 files. Also parse workflow YAML and run `actionlint` where installed.
Actual GitHub job scheduling/cache behavior must be checked on the PR; local selector tests
are not a substitute for a hosted Actions run.

See GitHub's [workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
and [concurrency documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
