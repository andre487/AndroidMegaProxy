# Release APK reuse benchmark — 2026-10-03

Source baseline: `5c7047c` (v1.0.0), macOS arm64, JDK 21. Both builds used
`bundle exec fastlane android release_artifacts`, the same local release signer,
SDK/NDK, Go module cache and Gradle daemon. Baseline ran first, candidate second;
existing dependency/compiler caches were retained. This is one sequential pair,
not a cold-cache benchmark, median, or GitHub runner performance guarantee.

## Change and timing

Build one universal gomobile AAR and reuse it for all APKs with the existing
Gradle ABI filters. Remove the five intermediate Gradle cleans. Preserve binding
flags, version-code offsets, APK_VARIANT, signing and verification.

| Complete lane | Wall time |
| --- | ---: |
| Baseline | 214.47 s |
| Candidate | 177.11 s |

Observed saving: 37.36 s (17.4%). Gradle reports reused intermediate tasks;
variant-specific BuildConfig/version fields still require Kotlin/Java/R8 work.
No Gradle build-cache or dependency changes were needed.

## Artifact checks

All five signed APKs are byte-for-byte identical between the two runs (matching
SHA-256), including every ZIP entry. Sizes below are exact bytes, not rounded MB.
All size deltas are zero. Universal contains four native ABIs; each other APK
contains only its named ABI, including transitive AndroidX native libraries.

| APK variant | Baseline bytes | Candidate bytes | SHA-256 (both) |
| --- | ---: | ---: | --- |
| universal | 55573043 | 55573043 | `e085e3fc85cec1636a7306e77bd7067aa90710cf013fe5b625ee17c459411a46` |
| armeabi-v7a | 14998868 | 14998868 | `7d9646b06e9c793d5dafbc1edbbd97326864bb0ede6691164e42c4f22f8e8cf4` |
| arm64-v8a | 15364196 | 15364196 | `c9ac04f98b66c29c06cc40e17ef231854b89d85880291f807d0919e90da4a2d7` |
| x86 | 15042172 | 15042172 | `a9bd1796d8df34583745cd4700a4a6b3029110278b1d20a5aeb092a7076554c9` |
| x86_64 | 16300126 | 16300126 | `ae155d94a1fcd014bd4d79cbab65425809d1672d2525de67a129d7ba744da2d5` |

Both lanes passed native tests, Android JVM tests and release lint/build tasks.
The candidate retained reports for 169 JVM tests in 46 suites: zero failures,
errors or skips. Every APK passed the script's apksigner certificate and
version-code verification; independent aapt checks confirmed versionName 1.0.0,
minSdk 26 and codes 15000–15004 with their existing offsets.

Byte-identical universal output confirms this local incremental build matches the
baseline's clean universal assembly. This is not an official F-Droid buildserver
reproducibility result or a new device/VPN test. No APK was published or installed.

Local evidence (ignored): `dist/release-benchmark/{baseline,optimized}.log`,
`dist/release-benchmark/{baseline,optimized}/*.apk`, and `comparison.json`.
Reproduce with separate MEGAPROXY_RELEASE_DIR values and `/usr/bin/time -p` around
the lane; compare SHA256SUMS, aapt badging and ZIP library paths for each variant.

## APK contents review

ARM64's Go library is 13,294,096 bytes; AndroidX graphics-path is 10,096 bytes.
Both are stored uncompressed. No extra CPU architectures or packaged source AAR
were found in architecture-specific APKs. Common DEX/resources are expected.
The resource table is 371,144 bytes and includes dependency translations beyond
English/Russian; this is the entire table size, not the translations' overhead.
META-INF and Kotlin metadata together occupy about 22 KB compressed; coroutine
DebugProbesKt.bin and kotlin-tooling-metadata.json add 1,066 compressed bytes.
These are possible separate size investigations, not demonstrated safe deletions.
Resource stripping and native compression were deliberately not mixed into this
build-reuse change.
