# Release automation

Run **Actions → Prepare and merge release → Run workflow**, select `main`, and enter
an explicit new version such as `0.1.2` (without `v`). Starting this workflow authorizes
creation of a release PR, automatic squash merge after full CI, and a release tag.
The existing **Release Android artifacts** workflow then builds/signs/publishes APKs.
This does not replace the [device release review](release-testing.md).

The workflow is installed by merging its implementation PR. No release is created
merely by merging that implementation or adding secrets.

## One-time setup

In repository **Settings → Secrets and variables → Actions**, configure:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `OPENAI_API_KEY` | OpenAI API key, used only for generating the two changelog texts. |
| Variable | `OPENAI_RELEASE_MODEL` | A model available to your API project that supports Responses API Structured Outputs, for example `gpt-4o-mini`. No implicit model fallback. |
| Secret | `RELEASE_BOT_TOKEN` | Fine-grained PAT limited to this repository: Contents read/write, Pull requests read/write, Actions read. |

Keep the existing Android signing secrets for the tag workflow:
`ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. The preparation and PR checks do not receive them.

Enable squash merges. Protect `main`, require `Change scope`, `Python tests and style`,
`Native Go tests`, and `Android tests and checks`, and disable bypass for the bot.
If policy requires human review, approve the generated PR yourself; automation does
not approve its own PR or bypass that policy. A denied merge stops finalization;
rerun the failed finalization job after approval. Merge queues are not supported by
this direct squash-merge workflow. Tag rules must permit the bot to create `v*` tags.

Use a separate bot account if you want its permissions distinct from your own.
This initial implementation uses a PAT; GitHub App token minting/renewal is not
implemented. Do not put a short-lived installation token into a persistent secret
and assume it will still work later.

The separate token is intentional: GitHub documents that `GITHUB_TOKEN`-created PR
runs require approval and its pushes do not trigger downstream workflows. A PAT
allows PR CI and the tag-triggered release build to run normally.
[GitHub token behaviour](https://docs.github.com/en/actions/concepts/security/github_token).

## What happens

1. Check out the selected `main` commit. Fail if `main` has already advanced,
   the checkout is dirty, or the version, branch or tag is invalid/existing.
2. Find the highest stable `vX.Y.Z` tag reachable from that commit. Send commit
   subjects/bodies and diff statistics since that tag to OpenAI. Code, repository
   secrets and signing material are not included. History over 100,000 characters
   fails rather than being silently truncated. API calls incur charges on the API
   project; no live API call is part of unit tests.
3. Validate the complete structured response: English and Russian text only,
   each 1–500 characters. Refusal, incomplete/malformed output or API errors stop
   before release files are written. The model only writes text; it does not execute
   commands, choose versions, merge or tag. Structured output does not guarantee
   factual correctness: notes are visible in the PR and Actions summary.
4. Create `release/vX.Y.Z`. Set `versionName` and increment `versionCodeBase` by one
   in `app/build.gradle.kts`. Keep `base * 1000 + ABI offset` unchanged for F-Droid
   and variant-preserving updates. For example base 14 → 15 produces universal
   `15000`, arm `15001`, arm64 `15002`, x86 `15003`, x86_64 `15004`.
5. Add `fastlane/metadata/android/{en-US,ru-RU}/changelogs/15000.txt` for that example.
   Old changelogs and store descriptions are not rewritten. Open a PR to `main`.
6. Wait up to 60 minutes for **CI / pull_request** for the exact PR and head commit.
   Release branches run every suite even on their first CI attempt. All four named
   jobs must finish with `success`; skipped, neutral, failed and cancelled jobs
   are insufficient. The recorded comparison base must match the release parent. The PR number is
   also recorded as a successful CI step because GitHub may remove run-to-PR
   links after merge; this allows a verified retry of tag creation.
7. Recheck PR identity and unchanged `main`, then request squash merge with the
   expected head SHA. GitHub protections still apply. Verify that the resulting
   commit belongs to `main` and has exactly the tested head's tree.
8. Create lightweight tag `vX.Y.Z` on **the actual merged commit**, never on the
   release branch head or whatever `main` happens to point to later. The PAT causes
   the existing tag build to run. Its new GitHub Release uses the same committed
   EN/RU notes. A finalization rerun accepts an existing matching tag and never
   moves a conflicting tag.

The version must exceed both the version in code and the previous stable tag.
Prereleases and automatic version selection are deliberately unsupported. Preparation
and finalization execute trusted workflow source, not code fetched from the release PR.
Only Gradle's two version fields and the two changelog files may be changed by the
release commit. Concurrent release preparations are serialized.

## Failure and recovery

No retry force-pushes branches, moves tags or rewrites historical notes. A failed
check leaves a reviewable PR without a tag. After fixing/re-running its CI, use
**Re-run failed jobs** on the preparation workflow if the PR head and base are
unchanged. This reuses preparation outputs without paying for another API response.
If the PR was already merged but tag creation failed, the same finalization rerun
rechecks CI/tree and creates or verifies the tag without merging again.

If `main` or the PR head changed, the old finalization cannot approve that new code.
Update the branch deliberately so its single release commit is based on current
`main`, rerun full CI, and finalize the explicitly selected new head using a trusted
`main` checkout, authenticated `gh`, and Fastlane:

```sh
export GITHUB_REPOSITORY=andre487/AndroidMegaProxy
bundle exec fastlane android release_finish version:0.1.2 pr:123 head:FULL_40_CHARACTER_SHA
```

The helper fetches the PR for validation but never checks it out or executes it.
This command merges and tags; it is not a dry run. If preparation pushed the branch
but PR creation failed, inspect that branch, create its PR manually, then use this
same recovery procedure. Starting preparation again never silently adopts or
replaces an existing branch. After a tag-build failure, rerun that build instead
of moving the tag. Automated external F-Droid submissions are not part of this flow.

Manual preparation uses `bundle exec fastlane android release_prepare version:0.1.2`
from a clean current-main checkout, with the same API/token/model configuration.

API format: [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
Merge contract: [GitHub merge API](https://docs.github.com/en/rest/pulls/pulls#merge-a-pull-request).

[Русская версия](../ru/release-automation.md)
