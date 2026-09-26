package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The shared vocabulary for building test data.
 *
 * <p>Lives in testFixtures so all four tiers compile against one definition. Without this, every
 * one of several hundred tests restates its own setup and the suite becomes unmaintainable long
 * before it becomes slow.
 */
public final class QuoteTestData {

    public static final Instant FROZEN_NOW = Instant.parse("2026-01-15T10:30:00Z");

    private QuoteTestData() {
    }

    /** A valid command with no surprises, for tests whose subject is something else. */
    public static QuoteRequest request() {
        return request("cust-1", "WIDGET", "10000.00", "USD", 12);
    }

    public static QuoteRequest request(String amount, int termMonths) {
        return request("cust-1", "WIDGET", amount, "USD", termMonths);
    }

    public static QuoteRequest request(String customerId, String productCode, String amount, String currency,
            int termMonths) {
        return new QuoteRequest(customerId, productCode, new BigDecimal(amount), currency, termMonths);
    }

    /** A persisted-looking quote, for tests that need a row without going through the database. */
    public static Quote quote() {
        return quote(UUID.randomUUID(), "cust-1", "WIDGET", "10000.00", "USD", 12, "4.25", "10425.00");
    }

    public static Quote quote(String amount, String annualPercentage, String total) {
        return quote(UUID.randomUUID(), "cust-1", "WIDGET", amount, "USD", 12, annualPercentage, total);
    }

    public static Quote quote(UUID id, String customerId, String productCode, String amount, String currency,
            int termMonths, String annualPercentage, String total) {
        return Quote.create(id, customerId, productCode, new BigDecimal(amount), currency, termMonths,
                new BigDecimal(annualPercentage), new BigDecimal(total), FROZEN_NOW);
    }
}
