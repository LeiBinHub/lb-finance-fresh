package com.lb.financefresh.market;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Saved bilingual release calendar. Redis only remembers whether this copy is still fresh. */
class CalendarStore {

    private final Path file;

    CalendarStore(Path file) {
        this.file = file;
    }

    List<ReleaseCalendar.Item> list() throws Exception {
        if (!Files.exists(file)) return List.of();
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT release_date, name, chinese FROM release_calendar ORDER BY release_date, name");
             ResultSet rows = query.executeQuery()) {
            List<ReleaseCalendar.Item> items = new ArrayList<>();
            while (rows.next()) {
                items.add(new ReleaseCalendar.Item(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
            return items;
        }
    }

    void replace(List<ReleaseCalendar.Item> items) throws Exception {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS release_calendar (
                          release_date TEXT NOT NULL,
                          name TEXT NOT NULL,
                          chinese TEXT NOT NULL
                        )
                        """);
                statement.executeUpdate("DELETE FROM release_calendar");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO release_calendar(release_date, name, chinese) VALUES (?, ?, ?)")) {
                for (ReleaseCalendar.Item item : items) {
                    insert.setString(1, item.date());
                    insert.setString(2, item.name());
                    insert.setString(3, item.chinese());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }
}
