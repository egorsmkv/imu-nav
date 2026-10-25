# Google Play bundle preparation

[Documentation index](../README.md)

IMU Nav is a research prototype, not a safety system. This process **prepares artifacts only**.
It does not create keys, create a Play listing, upload, submit for review or publish the app.
Play Console account/listing state and the registered upload certificate have not been verified.

## Build modes and trust boundary

**Google Play bundle** (`.github/workflows/play-bundle.yml`) runs only when started manually
(Actions → Google Play bundle → Run workflow); `mode=test` runs unsigned validation. It builds the actual R8 release with
`:app:bundlePlayRelease`, runs `check` and `:app:lintPlayRelease`, and passes `-PunsignedPlay`
so even an existing local `keystore.properties` cannot sign that CI build. No private signing
variables are referenced by this job. Its `imu-nav-play-unsigned-<sha>` artifact is not an upload release.

After this workflow is merged, run it manually **on main**, select `mode=release` and enter
`last_play_version_code`: the largest code ever uploaded on any Play track, including drafts
and retired releases. Use `0` only if nothing has ever been uploaded. The build must have a
strictly larger code. The current source remains **0.8.0 / code 8**; this change does not bump
it or tag a release. If 8 has been used, prepare a version update first using the existing
App version workflow. A test-mode run without this input checks source consistency, not Play history.

A separate fresh signing runner downloads only the successful artifact from this same run,
checks SHA-256, and signs a copy with JDK `jarsigner` (AABs use JAR signing, not `apksigner`).
It runs no Gradle build and restores no build caches. Java reads every payload entry with
cryptographic JAR verification, rejects unsigned/modified/multiple-signer payloads and checks
the SHA-256 of the signing certificate. The signed bundle is also validated by bundletool.
Signing fails closed on missing credentials, a missing pin, a mismatched key or existing output.
Temporary private key files are owner-only and deleted; only AAB, mapping and public evidence
are uploaded as `imu-nav-play-signed-<sha>`. The unsigned and signed SHA-256 values differ.
`verification.json` describes the unsigned input; signed `SHA256SUMS` and `signed-bundle.json`
describe the final upload file. Artifacts expire after 14 days; retain release evidence securely.

## Required GitHub and existing key setup

Repository YAML cannot enforce environment protection or make repository-level secrets safe
from a same-repository contributor who changes a workflow. Configure these settings **before
any signed run**; do not leave duplicate private keys at repository or organization scope:

1. Create environment **google-play-release**, restrict deployment branches to **main only**,
   add required reviewers, prevent self-review, and disable administrator bypass where supported.
   Protect main with PR review and required checks; restrict workflow/environment administration.
2. Put the **existing** key material in this environment as `RELEASE_KEYSTORE_BASE64`,
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
   Add environment variable `PLAY_UPLOAD_CERT_SHA256` using the **upload key certificate**
   fingerprint from Play Console → Setup/App integrity → App signing (UI labels can change).
3. Verify that the existing key actually matches that upload certificate. Play app signing
   certificates and upload certificates can differ. If it does not match, locate the already
   registered upload key or resolve the mismatch with the owner; **do not generate a replacement**
   or silently use the benchmark/debug key. For a first listing, establish the existing upload
   identity in Play Console with the owner before preparing a release.
4. Move existing F-Droid `RELEASE_*` and `BENCHMARK_*` private secrets into protected environment
   **android-distribution**. Allow reviewed main manual runs and reviewed `v*` tags only, require
   approval and check each tag belongs to reviewed main. The updated F-Droid PR/merge-queue job
   uses **ci-validation**, which must contain **no private secrets**. Its benchmark artifact is
   unsigned. Configure certificate variables there too (`RELEASE_CERT_SHA256`, `BENCHMARK_CERT_SHA256`).
   Remove the old private repository/organization secret copies after both environments are set.

No settings/secrets are created or inspected by this PR. An absent environment can be implicitly
created without protections by GitHub, so its name in YAML alone is insufficient. If your account
plan cannot enforce approvals, restrict branches and release permissions and use a controlled
local signing step rather than leaving keys accessible to unreviewed sources.

## What validation proves

The pinned bundletool **1.18.3**, downloaded with a checked SHA-256, validates the AAB ZIP/protobuf
structure. The merged base manifest must match `org.imunav.app`, minimum SDK 26, target SDK 36,
non-debuggable release and the Gradle/F-Droid version declarations. The verifier records all
merged permissions, rejects a reused Play code when the operator supplies the ceiling, checks
all ARM64 ELF LOAD/RELRO layouts (including MapLibre and Graphics Path), requires both release
JNI ABIs, and requires `PAGE_ALIGNMENT_16K` in BundleConfig.

bundletool generates a disposable universal test APK with its SDK debug test identity; that
identity is never used to sign the upload AAB or replace the existing developer key. The APK
must pass the existing ELF verifier and `zipalign -c -P 16 4`. AAB ZIP offsets themselves are
not APK installation offsets. See [bundletool](https://developer.android.com/tools/bundletool)
and [16 KB guidance](https://developer.android.com/guide/practices/page-sizes).

Static checks cannot prove runtime compatibility or Play approval. Existing GPS instrumentation
covers an x86_64 16 KB **F-Droid debug** build, not the ARM64 R8 Play release. Before distributing,
install Play-generated splits on ARM64 Android 15+ with `adb shell getconf PAGE_SIZE` reporting
16384; test cold launch, MapLibre drawing, Graphics Path, JNI, offline pack loading, navigation,
background/foreground transition and process recovery. Also test Android 8/9 and Android 16,
permission denial/approximate location and stopping navigation/sharing. Use synthetic trips.
Archive evidence and check Play Console's generated APK/device catalog warnings.

## Publication requirements audit (14 October 2026)

These are remaining actions/evidence, not a claim that Google approved the application.
The source manifest and relevant features were inspected; the final merged permission inventory
is produced by CI. Recheck Console and linked official policy when submitting.

| Area | Repository state / requirement | Action before publication |
| --- | --- | --- |
| Target API | Target 36 matches the current phone/Android Auto new-app and update requirement (effective 31 August 2026; extensions to 1 November). | Confirm CI merged manifest and Console acceptance. [Policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en). |
| 16 KB | Rust/C++ and prebuilt native dependencies require ELF and APK packaging checks. Official guidance currently states enforcement for updates from 1 February 2027. | Pass this workflow and ARM64 release runtime testing now; confirm account-specific Console warnings. [Guidance](https://developer.android.com/guide/practices/page-sizes). |
| Location FGS | `NavService` declares `location`, `FOREGROUND_SERVICE_LOCATION`; fine/coarse location requested. Android 14+ service types need Console declarations. | Declare navigation and explicit live sharing; describe delay/interruption impact; provide a video showing user initiation, ongoing notification and Stop. Verify foreground start restrictions, denial handling and service stops when its intended action ends. [FGS requirements](https://support.google.com/googleplay/android-developer/answer/13392821). |
| Background location | No `ACCESS_BACKGROUND_LOCATION` in source manifest. A location FGS can still fall under background-location policy when equivalent to background access. | Document actual screen-off navigation, automatic sync and live sharing behavior. Review merged manifest. If background policy applies, provide core-function justification, declaration, disclosure before consent, video and privacy policy. Do not add background permission merely for publication. [Location policy](https://support.google.com/googleplay/android-developer/answer/9799150). |
| Data safety | Offline location processing coexists with optional cell sync, accounts, live sharing, diagnostics and online routing/search. `PlaceSearch`, `OsrmRouter`, `PositionSharing`, `CellSync` and `ProfileUpload` are relevant; provider behavior/retention needs operator verification. | Complete a per-feature data-flow/SDK audit: precise/approximate location, IDs/account details, diagnostics and relevant activity data, recipients, purpose, retention, optionality and encryption. Do not answer “no collection” solely because navigation works offline. Declare ephemeral off-device processing according to the form; assess user-initiated sharing exceptions individually. Account deletion needs in-app and externally accessible request paths if users can create accounts. [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469). |
| Privacy policy | Server notices and consent UI exist, but a stable public app-wide privacy policy URL and Play listing are unconfirmed. | Publish an accessible policy matching developer identity and all local/server/SDK features; expose it in app and listing, disclose contact/retention/deletion/security. Server-specific notices alone do not establish app-wide compliance. [App review requirements](https://support.google.com/googleplay/android-developer/answer/9859455). |
| Other permissions | Activity recognition, Bluetooth connect, notifications, mock-location export, battery-optimization exemption and extra location commands remain in source manifest. | Record why each is needed and test denial. Direct battery exemption requests need core-function justification or use general battery settings instead. GPS export must remain explicit Developer options opt-in; verify reviewer instructions without trusting mock GPS for navigation. Do not request SMS/call/storage or accessibility permissions as a workaround. [Doze policy](https://developer.android.com/training/monitoring-device-state/doze-standby). |
| Network privacy | Manifest allows cleartext traffic for configured services; location may leave device through user-selected services. | Verify public endpoints use HTTPS and consent matches recipients. Do not claim all data is encrypted in transit while supported HTTP/LAN paths send sensitive data; explain or restrict those paths for the distribution. |
| Android Auto | Navigation CarAppService and car metadata/templates are present; this is projected Android Auto, not an AAOS vehicle APK. | Opt in to Android Auto in Console; test with DHU/real host, host certificates, navigation templates, voice/turn/notification behavior and driver-distraction rules. Open/production car review is blocking; internal testing has no form-factor review. Provide reviewer account/access and synthetic route/offline pack instructions; mock GPS is classified BAD by design, so reviewers need a usable manual-start path. [Distribution](https://developer.android.com/training/cars/distribute), [quality criteria](https://developer.android.com/docs/quality-guidelines/car-app-quality). |
| Account/store setup | Play listing/account/access, historical codes and signing enrollment unconfirmed. | Confirm account verification, listing/screenshots, contact, content rating, audience, ads declaration and App access details. For personal accounts created after 13 November 2023: at least 12 closed-test testers continuously opted in for 14 days, then apply for production access. [Testing rules](https://support.google.com/googleplay/android-developer/answer/14151465). |

## Manual upload after the owner's decision

1. Confirm account, package, upload certificate and largest historical version code. Finish the
   policy declarations/evidence above and review the release commit and Actions results.
2. Run `Google Play bundle` on main in release mode. A reviewer approves the protected signing
   environment. Download `imu-nav-play-signed-<sha>` only from that successful run.
3. In the extracted artifact run `sha256sum --check SHA256SUMS` (or PowerShell `Get-FileHash
   app-play-release.aab -Algorithm SHA256`) and compare `signed-bundle.json`. Keep `mapping.txt`.
4. Open the existing app in Play Console → Test and release → Testing → Internal testing →
   Create new release. Enroll/configure Play App Signing if required, retaining the agreed existing
   upload identity. Upload **app-play-release.aab**, not the unsigned AAB, APK or artifact ZIP.
5. Inspect detected package, version code/name, target API, upload certificate, size, generated
   APKs/16 KB warnings, supported devices and mapping processing. Supply mapping if Console has
   not extracted it. Enter release notes and save the draft. Provide reviewer access as needed.
6. Uploading/saving a draft is distinct from submitting or rolling out. Wait for the owner's
   explicit decision before starting even an internal-test rollout or sending for review.
   Test Play-signed installs/updates and pre-launch reports before closed/open/production rollout.

For local verification: build `./gradlew -PunsignedPlay :app:bundlePlayRelease`, download pinned
bundletool using `bash scripts/download-bundletool.sh /path/bundletool.jar`, then use
`python3 tools/play_bundle.py verify --bundle app/build/outputs/bundle/playRelease/app-play-release.aab
--bundletool /path/bundletool.jar --zipalign "$ANDROID_HOME/build-tools/36.0.0/zipalign"
--output build/play-validation --last-play-version-code N` as one command.
Existing key environment variables can sign a separate copy with `tools/play_bundle.py sign`;
add `IMUNAV_PLAY_UPLOAD_CERT_SHA256` first. Never print/source secrets in CI logs or attach private files.
