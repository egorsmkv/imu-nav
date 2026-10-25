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
| App UI / main resources | F-Droid build, Kotlin coverage and Android GPS emulator matrix |
| App AndroidTest / Debug fixtures | Android GPS emulator matrix |
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

The [Android GPS instrumentation](../mock_location_testing.md) workflow builds APKs once and
runs real platform tests on API 28, 31, 35 and 36, plus API 35 with 16 KB pages, with per-API
JUnit/logcat/location/page-size evidence. Saved-trip validation and actual search recreation/
rotation and explicit Graphics Path JNI (Rust port) loading and iteration run with the GPS suite. APK ELF/ZIP alignment gates inspect debug, release and benchmark
outputs; changing the verifier selects both Android and F-Droid jobs.
It selects app, core, routing, native, runner and Gradle changes and always reports its build gate.

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

## Prepare an app version

Run **Actions → Update app version → Run workflow** on `main`. Enter a stable
`major.minor.patch` version (for example `0.7.0`). Leave `version_code` empty to
increment Android's current code, or enter a larger integer (maximum 2100000000).
The updater rejects equal/older versions, malformed inputs and inconsistent metadata
before changing files. It updates `app/build.gradle.kts`, appends a new F-Droid build
recipe copied from the current recipe, and updates F-Droid's current-version fields.
Existing recipes remain available for older tags.

The workflow pushes `release/version-<version>` and attempts to open a PR against `main`.
It uses the repository's `GITHUB_TOKEN`; for automatic PR creation, enable
**Settings → Actions → General → Workflow permissions → Allow GitHub Actions to
create and approve pull requests**. If GitHub rejects PR creation because this setting
is disabled, the run succeeds with a warning and a manual PR link in the workflow
summary. Other PR creation errors still fail the run; the summary retains the manual
link so the pushed branch can be recovered. Review the
PR and approve its workflow runs if GitHub requests approval. If checks are not
scheduled by your GitHub configuration, close and reopen the PR as a maintainer to
trigger the normal `pull_request` checks. See GitHub's
[workflow triggering rules](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).
An existing version branch is never overwritten. Rerunning a partially completed update
verifies that the branch's parent is an ancestor of `main` and its latest commit contains exactly the requested version patch,
including no unrelated file changes, then reuses it and any open PR. A differing patch
fails for manual review. Changes to the version files on `main` can also cause this
comparison to fail; open the PR manually in that case. Runs are serialized, and
branch protection still controls merging.

After checks pass, merge the PR and separately create `v<version>` on the merged
release commit. This triggers the existing F-Droid tag build. The version workflow
creates neither tags nor releases and does not automatically merge the PR.
Local equivalent: `python3 tools/update_app_version.py --version-name 0.7.0`
(with optional `--version-code 8`), then review both files before committing.
