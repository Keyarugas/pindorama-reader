package mihon.gradle

import org.gradle.api.Project

interface BuildConfig {
    val enableUpdater: Boolean
    val includeDependencyInfo: Boolean
}

val Project.Config: BuildConfig get() = object : BuildConfig {
    // Pindorama builds do not use the official Mihon updater.
    override val enableUpdater: Boolean = false
    override val includeDependencyInfo: Boolean = project.hasProperty("include-dependency-info")
}
