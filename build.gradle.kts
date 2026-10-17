import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
}

val releaseAar = layout.projectDirectory.file("youtube-music-data-release.aar")
val melooraAar = layout.projectDirectory.file("meloora-music-data.aar")

tasks.register<Copy>("buildReleaseArtifact") {
    group = "distribution"
    description = "Builds the release AAR and copies it to the project root."
    doNotTrackState("The destination is the project root and contains Gradle's internal lock files.")
    dependsOn(":youtube-music-data:assembleRelease")
    from(project(":youtube-music-data").layout.buildDirectory.dir("outputs/aar")) {
        include("youtube-music-data-release.aar")
    }
    into(layout.projectDirectory)
    rename { releaseAar.asFile.name }
}

tasks.register<Copy>("buildMelooraReleaseArtifact") {
    group = "distribution"
    description = "Copies the release AAR using Meloora's deployment filename."
    doNotTrackState("The destination is the project root and contains Gradle's internal lock files.")
    dependsOn("buildReleaseArtifact")
    from(releaseAar)
    into(layout.projectDirectory)
    rename { melooraAar.asFile.name }
}

tasks.register("buildReleaseArtifacts") {
    group = "distribution"
    description = "Builds both the canonical and Meloora-named release AARs."
    dependsOn("buildMelooraReleaseArtifact")
}
