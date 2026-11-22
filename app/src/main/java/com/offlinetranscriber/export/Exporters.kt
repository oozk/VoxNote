package com.offlinetranscriber.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportSegment(val startMs: Long, val endMs: Long, val text: String)

data class ExportDocument(
    val title: String,
    val segments: List<ExportSegment>,
    val metadata: List<Pair<String, String>> = emptyList(),
)

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    TXT("Plain text (.txt)", "txt", "text/plain"),
    TIMESTAMPED("Text with timestamps (.txt)", "txt", "text/plain"),
    DOCX("Word document (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    SRT("SRT subtitles (.srt)", "srt", "application/x-subrip"),
    VTT("WebVTT subtitles (.vtt)", "vtt", "text/vtt"),
}

object Exporters {
    fun export(doc: ExportDocument, format: ExportFormat, out: OutputStream) {
        when (format) {
            ExportFormat.TXT -> out.write(plainText(doc).toByteArray())
            ExportFormat.TIMESTAMPED -> out.write(timestamped(doc).toByteArray())
            ExportFormat.SRT -> out.write(srt(doc).toByteArray())
            ExportFormat.VTT -> out.write(vtt(doc).toByteArray())
            ExportFormat.DOCX -> docx(doc, out)
        }
    }

    fun exportToBytes(doc: ExportDocument, format: ExportFormat): ByteArray =
        ByteArrayOutputStream().also { export(doc, format, it) }.toByteArray()

    /** Segments joined into paragraphs (a new paragraph after a pause of > 2 s). */
    fun plainText(doc: ExportDocument): String = buildString {
        var prevEnd = -1L
        for (s in doc.segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            if (isNotEmpty()) append(if (prevEnd >= 0 && s.startMs - prevEnd > 2000) "\n\n" else " ")
            append(t)
            prevEnd = s.endMs
        }
        append('\n')
    }

    fun timestamped(doc: ExportDocument): String = buildString {
        for (s in doc.segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            append('[').append(clock(s.startMs)).append(" - ").append(clock(s.endMs)).append("] ").append(t).append('\n')
        }
    }

    fun srt(doc: ExportDocument): String = buildString {
        var n = 1
        for (s in doc.segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            append(n++).append('\n')
            append(subtitleTime(s.startMs, ',')).append(" --> ").append(subtitleTime(maxOf(s.endMs, s.startMs + 1), ',')).append('\n')
            append(t).append("\n\n")
        }
    }

    fun vtt(doc: ExportDocument): String = buildString {
        append("WEBVTT\n\n")
        for (s in doc.segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            append(subtitleTime(s.startMs, '.')).append(" --> ").append(subtitleTime(maxOf(s.endMs, s.startMs + 1), '.')).append('\n')
            append(t.replace("-->", "->")).append("\n\n")
        }
    }

    fun subtitleTime(ms: Long, sep: Char): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val f = ms % 1000
        return "%02d:%02d:%02d%c%03d".format(h, m, s, sep, f)
    }

    fun clock(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Minimal, standards-compliant Office Open XML document (opens in Word, LibreOffice, Google Docs). */
    fun docx(doc: ExportDocument, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>""")
            entry("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""")
            entry("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>${xml(doc.title)}</dc:title><dc:creator>Offline Transcriber</dc:creator>
</cp:coreProperties>""")
            entry("word/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Meta"><w:name w:val="Meta"/><w:basedOn w:val="Normal"/><w:rPr><w:color w:val="666666"/><w:sz w:val="18"/></w:rPr></w:style>
</w:styles>""")
            val body = StringBuilder()
            body.append(para(doc.title, "Title"))
            for ((k, v) in doc.metadata) body.append(para("$k: $v", "Meta"))
            for (s in doc.segments) {
                val t = s.text.trim()
                if (t.isEmpty()) continue
                body.append("<w:p><w:r><w:rPr><w:color w:val=\"808080\"/></w:rPr><w:t xml:space=\"preserve\">[")
                    .append(clock(s.startMs)).append("] </w:t></w:r><w:r><w:t xml:space=\"preserve\">")
                    .append(xml(t)).append("</w:t></w:r></w:p>")
            }
            entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""")
        }
    }

    private fun para(text: String, style: String) =
        "<w:p><w:pPr><w:pStyle w:val=\"$style\"/></w:pPr><w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"

    internal fun xml(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit  // illegal in XML 1.0
            else -> append(c)
        }
    }
}
