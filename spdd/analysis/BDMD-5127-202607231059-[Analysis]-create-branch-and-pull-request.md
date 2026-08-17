# SPDD Analysis: Local branch, selective push, pure orphan + merge, and Pull Request

## Original Business Requirement

Extend git-utils so consuming applications can:

1. Create and check out a collision-safe local branch from current HEAD.
2. Selectively publish one named branch or one named tag (without changing legacy `push`).
3. Create a same-repository Pull Request / Merge Request via provider APIs.
4. Support a **pure-orphan + tag + merge** Git process so a checkpoint can contain only generated content, then be integrated into the default branch without including pre-existing user files in the 3-way-merge baseline.

All four features are specified and implemented, including tip promotion for missing/unborn `mergeBranch` targets (capability 4c). Legacy `push` remains intact, while `pushBranch` / `pushTag`, pure orphan creation, related/unrelated local merge, and tip promotion are additive APIs.

## Why Feature 4 (pure orphan + tag + merge)

For a non-empty target repository:

- If generated content is written onto `main` and that mixed tip is tagged, pre-existing user files become part of the generated-content baseline.
- If a later update branches from that tag, clears the tree, and writes only the next generated version, Git treats user-only files as deletions and tries to remove them from `main` in the PR.

Correct isolated-checkpoint process (non-empty target):

```
       [checkpoint-v1] (Pure generated content v1)
              C1 ──────────────────────────────> C2 (update/generated-v2)
               \                                  \
                \ (Merge into main)                \ (PR / Merge)
 main:  M0 ────> M1 ────────────────> M2 ──────────> M3
   (Existing    (Has generated v1 +    (User edits
  User Files)   User Files)            user files)
```

Empty / unborn target (e.g. freshly created remote with no commits, after `readRepository` orphan-bootstraps the default branch name):

```
       [checkpoint-v1]
              C1
              │
              │ tip promotion (no merge commit)
 main:       C1   ← integration branch tip becomes the pure commit
```

Consumers use the **same** orphan → commit → tag → `mergeBranch` → push composition for both empty and non-empty targets. `mergeBranch` tip-promotes when the target has no resolved tip.

Required Git capabilities on an already-cloned local repo:

| Step | Intent | git-utils today |
|------|--------|-----------------|
| A | Start an **orphan** branch with an **empty** index/work tree (no parent commit) | Implemented by `createAndCheckoutOrphanBranch` |
| B | Consumer writes pure render, `add`, `commit` | Exists |
| C | Tag the pure tip (`addTag`) | Exists |
| D | **Merge** the orphan branch into the integration branch (usually default/`main`), or **tip-promote** when the integration branch is missing/unborn | Implemented by `mergeBranch` (related, unrelated, and unborn-target tip promotion) |
| E | Push integration branch + checkpoint tag (`pushBranch`, `pushTag`) | Exists (after Feature 2) |

With A and D as first-class `GitOperation` APIs, consumers can compose this process without embedding raw JGit.

## Feature inventory

### Feature 1 — Create and check out a local branch from current HEAD

Consumers that already cloned a repository at a given revision need to:

- Create a new **local** branch from **current HEAD** (including detached HEAD after tag/commit checkout).
- Check it out so subsequent `add` / `commit` / `addTag` / selective push run on it.
- Fail if the name already exists **locally or on `origin`** (`ls-remote`).
- API:

```text
String createAndCheckoutBranch(File repoDir, String branchName)
```

Returns the tip SHA after checkout.

**Status:** Specified and implemented.

### Feature 2 — Selective push (preserve legacy `push`)

- Keep `push(File repoDir, boolean pushTags)` **intact** (`setPushAll` + optional `setPushTags`).
- Add:

```text
void pushBranch(File repoDir, String branchName)
void pushTag(File repoDir, String tagName)
```

- Each publishes exactly one named local ref with a non-force refspec and verifies remote update results.
- Independent of current HEAD (named branch may be pushed while detached or on another branch).

**Status:** Specified and implemented (supersedes earlier “harden `push` to current-branch-only” idea).

### Feature 3 — Create Pull Request / Merge Request via provider APIs

- Same-repository PR/MR through `GitProvider` HTTP APIs (all providers in v1).
- Does **not** push, merge, or delete branches.
- Caller pushes the source branch first (normally `pushBranch`).

```text
PullRequest createPullRequest(Repository repository, CreatePullRequest createPullRequest)
```

**`CreatePullRequest`:** required `sourceBranch`, `title`; optional `targetBranch` (default `repository.defaultBranch`), `body`.
**`PullRequest`:** required `id`, `webUrl`; plus normalized branches/title/body; optional `state`.

**Status:** Specified and implemented.

### Feature 4 — Pure orphan branch + local merge

Support composable primitives so a consumer can:

1. Clone the target repo at the integration branch (`readRepository`).
2. Create and check out an **orphan** branch with empty index and empty work tree (preserving `.git`).
3. Write pure generated content, `add`, `commit`.
4. `addTag` on that commit.
5. **Integrate** the orphan branch into the integration branch via `mergeBranch` (two-parent merge when the target already has commits; **tip promotion** when the target is missing or unborn).
6. `pushBranch` (integration branch) + `pushTag` (checkpoint).

#### Implemented public APIs

```text
void createAndCheckoutOrphanBranch(File repoDir, String branchName)

String mergeBranch(File repoDir, String sourceBranch, String targetBranch)
```

**`createAndCheckoutOrphanBranch`:**

- Creates/checks out an orphan branch named `branchName`; trims the name, normalizes one leading `refs/heads/`, rejects other `refs/` namespaces, and validates the full local ref.
- Resulting commit history for the new branch has **no parent** after the first commit on it.
- Requires `RepositoryState.SAFE`, a clean status, and no ignored entries before mutation.
- Refuses an exact local/current-unborn collision and an exact remote collision found by authenticated `ls-remote`.
- After checkout: **empty committed `DirCache` and empty work tree**, preserving `.git` as either a directory or worktree pointer and preserving external repository metadata. NIO traversal does not follow symbolic links.
- Verifies symbolic unresolved `HEAD`, an empty index, and a metadata-only work tree.
- Returns `void` because an unborn orphan has no SHA. Caller obtains the tip after its first commit.
- If cleanup fails after checkout, force-checks out and hard-resets the original branch/tip, restores its committed tree, removes any partial orphan ref, and preserves rollback failures as suppressed exceptions.

**`mergeBranch`:**

- Validates pristine repository state, valid local-ref names, and distinct branch names.
- **Source** must resolve to an exact local branch tip (non-null object id). Missing or unborn source is rejected.
- **Target** may be (a) a born local branch tip, (b) an unborn local branch (symbolic ref / no object id), or (c) absent as a local ref when the consumer is bootstrapping an empty repository’s integration branch name.
- When the target already has a tip: check out `targetBranch`, then merge `sourceBranch` without squash or rebase. Equal tips are a successful no-op. Related histories use standard JGit merge; unrelated histories use an in-core `ResolveMerger` with an empty-tree synthetic base, then a deterministic two-parent commit (first parent = old target tip, second parent = source tip).
- When the target has **no** tip (missing or unborn): **tip promotion** — create or update `refs/heads/{target}` to point at the source tip, check out the target, and return that SHA. No merge commit is created (linear history starting at the pure commit). This is not a force-reset of an existing tip. On tip-promotion failure after mutation starts, force-delete the partially promoted target ref when present and clear merge metadata.
- Does **not** push.
- On conflict/failure when a pre-merge target tip existed: checks out target before hard-resetting its pre-merge tip, clears merge metadata, and throws `GitOperationException("mergeBranch", …)` without leaving a half-merged tree.
- Returns the resulting tip SHA of `targetBranch` after success (merge tip or promoted tip).

Tagging remains **`addTag`** (existing). No combined “orphan+tag+merge” mega-method — keep composable primitives.

#### Consumer orchestration

```text
readRepository(target, defaultBranch pointer)
  → createAndCheckoutOrphanBranch(repo, orphanBranchName)
  → clean already empty / write generated content
  → add / commit
  → addTag(checkpoint)
  → mergeBranch(repo, orphanBranchName, defaultBranch)
  → pushBranch(defaultBranch)
  → pushTag(checkpoint)
  → optional: delete local orphan branch only (remote orphan never pushed) OR never push orphan
```

**Recommendation:** do **not** push the orphan branch; only push the integration branch + tag. The orphan branch is a local vehicle for the pure commit; after merge or tip promotion, the pure commit is reachable from the integration branch and the tag. Empty and non-empty targets share this composition.

#### Provider mandatory-field survey (create PR) — unchanged

| Provider | Endpoint (conceptual) | Mandatory create fields | Optional (common) | Result URL field |
|----------|----------------------|-------------------------|-------------------|------------------|
| **GitHub** | `POST /repos/{owner}/{repo}/pulls` | `head`, `base`; `title` (unless converting issue) | `body`, `draft`, … | `html_url` |
| **GitLab** | `POST /projects/:id/merge_requests` | `title`, `source_branch`, `target_branch` | `description`, … | `web_url` |
| **Bitbucket Cloud** | `POST .../pullrequests` | `title`, `source.branch.name` | `description`, `destination`, … | `links.html.href` |
| **Azure DevOps** | `POST .../pullrequests` | `sourceRefName`, `targetRefName`, `title` | `description`, `isDraft`, … | `_links.web.href` |

## Related existing behavior

| Capability | Status |
|------------|--------|
| Clone/checkout at branch, tag, or commit | Exists (`readRepository` + `RepositoryPointer`) |
| Orphan checkout for **empty remote** only | Internal to `readRepository` — **not** reusable for non-empty repos |
| Stage including deletions | Exists (`AddMode`) |
| Commit, create tag | Exists |
| Legacy push all branches (+ optional all tags) | Exists — **must remain unchanged** |
| Selective `pushBranch` / `pushTag` | Exists (Feature 2) |
| Create branch from HEAD | Exists (Feature 1) |
| Create PR/MR | Exists (Feature 3) |
| Public orphan branch with empty tree | Implemented (`createAndCheckoutOrphanBranch`) |
| Public local branch merge | Implemented (`mergeBranch`) — tip promotion for unborn/missing target **done** |

## Capability gap summary

| # | Capability | Layer | Status |
|---|------------|-------|--------|
| 1 | `createAndCheckoutBranch` + local/remote refuse via `ls-remote` | `GitOperation` | **Done** |
| 2 | Legacy `push` intact; `pushBranch` / `pushTag` selective | `GitOperation` | **Done** |
| 3 | `createPullRequest(Repository, CreatePullRequest)` all providers | `GitProvider` | **Done** |
| 4a | `createAndCheckoutOrphanBranch(File, String)` empty index/work tree | `GitOperation` | **Done** |
| 4b | `mergeBranch` related/unrelated merge → tip SHA; clean fail on conflict | `GitOperation` | **Done** |
| 4c | `mergeBranch` tip promotion when target is missing or unborn | `GitOperation` | **Done** |

## Domain Concept Identification

#### Existing Concepts (from codebase)

- **GitOperation / GitOperationImpl**: Local JGit facade — init, read, add, commit, push, pushBranch, pushTag, createAndCheckoutBranch, createAndCheckoutOrphanBranch, mergeBranch, addTag, getHeadSha.
- **RepositoryPointer**: Positions clone; tag checkout is shallow and typically detached.
- **Tag** + `addTag`: Tag at commit SHA.
- **AddMode**: Staging modes including deletions.
- **GitProvider**: Collaboration + `createPullRequest`; local orphan/merge remains correctly encapsulated by `GitOperation`.
- **Exceptions**: `GitOperationException`, `GitClientException`, `GitProviderAuthenticationException`.

#### Concepts Introduced

- **Orphan branch (public)**: Branch with no parent history until first commit; empty tree ready for a pure render. Distinct from empty-repo bootstrap inside `readRepository`.
- **Local merge into integration branch**: Merge the pure orphan tip into the default/integration branch without requiring a provider PR.
- **Tip promotion (unborn/missing target)**: When the integration branch has no resolved tip, `mergeBranch` points the target ref at the source tip and checks it out, so empty-repository consumers keep the same orphan → tag → integrate composition without a separate API.
- (Already introduced) **CreatePullRequest** / **PullRequest**, **pushBranch** / **pushTag**.

#### Key Business Rules

- Create-branch uses **current HEAD** only; refuse local/remote name collisions via `ls-remote`.
- Legacy `push` behavior must not change; selective publish uses `pushBranch` / `pushTag` only.
- Local branch creation and merge belong on **`GitOperation`**; PR creation on **`GitProvider`**.
- Orphan API must yield an **empty tree** (not “orphan checkout leaving previous work-tree files”).
- Pure checkpoint tag is applied with existing `addTag` on the orphan tip **before or after** merge as long as the tag points at the **pure commit** (C1), not at a later main-only commit that mixes user files. Prefer tag **on the orphan tip before merge** so the tag object is unambiguous.
- Empty-repository consumers use the **same** orphan flow; they must not special-case “commit directly on main” in the library. `mergeBranch` tip-promotes when the target tip is missing or unborn.
- Tip promotion must never overwrite an existing target tip; when the target already has commits, only merge (or equal-tip no-op) applies.
- `mergeBranch` must not push; the caller pushes with selective APIs afterward.
- Merge conflicts → fail clearly (`GitOperationException`); do not leave a dirty conflicted work tree for the caller to continue.
- PR merge / branch delete remain out of scope for Feature 3.
- `createPullRequest` does not push.

## Strategic Approach

#### Solution Direction

- Keep Features 1–3 as shipped.
- Use the implemented Feature 4 primitives on `GitOperationImpl`: orphan checkout plus locked-index/NIO cleanup, standard JGit merge for related histories, an empty-tree in-core merge for unrelated histories, and tip promotion when the integration branch tip is missing or unborn.
- Do **not** force consumers to implement orphan+merge with raw JGit.
- Prefer **not** pushing the orphan branch; push integration branch + tag only.
- Prefer one consumer composition for empty and non-empty targets via `mergeBranch` tip promotion rather than a separate “commit on main” path.

#### Key Design Decisions

| Topic | Decision |
|-------|----------|
| Legacy `push` | **Unchanged** |
| Selective push | `pushBranch` / `pushTag` |
| Orphan API | `void createAndCheckoutOrphanBranch(File repoDir, String branchName)` — empty index + empty work tree |
| Orphan remote collision | Refuse exact local/current-unborn and remote refs; remote check uses authenticated `ls-remote` |
| Merge API | `String mergeBranch(File repoDir, String sourceBranch, String targetBranch)` → tip SHA of target after merge |
| Merge on conflict | Fail with `GitOperationException`; restore target to pre-merge tip (clean abort) |
| Unborn / missing target | **Tip promotion**: point target at source tip; return that SHA; no merge commit |
| Empty-repo consumer path | Same orphan → commit → tag → `mergeBranch` → push as non-empty; no alternate “commit on main” library path |
| Tag | Existing `addTag` on pure commit |
| Combined workflow API | Rejected — compose primitives |
| Local vs user/provider merge | git-utils provides **local merge** (plus tip promotion for unborn targets). Opening a PR from the orphan remains possible but requires pushing the orphan. **Recommend local merge/tip-promote + push integration branch + tag** for automated integration. |

#### Alternatives Considered

- **Harden legacy `push` to current-branch-only**: Rejected — keep `push`; use selective methods.
- **Provider HTTP create-branch as primary for Feature 1**: Rejected.
- **Only document orphan via empty-repo `readRepository`**: Rejected — does not cover non-empty repositories.
- **Soft reset / delete all files on main then commit as “pure”**: Rejected — pollutes main history and baseline.
- **Open an initial PR from a pushed orphan instead of local merge**: Possible consumer alternative, but rejected as the library gap filler because local integration is a useful provider-neutral primitive.
- **Single method `applyPureCommitAndMerge(...)`**: Rejected — too opinionated; breaks GitOperation style.
- **Leave conflicted merge for caller to resolve**: Rejected; prefer abort + exception.
- **Require consumers to commit pure content directly on an unborn default branch**: Rejected — splits empty vs non-empty orchestration; tip promotion inside `mergeBranch` keeps one composition.
- **Reject missing/unborn target forever**: Rejected — breaks empty-repository initial generation after `readRepository` orphan-bootstraps the default branch name.

## Risk & Gap Analysis

#### Resolved Decisions (Features 1–3)

| Topic | Decision |
|-------|----------|
| Create-branch signature | `String createAndCheckoutBranch(File, String)` |
| Remote existence | `ls-remote` on `origin` |
| Push model | Legacy `push` intact; `pushBranch` / `pushTag` |
| PR signature / DTOs | `CreatePullRequest` in → `PullRequest` out; all providers |

#### Resolved Decisions (Feature 4)

| Topic | Proposal |
|-------|----------|
| Orphan signature | `void createAndCheckoutOrphanBranch(File repoDir, String branchName)` |
| Orphan tree | Empty index + empty work tree; preserve `.git` |
| Orphan name collisions | Refuse local; refuse remote via `ls-remote` (consistent with create-branch) |
| Merge signature | `String mergeBranch(File repoDir, String sourceBranch, String targetBranch)` |
| Merge strategy | Standard JGit merge for related histories; in-core empty-tree merge with a deterministic two-parent commit for unrelated histories |
| Unborn / missing target | **Tip promotion**: create/update target ref to the source tip; check out target; return that SHA; no merge commit |
| Empty-repo consumer | Same orphan → commit → tag → `mergeBranch` → push composition as non-empty targets |
| Merge conflicts | Exception + hard reset target to pre-merge tip (only when a pre-merge tip existed) |
| Push orphan | Library does not push implicitly; consumers normally publish only the integration branch and checkpoint tag |

#### Remaining Consumer Decisions

- Exact orphan branch naming (ephemeral local-only name vs conventional name).

#### Edge Cases

- Orphan on repo that already has `branchName` locally/remotely → refuse.
- Merge when source is not an ancestor and overlaps paths with target → conflict → abort.
- Merge when target has no commits yet (missing or unborn local tip) → **tip promotion** (resolved): target ref becomes the source tip; no merge commit; returned SHA equals source tip.
- Missing or unborn **source** → still reject (`GitOperationException("mergeBranch", …)`).
- Tip promotion must not replace an existing target tip (born target always uses merge / equal-tip no-op).
- Tag pointing at pure commit after merge or tip promotion still reachable from main.
- Shallow clone + merge may need deepen — mitigate with tests (full or deepen-as-needed).
- Cleaning work tree must preserve `.git` files/directories and external repository metadata; ignored content is rejected by the pristine precondition.

#### Technical Risks

- JGit orphan + empty tree correctness across platforms (file locks on Windows).
- Shallow clones may not contain enough history for reliable merge-base discovery; consumers may need a full/deepened clone.
- Accidentally pushing an orphan can pollute the remote with temporary names; consumer conventions and selective push avoid it.
- Conflict abort must be reliable (reset hard) so temp dirs stay reusable or safely deleted.

#### Acceptance Criteria Coverage

| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | `createAndCheckoutBranch` from HEAD returns tip SHA | Yes | Done |
| 2 | Create-branch refuses local/remote name via `ls-remote` | Yes | Done |
| 3 | Selective `pushBranch` / `pushTag`; legacy `push` unchanged | Yes | Done |
| 4 | `createPullRequest` all providers; no push/merge/delete | Yes | Done |
| 5 | Failures use existing exception types | Yes | Implemented with stable `createAndCheckoutOrphanBranch` / `mergeBranch` operation names |
| 6 | `createAndCheckoutOrphanBranch` yields empty tree orphan ready for pure commit | Yes | Implemented and covered by a real-repository parentless-commit test |
| 7 | `mergeBranch` merges source into target and returns tip SHA; conflicts abort cleanly | Yes | Related and unrelated paths implemented with rollback |
| 7b | `mergeBranch` tip-promotes when target is missing/unborn; returns source tip SHA | Yes | Done — MERGE-006 / FLOW-002 |
| 8 | Consumers can compose orphan → commit → tag → merge → `pushBranch`/`pushTag` without raw JGit | Yes | Covered by FLOW-001; empty-repo compose covered by FLOW-002 |
| 9 | Tests: orphan empty tree; merge preserves unrelated main files; conflict aborts; tip promotion; selective push unchanged | Partial | Core real-repository cases including tip promotion pass; remaining Gherkin traceability to be completed |
