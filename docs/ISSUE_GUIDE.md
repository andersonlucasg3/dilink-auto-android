# How to open an issue (issue operating guide)

> This document is the issue operating standard for all Agents in the repository and **must be followed**.
> Related: how to open a PR in [PR_GUIDE.md](PR_GUIDE.md); workflow overview in [AGENTS.md](../AGENTS.md) §3.

## Definition: the issue is the smallest delegable unit

- One issue = one thing that can be delegated independently to a subagent, validated independently and closed independently.
- **Only open the issue, do not make the fix**: problems found in inspection, real-vehicle testing and code review (no matter the size) must first become an issue; whether it will be fixed, by whom and when is decided in the issue discussion.
- Acting without opening an issue = work without a trace. Any "I think there is a problem here" must first become an issue with evidence.

## When to open one

| Scenario | Title type | Label |
|---|---|---|
| Broken feature, behavior outside expectations | `bug` | `bug` |
| Defect in the test/build/gate itself (false positive, false negative, environment dependency) | `bug` or `fix` | `bug` |
| Need for a new feature / new capability | `feat` | `enhancement` |
| Refactoring (without changing external behavior) | `refactor` | depends on the content |
| Performance optimization | `perf` | `enhancement` |
| Documentation inclusion/review | `docs` | `documentation` |
| Collaboration standard / process convention | `[规范]` ("standard") | `documentation` |
| Research / feasibility report | `[存档]` ("archive") | `documentation` |
| General gap tracking, delivery summary | `[追踪]` ("tracking") / `[记录]` ("record") | `documentation` |

Every new issue carries a type prefix; do not use titles like "there is a problem" or "look at this".

## Title format

```
type(scope): one-line summary
```

- The summary must make clear "what broke / what is wanted", so that reading the list makes the state of the project understandable.
- `scope` indicates the subsystem: `vd-server` / `app-server` / `app-client` / `app-desktop` / `protocol` / `ci` / `tests` / `documentation` etc., and may be omitted.
- Later fixes with the same root cause reference each other's number and state in the title which generation it is (e.g. `… (#3 second case)`).
- Examples:
  - `bug(vd-server): real vehicle "smooth → progressive stutter → freeze → disconnect and restart": blocking synchronous write of the encoded frame to the socket; backpressure to the VD and the 5s write timeout terminate the session`
  - `bug(app-server): black-screen auto-correction wrongly triggers a re-handshake on a 3-second static image and the session is rebuilt repeatedly`
  - `[规范] Repository changes always follow issue → plan → worktree → PR → revalidation`

## The four body elements (none may be missing)

### 1. Symptom

The symptom section of a behavior issue must provide **a reproduction command that can be pasted directly into PowerShell**, plus the expected output, the actual output and the trigger condition:

| Field | Requirement |
|---|---|
| Reproduction command | A command that can be pasted and executed in full (with the necessary prefixes such as `.\gradlew.bat`, `adb`, `.vnev`); if it involves several steps, first consolidate it into a single line or a code block that can be pasted in full |
| Expected output | What should be seen under normal conditions (original text or key lines) |
| Actual output | What is seen now; paste the **original text** (including exit code, error message and log lines) |
| Trigger condition / frequency | On which device/environment/condition it occurs; if intermittent, state "occurrences / total"; if always reproducible, write that it is always reproducible |

- Issues that are not about behavior (documentation only, refactoring only, policy only) write in the symptom section `Reproduction command: none (does not involve runtime behavior)`.
- Counterexamples: "sometimes it hangs", "does not look right" — anything not reproducible must first receive `needs-repro`.

### 2. Evidence chain

- Every claim needs a `file:line` or a reproducible command; for logs, provide the path and the key lines.
- Real-vehicle / desktop app problems should preferably quote the original text of the field log and state which build the log came from (debug/release, whether the diagnostic switch was on).
- **No evidence, no fix** (AGENTS.md §2): a finding without evidence is considered fictitious.

### 3. Impact area

- Who is affected (which module/which flow/which device type), under what condition it triggers, and severity with **P+number**: P0 blocking / P1 common / P2 corner case.
- The fix order is defined by priority in the issue, not in the implementation.
- A large issue that does not fit into one thing: first define the **subsections (P+letter)** and then delegate — one subsection = one PR that can be merged independently; the naming is in [PR_GUIDE.md](PR_GUIDE.md) "Division and naming standard".

### 4. Fix candidates + regression suggestion

- State the suggested direction (there may be several candidates; a perfect solution is not required) and make clear **with which test/verification recurrence will be pinned**.
- Every fixed defect must leave a "tombstone": in the unit test, the comment or the document, write which issue it prevents from recurring.

## Reproduction command and `needs-repro`

If, when opening the issue, it is not possible to provide a pasteable reproduction command, apply `needs-repro`; do not write "it sometimes happens":

- **Discoverer**: add the command line and the expected/actual outputs and then remove the label; if it is truly impossible to include one, explain in the issue why it is not reproducible (timing, real-vehicle environment, permissions) and let the maintainer decide whether to downgrade it to an observation item.
- **Fixer**: when writing the plan, it is mandatory to first fill in an acceptance baseline line — `Reproduction with the issue command: success / failure (paste the original output)`. A failed reproduction indicates that the issue description does not match the current state: return it to the discoverer for more information and do not start implementation; only after reproducing successfully remove `needs-repro` and start changing.
- **Revalidation**: after the merge, run the same command again; see "Closure: behavior issues depend on revalidation".

## Closure: behavior issues depend on revalidation, not on the merge

- Documentation-only / refactoring-only PRs may use `Closes #NN`, and close on merge.
- Behavior PRs (`fix` / `feat` / `perf` and any PR that changes runtime code) use `Refs #NN` in the body; after the merge, the issue stays open and receives `needs-live-verify`, awaiting revalidation.
- **Revalidation** is performed by the discoverer (or by a verification agent in a completely fresh context), following the template in [PR_GUIDE.md](PR_GUIDE.md) "After the merge: review and revalidation", reporting the environment, the reproduction command, the actual output and real-vehicle evidence; only with the conclusion "symptom disappeared" is the label removed and the issue closed. If it still reproduces, remove the label, keep the issue open and add more evidence.
- The fixer cannot revalidate their own work.

## The record trio (use issues as the project's memory)

| Type | Use |
|---|---|
| `[记录]` ("record") | Post-merge state snapshot: summary of what was delivered in a stage + real-vehicle verification result, serving as a baseline of the current state for subsequent issues |
| `[存档]` ("archive") | Feasibility/research report: conclusions and risk list, cited by subsequent issues as the origin |
| `[追踪]` ("tracking") | General gap register: item by item, list "done / not done / evaluated and recommended not to do, closed", preventing the same gap from being reopened repeatedly |

Real-vehicle verification results do not go into the long issue text: fill in the repository documentation via a docs PR and leave only the conclusion and the link in the issue.

## Anti-patterns

- Putting several things into one issue — it cannot be closed independently; split by subsections.
- Conclusion only, no evidence — return it for more information.
- Treating the issue as a chat history or progress bulletin — the process goes in PR comments, the conclusion goes in the issue.
- Pushing directly to main after fixing — going through a PR is mandatory.
- A behavior issue closed on merge with `Closes` — the symptom was not verified by the reproduction command; the closure is only the fixer's claim.
- A symptom described only as "sometimes fails", without a pasteable command — apply `needs-repro` first.

## Template (ready to copy)

```markdown
## Symptom

- Reproduction command: `<a command that can be pasted directly into PowerShell>`
- Expected output: <what should be seen under normal conditions>
- Actual output: <what is seen now; paste the original text>
- Trigger condition / frequency: <device / environment / condition / intermittent or always reproducible; if not a behavior issue, write "none (does not involve runtime behavior)">

## Evidence chain

- `<file>:<line>`: <what that code/configuration demonstrates>
- Log: <path> key lines <...>

## Impact area

<who is affected, under what condition, severity P0/P1/P2>

## Fix candidates

<suggested direction; with which test/verification to pin recurrence; what explicitly will not be done>
```
