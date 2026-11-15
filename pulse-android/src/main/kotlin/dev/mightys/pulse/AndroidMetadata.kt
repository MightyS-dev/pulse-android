package dev.mightys.pulse

import android.content.Context
import android.content.pm.ApplicationInfo
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
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val result = linkedMapOf(
            "platform" to "mobile", "osName" to "Android", "osVersion" to numericRelease(Build.VERSION.RELEASE),
            "appVersion" to appVersion(packageInfo?.versionName, build?.toString()), "appBuildNumber" to (build?.toString() ?: ""),
            "deviceModel" to deviceModel(Build.MODEL), "locale" to localeTag(Locale.getDefault()),
            "bundleId" to context.packageName, "osApiLevel" to Build.VERSION.SDK_INT.toString(),
            "isSimulator" to isEmulator(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT).toString(),
            "isDebugBuild" to debuggable.toString(),
            "installSource" to installSource(debuggable, installer(context)),
        )
        Build.MANUFACTURER?.takeIf { it.isNotBlank() }?.let { result["deviceBrand"] = it }
        architecture(Build.SUPPORTED_ABIS?.firstOrNull())?.let { result["architecture"] = it }
        appName(context)?.let { result["appName"] = it }
        if (includeIdentity) result["signingCertSha256"] = signingCertificate(context)
        return result
    }

    private fun appName(context: Context): String? = try {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
    } catch (_: Exception) { null }

    fun installSource(debuggable: Boolean, installer: String?): String = when {
        debuggable -> "debug"
        installer.isNullOrBlank() -> "sideload"
        installer == "com.android.vending" -> "playstore"
        installer == "com.sec.android.app.samsungapps" -> "galaxystore"
        installer == "com.amazon.venezia" -> "amazon"
        else -> "other"
    }

    fun isEmulator(fingerprint: String?, hardware: String?, product: String?): Boolean =
        fingerprint.orEmpty().startsWith("generic") || hardware.orEmpty().lowercase(Locale.ROOT) in setOf("goldfish", "ranchu") ||
            product.orEmpty().startsWith("sdk")

    fun architecture(abi: String?): String? = when (val value = abi?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "arm64-v8a" -> "arm64"
        "armeabi-v7a", "armeabi" -> "armv7"
        else -> value
    }

    /** Decimal with a dot and at most three decimals, without trailing zeros. */
    fun decimal(value: Float): String =
        String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

    fun connection(wifi: Boolean, cellular: Boolean, ethernet: Boolean): String = when {
        wifi -> "wifi"
        cellular -> "cellular"
        ethernet -> "ethernet"
        else -> "other"
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
