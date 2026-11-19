package com.huanhren.deltaharmonica.online

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object JsonpParser {
    fun parse(text: String, expectedKey: String): JsonObject {
        try {
            val scanner = Scanner(text)
            scanner.token("__dfh")
            scanner.token("(")
            val key = scanner.stringValue()
            require(key == expectedKey)
            scanner.token(",")
            val json = scanner.objectText()
            scanner.token(")")
            scanner.consume(";")
            require(scanner.atEnd())
            return Json.parseToJsonElement(json) as JsonObject
        } catch (error: IllegalArgumentException) {
            throw OnlineLibraryException(OnlineLibraryException.Kind.PROTOCOL, "曲库返回的数据格式有误", error)
        }
    }

    // Validate JSON syntax before kotlinx decodes its tree. In particular, a JsonElement
    // can otherwise accept unquoted literals that aren't JSON. Limit nesting before allocating.
    private class Scanner(private val text: String) {
        private var offset = 0

        private fun skipWhitespace() {
            while (offset < text.length && text[offset] in " \t\r\n") offset++
        }

        fun atEnd(): Boolean {
            skipWhitespace()
            return offset == text.length
        }

        fun consume(token: String): Boolean {
            skipWhitespace()
            if (!text.startsWith(token, offset)) return false
            offset += token.length
            return true
        }

        fun token(expected: String) {
            require(consume(expected))
        }

        fun stringValue(): String {
            skipWhitespace()
            val start = offset
            string()
            return Json.decodeFromString(text.substring(start, offset))
        }

        private fun string() {
            step('"')
            while (offset < text.length) {
                when (val char = text[offset++]) {
                    '"' -> return
                    '\\' -> escape()
                    else -> require(char >= ' ')
                }
            }
            throw SerializationException("Unterminated JSON string")
        }

        private fun escape() {
            require(offset < text.length)
            when (text[offset++]) {
                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                'u' -> repeat(4) {
                    require(offset < text.length && text[offset++] in "0123456789abcdefABCDEF")
                }
                else -> throw SerializationException("Invalid JSON escape")
            }
        }

        fun objectText(): String {
            skipWhitespace()
            val start = offset
            step('{')
            fields(depth = 1)
            return text.substring(start, offset)
        }

        private fun fields(depth: Int) {
            if (consume("}")) return
            val keys = mutableSetOf<String>()
            do {
                require(keys.add(stringValue()))
                token(":")
                value(depth)
            } while (consume(","))
            token("}")
        }

        private fun array(depth: Int) {
            if (consume("]")) return
            do {
                value(depth)
            } while (consume(","))
            token("]")
        }

        private fun value(depth: Int) {
            require(depth < 32)
            skipWhitespace()
            when (text.getOrNull(offset)) {
                '{' -> { offset++; fields(depth + 1) }
                '[' -> { offset++; array(depth + 1) }
                '"' -> string()
                't' -> token("true")
                'f' -> token("false")
                'n' -> token("null")
                else -> number()
            }
        }

        private fun number() {
            if (text.getOrNull(offset) == '-') offset++
            if (text.getOrNull(offset) == '0') offset++ else digits()
            if (text.getOrNull(offset) == '.') {
                offset++
                digits()
            }
            if (text.getOrNull(offset) == 'e' || text.getOrNull(offset) == 'E') {
                offset++
                if (text.getOrNull(offset) == '+' || text.getOrNull(offset) == '-') offset++
                digits()
            }
        }

        private fun digits() {
            val start = offset
            while (offset < text.length && text[offset] in '0'..'9') offset++
            require(start != offset)
        }

        private fun step(expected: Char) {
            require(text.getOrNull(offset) == expected)
            offset++
        }
    }
}
