# Releasing Skipper

Skipper publishes two artifacts to **Maven Central**, from CI, on every `v*` tag:
`com.airbnb.skipper:skipper-core` (the engine) and `com.airbnb.skipper:skipper-testutils` (the
test harness, built from `testutils/`). They share one version. That is the only place either is
published.

Every version on Central is permanent, public and undeletable, so only deliberately tagged
releases go there. Internal consumers need nothing extra: Artifactory mirrors Central, so a
release shows up there on its own — there is no direct upload to Artifactory, and no
per-commit publishing of any kind.

## One-time setup

Credentials live in the CircleCI context `skipper-publish` and are never needed locally —
nothing is committed here:

```
ORG_GRADLE_PROJECT_mavenCentralUsername   Central Portal user token: username
ORG_GRADLE_PROJECT_mavenCentralPassword   Central Portal user token: password
SIGNING_KEY                               ASCII-armoured PGP private key, or base64 of it
SIGNING_PASSWORD                          its passphrase
```

Central requires every artifact to be signed and verifies the signature against a public
keyserver, so the key's public half must be published there first.

Prefer storing `SIGNING_KEY` as base64 (`gpg --armor --export-secret-keys KEY_ID | base64`).
The armoured key is multi-line, and a variable field that flattens it onto one line fails
inside the release job with only `Could not read PGP secret key` to go on — the same message
a wrong passphrase gives. base64 survives any field, line-wrapped or not.

None of these are needed to build or test. The Central task only asks for its credentials
when actually run, so `./gradlew build` and `publishToMavenLocal` work with no configuration
whatsoever.

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

## Publishing a release

Releases are **immutable**: Maven Central will not accept a re-upload of a version that
already exists. A mistake is corrected by publishing a higher version, never by replacing
one.

1. **Merge a releasable pull request.** Squash-merging a PR whose title starts with `fix:`,
   `perf:`, `feat:` or a `!:` breaking marker is what starts a release. Check the version it
   will produce before merging if it matters:

   ```bash
   scripts/next-version.sh   # on an up-to-date main, with the PR's title in mind
   ```

2. **CI tags it.** On `main`, once `jvm-build` and `format-check` pass, the `tag-release` job
   runs `scripts/next-version.sh` and pushes the `v*` tag it prints. It does nothing when no
   releasable commit has landed since the last tag, or when the tag already exists, so
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

3. **The tag triggers the `release` workflow:** `jvm-build` runs the full test suite on the
   tagged commit, then `publish-release` signs the artifacts and uploads them to the Central
   Portal, where they sit as a validated deployment.

4. **Release it in the Portal.** Sign in at central.sonatype.com, open *Deployments*, check
   the artifacts look right, and press *Publish*. Until you do, nothing is public and the
   deployment can be dropped instead. Expect roughly 15–30 minutes after that before the
   version is resolvable from `repo1.maven.org`.

5. Publish the GitHub release so the releases page matches what shipped:

   ```bash
   gh release create "v$V" --generate-notes
   ```

6. Make sure the dependency snippets in `README.md` and `web/src/content/docs/quickstart.md`
   say `$V`. A releasable PR should bump them itself, since its title decides the version;
   the README badge updates on its own, the copy-pasteable snippets do not.

## Notes

- **Signing** is off unless `SIGNING_KEY` and `SIGNING_PASSWORD` are set, in which case every
  publication is signed. Maven Central rejects unsigned artifacts, so the CI release job
  always has them; `publishToMavenLocal` does not need them.
- **The javadoc jar** Central requires is produced by Dokka in javadoc format. The module is
  Kotlin-first, so the stock `javadoc` task would document almost nothing.
- **A failed or unwanted Central deployment** can be dropped in the Portal with no trace.
  A *released* one cannot: the fix is always a higher version.
- **Reproducibility**: jar timestamps and file order are normalised, so rebuilding a tag
  produces byte-identical artifacts that can be compared against what was published.
