package com.lb.financefresh.pipeline;

import com.lb.financefresh.config.FreshSettings;
import com.lb.financefresh.filing.FilingExtract;
import com.lb.financefresh.filing.FilingPictures;
import com.lb.financefresh.filing.FilingShots;
import com.lb.financefresh.llm.MediaArticleWriter;
import com.lb.financefresh.market.MarketCollector;
import com.lb.financefresh.model.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class FilingService {

    private static final Logger log = LoggerFactory.getLogger(FilingService.class);
    private static final Pattern TICKER = Pattern.compile("^[A-Za-z0-9.]{1,12}$");
    public static final int MAX_COMPANIES = 3;
    static final int MAX_TOTAL_CHARS = 120_000;
    static final int MAX_NOTE = 800;

    private final ReentrantLock lock = new ReentrantLock();
    private final FreshSettings settings;
    private final FilingArchive archive;
    private final MediaArticleWriter writer;
    private final MarketCollector market;

    public FilingService(FreshSettings settings, FilingArchive archive, MediaArticleWriter writer,
                         MarketCollector market) {
        this.settings = settings;
        this.archive = archive;
        this.writer = writer;
        this.market = market;
    }

    public FilingArchive.SavedFiling generate(List<CompanyUpload> companies, String note) {
        Packed packed = packCompanies(companies);
        String remark = note == null ? "" : note.strip();
        if (remark.length() > MAX_NOTE) {
            throw new IllegalArgumentException("研究话题太长，请控制在 800 字以内");
        }
        if (!lock.tryLock()) throw new IllegalStateException("上一篇财报研读还在生成");
        try {
            List<Quote> quotes = liveQuotes(packed.tickers());
            LocalDate day = LocalDate.now(ZoneId.of(settings.zone()));
            MediaArticleWriter.Article article = writer.writeFiling(
                    day, packed.originalName(), packed.tickerLine(), remark, packed.text(), quotes, packed.pictures());
            article = FilingPictures.place(article, packed.pictures());
            List<FilingExtract.Picture> used = FilingPictures.used(article, packed.pictures());
            if (used.isEmpty()) {
                FilingShots.Filled filled = FilingShots.fill(article);
                article = filled.article();
                used = filled.pictures();
            }
            String clock = ZonedDateTime.now(ZoneId.of(settings.zone())).format(DateTimeFormatter.ofPattern("HHmm"));
            return archive.save(day, clock, packed.originalName(), packed.tickerLine(), remark, article, used);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? "财报研读失败" : e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private List<Quote> liveQuotes(List<String> tickers) {
        try {
            return market.liveQuotes(tickers);
        } catch (Exception e) {
            log.warn("live quotes for filing failed: {}", e.toString());
            return List.of();
        }
    }

    static Packed packCompanies(List<CompanyUpload> uploads) {
        List<CompanyUpload> companies = presentCompanies(uploads);
        List<CompanyPack> packs = new ArrayList<>();
        int total = 0;
        for (CompanyUpload company : companies) {
            CompanyPack pack = readCompany(company);
            packs.add(pack);
            total += pack.chars();
        }
        if (total > MAX_TOTAL_CHARS) {
            for (int i = 0; i < packs.size(); i++) {
                packs.set(i, packs.get(i).truncated(total));
            }
        }
        StringBuilder body = new StringBuilder();
        List<String> names = new ArrayList<>();
        List<FilingExtract.Picture> merged = new ArrayList<>();
        List<String> tickers = new ArrayList<>();
        int id = 1;
        for (int i = 0; i < packs.size(); i++) {
            CompanyPack pack = packs.get(i);
            tickers.add(pack.ticker());
            body.append("===== 公司 ").append(i + 1).append('/').append(packs.size())
                    .append("：").append(pack.ticker()).append(" =====\n");
            for (KindPack kind : pack.kinds()) {
                names.add(pack.ticker() + " " + kind.label() + " " + kind.name());
                body.append("----- ").append(kind.label()).append("：").append(kind.name()).append(" -----\n")
                        .append(kind.text()).append("\n\n");
                for (FilingExtract.Picture picture : kind.pictures()) {
                    if (picture == null) continue;
                    if (id > FilingExtract.MAX_PICTURES) break;
                    String place = pack.ticker() + " · " + kind.label() + " · "
                            + (picture.place() == null ? "" : picture.place());
                    merged.add(new FilingExtract.Picture(
                            id++, place, picture.caption(), picture.mime(),
                            picture.bytes(), picture.width(), picture.height()));
                }
            }
            body.append('\n');
        }
        return new Packed(String.join(" · ", names), String.join(",", tickers), tickers,
                body.toString().strip(), List.copyOf(merged));
    }

    static List<CompanyUpload> presentCompanies(List<CompanyUpload> uploads) {
        List<CompanyUpload> companies = new ArrayList<>();
        if (uploads != null) {
            for (CompanyUpload upload : uploads) {
                if (upload != null && upload.used()) companies.add(upload);
            }
        }
        if (companies.isEmpty()) {
            throw new IllegalArgumentException("请至少填一家公司的标的，并上传 10-Q、电话会纪要、一致预期表");
        }
        if (companies.size() > MAX_COMPANIES) {
            throw new IllegalArgumentException("一次最多对照 " + MAX_COMPANIES + " 家公司");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (CompanyUpload company : companies) {
            String ticker = normalizeOneTicker(company.ticker());
            if (!seen.add(ticker)) {
                throw new IllegalArgumentException("两家公司不要用同一个标的");
            }
            if (!hasFile(company.tenQ()) || !hasFile(company.transcript()) || !hasFile(company.consensus())) {
                throw new IllegalArgumentException(ticker + " 需要同时上传 10-Q、电话会纪要、一致预期表");
            }
        }
        return companies;
    }

    private static CompanyPack readCompany(CompanyUpload company) {
        String ticker = normalizeOneTicker(company.ticker());
        KindPack tenQ = readKind(company.tenQ(), "10-Q / 业绩稿");
        KindPack transcript = readKind(company.transcript(), "电话会纪要");
        KindPack consensus = readKind(company.consensus(), "一致预期");
        return new CompanyPack(ticker, List.of(tenQ, transcript, consensus));
    }

    private static KindPack readKind(MultipartFile file, String label) {
        String name = originalName(file);
        if (!FilingExtract.allowed(name)) {
            throw new IllegalArgumentException("只支持 pdf、excel、docx、doc");
        }
        try {
            FilingExtract.Extracted extracted = FilingExtract.read(name, file.getBytes());
            return new KindPack(label, name, extracted.text(), extracted.pictures());
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage() == null ? "这份文件读不出来" : e.getMessage(), e);
        }
    }

    static String originalName(MultipartFile file) {
        String raw = file == null ? "" : file.getOriginalFilename();
        if (raw == null || raw.isBlank()) return "filing.pdf";
        String name = Path.of(raw.replace('\\', '/')).getFileName().toString().strip();
        return name.isBlank() ? "filing.pdf" : name;
    }

    static String normalizeTicker(String ticker) {
        return String.join(",", parseTickers(ticker));
    }

    static List<String> parseTickers(String ticker) {
        String raw = ticker == null ? "" : ticker.strip();
        if (raw.isBlank()) return List.of();
        Set<String> symbols = new LinkedHashSet<>();
        for (String part : raw.split("[,，;；/\\s]+")) {
            if (part == null || part.isBlank()) continue;
            symbols.add(normalizeOneTicker(part));
            if (symbols.size() > MAX_COMPANIES) {
                throw new IllegalArgumentException("标的一次最多 " + MAX_COMPANIES + " 个");
            }
        }
        return List.copyOf(symbols);
    }

    static String normalizeOneTicker(String ticker) {
        String value = ticker == null ? "" : ticker.strip().toUpperCase();
        if (value.isBlank() || !TICKER.matcher(value).matches()) {
            throw new IllegalArgumentException("标的写成 GOOGL 或 MSFT 这种即可");
        }
        return value;
    }

    static boolean hasFile(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    public record CompanyUpload(String ticker, MultipartFile tenQ, MultipartFile transcript, MultipartFile consensus) {
        boolean used() {
            return (ticker != null && !ticker.isBlank())
                    || hasFile(tenQ) || hasFile(transcript) || hasFile(consensus);
        }
    }

    record KindPack(String label, String name, String text, List<FilingExtract.Picture> pictures) {
        KindPack {
            label = label == null ? "" : label;
            name = name == null ? "" : name;
            text = text == null ? "" : text;
            pictures = pictures == null ? List.of() : List.copyOf(pictures);
        }

        KindPack truncated(int totalChars) {
            int keep = Math.max(4_000, (int) ((long) text.length() * MAX_TOTAL_CHARS / Math.max(totalChars, 1)));
            if (text.length() <= keep) return this;
            return new KindPack(label, name, text.substring(0, keep) + "\n…（后文已截断）", pictures);
        }
    }

    record CompanyPack(String ticker, List<KindPack> kinds) {
        CompanyPack {
            ticker = ticker == null ? "" : ticker;
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
        }

        int chars() {
            int total = 0;
            for (KindPack kind : kinds) total += kind.text().length();
            return total;
        }

        CompanyPack truncated(int totalChars) {
            List<KindPack> cut = new ArrayList<>();
            for (KindPack kind : kinds) cut.add(kind.truncated(totalChars));
            return new CompanyPack(ticker, cut);
        }
    }

    record Packed(String originalName, String tickerLine, List<String> tickers, String text,
                  List<FilingExtract.Picture> pictures) {
        Packed {
            originalName = originalName == null ? "" : originalName;
            tickerLine = tickerLine == null ? "" : tickerLine;
            tickers = tickers == null ? List.of() : List.copyOf(tickers);
            text = text == null ? "" : text;
            pictures = pictures == null ? List.of() : List.copyOf(pictures);
        }
    }
}
