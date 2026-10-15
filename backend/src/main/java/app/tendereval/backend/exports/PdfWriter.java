package app.tendereval.backend.exports;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** The report as a printable PDF: a title, key facts, then each sheet as a table. */
final class PdfWriter {

  private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
  private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
  private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 8);
  private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

  private PdfWriter() {}

  static byte[] write(String title, List<Sheet> sheets) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
    try {
      com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
      doc.addTitle(title);
      doc.open();
      doc.add(new Paragraph(title, TITLE));
      for (Sheet s : sheets) {
        Paragraph h = new Paragraph(s.name(), HEAD);
        h.setSpacingBefore(12);
        h.setSpacingAfter(4);
        doc.add(h);
        int cols = Math.max(1, s.header().isEmpty() ? width(s) : s.header().size());
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(100);
        t.setHeaderRows(s.header().isEmpty() ? 0 : 1);
        for (String c : s.header()) {
          t.addCell(cell(c, BOLD, true));
        }
        for (List<String> r : s.rows()) {
          for (int i = 0; i < cols; i++) {
            t.addCell(cell(i < r.size() ? r.get(i) : "", BODY, false));
          }
        }
        doc.add(t);
      }
    } catch (DocumentException e) {
      throw new IllegalStateException("pdf", e);
    } finally {
      doc.close();
    }
    return out.toByteArray();
  }

  private static int width(Sheet s) {
    return s.rows().stream().mapToInt(List::size).max().orElse(1);
  }

  private static PdfPCell cell(String text, Font font, boolean head) {
    PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
    c.setPadding(3);
    c.setHorizontalAlignment(Element.ALIGN_LEFT);
    if (head) {
      c.setBackgroundColor(new java.awt.Color(0xE8, 0xEC, 0xF1));
    }
    return c;
  }
}
