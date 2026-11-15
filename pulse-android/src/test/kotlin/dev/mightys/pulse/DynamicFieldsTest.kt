package dev.mightys.pulse

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

class DynamicFieldsTest {
    private val phone = RawState(
        orientation = Configuration.ORIENTATION_PORTRAIT, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
        layoutDirection = 0, fontScale = 1.15f, fontWeightAdjustment = 300, smallestWidthDp = 393, isWatch = false, isAutomotive = false,
        widthPx = 1179, heightPx = 2556, densityDpi = 460, density = 3f, displayCount = 1, animatorScale = 1f, transitionScale = 1f,
        inversion = 0, highContrast = 0, daltonizer = 0, appLanguage = "en", preferredLanguage = "de", region = "DE",
    )

    @Test fun phoneValuesHaveTheExactFormats() {
        val f = DynamicFields.fields(phone)
        assertEquals("phone", f["deviceType"]); assertEquals("portrait", f["orientation"]); assertEquals("dark", f["colorScheme"])
        assertEquals("ltr", f["layoutDirection"]); assertEquals("1.15", f["fontScale"]); assertEquals("300", f["fontWeightAdjustment"])
        assertEquals("true", f["boldText"]); assertEquals("393", f["screenWidth"]); assertEquals("852", f["screenHeight"])
        assertEquals("3", f["screenScale"]); assertEquals("1179", f["screenWidthPx"]); assertEquals("2556", f["screenHeightPx"])
        assertEquals("460", f["screenDpi"]); assertEquals("1", f["screenCount"]); assertEquals("false", f["reduceMotion"])
        assertEquals("false", f["reduceTransparency"]); assertEquals("false", f["invertColors"]); assertEquals("false", f["increaseContrast"])
        assertEquals("false", f["differentiateWithoutColor"]); assertEquals("en", f["language"]); assertEquals("de", f["preferredLanguage"])
        assertEquals("DE", f["region"])
    }

    @Test fun deviceTypeEnumValues() {
        assertEquals("tablet", DynamicFields.fields(phone.copy(smallestWidthDp = 600))["deviceType"])
        assertEquals("tv", DynamicFields.fields(phone.copy(uiMode = Configuration.UI_MODE_TYPE_TELEVISION))["deviceType"])
        assertEquals("watch", DynamicFields.fields(phone.copy(isWatch = true))["deviceType"])
        assertEquals("car", DynamicFields.fields(phone.copy(isAutomotive = true))["deviceType"])
    }

    @Test fun valuesChangeWithTheConfiguration() {
        val f = DynamicFields.fields(phone.copy(orientation = Configuration.ORIENTATION_LANDSCAPE, uiMode = Configuration.UI_MODE_NIGHT_NO,
            layoutDirection = 1, fontScale = 1.3f, animatorScale = 0f, transitionScale = 0f, inversion = 1, highContrast = 1, daltonizer = 1))
        assertEquals("landscape", f["orientation"]); assertEquals("light", f["colorScheme"]); assertEquals("rtl", f["layoutDirection"])
        assertEquals("1.3", f["fontScale"]); assertEquals("true", f["reduceMotion"]); assertEquals("true", f["reduceTransparency"])
        assertEquals("true", f["invertColors"]); assertEquals("true", f["increaseContrast"]); assertEquals("true", f["differentiateWithoutColor"])
    }

    @Test fun missingReadingsAreOmitted() {
        val f = DynamicFields.fields(phone.copy(orientation = 0, fontWeightAdjustment = null, displayCount = null, animatorScale = null,
            transitionScale = null, inversion = null, highContrast = null, daltonizer = null, appLanguage = null, preferredLanguage = "", region = null,
            widthPx = 0, densityDpi = 0))
        for (key in listOf("orientation", "fontWeightAdjustment", "boldText", "screenCount", "reduceMotion", "reduceTransparency", "invertColors",
            "increaseContrast", "differentiateWithoutColor", "language", "preferredLanguage", "region", "screenWidth", "screenDpi"))
            assertNull(key, f[key])
    }

    @Test fun installSourceMapping() {
        assertEquals("debug", AndroidMetadata.installSource(true, "com.android.vending"))
        assertEquals("playstore", AndroidMetadata.installSource(false, "com.android.vending"))
        assertEquals("galaxystore", AndroidMetadata.installSource(false, "com.sec.android.app.samsungapps"))
        assertEquals("amazon", AndroidMetadata.installSource(false, "com.amazon.venezia"))
        assertEquals("sideload", AndroidMetadata.installSource(false, null))
        assertEquals("other", AndroidMetadata.installSource(false, "com.example.store"))
    }

    @Test fun emulatorAndArchitectureAndConnectionHelpers() {
        assertTrue(AndroidMetadata.isEmulator("generic/sdk_gphone/x", "ranchu", "sdk_gphone64"))
        assertTrue(AndroidMetadata.isEmulator("google/x", "goldfish", "x"))
        assertFalse(AndroidMetadata.isEmulator("samsung/dm1q/x", "qcom", "dm1qxxx"))
        assertEquals("arm64", AndroidMetadata.architecture("arm64-v8a")); assertEquals("armv7", AndroidMetadata.architecture("armeabi-v7a"))
        assertEquals("x86_64", AndroidMetadata.architecture("x86_64")); assertNull(AndroidMetadata.architecture(null))
        assertEquals("wifi", AndroidMetadata.connection(true, true, false)); assertEquals("cellular", AndroidMetadata.connection(false, true, false))
        assertEquals("ethernet", AndroidMetadata.connection(false, false, true)); assertEquals("other", AndroidMetadata.connection(false, false, false))
        assertEquals("1", AndroidMetadata.decimal(1f)); assertEquals("1.152", AndroidMetadata.decimal(1.1520001f))
    }
}
