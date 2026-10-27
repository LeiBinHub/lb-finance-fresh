package com.lb.financefresh.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.model.BriefKind;
import com.lb.financefresh.model.CalendarEvent;
import com.lb.financefresh.model.Quote;
import com.lb.financefresh.model.WatchLabels;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BriefRenderer {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final FreshSettings settings;

    public BriefRenderer(FreshSettings settings) {
        this.settings = settings;
    }

    public String render(BriefKind kind, LocalDate day, String yieldLine, String macroMonitor, List<Quote> quotes,
                          List<CalendarEvent> released, JsonNode model) {
        ZoneId zone = ZoneId.of(settings.zone());
        ZonedDateTime now = ZonedDateTime.now(zone);
        StringBuilder out = new StringBuilder();
        out.append(kind.title()).append(" · ").append(day);
        if (day.equals(now.toLocalDate())) {
            out.append(" ").append(now.format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        out.append(" 北京时间\n");
        if (!day.equals(now.toLocalDate())) {
            out.append("生成于 ").append(CLOCK.format(now)).append(" 北京时间\n");
        }
        out.append("\n");
        out.append("📊 宏观与流动性水位\n");
        out.append(yieldLine).append("\n");
        if (macroMonitor != null && !macroMonitor.isBlank()) out.append("\n").append(macroMonitor.strip()).append("\n");
        String macro = text(model, "macroComment");
        if (!macro.isBlank()) out.append(macro).append("\n");
        if (!released.isEmpty()) {
            out.append("已落地：\n");
            for (CalendarEvent event : released) out.append("- ").append(line(event)).append("\n");
        }
        out.append("\n🎯 自选股异动归因\n");
        out.append(movers(quotes, model.path("movers")));
        out.append("\n🔥 全市场三大核心叙事\n");
        out.append(narratives(model.path("narratives")));
        return out.toString().strip() + "\n";
    }

    private String movers(List<Quote> quotes, JsonNode modelMovers) {
        if (quotes == null || quotes.isEmpty()) return "这次没有取到自选股报价。\n";
        Map<String, JsonNode> notes = new LinkedHashMap<>();
        if (modelMovers.isArray()) {
            for (JsonNode node : modelMovers) notes.put(node.path("symbol").asText("").toUpperCase(Locale.ROOT), node);
        }
        StringBuilder out = new StringBuilder();
        for (Quote quote : quotes) {
            JsonNode note = notes.get(quote.symbol());
            out.append(WatchLabels.display(quote.symbol()));
            out.append(" · ").append(quote.session()).append("\n");
            out.append(quote.sessionTable());
            if (quote.notable(settings.moverPercent()) || note != null) {
                double sentiment = note == null ? 0 : note.path("sentiment").asDouble(0);
                String driver = note == null ? "输入里没有对应新闻" : note.path("driver").asText("输入里没有明确原因");
                out.append(String.format(Locale.US, "情绪 %.2f | %s\n", sentiment, driver));
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static String narratives(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty()) return "本次没有足够的跨源材料。\n";
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (JsonNode node : nodes) {
            if (index > 3) break;
            out.append(index++).append(". ").append(node.path("title").asText("")).append("\n");
            String detail = node.path("detail").asText("");
            if (!detail.isBlank()) out.append(detail).append("\n");
        }
        return out.toString();
    }

    private static String line(CalendarEvent event) {
        String detail = event.detail() == null || event.detail().isBlank() ? "" : "，" + event.detail();
        return event.whenText() + " " + event.title() + detail;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }
}
