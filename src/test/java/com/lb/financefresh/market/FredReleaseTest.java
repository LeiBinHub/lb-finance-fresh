package com.lb.financefresh.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FredReleaseTest {

    @Test
    void calendarKeepsReleasesFromTheChosenDay() throws Exception {
        String body = """
                {"release_dates":[
                  {"date":"2026-10-14","release_name":"Yesterday"},
                  {"date":"2026-10-15","release_name":"Retail Sales"},
                  {"date":"2026-10-15","release_name":"FOMC Press Release"},
                  {"date":"2026-10-16","release_name":""}
                ]}
                """;
        List<FredClient.Release> rows = FredClient.releases(new ObjectMapper().readTree(body), LocalDate.of(2026, 10, 15));
        assertEquals(2, rows.size());
        assertEquals("2026-10-15", rows.get(0).date());
        assertEquals("FOMC Press Release", rows.get(0).name());
        assertEquals("Retail Sales", rows.get(1).name());
    }

    @Test
    void maintenanceHtmlIsNotTreatedAsJson() {
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> FredClient.parse("<!doctype html><title>Site Maintenance | St. Louis Fed</title>"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("维护"));
    }
}
