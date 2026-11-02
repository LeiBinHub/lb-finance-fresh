package com.lb.financefresh.filing;

import com.lb.financefresh.llm.MediaArticleWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When a filing article has no embedded pictures, render short readable
 * pull-quotes from the Chinese body as PNG cards.
 */
public final class FilingShots {

    private static final Logger log = LoggerFactory.getLogger(FilingShots.class);
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?(?:[BbMmKk亿万]|%)?");
    private static final int MAX_SHOTS = 3;
    private static final int MAX_CHARS = 110;
    private static final Color PAPER = new Color(0xF7F4EE);
    private static final Color INK = new Color(0x1C1917);
    private static final Color RULE = new Color(0xC2410C);
    private static final Color EDGE = new Color(0xE7E0D6);

    private FilingShots() {
    }

    public record Filled(MediaArticleWriter.Article article, List<FilingExtract.Picture> pictures) {
        public Filled {
            pictures = pictures == null ? List.of() : List.copyOf(pictures);
        }
    }

    public static Filled fill(MediaArticleWriter.Article article) {
        if (article == null) return new Filled(null, List.of());
        List<String> paras = paragraphs(article.bodyZh());
        List<Integer> picks = pick(paras);
        if (picks.isEmpty()) return new Filled(article, List.of());
        List<FilingExtract.Picture> pictures = new ArrayList<>();
        List<String> zhParas = new ArrayList<>(paras);
        int id = 1;
        for (int index : picks) {
            String quote = clip(plain(zhParas.get(index)));
            if (quote.isBlank()) continue;
            byte[] png = render(quote);
            if (png.length == 0) continue;
            pictures.add(new FilingExtract.Picture(
                    id, "正文摘录", quote.length() > 24 ? quote.substring(0, 24) + "…" : quote,
                    "image/png", png, 1080, 0));
            zhParas.set(index, zhParas.get(index) + "\n<<<IMG:" + id + ">>>");
            id++;
            if (pictures.size() == MAX_SHOTS) break;
        }
        if (pictures.isEmpty()) return new Filled(article, List.of());
        String zh = String.join("\n\n", zhParas);
        String en = FilingPictures.copyMarks(zh, article.bodyEn());
        return new Filled(new MediaArticleWriter.Article(article.titleZh(), zh, article.titleEn(), en), pictures);
    }

    static List<Integer> pick(List<String> paras) {
        if (paras == null || paras.isEmpty()) return List.of();
        int[] scores = new int[paras.size()];
        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < paras.size(); i++) {
            scores[i] = score(paras.get(i));
            if (scores[i] > 0) ranked.add(i);
        }
        ranked.sort((a, b) -> Integer.compare(scores[b], scores[a]));
        List<Integer> out = new ArrayList<>();
        for (int index : ranked) {
            if (tooClose(out, index, paras.size())) continue;
            out.add(index);
            if (out.size() == MAX_SHOTS) break;
        }
        if (out.isEmpty() && !paras.isEmpty()) out.add(0);
        out.sort(Integer::compareTo);
        return out;
    }

    static int score(String raw) {
        String text = plain(raw);
        if (text.length() < 16) return 0;
        if (text.startsWith("#") || text.startsWith("|")) return 0;
        int numbers = 0;
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) numbers++;
        if (numbers >= 5) return 0;
        int score = 12;
        if (text.length() >= 40 && text.length() <= 180) score += 8;
        if (text.length() > 280) score -= 6;
        if (numbers <= 3) score += 6;
        else score -= numbers;
        if (text.contains("但") || text.contains("不是") || text.contains("已经") || text.contains("还在")
                || text.contains("定价") || text.contains("市场") || text.contains("说明")
                || text.contains("亏") || text.contains("砸") || text.contains("先")) {
            score += 10;
        }
        return Math.max(score, 0);
    }

    static String clip(String text) {
        String value = text == null ? "" : text.strip();
        if (value.length() <= MAX_CHARS) return value;
        return value.substring(0, MAX_CHARS).strip() + "…";
    }

    static byte[] render(String quote) {
        Font font = bodyFont(36f);
        BufferedImage probe = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D measure = probe.createGraphics();
        measure.setFont(font);
        FontMetrics fm = measure.getFontMetrics();
        int width = 1080;
        int padX = 72;
        int padY = 56;
        List<String> lines = wrap(quote, fm, width - padX * 2);
        if (lines.size() > 6) lines = lines.subList(0, 6);
        int lineH = fm.getHeight();
        int height = padY * 2 + lineH * lines.size() + 12;
        measure.dispose();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(PAPER);
        g.fillRect(0, 0, width, height);
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(10, 10, width - 21, height - 21, 28, 28);
        g.setColor(RULE);
        g.fillRect(10, 10, 14, height - 20);
        g.setFont(font);
        g.setColor(INK);
        int y = padY + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, padX, y);
            y += lineH;
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            log.warn("filing shot render failed: {}", e.toString());
            return new byte[0];
        }
        return out.toByteArray();
    }

    static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) return lines;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            if ("\n".equals(ch)) {
                lines.add(line.toString());
                line = new StringBuilder();
                continue;
            }
            if (!line.isEmpty() && fm.stringWidth(line + ch) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            line.append(ch);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.strip().split("\\n\\s*\\n")) {
            String para = part.strip();
            if (!para.isBlank() && !FilingPictures.MARK.matcher(para).find()) out.add(para);
        }
        return out;
    }

    private static boolean tooClose(List<Integer> picked, int index, int size) {
        if (picked.isEmpty()) return false;
        int gap = Math.max(1, size / 4);
        for (int other : picked) {
            if (Math.abs(other - index) < gap) return true;
        }
        return false;
    }

    private static String plain(String text) {
        return text == null ? "" : text.replaceAll("<<<IMG:\\d+>>>", "").strip();
    }

    static Font bodyFont(float size) {
        for (Path path : fontFiles()) {
            try {
                if (!Files.isRegularFile(path)) continue;
                Font[] fonts = Font.createFonts(path.toFile());
                if (fonts.length == 0) continue;
                return fonts[0].deriveFont(Font.PLAIN, size);
            } catch (Exception e) {
                log.debug("skip font {}: {}", path, e.toString());
            }
        }
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (Font font : ge.getAllFonts()) {
            String name = font.getFontName().toLowerCase();
            if (name.contains("heiti") || name.contains("pingfang") || name.contains("hiragino sans gb")
                    || name.contains("noto sans cjk") || name.contains("noto sans sc")
                    || name.contains("source han") || name.contains("wenquanyi")
                    || name.contains("songti") || name.contains("uming")) {
                return font.deriveFont(Font.PLAIN, size);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size));
    }

    private static List<Path> fontFiles() {
        return List.of(
                Path.of("data", "fonts", "NotoSansSC-Regular.otf"),
                Path.of("data", "fonts", "NotoSansSC-Regular.ttf"),
                Path.of("data", "fonts", "wqy-microhei.ttc"),
                Path.of("/System/Library/Fonts/STHeiti Light.ttc"),
                Path.of("/System/Library/Fonts/Hiragino Sans GB.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf"));
    }
}
