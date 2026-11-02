package com.lb.financefresh.pipeline;

import com.lb.financefresh.filing.FilingExtract;
import com.lb.financefresh.llm.MediaArticleWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Earnings-research posts written from an uploaded filing. */
@Component
public class FilingArchive {

    static final Pattern NAME = Pattern.compile("^filing-(\\d{8})-(\\d{4})\\.md$");

    private final Path dir;

    @Autowired
    public FilingArchive() {
        this(Path.of("data", "filings"));
    }

    FilingArchive(Path dir) {
        this.dir = dir;
    }

    public SavedFiling save(LocalDate day, String clock, String originalName, String ticker, String note,
                            MediaArticleWriter.Article article) throws IOException {
        return save(day, clock, originalName, ticker, note, article, List.of());
    }

    public SavedFiling save(LocalDate day, String clock, String originalName, String ticker, String note,
                            MediaArticleWriter.Article article, List<FilingExtract.Picture> pictures) throws IOException {
        if (day == null || clock == null || !clock.matches("\\d{4}")) {
            throw new IllegalArgumentException("没有这份财报研读");
        }
        String name = "filing-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + clock + ".md";
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), MediaArchive.serialize(article));
        Files.writeString(dir.resolve(metaName(name)), meta(originalName, ticker, note));
        writePictures(name, pictures);
        return describe(name, article.titleZh(), article.titleEn(), originalName, ticker);
    }

    public List<SavedFiling> list() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> NAME.matcher(name).matches())
                    .map(name -> {
                        try {
                            MediaArticleWriter.Article article = readArticle(name);
                            Meta meta = readMeta(name);
                            return describe(name, article.titleZh(), article.titleEn(), meta.originalName(), meta.ticker());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(item -> item != null)
                    .sorted(Comparator.comparing(SavedFiling::date).thenComparing(SavedFiling::time).reversed())
                    .toList();
        }
    }

    public SavedFiling get(String name) throws IOException {
        MediaArticleWriter.Article article = readArticle(name);
        Meta meta = readMeta(name);
        return describe(name, article.titleZh(), article.titleEn(), meta.originalName(), meta.ticker());
    }

    public MediaArticleWriter.Article readArticle(String name) throws IOException {
        return MediaArticleWriter.parse(Files.readString(articleFile(name)));
    }

    public String read(String name) throws IOException {
        return Files.readString(articleFile(name));
    }

    public List<StoredImage> images(String name) throws IOException {
        requireName(name);
        Path index = imageDir(name).resolve("index.txt");
        if (!Files.isRegularFile(index)) return List.of();
        List<StoredImage> out = new ArrayList<>();
        for (String line : Files.readString(index).split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", 4);
            if (parts.length < 2) continue;
            int id;
            try {
                id = Integer.parseInt(parts[0].strip());
            } catch (NumberFormatException e) {
                continue;
            }
            String file = parts[1].strip();
            String place = parts.length > 2 ? parts[2] : "";
            String caption = parts.length > 3 ? parts[3] : "";
            out.add(new StoredImage(id, file, place, caption, "image/png", new byte[0]));
        }
        return out;
    }

    public StoredImage image(String name, int id) throws IOException {
        requireName(name);
        if (id < 1 || id > 99) throw new IllegalArgumentException("还没有这张财报配图");
        Path file = imageDir(name).resolve(id + ".png");
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("还没有这张财报配图");
        String place = "";
        String caption = "";
        for (StoredImage item : images(name)) {
            if (item.id() == id) {
                place = item.place();
                caption = item.caption();
                break;
            }
        }
        return new StoredImage(id, file.getFileName().toString(), place, caption, "image/png", Files.readAllBytes(file));
    }

    private Path articleFile(String name) throws IOException {
        requireName(name);
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("还没有这份财报研读");
        return file;
    }

    private Meta readMeta(String name) {
        Path file = dir.resolve(metaName(name));
        if (!Files.isRegularFile(file)) return new Meta("", "");
        try {
            String raw = Files.readString(file);
            return new Meta(field(raw, "originalName"), field(raw, "ticker"));
        } catch (Exception e) {
            return new Meta("", "");
        }
    }

    static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("没有这份财报研读");
        }
    }

    static String metaName(String name) {
        requireName(name);
        return name.substring(0, name.length() - 3) + "meta.txt";
    }

    static String stem(String name) {
        requireName(name);
        return name.substring(0, name.length() - 3);
    }

    private Path imageDir(String name) {
        return dir.resolve(stem(name));
    }

    private void writePictures(String name, List<FilingExtract.Picture> pictures) throws IOException {
        Path folder = imageDir(name);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
        }
        if (pictures == null || pictures.isEmpty()) {
            Files.deleteIfExists(folder);
            return;
        }
        Files.createDirectories(folder);
        StringBuilder index = new StringBuilder();
        for (FilingExtract.Picture picture : pictures) {
            if (picture == null || picture.bytes() == null || picture.bytes().length == 0) continue;
            Files.write(folder.resolve(picture.id() + ".png"), picture.bytes());
            index.append(picture.id()).append('\t')
                    .append(picture.id()).append(".png\t")
                    .append(clean(picture.place())).append('\t')
                    .append(clean(picture.caption())).append('\n');
        }
        Files.writeString(folder.resolve("index.txt"), index.toString());
    }

    private static String meta(String originalName, String ticker, String note) {
        return "originalName=" + clean(originalName) + "\n"
                + "ticker=" + clean(ticker) + "\n"
                + "note=" + clean(note) + "\n";
    }

    private static String field(String raw, String key) {
        if (raw == null) return "";
        String prefix = key + "=";
        for (String line : raw.split("\\R")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).strip();
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static SavedFiling describe(String name, String titleZh, String titleEn, String originalName, String ticker) {
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches()) throw new IllegalArgumentException("没有这份财报研读");
        String day = matcher.group(1);
        String clock = matcher.group(2);
        String date = day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8);
        String time = clock.substring(0, 2) + ":" + clock.substring(2, 4);
        String title = titleZh == null || titleZh.isBlank()
                ? (titleEn == null || titleEn.isBlank() ? "财报研读 " + time : titleEn)
                : titleZh;
        return new SavedFiling(name, date, time, title, titleEn == null ? "" : titleEn,
                originalName == null ? "" : originalName, ticker == null ? "" : ticker);
    }

    private record Meta(String originalName, String ticker) {
    }

    public record SavedFiling(String name, String date, String time, String title, String titleEn,
                              String originalName, String ticker) {
    }

    public record StoredImage(int id, String file, String place, String caption, String mime, byte[] bytes) {
    }
}
