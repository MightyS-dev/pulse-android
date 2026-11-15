package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SerializationTest {
    private fun signal(type: String? = "purchase", props: Map<String, Any?> = emptyMap(), value: Double? = 4.5): ByteArray? =
        SignalJson.encode(Input(0, "session", Hash.hex("user"), type, value, props),
            mapOf("platform" to "mobile", "osName" to "Android", "osVersion" to "16", "appVersion" to "1.2.3",
                "appBuildNumber" to "42", "deviceModel" to "test", "locale" to "sr-RS",
                "sdkName" to PulseInfo.SDK_NAME, "sdkVersion" to PulseInfo.VERSION), false)

    @Test fun contractShapeMatchesBundledFixture() {
        val copied = javaClass.getResourceAsStream("/v1.jsonc")!!.readBytes()
        val fixture = JsonReader.parse(copied.toString(Charsets.UTF_8).lineSequence().filterNot { it.trim().startsWith("//") }.joinToString("\n")) as List<*>
        val expected = fixture.first() as Map<*, *>
        val actual = JsonReader.parse(signal(props = mapOf("item" to "coins", "amount" to 2))!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(expected.keys, actual.keys)
        assertEquals((expected["system"] as Map<*, *>).keys, (actual["system"] as Map<*, *>).keys)
        assertEquals("1970-01-01T00:00:00.000Z", actual["timestamp"])
    }

    @Test fun escapesUnicodeAndBooleansRoundTrip() {
        val text = "quotes\" \\ \b \u0000 \t \r \n 日本 😀"
        val raw = signal(text, mapOf("text" to text, "yes" to true, "no" to false, "long" to Long.MAX_VALUE,
            "fraction" to 1.25f, "bad" to listOf(1), "nan" to Double.NaN))!!
        val parsed = JsonReader.parse(raw.toString(Charsets.UTF_8)) as Map<*, *>
        val props = parsed["props"] as Map<*, *>
        assertEquals(text, parsed["type"]); assertEquals(text, props["text"])
        assertEquals("true", props["yes"]); assertEquals("false", props["no"])
        assertEquals("9223372036854775807", (props["long"] as JsonNumber).text)
        assertFalse(props.containsKey("bad")); assertFalse(props.containsKey("nan"))
    }

    @Test fun localeNeverChangesDecimalSeparator() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf("sr-RS", "de-DE")) {
                Locale.setDefault(Locale.forLanguageTag(locale))
                val text = signal(props = mapOf("number" to 1234.5), value = 1234.5)!!.toString(Charsets.UTF_8)
                assertTrue(text.contains("1234.5")); assertFalse(text.contains("1234,5"))
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun validationUsesUtf16AndUtf8Limits() {
        for (type in listOf(null, "", " \n", "x".repeat(201), "😀".repeat(101), "\uD800")) assertNull(signal(type))
        assertNotNull(signal("😀".repeat(100)))
        assertNull(signal(value = Double.NaN)); assertNull(signal(value = Double.POSITIVE_INFINITY))
        val inputs = (0..40).associate { "k%02d".format(Locale.ROOT, it) to "x".repeat(1024) } +
            mapOf("z".repeat(129) to "bad", "longValue" to "a".repeat(1025), "lone\uD800" to "bad")
        val parsed = JsonReader.parse(signal(props = inputs)!!.toString(Charsets.UTF_8)) as Map<*, *>
        val props = parsed["props"] as Map<*, *>
        assertTrue(props.size <= 32); assertTrue(JsonWriter.value(props).toByteArray().size <= 8192)
        assertFalse(props.containsKey("longValue")); assertFalse(props.containsKey("lone\uD800"))
        val small = (0..40).associate { "k$it" to true }
        val all = JsonReader.parse(signal(props = small)!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(32, (all["props"] as Map<*, *>).size)
    }

    @Test fun strictReaderRejectsAmbiguityMalformedUnicodeAndDeepNesting() {
        for (text in listOf("{\"pause\":true,\"pa\\u0075se\":false}", "[".repeat(34) + "]".repeat(34),
            "{\"x\":01}", "{\"x\":NaN}", "{\"x\":1.}", "{\"x\":\"\\uD800\"}", "{\"x\":true,}", "true false", "[1,]")) {
            assertNull(text, JsonReader.parse(text))
        }
        assertNotNull(JsonReader.parse("{\"escaped\":\"\\uD83D\\uDE00\",\"unknown\":[true,false,null,-1.25e+3]}"))
    }

    @Test fun adviceFieldsAreIndependentAndBounded() {
        assertEquals(Advice(5.0, true), Advice.read("{\"recommendedSendIntervalSeconds\":1,\"pause\":true}".toByteArray()))
        assertEquals(Advice(3600.0, null), Advice.read("{\"recommendedSendIntervalSeconds\":999999}".toByteArray()))
        assertEquals(Advice(null, true), Advice.read("{\"recommendedSendIntervalSeconds\":\"bad\",\"pause\":true}".toByteArray()))
        for (text in listOf("", "[]", "null", "{\"pause\":\"true\",\"recommendedSendIntervalSeconds\":false}",
            "{\"recommendedSendIntervalSeconds\":-1}", "{\"recommendedSendIntervalSeconds\":1e999}", "x".repeat(65537))) {
            assertEquals(Advice(), Advice.read(text.toByteArray()))
        }
    }

    @Test fun sha256StandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hash.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hash.hex("abc"))
    }

    @Test fun optionsNormalizeWithoutThrowing() {
        val options = PulseOptions(flushIntervalSeconds = Double.NaN, maxBatchSize = Int.MAX_VALUE,
            maxQueueSize = -1, maxQueueAgeDays = Double.POSITIVE_INFINITY).normalized()
        assertEquals(20.0, options.flushIntervalSeconds, 0.0); assertEquals(1000, options.maxBatchSize)
        assertEquals(1, options.maxQueueSize); assertEquals(7.0, options.maxQueueAgeDays, 0.0)
        assertEquals(29.0, PulseOptions(maxQueueAgeDays = 100.0).normalized().maxQueueAgeDays, 0.0)
    }
}
