package com.lb.financefresh.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.market.ApiFailures;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.SourceLink;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class BriefArchive {

    static final Pattern NAME = Pattern.compile("^(premarket|recap|now)-(\\d{8})-(\\d{4})\\.md$");

    private final FreshSettings settings;
    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public BriefArchive(FreshSettings settings) {
        this(settings, Path.of("data", "briefs"));
    }

    BriefArchive(FreshSettings settings, Path dir) {
        this.settings = settings;
        this.dir = dir;
    }

    public SavedBrief save(BriefKind kind, LocalDate day, String text) throws IOException {
        return save(kind, day, text, List.of(), List.of());
    }

    public SavedBrief save(BriefKind kind, LocalDate day, String text, List<SourceLink> sources,
                           List<ApiFailures.FailedCall> failures) throws IOException {
        Files.createDirectories(dir);
        String clock = ZonedDateTime.now(ZoneId.of(settings.zone())).format(DateTimeFormatter.ofPattern("HHmm"));
        String name = kind.name().toLowerCase() + "-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + clock + ".md";
        Files.writeString(dir.resolve(name), text);
        json.writeValue(sidecar(name, "sources.json").toFile(), sources);
        json.writeValue(sidecar(name, "failures.json").toFile(), failures);
        return describe(name);
    }

    public List<String> dates() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .map(BriefArchive::match)
                    .flatMap(matcher -> matcher == null ? Stream.empty() : Stream.of(dateOf(matcher)))
                    .distinct()
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    public List<SavedBrief> on(LocalDate date) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        String day = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        Matcher matcher = match(name);
                        return matcher != null && matcher.group(2).equals(day);
                    })
                    .map(BriefArchive::describe)
                    .sorted(Comparator.comparing(SavedBrief::time).reversed())
                    .toList();
        }
    }

    public String read(String name) throws IOException {
        Path file = briefFile(name);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("这一天还没有这份简报");
        return Files.readString(file);
    }

    public List<SourceLink> sources(String name) throws IOException {
        Path file = sidecar(name, "sources.json");
        if (!Files.isRegularFile(file)) return List.of();
        return json.readValue(file.toFile(), new TypeReference<>() {});
    }

    public List<ApiFailures.FailedCall> failures(String name) throws IOException {
        Path file = sidecar(name, "failures.json");
        if (!Files.isRegularFile(file)) return List.of();
        return json.readValue(file.toFile(), new TypeReference<>() {});
    }

    private Path briefFile(String name) {
        describe(name);
        return dir.resolve(name);
    }

    private Path sidecar(String name, String suffix) {
        describe(name);
        String stem = name.substring(0, name.length() - ".md".length());
        return dir.resolve(stem + "." + suffix);
    }

    public static SavedBrief describe(String name) {
        Matcher matcher = match(name);
        if (matcher == null) throw new IllegalArgumentException("没有这份简报");
        String kind = matcher.group(1);
        String day = matcher.group(2);
        String clock = matcher.group(3);
        String date = day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8);
        String time = clock.substring(0, 2) + ":" + clock.substring(2, 4);
        String title = switch (kind) {
            case "premarket" -> "盘前简报";
            case "recap" -> "复盘早报";
            case "now" -> "当前时报";
            default -> kind;
        } + " " + time;
        return new SavedBrief(name, kind, date, time, title);
    }

    private static String dateOf(Matcher matcher) {
        String day = matcher.group(2);
        return day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6, 8);
    }

    private static Matcher match(String name) {
        Matcher matcher = NAME.matcher(name);
        return matcher.matches() ? matcher : null;
    }

    public record SavedBrief(String name, String kind, String date, String time, String title) {
    }
}
