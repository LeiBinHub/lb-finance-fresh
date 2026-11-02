package com.lb.financefresh.filing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Pulls readable text and embedded pictures from an uploaded earnings file. */
public final class FilingExtract {

    public static final int MAX_CHARS = 80_000;
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public static final int MAX_PICTURES = 12;
    private static final int MIN_EDGE = 80;
    private static final int MAX_EDGE = 2400;
    private static final int MIN_BYTES = 400;
    private static final Pattern FIGURE = Pattern.compile(
            "(?i)(figure|exhibit|chart|table|图表|附图|图\\s*\\d)");

    private FilingExtract() {
    }

    public record Picture(int id, String place, String caption, String mime, byte[] bytes, int width, int height) {
        public Picture {
            place = place == null ? "" : place;
            caption = caption == null ? "" : caption;
            mime = mime == null || mime.isBlank() ? "image/png" : mime;
            bytes = bytes == null ? new byte[0] : bytes;
        }
    }

    public record Extracted(String text, List<Picture> pictures) {
        public Extracted {
            text = text == null ? "" : text;
            pictures = pictures == null ? List.of() : List.copyOf(pictures);
        }
    }

    public static String kind(String filename) {
        String name = filename == null ? "" : filename.strip();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean allowed(String filename) {
        return switch (kind(filename)) {
            case "pdf", "xlsx", "xls", "docx", "doc" -> true;
            default -> false;
        };
    }

    public static String text(String filename, byte[] bytes) {
        return read(filename, bytes).text();
    }

    public static Extracted read(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("还没有上传文件");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件太大，请压缩到 20MB 以内");
        }
        if (!allowed(filename)) {
            throw new IllegalArgumentException("只支持 pdf、excel、docx、doc");
        }
        Extracted extracted;
        try {
            extracted = switch (kind(filename)) {
                case "pdf" -> pdf(bytes);
                case "xlsx", "xls" -> excel(bytes);
                case "docx" -> docx(bytes);
                case "doc" -> doc(bytes);
                default -> throw new IllegalArgumentException("只支持 pdf、excel、docx、doc");
            };
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("这份文件读不出来，请换成可复制文字的版本", e);
        }
        String text = extracted.text() == null ? "" : extracted.text().replace('\u0000', ' ').strip();
        if (text.isBlank()) {
            throw new IllegalArgumentException("这份文件里没有可读取的文字，可能是扫描件");
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n…（后文已截断）";
        }
        return new Extracted(text, extracted.pictures());
    }

    private static Extracted pdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("这份 PDF 加了密，解不开");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            List<Raw> raw = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document);
                collectPdfImages(page.getResources(), raw, "第" + (i + 1) + "页", pageText);
            }
            return new Extracted(text, finish(raw));
        }
    }

    private static void collectPdfImages(PDResources resources, List<Raw> raw, String place, String pageText)
            throws Exception {
        if (resources == null) return;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDImageXObject image) {
                if (image.isStencil()) continue;
                addImage(raw, place, captionFrom(pageText), image.getImage());
            } else if (object instanceof PDFormXObject form) {
                collectPdfImages(form.getResources(), raw, place, pageText);
            }
        }
    }

    private static Extracted excel(byte[] bytes) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet == null) continue;
                out.append("# ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    if (row == null) continue;
                    List<String> cells = new ArrayList<>();
                    short last = row.getLastCellNum();
                    boolean any = false;
                    for (int c = 0; c < last; c++) {
                        String value = formatter.formatCellValue(row.getCell(c)).strip();
                        cells.add(value);
                        if (!value.isBlank()) any = true;
                    }
                    if (!any) continue;
                    out.append(String.join("\t", cells)).append('\n');
                }
            }
            List<Raw> raw = new ArrayList<>();
            List<? extends PictureData> pictures = picturesOf(workbook);
            String place = workbook.getNumberOfSheets() > 0 && workbook.getSheetAt(0) != null
                    ? "工作表 " + workbook.getSheetAt(0).getSheetName()
                    : "工作表";
            for (PictureData picture : pictures) {
                if (picture == null) continue;
                addBytes(raw, place, place, picture.getData());
            }
            return new Extracted(out.toString(), finish(raw));
        }
    }

    private static List<? extends PictureData> picturesOf(Workbook workbook) {
        if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook xssf) {
            return xssf.getAllPictures();
        }
        if (workbook instanceof HSSFWorkbook hssf) {
            return hssf.getAllPictures();
        }
        return List.of();
    }

    private static Extracted docx(byte[] bytes) throws Exception {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            List<Raw> raw = new ArrayList<>();
            String last = "";
            for (IBodyElement element : document.getBodyElements()) {
                if (!(element instanceof XWPFParagraph paragraph)) continue;
                String line = paragraph.getText() == null ? "" : paragraph.getText().strip();
                for (XWPFRun run : paragraph.getRuns()) {
                    if (run == null) continue;
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        if (picture == null || picture.getPictureData() == null) continue;
                        String caption = !line.isBlank() ? line : last;
                        addBytes(raw, "文档", caption, picture.getPictureData().getData());
                    }
                }
                if (!line.isBlank()) last = line;
            }
            for (XWPFPictureData picture : document.getAllPictures()) {
                if (picture == null) continue;
                addBytes(raw, "文档", last, picture.getData());
            }
            return new Extracted(text, finish(raw));
        }
    }

    private static Extracted doc(byte[] bytes) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText();
            List<Raw> raw = new ArrayList<>();
            if (document.getPicturesTable() != null) {
                for (org.apache.poi.hwpf.usermodel.Picture picture : document.getPicturesTable().getAllPictures()) {
                    if (picture == null) continue;
                    addBytes(raw, "文档", captionFrom(text), picture.getContent());
                }
            }
            return new Extracted(text, finish(raw));
        }
    }

    private static void addImage(List<Raw> raw, String place, String caption, BufferedImage image) throws Exception {
        if (image == null) return;
        addRaw(raw, place, caption, encodePng(image), image.getWidth(), image.getHeight());
    }

    private static void addBytes(List<Raw> raw, String place, String caption, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length < MIN_BYTES) return;
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) return;
        addRaw(raw, place, caption, encodePng(image), image.getWidth(), image.getHeight());
    }

    private static void addRaw(List<Raw> raw, String place, String caption, byte[] bytes, int width, int height) {
        if (bytes == null || bytes.length < MIN_BYTES) return;
        if (width < MIN_EDGE || height < MIN_EDGE) return;
        if (width > MAX_EDGE && height > MAX_EDGE) return;
        if (width > height * 12 || height > width * 12) return;
        raw.add(new Raw(place, clip(caption), bytes, width, height, bytes.length + ":" + Arrays.hashCode(bytes)));
    }

    private static List<Picture> finish(List<Raw> raw) {
        Map<String, Raw> unique = new LinkedHashMap<>();
        for (Raw item : raw) {
            if (item == null || item.hash.isBlank()) continue;
            unique.putIfAbsent(item.hash, item);
        }
        List<Raw> ranked = new ArrayList<>(unique.values());
        ranked.sort((a, b) -> Integer.compare(b.width * b.height, a.width * a.height));
        if (ranked.size() > MAX_PICTURES) ranked = new ArrayList<>(ranked.subList(0, MAX_PICTURES));
        List<Picture> pictures = new ArrayList<>();
        int id = 1;
        for (Raw item : ranked) {
            pictures.add(new Picture(id++, item.place, item.caption, "image/png", item.bytes, item.width, item.height));
        }
        return pictures;
    }

    private static byte[] encodePng(BufferedImage source) throws Exception {
        BufferedImage image = source;
        int max = 1600;
        if (source.getWidth() > max || source.getHeight() > max) {
            double scale = Math.min(max / (double) source.getWidth(), max / (double) source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
            image = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String captionFrom(String text) {
        String collapsed = clip(text);
        if (collapsed.isBlank()) return "";
        for (String line : collapsed.split("\\R")) {
            String stripped = line.strip();
            if (stripped.length() >= 8 && FIGURE.matcher(stripped).find()) return clip(stripped);
        }
        return collapsed;
    }

    private static String clip(String text) {
        if (text == null) return "";
        String collapsed = text.replace('\u0000', ' ').replaceAll("[\\t\\x0B\\f]+", " ").strip();
        collapsed = collapsed.replaceAll("\\R+", " ");
        if (collapsed.length() <= 220) return collapsed;
        return collapsed.substring(0, 220).strip();
    }

    private record Raw(String place, String caption, byte[] bytes, int width, int height, String hash) {
    }
}
