# Integration Matrix

The AAR is delivered as a local file, so it has no Maven POM. Hosts must declare these runtime dependencies explicitly:

```text
org.jetbrains.kotlin:kotlin-stdlib:2.2.21
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
com.squareup.okhttp3:okhttp:4.12.0
org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3
```

Kotlin DSL:

```kotlin
dependencies {
    implementation(files("libs/youtube-music-data-release.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

Groovy:

```groovy
dependencies {
    implementation files('libs/youtube-music-data-release.aar')
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.2.21'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3'
}
```

Use the listed versions as the tested minimums. Retain newer host versions only after Gradle resolves a compatible graph and the affected module compiles. The host must resolve dependencies from `mavenCentral()` or an equivalent mirror and use a Kotlin toolchain compatible with Kotlin 2.2 metadata.

The public API is imported from `com.music.player.yt.data`. The Android namespace can be changed while building the library with `-PytDataNamespace=...`, but that does not change Kotlin FQCNs.

`resolveStream` returns a Media3-free `StreamInfo`. Media3 is not a required AAR runtime dependency. Only a host that already implements playback may add its own compatible Media3 `common` and `datasource` modules, then pass `StreamInfo.url` and `StreamInfo.requestHeaders` to its host-side data source. Do not add Media3 to the AAR or change host playback code during a standard integration.
