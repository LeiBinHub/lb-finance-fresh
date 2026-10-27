package com.lb.financefresh.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Observations already fetched, merged by series and date. A later fetch adds new dates. */
@Component
public class MacroHistory {

    private final Path file;
    private final ObjectMapper json;

    @Autowired
    public MacroHistory(ObjectMapper json) {
        this(json, Path.of("data", "macro", "history.json"));
    }

    MacroHistory(ObjectMapper json, Path file) {
        this.json = json;
        this.file = file;
    }

    public synchronized void merge(String series, List<FredClient.Point> points) throws IOException {
        if (points == null || points.isEmpty()) return;
        Map<String, TreeMap<String, Double>> all = read();
        TreeMap<String, Double> row = all.computeIfAbsent(series, key -> new TreeMap<>());
        for (FredClient.Point point : points) {
            row.put(point.date().toString(), point.value());
        }
        write(all);
    }

    public synchronized Map<String, List<FredClient.Point>> charts() throws IOException {
        Map<String, TreeMap<String, Double>> all = read();
        Map<String, List<FredClient.Point>> out = new LinkedHashMap<>();
        for (String series : List.of("BAMLH0A0HYM2", "T10Y2Y", "T10Y3M", "ICSA", "SAHMCURRENT", "GDPNOW")) {
            out.put(series, pointsOf(all.get(series)));
        }
        out.put("NETLIQ", netLiquidity(pointsOf(all.get("WALCL")), pointsOf(all.get("WTREGEN")), pointsOf(all.get("RRPONTSYD"))));
        return out;
    }

    static List<FredClient.Point> netLiquidity(List<FredClient.Point> assets, List<FredClient.Point> account, List<FredClient.Point> reverseRepo) {
        List<FredClient.Point> out = new ArrayList<>();
        for (FredClient.Point asset : assets) {
            FredClient.Point treasury = asOf(account, asset.date());
            FredClient.Point repo = asOf(reverseRepo, asset.date());
            if (treasury == null || repo == null) continue;
            double billions = asset.value() / 1000.0 - treasury.value() / 1000.0 - repo.value();
            out.add(new FredClient.Point(asset.date(), billions));
        }
        return out;
    }

    private static FredClient.Point asOf(List<FredClient.Point> ascending, LocalDate day) {
        FredClient.Point found = null;
        for (FredClient.Point point : ascending) {
            if (point.date().isAfter(day)) break;
            found = point;
        }
        return found;
    }

    private static List<FredClient.Point> pointsOf(TreeMap<String, Double> row) {
        if (row == null || row.isEmpty()) return List.of();
        List<FredClient.Point> out = new ArrayList<>();
        row.forEach((date, value) -> out.add(new FredClient.Point(LocalDate.parse(date), value)));
        out.sort(Comparator.comparing(FredClient.Point::date));
        return out;
    }

    private Map<String, TreeMap<String, Double>> read() throws IOException {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        Map<String, List<Reading>> stored = json.readValue(file.toFile(), new TypeReference<>() {});
        Map<String, TreeMap<String, Double>> all = new LinkedHashMap<>();
        stored.forEach((series, readings) -> {
            TreeMap<String, Double> row = new TreeMap<>();
            for (Reading reading : readings) row.put(reading.date(), reading.value());
            all.put(series, row);
        });
        return all;
    }

    private void write(Map<String, TreeMap<String, Double>> all) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, List<Reading>> stored = new LinkedHashMap<>();
        all.forEach((series, row) -> {
            List<Reading> readings = new ArrayList<>();
            row.forEach((date, value) -> readings.add(new Reading(date, value)));
            stored.put(series, readings);
        });
        json.writeValue(file.toFile(), stored);
    }

    public record Reading(String date, double value) {}
}
