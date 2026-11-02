package com.lb.financefresh.pipeline;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilingServiceTest {

    @Test
    void keepsOriginalFileNameAndNormalizesTicker() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "C:\\\\Reports\\\\GOOGL-10Q.pdf", "application/pdf", new byte[]{1});
        assertEquals("GOOGL-10Q.pdf", FilingService.originalName(file));
        assertEquals("GOOGL", FilingService.normalizeTicker(" googl "));
        assertEquals("MSFT,GOOGL", FilingService.normalizeTicker(" msft, googl "));
        assertEquals("", FilingService.normalizeTicker("  "));
        assertThrows(IllegalArgumentException.class, () -> FilingService.normalizeTicker("too long ticker!!"));
    }

    @Test
    void packsOneCompanyByThreeKinds() throws Exception {
        FilingService.Packed packed = FilingService.packCompanies(List.of(company(
                "MSFT", "Azure up 48%.", "Nadella on Foundry.", "Revenue 90.6B")));
        assertEquals("MSFT", packed.tickerLine());
        assertTrue(packed.text().contains("===== 公司 1/1：MSFT ====="));
        assertTrue(packed.text().contains("----- 10-Q / 业绩稿：MSFT-10Q.pdf -----"));
        assertTrue(packed.text().contains("----- 电话会纪要：MSFT-call.pdf -----"));
        assertTrue(packed.text().contains("----- 一致预期：MSFT-street.xlsx -----"));
        assertTrue(packed.text().contains("Azure up 48%"));
        assertTrue(packed.text().contains("Nadella on Foundry."));
        assertTrue(packed.text().contains("90.6B"));
    }

    @Test
    void packsTwoCompaniesForCompare() throws Exception {
        FilingService.Packed packed = FilingService.packCompanies(List.of(
                company("MSFT", "Azure 48%", "Hood capex", "90.6B"),
                company("GOOGL", "Cloud 103%", "Pichai TPU", "127.9B")));
        assertEquals("MSFT,GOOGL", packed.tickerLine());
        assertEquals(List.of("MSFT", "GOOGL"), packed.tickers());
        assertTrue(packed.text().contains("===== 公司 1/2：MSFT ====="));
        assertTrue(packed.text().contains("===== 公司 2/2：GOOGL ====="));
        assertTrue(packed.text().contains("Cloud 103%"));
    }

    @Test
    void rejectsIncompleteOrDuplicateCompanies() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FilingService.packCompanies(List.of()));
        FilingService.CompanyUpload missing = new FilingService.CompanyUpload(
                "MSFT", pdf("MSFT-10Q.pdf", "rev"), pdf("MSFT-call.pdf", "call"), empty("consensus.xlsx"));
        assertThrows(IllegalArgumentException.class, () -> FilingService.packCompanies(List.of(missing)));
        assertThrows(IllegalArgumentException.class, () -> FilingService.packCompanies(List.of(
                company("MSFT", "a", "b", "c"),
                company("msft", "d", "e", "f"))));
    }

    private static FilingService.CompanyUpload company(String ticker, String tenq, String call, String street)
            throws Exception {
        return new FilingService.CompanyUpload(
                ticker,
                pdf(ticker + "-10Q.pdf", tenq),
                pdf(ticker + "-call.pdf", call),
                xlsx(ticker + "-street.xlsx", "Revenue", street));
    }

    private static MockMultipartFile pdf(String name, String text) throws Exception {
        return new MockMultipartFile("file", name, "application/pdf", pdfBytes(text));
    }

    private static MockMultipartFile xlsx(String name, String key, String value) throws Exception {
        return new MockMultipartFile(
                "file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsxBytes(key, value));
    }

    private static MockMultipartFile empty(String name) {
        return new MockMultipartFile("file", name, "application/pdf", new byte[0]);
    }

    private static byte[] pdfBytes(String text) throws Exception {
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

    private static byte[] xlsxBytes(String key, String value) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Street");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(key);
            row.createCell(1).setCellValue(value);
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
