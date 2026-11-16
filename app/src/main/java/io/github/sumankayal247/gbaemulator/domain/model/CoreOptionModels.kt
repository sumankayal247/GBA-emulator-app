package io.github.sumankayal247.gbaemulator.domain.model

data class CoreOptionChoice(
    val value: String,
    val label: String,
)

data class CoreOptionItem(
    val key: String,
    val description: String,
    val info: String,
    val categoryKey: String,
    val defaultValue: String,
    val currentValue: String,
    val visible: Boolean,
    val choices: List<CoreOptionChoice>,
) {
    fun currentLabel(): String =
        choices.firstOrNull { it.value == currentValue }?.label ?: currentValue
}

/**
 * Pure-Kotlin RFC-8259 JSON parser for CoreOptionsStore::toJson() output.
 * Zero Android SDK dependencies so it executes identically on JVM unit tests and Android.
 */
object CoreOptionsJsonParser {
    fun parse(json: String?): List<CoreOptionItem> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val reader = JsonReader(json)
            val root = reader.readValue() as? List<*> ?: return emptyList()
            root.mapNotNull { entry ->
                val obj = entry as? Map<*, *> ?: return@mapNotNull null
                val key = obj["key"] as? String ?: return@mapNotNull null
                val desc = obj["desc"] as? String ?: key
                val info = obj["info"] as? String ?: ""
                val categoryKey = obj["categoryKey"] as? String ?: ""
                val defaultValue = obj["defaultValue"] as? String ?: ""
                val currentValue = obj["currentValue"] as? String ?: defaultValue
                val visible = obj["visible"] as? Boolean ?: true
                val rawChoices = obj["choices"] as? List<*> ?: emptyList<Any>()
                val choices = rawChoices.mapNotNull { c ->
                    val cMap = c as? Map<*, *> ?: return@mapNotNull null
                    val v = cMap["value"] as? String ?: return@mapNotNull null
                    val l = cMap["label"] as? String ?: v
                    CoreOptionChoice(value = v, label = l)
                }
                CoreOptionItem(
                    key = key,
                    description = desc,
                    info = info,
                    categoryKey = categoryKey,
                    defaultValue = defaultValue,
                    currentValue = currentValue,
                    visible = visible,
                    choices = choices,
                )
            }
        }.getOrDefault(emptyList())
    }

    private class JsonReader(private val s: String) {
        private var pos = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw IllegalArgumentException("Expected ':'")
                pos++
                val value = readValue()
                map[key] = value
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == '}') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list.add(readValue())
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == ']') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']'")
                }
            }
            return list
        }

        private fun readString(): String {
            if (s[pos] != '"') throw IllegalArgumentException("Expected '\"'")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\' && pos < s.length) {
                    when (val esc = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 <= s.length) {
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun readLiteral(expected: String, value: Any?): Any? {
            if (!s.startsWith(expected, pos)) throw IllegalArgumentException("Invalid literal")
            pos += expected.length
            return value
        }

        private fun readNumber(): Number {
            val start = pos
            while (pos < s.length && s[pos] in "-0123456789.eE+") pos++
            val raw = s.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
