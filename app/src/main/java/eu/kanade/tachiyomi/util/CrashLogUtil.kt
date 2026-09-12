package eu.kanade.tachiyomi.util

import android.content.Context
import android.os.Build
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.DiagnosticSanitizer
import kotlin.time.Clock

@Inject
class CrashLogUtil(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val networkPreferences: NetworkPreferences,
) {

    suspend fun dumpLogs(exception: Throwable? = null) = withNonCancellableContext {
        try {
            val file = context.createFileInCacheDir("mihon_crash_logs.txt")

            val logPriority = if (networkPreferences.verboseLogging.get()) "V" else "E"
            // Never write raw logcat to disk, including output from dependencies bypassing our logger.
            val process = ProcessBuilder("logcat", "*:$logPriority", "-d", "-v", "year", "-v", "zone")
                .redirectErrorStream(true)
                .start()
            try {
                file.bufferedWriter().use { output ->
                    output.write(DiagnosticSanitizer.sanitize(getDebugInfo()) + "\n\n")
                    getExtensionsInfo()?.let { output.write(DiagnosticSanitizer.sanitize(it) + "\n\n") }
                    exception?.let {
                        output.write(DiagnosticSanitizer.sanitize(it.stackTraceToString()) + "\n\n")
                    }
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { output.write(DiagnosticSanitizer.sanitize(it) + "\n") }
                    }
                }
                check(process.waitFor() == 0) { "Unable to collect diagnostic logs" }
            } finally {
                process.destroy()
            }

            val uri = file.getUriCompat(context)
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        } catch (_: Throwable) {
            withUIContext { context.toast("Failed to get logs") }
        }
    }

    fun getDebugInfo(): String {
        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()
        return """
            App ID: ${BuildConfig.APPLICATION_ID}
            App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA}, ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TIME})
            Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY})
            Device brand: ${Build.BRAND}
            Device manufacturer: ${Build.MANUFACTURER}
            Device name: ${Build.DEVICE} (${Build.PRODUCT})
            Device model: ${Build.MODEL}
            WebView: ${WebViewUtil.getVersion(context)}
            Current time: ${now.toLocalDateTime(tz)}${tz.offsetAt(now)}
        """.trimIndent()
    }

    private suspend fun getExtensionsInfo(): String? {
        val availableExtensions = extensionManager.availableExtensionsFlow.value.associateBy { it.pkgName }

        val extensionInfoList = extensionManager.getInstalledExtensions()
            .sortedBy { it.name }
            .mapNotNull {
                val availableExtension = availableExtensions[it.pkgName]
                val hasUpdate = (availableExtension?.versionCode ?: 0) > it.versionCode

                if (!hasUpdate && !it.isObsolete) return@mapNotNull null

                """
                    - ${it.name}
                      Installed: ${it.versionName} / Available: ${availableExtension?.versionName ?: "?"}
                      Orphaned: ${it.isObsolete}
                """.trimIndent()
            }

        return if (extensionInfoList.isNotEmpty()) {
            (listOf("Problematic extensions:") + extensionInfoList)
                .joinToString("\n")
        } else {
            null
        }
    }
}
