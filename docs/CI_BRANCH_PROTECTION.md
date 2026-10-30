# Branch protection and CI policy (owner checklist)

These settings require **repository admin** rights and cannot be applied by CI bots alone.

Open: https://github.com/valdissor1990-ui/vladossVPN/settings/branches

## Recommended rules for `main`

1. **Add branch protection rule** → Branch name pattern: `main`
2. Enable:
   - **Require a pull request before merging**
     - Require approvals: `1` (or `0` if you are solo and accept self-merge)
     - Dismiss stale reviews when new commits are pushed
   - **Require status checks to pass before merging**
     - Require branches to be up to date before merging (optional but safer)
     - Status checks to add (exact job names from Actions):
       - `Unit Tests` / `unit-tests` (from `.github/workflows/test.yml`)
       - `Pull Request Build Validation` / `build` (from `.github/workflows/build.yml`)
   - **Do not allow bypassing the above settings** (except for a dedicated release bot if you introduce one later)
   - **Restrict who can push to matching branches** — only maintainers
3. Optional but recommended:
   - **Require conversation resolution before merging**
   - **Block force pushes**
   - **Block deletions**

## Why this matters

- `release.yml` publishes a signed APK on every **push to main**.
- Without protection, a direct push or a bad merge can ship a broken build.
- Version uniqueness is now enforced in CI (tag collision fails the job), but **code quality** still needs required checks.

## Legacy `apply-*` workflows

Files under `.github/workflows/apply-*.yml` are one-shot patch bots used during earlier development. They:

- Must only run via **workflow_dispatch** (manual)
- Must only push to **feature branches**, never `main`
- Should not be used for new work — prefer normal PRs with reviewable diffs

## After enabling protection

1. Open a small test PR and confirm required checks appear.
2. Confirm you cannot push directly to `main` (expected error).
3. Confirm release still works after a normal merge to `main`.
