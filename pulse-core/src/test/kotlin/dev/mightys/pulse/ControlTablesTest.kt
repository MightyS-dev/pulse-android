package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** The four shared tables, copied byte for byte, drive the version comparison, the decision, the id and the texts. */
class ControlTablesTest {
    /** The shared tables are part of the private tree only; where they are not bundled these tests skip, visibly. */
    private fun resource(name: String): ByteArray {
        val stream = ControlTablesTest::class.java.getResourceAsStream("/control-v1/$name")
        Assume.assumeTrue("shared App Control tables are not bundled in this tree: $name", stream != null)
        return stream!!.readBytes()
    }
    private fun json(name: String): Any? = JsonReader.parse(resource(name).toString(Charsets.UTF_8))
    private fun sha(name: String) = Hash.bytes(resource(name)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun fixturesAreByteIdenticalToTheServerPackage() {
        assertEquals("027cc69450b95177d4c50ee2a9026df3dfe05e2b6f46d4e449dca872b4ab4009", sha("version-cases.json"))
        assertEquals("fdc4ef65c3bf22e1a5775e756d5660cf3608085eb2a5977c683b25affd15c622", sha("decision-cases.json"))
        assertEquals("f236a1c986276ea69aeca69ccafff368ba8a07a749269986814e57b256d01b2f", sha("control-id-cases.json"))
        assertEquals("39aac628bda36bbb86fb4a091dad3252be82dd2a6353af9ea1db574a5264dbf4", sha("texts.json"))
    }

    @Test fun everyVersionCasePasses() {
        val cases = json("version-cases.json") as List<*>
        assertTrue("at least 200 cases, found ${cases.size}", cases.size >= 200)
        for (case in cases) {
            val row = case as Map<*, *>
            val expected = when (val r = row["result"]) { "incomparable" -> null; is JsonNumber -> r.text.toInt(); else -> fail("bad row $row") as Nothing }
            assertEquals("${row["a"]} vs ${row["b"]}", expected, ControlVersion.compare(row["a"] as String, row["b"] as String))
        }
    }

    @Test fun everyDecisionCasePasses() {
        val cases = json("decision-cases.json") as List<*>
        assertTrue("at least 60 cases, found ${cases.size}", cases.size >= 60)
        for (case in cases) {
            val row = case as Map<*, *>; val input = row["input"] as Map<*, *>; val expected = row["expected"] as Map<*, *>
            val outcome = ControlDecision.decide(ControlTableRows.input(input))
            val label = row["name"] as String
            assertEquals(label, expected["state"], outcome.state)
            assertEquals(label, expected["allowContinue"], outcome.allowContinue)
            assertEquals(label, expected["banners"], outcome.banners)
            assertEquals(label, expected["card"], outcome.card)
        }
    }

    @Test fun controlIdsMatchForEveryKey() {
        val cases = json("control-id-cases.json") as List<*>
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val row = case as Map<*, *>
            assertEquals(row["note"] as String, row["controlId"], ControlIds.forKey(row["key"] as String))
        }
    }

    @Test fun builtInTextsEqualTheSharedTable() {
        val root = json("texts.json") as Map<*, *>
        val languages = (root["languages"] as List<*>).map { it as String }
        assertEquals(languages, ControlTexts.languages)
        val table = root["texts"] as Map<*, *>
        for (language in languages) {
            val expected = (table[language] as Map<*, *>).entries.associate { it.key as String to it.value as String }
            assertEquals(language, expected, ControlTexts.table(language))
        }
    }
}
