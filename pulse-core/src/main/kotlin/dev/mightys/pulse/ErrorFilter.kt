package dev.mightys.pulse

/** Removes personal data from error text. Applied to every message and frame before it is stored or sent. */
internal object ErrorFilter {
    private const val MARK = "<filtered>"
    private const val HEX = "[0-9a-fA-F]{1,4}"
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val url = Regex("""([A-Za-z][A-Za-z0-9+.-]*://[^\s?#]*)[?#]\S*""")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val ipv4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val ipv6 = Regex("""(?<![\w:])(?:(?:$HEX:){7}$HEX|(?=[0-9a-fA-F:]*[0-9a-fA-F])(?:$HEX(?::$HEX){0,6})?::(?:$HEX(?::$HEX){0,6})?)(?![\w:])""")
    private val digits = Regex("\\b0[xX][0-9a-fA-F]+|\\d{6,}")
    /** The line and optional column right after a file name in a frame. */
    private val position = Regex("""(?<=\.[A-Za-z0-9]{1,5}:)\d+(?::\d+)?""")
    private val fileUrl = Regex("""file://[^\s/]*/(?:[^\s/]+/)*([^\s/]+)""")
    private val windowsPath = Regex("""(?<!\w)[A-Za-z]:\\(?:[^\s\\]+\\)*([^\s\\]+)""")
    private val unixPath = Regex("""(?<![\w/.:<>~-])/(?:[^\s/]+/)*([^\s/]+)""")

    /** [frame] keeps a line and column after a file name; a message has no such exemption. */
    fun apply(text: String, frame: Boolean = false): String {
        var result = email.replace(text, MARK)
        result = url.replace(result) { it.groupValues[1] + MARK }
        result = uuid.replace(result, MARK)
        result = ipv4.replace(result, MARK)
        result = ipv6.replace(result, MARK)
        val kept = if (frame) position.findAll(result).map { it.range }.toList() else emptyList()
        result = digits.replace(result) { m ->
            if (m.value.startsWith("0x") || m.value.startsWith("0X") || kept.any { it.first <= m.range.last && m.range.first <= it.last }) m.value else MARK
        }
        result = fileUrl.replace(result) { it.groupValues[1] }
        result = windowsPath.replace(result) { it.groupValues[1] }
        return unixPath.replace(result) { it.groupValues[1] }
    }
}
