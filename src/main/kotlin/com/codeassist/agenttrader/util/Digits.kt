package com.codeassist.agenttrader.util

/**
 * Persian and Arabic-Indic digits, and the Persian decimal and thousands marks, mapped to
 * ASCII. The engine parses ASCII numbers only, so "set leverage ۲۰" used to answer
 * "Usage:" as if the command could not be read.
 */
fun toAsciiDigits(text: String): String = buildString(text.length) {
    for (ch in text) {
        append(
            when (ch) {
                in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')   // Persian digits ۰-۹
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')   // Arabic-Indic digits ٠-٩
                '\u066B' -> '.'                                    // Arabic decimal separator ٫
                '\u060C' -> ','                                    // Arabic comma ،
                else -> ch
            },
        )
    }
}
