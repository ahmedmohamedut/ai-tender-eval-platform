package app.tendereval.backend.exports;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** RFC 4180 CSV with a byte order mark (Excel) and a guard against formula injection. */
final class CsvWriter {

  private CsvWriter() {}

  static byte[] write(Sheet sheet) {
    StringBuilder out = new StringBuilder("﻿");
    line(out, sheet.header());
    for (List<String> r : sheet.rows()) {
      line(out, r);
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void line(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(cell(cells.get(i)));
    }
    out.append("\r\n");
  }

  static String cell(String raw) {
    String v = raw == null ? "" : raw;
    if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
      v = "'" + v;
    }
    if (v.indexOf(',') >= 0
        || v.indexOf('"') >= 0
        || v.indexOf('\n') >= 0
        || v.indexOf('\r') >= 0) {
      return '"' + v.replace("\"", "\"\"") + '"';
    }
    return v;
  }
}
