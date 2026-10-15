package app.tendereval.backend.exports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal Office Open XML workbook: sheets of inline strings, a bold header row. Cells are text
 * only, so no cell is ever read as a formula.
 */
final class XlsxWriter {

  private XlsxWriter() {}

  static byte[] write(List<Sheet> sheets) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      StringBuilder types =
          new StringBuilder(
              "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                  + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                  + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                  + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                  + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                  + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
      StringBuilder book =
          new StringBuilder(
              "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                  + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                  + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
      StringBuilder rels =
          new StringBuilder(
              "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                  + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                  + "<Relationship Id=\"rIdS\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
      int n = 0;
      for (Sheet s : sheets) {
        n++;
        types.append(
            "<Override PartName=\"/xl/worksheets/sheet"
                + n
                + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        book.append("<sheet name=\"")
            .append(escape(sheetName(s.name(), n)))
            .append("\" sheetId=\"")
            .append(n)
            .append("\" r:id=\"rId")
            .append(n)
            .append("\"/>");
        rels.append("<Relationship Id=\"rId")
            .append(n)
            .append(
                "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
            .append(n)
            .append(".xml\"/>");
        entry(zip, "xl/worksheets/sheet" + n + ".xml", sheet(s));
      }
      types.append("</Types>");
      book.append("</sheets></workbook>");
      rels.append("</Relationships>");
      entry(zip, "[Content_Types].xml", types.toString());
      entry(
          zip,
          "_rels/.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
              + "</Relationships>");
      entry(zip, "xl/workbook.xml", book.toString());
      entry(zip, "xl/_rels/workbook.xml.rels", rels.toString());
      entry(
          zip,
          "xl/styles.xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
              + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
              + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
              + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
              + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
              + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
              + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
              + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
              + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
              + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
              + "</styleSheet>");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static void entry(ZipOutputStream zip, String name, String body) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(body.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static String sheet(Sheet s) {
    StringBuilder x =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
    int r = 1;
    if (!s.header().isEmpty()) {
      row(x, r++, s.header(), 1);
    }
    for (List<String> cells : s.rows()) {
      row(x, r++, cells, 0);
    }
    return x.append("</sheetData></worksheet>").toString();
  }

  private static void row(StringBuilder x, int r, List<String> cells, int style) {
    x.append("<row r=\"").append(r).append("\">");
    for (int c = 0; c < cells.size(); c++) {
      x.append("<c r=\"")
          .append(column(c))
          .append(r)
          .append("\" t=\"inlineStr\" s=\"")
          .append(style)
          .append("\"><is><t xml:space=\"preserve\">")
          .append(escape(cells.get(c)))
          .append("</t></is></c>");
    }
    x.append("</row>");
  }

  static String column(int index) {
    StringBuilder s = new StringBuilder();
    int i = index;
    do {
      s.insert(0, (char) ('A' + i % 26));
      i = i / 26 - 1;
    } while (i >= 0);
    return s.toString();
  }

  private static String sheetName(String name, int n) {
    String cleaned = name == null ? "" : name.replaceAll("[\\\\/?*\\[\\]:]", " ").strip();
    if (cleaned.isEmpty()) {
      cleaned = "Sheet" + n;
    }
    return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
  }

  static String escape(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '&' -> b.append("&amp;");
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '"' -> b.append("&quot;");
        default -> {
          // XML 1.0 allows tab, newline, carriage return and printable characters only.
          if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20 && c != 0xFFFE && c != 0xFFFF) {
            b.append(c);
          }
        }
      }
    }
    return b.toString();
  }
}
