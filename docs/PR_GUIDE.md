# How to open a PR (PR operating guide)

> This document is the PR operating standard for all Agents in the repository and **must be followed**.
> Related: how to open an issue in [ISSUE_GUIDE.md](ISSUE_GUIDE.md); workflow overview in [AGENTS.md](../AGENTS.md) §3.

## Definition: the PR is the smallest reviewable unit

- One issue, one PR: the PR is the implementation vehicle for the issue. **A merge only means the change landed on `main`, it does not mean the symptom is gone** — behavior PRs use `Refs #NN` and the issue is closed after the discoverer's revalidation.
- Splitting principle: each PR must pass CI independently, be merged independently and be reverted independently; if it cannot be split, the boundary was not thought through — go back to the issue and complete the plan.

## Closure form: Closes or Refs

| Keyword | Applicable PR | Closing moment |
|---|---|---|
| `Closes #NN` | Documentation only (`docs/`, `*.md`), refactoring only (without changing external behavior) | Closes on merge |
| `Refs #NN` | Behavior: `fix` / `feat` / `perf`, and any PR that touches runtime code paths | The merge only enters the acceptance queue (`needs-live-verify`); it closes only after the discoverer's revalidation |

Criterion in one sentence: **after the merge you still need to observe the symptom once more on a real device to confirm it works** — if you do, it is a behavior change.

- Every PR for an issue with multiple subsections uses `Refs #NN`; after all subsections are merged, revalidation is done once and the issue is closed in a single go.
- A single PR that changes both documentation and behavior: treat it as a behavior PR.
- After merging a behavior PR, the fixer applies `needs-live-verify` to the issue; after revalidation passes, the revalidator removes the label and closes the issue.

## Division and naming standard

| Dimension | Identifier | Semantics |
|---|---|---|
| Subsection (stage) | `P+letter`: `PA`/`PB`/`PC`… | Splits a large issue into ordered stages that can be merged independently; **one subsection = one PR** |
| Priority | `P+number`: `P0`/`P1`/`P2` | P0 blocking (safety/data corruption/main flow inoperative), P1 defect on a common path, P2 corner case and improvement |

- First define the implementation order by priority; within the same priority, proceed in alphabetical order of the subsections; subsection numbering is unique within the issue and does not restart based on priority.
- Reference format: `#<issue>-P<letter>`, for example `#3-PA`.
- Where it goes: the subsection title in the issue is written as `### PA · <title>（P1）`; branches for multiple subsections are written as `<type>/<issue>-p<letter>-<slug>`; the PR title and the commit carry the suffix "subsection PA".

## Branch

```powershell
git worktree add .tmp/wt-<issue> -b <type>/<issue>-<slug> main
```

- Create it from the latest `main`; **use git worktree, do not switch branches in the main workspace**.
- `type` aligned with the issue type: `fix` / `feat` / `docs` / `refactor` / `chore` / `test` / `perf`.
- After using the worktree, run `git worktree remove .tmp/wt-<issue>`; do not leave it lying around in `.tmp/`.

## Title and commit

- PR title: `type(scope): summary（issue #NN）` — branch, title and commit carry the issue number in all three places.
  - Example: `fix(vd-server): encoded frames are now sent through a bounded queue; network backpressure no longer stalls the VD（issue #3）`
- The commit message also carries the issue number; a PR may have several commits, but each commit must explain itself; do not use `wip` / `fix`.

## Body sections

Fixed structure; no mandatory section may be missing:

| Section | Mandatory | Content |
|---|---|---|
| `Closes #NN` / `Refs #NN` | Yes | Choose one of the two according to the previous section; put it at the end of the body |
| `## Problem` | Yes | Reproduces the issue symptom + root cause (with `file:line`) |
| `## Changes` | Yes | List the changes per file, explaining the "why" of each file |
| `## Tests` | Yes | Paste the original output of `.\gradlew.bat` (task success/failure lines); do not write "passed" |
| `## Baseline validation` | Mandatory when fixing a test/gate issue | See below |
| `## Impact area` | Yes | Behavior change, compatibility, parts that explicitly do not change |
| `## Acceptance review` | Mandatory when splitting a large issue | Check item by item per subsection; unfinished items must be listed explicitly and turned into a new issue |

### Baseline validation is a hard requirement

When fixing an issue of the "test/gate false positive or false negative" kind, it is mandatory to prove that the regression actually pinned the defect:

- The same verification on the **base without the patch** (pristine worktree) must be red, and the failure signature must match the issue's symptom;
- After the patch, it must be green;
- Paste both outputs in the PR. Only "it went green with the patch" without "it goes red without the patch" = false green; it is not merged.

## Self-check before opening the PR

1. Run the build and unit tests aligned with CI locally and confirm that the green does not come from private state of the development machine:
   ```powershell
   .\gradlew.bat :app-client:assembleDebug :app-client:testDebugUnitTest
   ```
   If the change involves other modules, also run the unit tests of the corresponding module (e.g. `:protocol-core:test :vd-server:test :app-server:test`).
2. **Before dealing with a local red, run the baseline**: run the same verification again on the base without the patch to confirm whether it is a code or environment problem — environment problems do not go into the fix, but must be recorded in the PR.
3. Confirm that the branch starts from the latest `main` and that, after `git fetch origin main`, it is not behind.
4. Behavior changes on a real vehicle/desktop app must come with field evidence: key log lines, reproduction command output or screenshot path; unit tests alone do not count as validated behavior.

## Merge conditions (merge only if all are met)

1. **CI fully green and CI actually executed**: validate by command, do not trust "the button is clickable" by eye:

   ```powershell
   gh pr checks <NN>
   ```
   - It is valid only with **non-empty** output (a check exists) and **all checks successful** (exit 0). Empty output = CI did not run (see the note below); **do not merge just because the button is clickable**.
   - "CI did not run" is as invalid as "CI red"; the "local equivalent gate" no longer replaces CI — the "## Tests" section of the PR body must paste **at the same time** the original local `.\gradlew.bat` output and the CI run link; missing either one means it is not verified.
   - `.github/workflows/build.yml` runs `:app-client:assembleDebug :app-client:testDebugUnitTest :protocol-core:test` on both push and pull_request, on GitHub-hosted `ubuntu-latest` runners.

   **If CI produces no check at all**: this repository is the original DiLink-Auto repository (not a fork node), runs all workflows on GitHub-hosted runners and has no self-hosted runner, so a workflow only fails to start if Actions itself is disabled on the repository (`gh api repos/{owner}/{repo}/actions/permissions`) or the workflow has a tag/branch filter that does not match. Diagnose in that order before assuming the code was not tested; a `runs-on: self-hosted` label would leave every job queued forever, which is why it is forbidden here.

   `main` and `develop` are protected by a ruleset that requires a pull request, so a direct push is rejected (`GH013`); the rules above still depend on conscious execution because the ruleset does not (yet) require an approving review.
2. **No conflict**: GitHub shows it is mergeable. If there is a conflict, do not force the merge — resolve it in the worktree with `git merge origin/main` (or rebase), run the build and unit tests again, and only then push.
3. When applicable, the `## Baseline validation` section exists.
4. A behavior PR does not close the issue directly after the merge; follow the review and revalidation below.

## After the merge: review and revalidation

Review is the fixer's self-check, revalidation is acceptance by the discoverer; both must be done:

- **Claim vs reality, item by item (fixer)**: every item the PR says it did must be rechecked independently after the merge (`git status`, file existence, original build/unit test output).
- **Open items become a new issue**: omissions, collateral damage and items without real-device closure found in the review must be converted, one by one, into new follow-up issues; the merge does not close it, the review closes it.
- **Revalidation of a behavior issue (discoverer / verification agent in a completely fresh context)**: the fixer applies `needs-live-verify` to the issue after the merge; the revalidator posts a comment using the template below and only with the conclusion "symptom disappeared" removes the label and closes it. If it still reproduces, remove the label, keep the issue open and add more evidence. **The revalidator cannot be the Agent who authored this PR**.
  - **Independence is judged by Agent, not by GitHub account**: this repository's development flow is run by Agents, and each Agent is equivalent to a developer. The Agent who authored the PR and the Agent who authored the issue/revalidator must be different Agents; the same GitHub account (e.g. `ID-VerNe`) may correspond to different rounds of different Agents and **does not constitute self-approval**, nor does it require creating another reviewer account. The revalidation comment must record the Agent/session identifier, instead of using account equality to presume a role conflict.
- In case of recurrence with the same root cause, open a second-generation fix and reference each other; each generation leaves a test tombstone.

### Revalidation template

Post it in the issue comment; no field may be missing:

- **Environment**: `main` @ `<sha>`, local real device / CI, `<time>`; when it involves the vehicle's control center, state the car model, the control center's system version and the connection method (USB / WiFi ADB / direct)
- **Reproduction command**: preferably reuse exactly the one from the issue's "Symptom" section
- **Expected output**: what should be seen after the fix
- **Actual output**: paste the original output, do not write only "normal"
- **Real-device evidence**: log path and key lines (e.g. `tex=/cb=` from `Pipeline:`, `drops=/inputFails=` from `[VideoDecoder]`), duration of continuous observation
- **Conclusion**: symptom disappeared / still reproduces (if it still reproduces, paste the difference)

## Template (ready to copy)

```markdown
## Problem

<reproduction of the issue symptom + root cause, with `file:line`>

## Changes

`<file>`
- <changed point + why>

## Tests

```
<original .\gradlew.bat output: task success/failure lines>
```

Also passed locally: <list of unit tests per module>.

## Impact area

<behavior change / compatibility / parts that explicitly do not change>

## Acceptance review

- [x] <acceptance item 1 from the issue>
- [ ] <unfinished item, converted into #NN>

Refs #NN
```

A behavior PR keeps `Refs #NN` on the last line and, after the merge, follows "After the merge: review and revalidation", awaiting the discoverer's revalidation; a documentation-only / refactoring-only PR switches to `Closes #NN`.
