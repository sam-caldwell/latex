package com.samcaldwell.latex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.*
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class IconSvgValidationTest {
  // Resolve relative to the plugin module root when tests run with projectDir = intellij-latex-plugin
  private val iconsDir = Paths.get("src/main/resources/icons")

  @Test
  fun `svg icons are well-formed and safe`() {
    assertTrue(Files.isDirectory(iconsDir), "icons directory missing: $iconsDir")
    val svgs = Files.list(iconsDir).use { s -> s.filter { it.toString().endsWith(".svg") }.toList() }
    assertTrue(svgs.isNotEmpty(), "no .svg icons found in $iconsDir")

    for (svg in svgs) {
      assertTrue(Files.size(svg) > 0, "empty svg: $svg")
      assertTrue(Files.size(svg) < 200_000, "svg too large (>200KB): $svg")

      val doc = parseSecure(svg)
      val root = doc.documentElement
      assertEquals("svg", root.nodeName.lowercase(), "root element must be <svg>: $svg")
      val xmlns = root.getAttribute("xmlns")
      assertTrue(xmlns.isNotBlank(), "missing xmlns on <svg>: $svg")
      val viewBox = root.getAttribute("viewBox")
      assertTrue(viewBox.isNotBlank(), "missing viewBox on <svg>: $svg")

      // disallowed elements and attributes
      assertFalse(doc.getElementsByTagName("script").hasElements(), "<script> not allowed: $svg")
      assertFalse(doc.getElementsByTagName("foreignObject").hasElements(), "<foreignObject> not allowed: $svg")
      assertFalse(doc.getElementsByTagName("iframe").hasElements(), "<iframe> not allowed: $svg")
      assertFalse(doc.getElementsByTagName("embed").hasElements(), "<embed> not allowed: $svg")
      assertFalse(doc.getElementsByTagName("object").hasElements(), "<object> not allowed: $svg")

      // no external http(s) references via href/xlink:href or url(http)
      val all = doc.getElementsByTagName("*")
      var hasShape = false
      for (i in 0 until all.length) {
        val el = all.item(i) as org.w3c.dom.Element
        val name = el.tagName.lowercase()
        if (name in setOf("path", "rect", "circle", "line", "polyline", "polygon", "ellipse")) hasShape = true
        val href = el.getAttribute("href") + el.getAttribute("xlink:href")
        if (href.isNotBlank()) {
          assertFalse(href.startsWith("http://") || href.startsWith("https://"), "external href in $svg: $href")
        }
        // no onload/onerror handlers
        val attrs = listOf("onload", "onerror")
        for (a in attrs) assertFalse(el.hasAttribute(a), "event handler '$a' not allowed in $svg")
        // style url(http) not allowed
        val style = el.getAttribute("style").lowercase()
        assertFalse(style.contains("url(http"), "external url() in style not allowed: $svg")
      }
      assertTrue(hasShape, "svg should contain at least one vector shape: $svg")
    }
  }

  private fun parseSecure(path: Path): org.w3c.dom.Document {
    val dbf = DocumentBuilderFactory.newInstance().apply {
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
      setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
      isNamespaceAware = true
      isExpandEntityReferences = false
      isXIncludeAware = false
    }
    val db = dbf.newDocumentBuilder()
    Files.newInputStream(path).use { input ->
      return db.parse(input)
    }
  }

  private fun org.w3c.dom.NodeList.hasElements(): Boolean = this.length > 0
}
