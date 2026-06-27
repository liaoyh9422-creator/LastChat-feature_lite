package me.rerere.document

import java.io.File

object PdfParser {
    fun parserPdf(file: File): String = buildString {
        appendLine("[PDF support removed in this build]")
        append("File: ${file.name}")
    }
}
