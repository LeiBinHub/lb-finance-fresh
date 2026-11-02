package com.lb.financefresh.filing;

import com.lb.financefresh.llm.MediaArticleWriter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps at most three filing pictures that sit next to matching paragraphs. */
public final class FilingPictures {

    public static final int MAX_IN_ARTICLE = 3;
    public static final Pattern MARK = Pattern.compile("<<<IMG:(\\d+)>>>");

    private FilingPictures() {
    }

    public static MediaArticleWriter.Article place(MediaArticleWriter.Article article, List<FilingExtract.Picture> pictures) {
        if (article == null) return null;
        List<FilingExtract.Picture> candidates = pictures == null ? List.of() : pictures;
        String zh = article.bodyZh() == null ? "" : article.bodyZh();
        String en = article.bodyEn() == null ? "" : article.bodyEn();
        List<Integer> ids = selectedIds(zh + "\n" + en, candidates);
        zh = keepMarks(zh, ids);
        en = keepMarks(en, ids);
        if (hasMarks(zh) && !hasMarks(en)) en = copyMarks(zh, en);
        return new MediaArticleWriter.Article(article.titleZh(), zh, article.titleEn(), en);
    }

    public static List<FilingExtract.Picture> used(MediaArticleWriter.Article article, List<FilingExtract.Picture> pictures) {
        if (article == null || pictures == null || pictures.isEmpty()) return List.of();
        List<Integer> ids = selectedIds(
                (article.bodyZh() == null ? "" : article.bodyZh()) + "\n" + (article.bodyEn() == null ? "" : article.bodyEn()),
                pictures);
        List<FilingExtract.Picture> out = new ArrayList<>();
        for (int id : ids) {
            for (FilingExtract.Picture picture : pictures) {
                if (picture != null && picture.id() == id) out.add(picture);
            }
        }
        return out;
    }

    static List<Integer> selectedIds(String text, List<FilingExtract.Picture> pictures) {
        Set<Integer> allowed = new LinkedHashSet<>();
        if (pictures != null) {
            for (FilingExtract.Picture picture : pictures) {
                if (picture != null) allowed.add(picture.id());
            }
        }
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = MARK.matcher(text == null ? "" : text);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            if (!allowed.contains(id) || ids.contains(id)) continue;
            ids.add(id);
            if (ids.size() == MAX_IN_ARTICLE) break;
        }
        return ids;
    }

    static String keepMarks(String text, List<Integer> ids) {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        Matcher matcher = MARK.matcher(text);
        StringBuilder out = new StringBuilder();
        Set<Integer> seen = new LinkedHashSet<>();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            int id = Integer.parseInt(matcher.group(1));
            if (ids.contains(id) && seen.add(id)) out.append(matcher.group());
            last = matcher.end();
        }
        out.append(text.substring(last));
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    static String copyMarks(String from, String to) {
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = MARK.matcher(from == null ? "" : from);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            if (!ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) return to == null ? "" : to;
        List<String> paras = paragraphs(to);
        if (paras.isEmpty()) {
            StringBuilder out = new StringBuilder(to == null ? "" : to.strip());
            for (int id : ids) out.append("\n<<<IMG:").append(id).append(">>>");
            return out.toString().strip();
        }
        List<Integer> slots = markSlots(from, paras.size());
        for (int i = 0; i < ids.size() && i < slots.size(); i++) {
            int at = Math.min(slots.get(i), paras.size() - 1);
            paras.set(at, paras.get(at) + "\n<<<IMG:" + ids.get(i) + ">>>");
        }
        return String.join("\n\n", paras);
    }

    private static List<Integer> markSlots(String from, int paraCount) {
        List<String> paras = paragraphs(from);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < paras.size(); i++) {
            if (MARK.matcher(paras.get(i)).find()) slots.add(Math.min(i, Math.max(0, paraCount - 1)));
        }
        return slots;
    }

    private static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.strip().split("\\n\\s*\\n")) {
            String para = part.strip();
            if (!para.isBlank()) out.add(para);
        }
        if (out.isEmpty()) out.add(text.strip());
        return out;
    }

    private static boolean hasMarks(String text) {
        return text != null && MARK.matcher(text).find();
    }
}
