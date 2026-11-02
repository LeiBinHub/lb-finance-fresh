package com.lb.financefresh.filing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingExtractTest {

    @Test
    void readsPdfExcelAndDocx() throws Exception {
        assertTrue(FilingExtract.text("GOOGL-10Q.pdf", pdf("Revenue 96.4 billion")).contains("Revenue 96.4 billion"));
        assertTrue(FilingExtract.text("msft.xlsx", xlsx()).contains("Cloud"));
        assertTrue(FilingExtract.text("notes.docx", docx("EPS beat on ads")).contains("EPS beat on ads"));
    }

    @Test
    void extractsChartsFromPdfAndDocx() throws Exception {
        byte[] png = png(new Color(30, 90, 180), "Cloud 34%");
        FilingExtract.Extracted pdf = FilingExtract.read("10Q.pdf", pdfWithImage("Figure 2 Cloud revenue", png));
        assertEquals(1, pdf.pictures().size());
        assertEquals(1, pdf.pictures().getFirst().id());
        assertTrue(pdf.pictures().getFirst().caption().contains("Cloud")
                || pdf.pictures().getFirst().place().contains("第"));
        FilingExtract.Extracted word = FilingExtract.read("notes.docx", docxWithImage("Exhibit Cloud", png));
        assertEquals(1, word.pictures().size());
        assertTrue(word.pictures().getFirst().bytes().length > 1000);
    }

    @Test
    void rejectsUnsupportedOrEmpty() {
        assertThrows(IllegalArgumentException.class, () -> FilingExtract.text("notes.txt", "hello".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> FilingExtract.text("empty.pdf", new byte[0]));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Q");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("Segment");
            row.createCell(1).setCellValue("Cloud");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdfWithImage(String caption, byte[] png) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var image = LosslessFactory.createFromImage(document, ImageIO.read(new ByteArrayInputStream(png)));
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(image, 80, 420, 240, 140);
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(80, 390);
                stream.showText(caption);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxWithImage(String caption, byte[] png) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(caption);
            document.createParagraph().createRun()
                    .addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG, "chart.png",
                            Units.toEMU(200), Units.toEMU(120));
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] png(Color color, String label) throws Exception {
        BufferedImage image = new BufferedImage(220, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 220, 140);
        graphics.setColor(Color.WHITE);
        graphics.drawString(label, 24, 78);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
