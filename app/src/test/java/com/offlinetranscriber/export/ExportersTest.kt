package com.offlinetranscriber.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ExportersTest {
    private val doc = ExportDocument("Lab meeting <draft>", listOf(
        ExportSegment(0, 2500, " Hello & welcome."),
        ExportSegment(2500, 5000, "We use HPLC."),
        ExportSegment(9000, 3_723_456, "After a pause --> new topic."),
    ), listOf("Model" to "ggml-base"))

    @Test fun srt() {
        val s = Exporters.srt(doc)
        assertTrue(s.startsWith("1\n00:00:00,000 --> 00:00:02,500\nHello & welcome.\n\n2\n"))
        assertTrue(s.contains("3\n00:00:09,000 --> 01:02:03,456\n"))
    }

    @Test fun vtt() {
        val v = Exporters.vtt(doc)
        assertTrue(v.startsWith("WEBVTT\n\n00:00:00.000 --> 00:00:02.500\n"))
        assertTrue(v.contains("After a pause -> new topic."))  // cue text may not contain "-->"
    }

    @Test fun plainTextParagraphsAfterPauses() {
        assertEquals("Hello & welcome. We use HPLC.\n\nAfter a pause --> new topic.\n", Exporters.plainText(doc))
    }

    @Test fun timestamped() {
        val t = Exporters.timestamped(doc).lines()
        assertEquals("[00:00 - 00:02] Hello & welcome.", t[0])
        assertEquals("[00:09 - 1:02:03] After a pause --> new topic.", t[2])
    }

    @Test fun docxIsValidZipWithEscapedXml() {
        val bytes = Exporters.exportToBytes(doc, ExportFormat.DOCX)
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().decodeToString() }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")))
        val body = entries["word/document.xml"]!!
        assertTrue(body.contains("Lab meeting &lt;draft&gt;"))
        assertTrue(body.contains("Hello &amp; welcome."))
        // must be well-formed XML
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray()))
    }
}
