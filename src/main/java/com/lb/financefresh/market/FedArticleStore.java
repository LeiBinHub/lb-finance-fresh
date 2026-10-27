package com.lb.financefresh.market;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

/** Saved English excerpt, Chinese translation, and DeepSeek notes for Fed articles. */
class FedArticleStore {

    private final Path file;

    FedArticleStore(Path file) {
        this.file = file;
    }

    synchronized Saved find(String url) throws Exception {
        if (url == null || url.isBlank() || !Files.exists(file)) return null;
        ensure();
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT source, title, url, published, english, chinese, analysis
                     FROM fed_articles WHERE url = ?
                     """)) {
            query.setString(1, url);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                return new Saved(
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4),
                        row.getString(5),
                        row.getString(6),
                        row.getString(7));
            }
        }
    }

    synchronized void save(Saved article) throws Exception {
        if (article == null || article.url() == null || article.url().isBlank()) return;
        ensure();
        try (Connection connection = open();
             PreparedStatement upsert = connection.prepareStatement("""
                     INSERT INTO fed_articles(url, source, title, published, english, chinese, analysis, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(url) DO UPDATE SET
                       source = excluded.source,
                       title = excluded.title,
                       published = excluded.published,
                       english = excluded.english,
                       chinese = excluded.chinese,
                       analysis = excluded.analysis,
                       updated_at = excluded.updated_at
                     """)) {
            upsert.setString(1, article.url());
            upsert.setString(2, nullToEmpty(article.source()));
            upsert.setString(3, nullToEmpty(article.title()));
            upsert.setString(4, nullToEmpty(article.published()));
            upsert.setString(5, nullToEmpty(article.english()));
            upsert.setString(6, nullToEmpty(article.chinese()));
            upsert.setString(7, nullToEmpty(article.analysis()));
            upsert.setString(8, Instant.now().toString());
            upsert.executeUpdate();
        }
    }

    private void ensure() throws Exception {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS fed_articles (
                      url TEXT PRIMARY KEY,
                      source TEXT NOT NULL,
                      title TEXT NOT NULL,
                      published TEXT NOT NULL,
                      english TEXT NOT NULL,
                      chinese TEXT NOT NULL,
                      analysis TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record Saved(String source, String title, String url, String published,
                 String english, String chinese, String analysis) {
        boolean ready() {
            return chinese != null && !chinese.isBlank() && analysis != null && !analysis.isBlank();
        }
    }
}
