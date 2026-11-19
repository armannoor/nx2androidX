package com.nx.android.data

/**
 * A JSON reader that keeps the order of the members.
 *
 * The community library writes its files with the order of the cheat file they came from: the player sees the cheats
 * in the order their author wrote them. The JSON library of the phone keeps that order, the one in the unit test does
 * not (it is a hash map there), so the order is read from the text itself: both see the same file.
 *
 * Only what the library's files need is read: an object, an array, a string, a number, a boolean, null.
 */
internal object OrderedJson {

    /** The members of the object, in the order the text lists them */
    fun parse(text: String): Map<String, Any?> {
        val r = Reader(text)
        val value = r.value()
        r.end()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw JSONError("the text is not an object")
    }

    /** The text is not JSON */
    class JSONError(message: String) : Exception(message)

    private class Reader(private val s: String) {
        var i = 0

        /** Nothing but whitespace may follow the value */
        fun end() {
            skip()
            if (i < s.length) throw JSONError("there is text after the value")
        }

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skip()
            if (i >= s.length) throw JSONError("the text ends too early")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++   // '{'
            skip()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skip()
                if (i >= s.length || s[i] != '"') throw JSONError("a member's name")
                val name = str()
                skip()
                if (i >= s.length || s[i] != ':') throw JSONError("a member's colon")
                i++
                out[name] = value()
                skip()
                if (i >= s.length) throw JSONError("the object ends too early")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw JSONError("a member's comma")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++   // '['
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skip()
                if (i >= s.length) throw JSONError("the array ends too early")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw JSONError("an item's comma")
                }
            }
        }

        private fun str(): String {
            i++   // the opening quote
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw JSONError("a string ends too early")
                val c = s[i]
                if (c == '"') { i++; return out.toString() }
                if (c != '\\') { out.append(c); i++; continue }
                i++
                if (i >= s.length) throw JSONError("an escape ends too early")
                when (val e = s[i]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) throw JSONError("a unicode escape")
                        val hex = s.substring(i + 1, i + 5)
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw JSONError("an escape: $e")
                }
                i++
            }
        }

        private fun lit(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw JSONError("a literal")
            i += word.length
            return value
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            if (start == i) throw JSONError("a number")
            return s.substring(start, i).toDouble()
        }
    }
}
