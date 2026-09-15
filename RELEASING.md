# Releasing Skipper

Skipper publishes four artifacts to **Maven Central**, from CI, on every `v*` tag:
`com.airbnb.skipper:skipper-core` (the engine), `com.airbnb.skipper:skipper-testutils` (the test
harness, built from `testutils/`), `com.airbnb.skipper:skipper-state-machine` (the state-machine
DSL) and `com.airbnb.skipper:skipper-metrics-prometheus` (the Prometheus metrics backend, built from
`plugins/prometheus/`). They share one version. That is the only place any of them is published.

## Versions

Versions come from git history, not from a file in the tree — there is no version to bump
and no release commit. `scripts/next-version.sh` reads the
[Conventional Commit](https://www.conventionalcommits.org) prefix of every commit since the
last `v*` tag and prints the version they imply. Because `main` takes squash merges, that
prefix is each pull request's title.

| Prefix | Bump | Note |
| --- | --- | --- |
| `feat!:`, or `BREAKING CHANGE:` in the body | major | minor while the version is `0.x` |
| `feat:` | minor | |
| `fix:`, `perf:` | patch | |
| anything else | none | snapshots only, no release |

The highest bump in the range wins, so one `feat` batched with three `fix`es is a single
minor release.

```bash
scripts/next-version.sh             # the next release version; exits 3 if nothing releasable landed
scripts/next-version.sh --snapshot  # always prints a version, treating "nothing" as a patch
```

Every publish task takes its version as `-PVERSION_NAME`. A build given no version falls
back to a `0.0.0-LOCAL` sentinel that the publish tasks refuse to upload to a shared
repository, so a local experiment cannot become a release by accident.

## Publishing a release

1. **Merge a releasable pull request.** Squash-merging a PR whose title starts with `fix:`,
   `perf:`, `feat:` or a `!:` breaking marker is what starts a release. Check the version it
   will produce before merging if it matters:

   ```bash
   scripts/next-version.sh   # on an up-to-date main, with the PR's title in mind
   ```

2. **CI tags it.** On `main`, once every gating check passes (`jvm-build` in
   `.github/workflows/build.yml`), the `tag-release` job runs `scripts/next-version.sh`, pushes
   the `v*` tag it prints, and starts the `release` workflow on that tag. It does nothing when
   no releasable commit has landed since the last tag, or when the tag already exists, so
   re-runs and racing pipelines are safe. The tag is the release record, so it goes up before
   the artifact does.

   To cut a release by hand instead (or if the job is ever unavailable), the same steps work
   locally on a clean, up-to-date `main`:

   ```bash
   V=$(scripts/next-version.sh) && git tag -a "v$V" -m "Release $V" && git push origin "v$V"
   ```

   **First release only:** `v0.2.0` was tagged by hand. Versions `0.1.1`–`0.1.7` were
   consumed by an earlier per-commit build-number scheme and still exist in the internal
   mirror, so the first semantic release had to sort above them or "latest" there would
   point at the older artifact. The script takes over from that tag onward.

3. **The `release` workflow runs on the tag** (`.github/workflows/release.yml`; a hand-pushed
   tag starts it too): `jvm-build-jackson-2.9.10` runs the full test suite on the tagged
   commit, then `publish-release` signs the artifacts and uploads them to the Central Portal,
   where they sit as a validated deployment. The credentials live in the `skipper-publish`
   GitHub environment; the workflow header lists them.

4. **Manual release in the Portal.**

5. Make sure the dependency snippets in `README.md` and `web/src/content/docs/quickstart.md`
   say `$V`. A releasable PR should bump them itself, since its title decides the version;
   the README badge updates on its own, the copy-pasteable snippets do not.
