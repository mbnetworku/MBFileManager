package com.mbnetwork.filemanager

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Reads and edits Office Open XML files (.docx / .xlsx / .pptx) – they are ZIPs of XML. */
object OfficeDoc {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun kind(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "docx", "docm" -> "docx"; "xlsx", "xlsm" -> "xlsx"; "pptx", "pptm" -> "pptx"
        "doc", "xls", "ppt", "rtf", "odt", "ods", "odp" -> "old"; else -> null
    }

    fun isOffice(name: String) = kind(name) != null

    fun xml(zip: ZipFile, entry: String): Document? {
        val e = zip.getEntry(entry) ?: return null
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return zip.getInputStream(e).use { f.newDocumentBuilder().parse(it) }
    }

    fun bytes(doc: Document): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.STANDALONE, "yes")
        val out = ByteArrayOutputStream()
        t.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    /** Rewrites the ZIP, replacing changed XML parts, then swaps it in place. */
    fun save(file: File, replace: Map<String, ByteArray>) {
        val tmp = File(file.parentFile, ".${file.name}.saving")
        ZipFile(file).use { zin ->
            ZipOutputStream(tmp.outputStream().buffered()).use { zout ->
                for (e in zin.entries().toList()) {
                    zout.putNextEntry(ZipEntry(e.name))
                    val r = replace[e.name]
                    if (r != null) zout.write(r) else zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }
        if (!file.delete() || !tmp.renameTo(file)) { tmp.copyTo(file, true); tmp.delete() }
    }

    fun children(e: Element, ns: String, local: String): List<Element> {
        val out = mutableListOf<Element>()
        var n = e.firstChild
        while (n != null) { if (n is Element && n.namespaceURI == ns && n.localName == local) out += n; n = n.nextSibling }
        return out
    }

    fun all(e: Element, ns: String, local: String): List<Element> {
        val l = e.getElementsByTagNameNS(ns, local)
        return (0 until l.length).map { l.item(it) as Element }
    }

    fun text(p: Element, ns: String) = all(p, ns, "t").joinToString("") { it.textContent }

    /** Puts [value] in the first text run of paragraph [p] and empties the others (keeps formatting). */
    fun setParaText(p: Element, ns: String, runTag: String, value: String) {
        val ts = all(p, ns, "t")
        if (ts.isEmpty()) {
            val doc = p.ownerDocument
            val prefix = p.prefix?.let { "$it:" } ?: ""
            val r = doc.createElementNS(ns, "${prefix}$runTag")
            val t = doc.createElementNS(ns, "${prefix}t")
            t.textContent = value
            if (ns == W) t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
            r.appendChild(t); p.appendChild(r)
            return
        }
        ts[0].textContent = value
        if (ns == W) ts[0].setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
        ts.drop(1).forEach { it.textContent = "" }
    }

    /** Relationship id -> target for a part, e.g. "ppt/_rels/presentation.xml.rels". */
    fun rels(zip: ZipFile, relsEntry: String): Map<String, String> {
        val d = xml(zip, relsEntry) ?: return emptyMap()
        return all(d.documentElement, PKG, "Relationship").associate { it.getAttribute("Id") to it.getAttribute("Target") }
    }

    fun resolve(baseDir: String, target: String): String =
        if (target.startsWith("/")) target.drop(1) else {
            val parts = (baseDir.split('/') + target.split('/')).filter { it.isNotEmpty() }
            val st = ArrayDeque<String>()
            parts.forEach { if (it == "..") st.removeLastOrNull() else if (it != ".") st.addLast(it) }
            st.joinToString("/")
        }
}
