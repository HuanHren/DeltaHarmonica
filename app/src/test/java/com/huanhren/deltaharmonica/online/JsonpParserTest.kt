package com.huanhren.deltaharmonica.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class JsonpParserTest {
    @Test
    fun parsesSingleExpectedObjectWithWhitespaceAndEscapedContent() {
        val result = JsonpParser.parse(" \n__dfh ( \"catalog/a\" , {\"r\": [\"中文);\", \"a\\\\b\"]} ) ;\r\n", "catalog/a")
        assertEquals("""["中文);","a\\b"]""", result["r"].toString())
        assertTrue(JsonpParser.parse("""__dfh("catalog/a", {})""", "catalog/a").isEmpty())
    }

    @Test
    fun rejectsWrongKeyMalformedJsonAndExtraCode() {
        val invalid = listOf(
            """__dfh("catalog/b",{});""",
            """execute("catalog/a",{});""",
            """{"r":[]}""",
            """__dfh('catalog/a',{});""",
            """__dfh("catalog/a",[]);""",
            """__dfh("catalog/a",{"r":undefined});""",
            """__dfh("catalog/a",{"r":0x10});""",
            """__dfh("catalog/a",{"r":01});""",
            """__dfh("catalog/a",{"r":[1,]});""",
            """__dfh("catalog/a",{"r":[1 2]});""",
            """__dfh("catalog/a",{"r":"\q"});""",
            "__dfh(\"catalog/a\",{\"r\":\"\u0001\"});",
            """__dfh("catalog/a",{"r":[]""",
            """__dfh("catalog/a",{}); anything()""",
            """__dfh("catalog/a",{}); __dfh("catalog/a",{});""",
        )
        invalid.forEach { value ->
            assertThrows(value, OnlineLibraryException::class.java) { JsonpParser.parse(value, "catalog/a") }
        }
    }
}
