package mihon.gradle

import org.gradle.api.Project

interface BuildConfig {
    val includeTelemetry: Boolean
    val enableUpdater: Boolean
    val includeDependencyInfo: Boolean
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    // Pindorama builds never include Firebase or use the official Mihon updater.
    override val includeTelemetry: Boolean = false
    override val enableUpdater: Boolean = false
    override val includeDependencyInfo: Boolean = project.hasProperty("include-dependency-info")
}
