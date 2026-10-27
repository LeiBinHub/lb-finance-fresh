package com.lb.financefresh.pipeline;

import com.lb.financefresh.llm.MediaArticleWriter;
import com.lb.financefresh.model.MediaDesk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Media posts written from each brief fetch or a desk generate. Same stem as the brief file for US macro. */
@Component
public class MediaArchive {

    static final Pattern NAME = Pattern.compile("^(premarket|recap|now|us-micro|cn-micro)-(\\d{8})-(\\d{4})\\.md$");

    private final Path dir;

    @Autowired
    public MediaArchive() {
        this(Path.of("data", "media"));
    }

    MediaArchive(Path dir) {
        this.dir = dir;
    }

    public SavedArticle save(String briefName, MediaArticleWriter.Article article) throws IOException {
        if (deskOf(briefName) != MediaDesk.US_MACRO) {
            throw new IllegalArgumentException("美国宏观稿文件名无效");
        }
        return write(briefName, article);
    }

    public SavedArticle saveGenerated(MediaDesk desk, LocalDate day, String clock, MediaArticleWriter.Article article)
            throws IOException {
        if (desk == null || !desk.generated()) {
            throw new IllegalArgumentException("美国宏观由简报自动生成");
        }
        if (day == null || clock == null || !clock.matches("\\d{4}")) {
            throw new IllegalArgumentException("没有这份媒体文章");
        }
        String name = desk.id() + "-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + clock + ".md";
        return write(name, article);
    }

    public List<SavedArticle> list() throws IOException {
        return list(MediaDesk.US_MACRO);
    }

    public List<SavedArticle> list(MediaDesk desk) throws IOException {
        MediaDesk wanted = desk == null ? MediaDesk.US_MACRO : desk;
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        Matcher matcher = NAME.matcher(name);
                        return matcher.matches() && deskOf(name) == wanted;
                    })
                    .map(name -> {
                        try {
                            MediaArticleWriter.Article article = readArticle(name);
                            return describe(name, article.titleZh(), article.titleEn());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(item -> item != null)
                    .sorted(Comparator.comparing(SavedArticle::date).thenComparing(SavedArticle::time).reversed())
                    .toList();
        }
    }

    public SavedArticle get(String name) throws IOException {
        MediaArticleWriter.Article article = readArticle(name);
        return describe(name, article.titleZh(), article.titleEn());
    }

    public MediaArticleWriter.Article readArticle(String name) throws IOException {
        Path file = articleFile(name);
        return MediaArticleWriter.parse(Files.readString(file));
    }

    public String read(String name) throws IOException {
        return Files.readString(articleFile(name));
    }

    static String serialize(MediaArticleWriter.Article article) {
        return MediaArticleWriter.ZH_MARK + "\n"
                + "# " + article.titleZh().strip() + "\n\n"
                + article.bodyZh().strip() + "\n\n"
                + MediaArticleWriter.EN_MARK + "\n"
                + "# " + article.titleEn().strip() + "\n\n"
                + article.bodyEn().strip() + "\n";
    }

    static MediaDesk deskOf(String name) {
        Matcher matcher = NAME.matcher(name == null ? "" : name);
        if (!matcher.matches()) throw new IllegalArgumentException("没有这份媒体文章");
        return MediaDesk.fromFile(name);
    }

    private SavedArticle write(String name, MediaArticleWriter.Article article) throws IOException {
        deskOf(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), serialize(article));
        return describe(name, article.titleZh(), article.titleEn());
    }

    private Path articleFile(String name) throws IOException {
        deskOf(name);
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("还没有这份媒体文章");
        return file;
    }

    private static SavedArticle describe(String name, String titleZh, String titleEn) {
        Matcher matcher = NAME.matcher(name);
        if (!matcher.matches()) throw new IllegalArgumentException("没有这份媒体文章");
        MediaDesk desk = MediaDesk.fromFile(name);
        String day = matcher.group(2);
        String clock = matcher.group(3);
        String date = day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8);
        String time = clock.substring(0, 2) + ":" + clock.substring(2, 4);
        String title = titleZh == null || titleZh.isBlank()
                ? (titleEn == null || titleEn.isBlank() ? desk.title() + " " + time : titleEn)
                : titleZh;
        return new SavedArticle(name, desk.id(), date, time, title, titleEn == null ? "" : titleEn);
    }

    public record SavedArticle(String name, String desk, String date, String time, String title, String titleEn) {
    }
}
