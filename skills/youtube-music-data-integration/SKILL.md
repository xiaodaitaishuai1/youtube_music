---
name: youtube-music-data-integration
description: Use when integrating a local YouTube Music AAR into an Android project, including requests such as "接入 YouTube Music AAR", "把本地 AAR 集成到 Android 项目", runtime dependency setup, or local AAR compilation failures.
---

# YouTube Music Data AAR Integration

Integrate the local anonymous YouTube Music data AAR into an existing Android host without changing host business code.

## Procedure

1. Identify the host Android root, consumer module, its Android plugin, Gradle DSL, version catalogs, and convention plugins. Preserve all uncommitted changes. Continue with the direct local AAR flow only for a `com.android.application` module. For a `com.android.library` module, stop before copying or editing: a direct local AAR cannot be safely repackaged into a distributable Android library. Ask the user to choose the final application module, local Maven publication with explicit transitive dependencies, or an explicitly authorized AAR-repackaging design.
2. Before changing files, preflight the effective repository configuration, `minSdk`, Kotlin plugin, AGP, and JDK. Resolve flavor, convention-plugin, and version-catalog values before accepting the check. Require `mavenCentral()` or its corporate mirror, `minSdk >= 26`, JDK 17, an AGP configuration compatible with JDK 17, and Kotlin 2.2.21 or a newer toolchain verified to consume Kotlin 2.2 metadata. If any value cannot be determined or is incompatible, stop and report the required user-approved change; do not leave a partial integration or automatically alter `minSdk`, repositories, namespace, or application id.
3. Resolve the artifact path. Use the user-provided path first; otherwise locate `youtube-music-data-release.aar` in the current workspace. Stop with a clear error if more than one candidate exists.
4. Calculate SHA-256 for the source AAR and any existing `<consumer-module>/libs/youtube-music-data-release.aar`. Copy only when the destination is absent; reuse when hashes match; stop and show both hashes when they differ unless the user explicitly authorizes replacement. Do not use `flatDir`; use a direct file dependency.
5. Add the dependency using the host's existing Gradle DSL:
   - Groovy: `implementation files('libs/youtube-music-data-release.aar')`
   - Kotlin DSL: `implementation(files("libs/youtube-music-data-release.aar"))`
6. Add only missing runtime coordinates from [integration-matrix.md](references/integration-matrix.md). Before accepting host versions, inspect the target variant's runtime dependency graph or run `dependencyInsight` for Kotlin stdlib, Coroutines, OkHttp/Okio, and Serialization JSON/core. Retain only versions compatible with and not older than the tested baselines; otherwise add the matrix's exact versions. Java consumers also need the explicit Kotlin stdlib declaration.
7. Default to `implementation`. For a library consumer that exposes `com.music.player.yt.data` types in its public API, ask one targeted question and use `api` only when the user confirms that transitive exposure is needed.
8. Do not change namespace or application id unless explicitly requested; Kotlin imports remain `com.music.player.yt.data`. Calls in the guide must be made from a coroutine or another suspend context.
9. Compile the smallest affected target (`assembleDebug` or the host's requested variant). If it fails, report the first actionable Gradle error and leave unrelated files unchanged. When the host has not yet referenced this AAR from source code, report this as dependency-resolution and build verification only; do not claim business API compilation was verified.
10. Report the copied artifact, SHA-256 handling, modified Gradle files, resolved dependency versions, exact verification command, and any API-use verification limitation.

## Guardrails

- Never modify `C:\Works\SonicTube` as part of this integration.
- Never add Cookie storage, Context requirements, Retrofit, Hilt, Room, MMKV, download, or UI code for this AAR.
- Do not add Media3 or playback code during a standard AAR integration, and never add Media3 as an AAR dependency. `StreamInfo` is intentionally Media3-free; an existing host Media3 implementation may consume its URL and `requestHeaders` under the host's own playback code.
- Never overwrite an existing AAR with a different checksum. Show both paths and hashes, then wait for explicit replacement approval before writing the destination.
- Do not add duplicate dependencies or repositories.
- Treat the session returned by `YoutubeDataResult.Success.updatedSession` as caller-owned state.
- Treat `YoutubeClientVersions` as caller-owned state. A host may construct `YoutubeMusicDataClient(initialClientVersions = savedVersions)` and later update that instance only after `YoutubeVersionHelper().fetchCurrentVersion()` succeeds.
