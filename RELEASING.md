# Releasing Skipper

Skipper publishes `com.airbnb.skipper:skipper-core` to Artifactory. Releases are currently
cut by hand, following the steps below.

## One-time setup

The build takes its destination and credentials entirely from configuration — no repository
URL or credential is committed here. Set these four values, either in
`~/.gradle/gradle.properties` or as environment variables of the same name:

```properties
SNAPSHOT_REPOSITORY_URL=<destination for -SNAPSHOT versions>
RELEASE_REPOSITORY_URL=<destination for release versions>
artifactoryUsername=<repository username>
artifactoryPassword=<repository password or API token>
```

None of them are needed to build or test. Until the relevant URL is set there is no remote
publish task at all, so `./gradlew build` and `publishToMavenLocal` work with no
configuration whatsoever.

Check your setup without touching the network:

```bash
./gradlew publishToMavenLocal
```

That writes to `~/.m2/repository` and needs no credentials.

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

## Publishing a snapshot

Snapshots are cheap and prunable, so publish them freely from `main`:

```bash
./gradlew publishAllPublicationsToArtifactoryRepository -PVERSION_NAME="$(scripts/next-version.sh --snapshot)-SNAPSHOT"
```

## Publishing a release

Releases are **immutable**: Artifactory will not accept a re-upload of a version that
already exists. A mistake is corrected by publishing a higher version, never by replacing
one. Check `scripts/next-version.sh` output before tagging.

1. Be on an up-to-date `main` with a clean working tree.

2. Tag it. The tag is the release record, so it goes up before the artifact does:

   ```bash
   V=$(scripts/next-version.sh) && git tag -a "v$V" -m "Release $V" && git push origin "v$V"
   ```

   A non-zero exit here means nothing releasable has landed since the last tag.

3. Publish from that exact tag:

   ```bash
   git checkout "v$V"
   ./gradlew clean publishAllPublicationsToArtifactoryRepository -PVERSION_NAME="$V"
   ```

4. Publish the GitHub release so the releases page matches what shipped:

   ```bash
   gh release create "v$V" --generate-notes
   ```

5. Return to `main`: `git checkout main`.

## Notes

- **Signing** is off unless `SIGNING_KEY` and `SIGNING_PASSWORD` are set, in which case every
  publication is signed. Repositories that require signatures — Maven Central among them —
  need those two values present.
- **Reproducibility**: jar timestamps and file order are normalised, so rebuilding a tag
  produces byte-identical artifacts that can be compared against what was published.
