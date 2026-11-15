package dev.mightys.pulse

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

internal object AndroidMetadata {
    /** One format for every Pulse SDK: at most three parts, and the patch part is dropped when it is zero. */
    fun numericRelease(release: String?): String {
        val number = release?.let { Regex("^[0-9]+(?:\\.[0-9]+)*").find(it)?.value } ?: return ""
        val parts = number.split('.').take(3)
        return (if (parts.size == 3 && parts[2].trimStart('0').isEmpty()) parts.take(2) else parts).joinToString(".")
    }

    fun appVersion(versionName: String?, buildNumber: String?): String =
        versionName?.takeIf { it.isNotBlank() } ?: buildNumber?.takeIf { it.isNotBlank() } ?: "0"

    fun deviceModel(model: String?): String = model?.takeIf { it.isNotBlank() } ?: "Android"

    fun localeTag(locale: Locale): String = locale.toLanguageTag().ifBlank { "und" }

    fun certificateDigest(bytes: ByteArray?): String = if (bytes == null) "" else {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }

    @Suppress("DEPRECATION")
    fun system(context: Context, includeIdentity: Boolean): Map<String, String> {
        val packageInfo = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        val build = if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode else packageInfo?.versionCode?.toLong()
        val result = linkedMapOf(
            "platform" to "mobile", "osName" to "Android", "osVersion" to numericRelease(Build.VERSION.RELEASE),
            "appVersion" to appVersion(packageInfo?.versionName, build?.toString()), "appBuildNumber" to (build?.toString() ?: ""),
            "deviceModel" to deviceModel(Build.MODEL), "locale" to localeTag(Locale.getDefault()),
        )
        if (includeIdentity) {
            result["bundleId"] = context.packageName
            result["signingCertSha256"] = signingCertificate(context)
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun signingCertificate(context: Context): String = try {
        val manager = context.packageManager
        val certificate = if (Build.VERSION.SDK_INT >= 28) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?.apkContentsSigners?.firstOrNull()
        } else manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        certificateDigest(certificate?.toByteArray())
    } catch (_: Exception) { "" }
}
