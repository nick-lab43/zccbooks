package za.co.zccbooks;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReportPdfWriterTest {
    @Test
    void createsReadablePdfWithReportMetadataAndRows() throws IOException {
        byte[] pdf = ReportPdfWriter.create("MEMBERSHIP REGISTER", "CELL - TEST CELL",
            "2026-01-01", "2026-11-16", "finance.admin",
            List.of("Member", "Application number", "Status"),
            java.util.Collections.singletonList(new String[] { "Example Member", "ZCC-001", "ACTIVE" }));

        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(document.getNumberOfPages() >= 1);
            assertTrue(text.contains("ZION CHRISTIAN CHURCH"));
            assertTrue(text.contains("MEMBERSHIP REGISTER"));
            assertTrue(text.contains("CELL - TEST CELL"));
            assertTrue(text.contains("Example Member"));
            assertTrue(text.contains("ZCC-001"));
        }
    }

    @Test
    void paginatesLongReportsWithoutDroppingRows() throws IOException {
        List<String[]> rows = java.util.stream.IntStream.range(0, 120)
            .mapToObj(i -> new String[] { "Member " + i, "ZCC-" + i, "ACTIVE" }).toList();
        byte[] pdf = ReportPdfWriter.create("MEMBERSHIP REGISTER", "CELL - TEST CELL",
            "2026-01-01", "2026-11-16", "finance.admin",
            List.of("Member", "Application number", "Status"), rows);

        try (var document = Loader.loadPDF(pdf)) {
            assertTrue(document.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Member 0"));
            assertTrue(text.contains("Member 119"));
        }
    }
}
