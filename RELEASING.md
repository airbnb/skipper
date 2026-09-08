# Releasing Skipper

Skipper publishes `com.airbnb.skipper:skipper-core` to two places, on two cadences:

| Destination | When | Version | How |
| --- | --- | --- | --- |
| **Maven Central** | on a `v*` tag | semantic, e.g. `0.2.0` | CI, automatically |
| **Artifactory** | every commit on `main` | build number, `0.<minor>.<commit count>` | by hand |

Central is the public channel. Every version published there is permanent, public and
undeletable, so only deliberately tagged releases go there. Artifactory is the internal
channel: per-commit build numbers let internal consumers track "whatever is newest" without
anyone choosing a version, and it is published by hand because it is not reachable from CI.

Both are the same publication; only the destination and the version differ.

## One-time setup

The build takes every destination and credential from configuration — nothing is committed
here.

**Artifactory (local, for the per-commit publish).** Set these four values, either in
`~/.gradle/gradle.properties` or as environment variables of the same name:

```properties
SNAPSHOT_REPOSITORY_URL=<destination for -SNAPSHOT versions>
RELEASE_REPOSITORY_URL=<destination for release versions>
artifactoryUsername=<repository username>
artifactoryPassword=<repository password or API token>
```

**Maven Central (CI, for tagged releases).** Lives in the CircleCI context `skipper-publish`
and is never needed locally:

```
ORG_GRADLE_PROJECT_mavenCentralUsername   Central Portal user token: username
ORG_GRADLE_PROJECT_mavenCentralPassword   Central Portal user token: password
SIGNING_KEY                               ASCII-armoured PGP private key
SIGNING_PASSWORD                          its passphrase
```

Central requires every artifact to be signed and verifies the signature against a public
keyserver, so the key's public half must be published there first.

None of these are needed to build or test. Without an Artifactory URL that repository is not
registered at all, and the Central task only asks for its credentials when actually run, so
`./gradlew build` and `publishToMavenLocal` work with no configuration whatsoever.

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
scripts/next-version.sh --build     # a unique, monotonic version for THIS commit; never fails
```

`--build` is the one used for per-commit publishing. It ignores commit prefixes entirely and
prints `0.<minor>.<commit count>`: monotonic and unique because `main` is linear under squash
merges, and correctly ordered because Maven compares each component numerically (`0.1.11` is
newer than `0.1.9`). Tagging `v0.2.0` moves the whole series to `0.2.<count>`. It carries no
semantic meaning by design — it is a build number, not a release.

Every publish task takes its version as `-PVERSION_NAME`. A build given no version falls
back to a `0.0.0-LOCAL` sentinel that the publish tasks refuse to upload to a shared
repository, so a local experiment cannot become a release by accident.

## Publishing a build (every commit)

```bash
./gradlew publishAllPublicationsToArtifactoryRepository -PVERSION_NAME="$(scripts/next-version.sh --build)"
```

This goes to the **release** repository, not snapshots, which is deliberate. A consumer with
a pinned lockfile — Bazel, for instance — needs artifacts that are immutable and never
pruned. A snapshot is neither: pinning one silently freezes the consumer on a single
arbitrary timestamped build, until it is pruned out from under them. The cost of using real
versions is that every merge leaves a permanent artifact, roughly 1 MB of jar plus sources.

Consumers discover the newest version from the repository itself, so nothing needs to tell
them what was published:

```bash
curl -s "$REPO/com/airbnb/skipper/skipper-core/maven-metadata.xml" | grep '<latest>'
```

Snapshots remain available (`--snapshot`, with a `-SNAPSHOT` suffix, which routes to the
snapshot repository) for consumers that genuinely want a moving target.

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

3. **CI takes it from here.** The tag triggers the `release` workflow: `jvm-build` runs the
   full test suite on the tagged commit, then `publish-release` signs the artifacts and
   uploads them to the Central Portal, where they sit as a validated deployment.

4. **Release it in the Portal.** Sign in at central.sonatype.com, open *Deployments*, check
   the artifacts look right, and press *Publish*. Until you do, nothing is public and the
   deployment can be dropped instead. Expect roughly 15–30 minutes after that before the
   version is resolvable from `repo1.maven.org`.

5. Publish the GitHub release so the releases page matches what shipped:

   ```bash
   gh release create "v$V" --generate-notes
   ```

Artifactory already has this commit as a build number, so no manual publish is needed for a
release. If internal consumers want the semantic version there too:

```bash
git checkout "v$V" && ./gradlew clean publishAllPublicationsToArtifactoryRepository -PVERSION_NAME="$V"
```

## Notes

- **Signing** is off unless `SIGNING_KEY` and `SIGNING_PASSWORD` are set, in which case every
  publication is signed. Maven Central rejects unsigned artifacts, so the CI release job
  always has them; a local Artifactory publish does not need them.
- **The javadoc jar** Central requires is produced by Dokka in javadoc format. The module is
  Kotlin-first, so the stock `javadoc` task would document almost nothing.
- **A failed or unwanted Central deployment** can be dropped in the Portal with no trace.
  A *released* one cannot: the fix is always a higher version.
- **Reproducibility**: jar timestamps and file order are normalised, so rebuilding a tag
  produces byte-identical artifacts that can be compared against what was published.
