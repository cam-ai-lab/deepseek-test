package com.example.quotes.blackbox.support;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only access to the database, for the few assertions the API cannot make.
 *
 * <p>The connection uses the {@code blackbox_reader} role, which the database grants only
 * {@code SELECT}. A step that tried to write would fail at the database, so "SQL is for assertions
 * only" is enforced by PostgreSQL rather than by review.
 *
 * <p>Prefer asserting through the API. Reach for this when the API genuinely cannot show the answer:
 * that nothing was persisted, or that a stored number kept its scale.
 */
public class QuoteDb {

    private final JdbcTemplate jdbc;

    public QuoteDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countFor(String customerId) {
        Integer count = this.jdbc.queryForObject("SELECT count(*) FROM quotes WHERE customer_id = ?",
                Integer.class, customerId);
        return count == null ? 0 : count;
    }

    public BigDecimal storedTotalFor(String customerId) {
        return this.jdbc.queryForObject("SELECT total FROM quotes WHERE customer_id = ?", BigDecimal.class,
                customerId);
    }

    public BigDecimal storedRateFor(String customerId) {
        return this.jdbc.queryForObject("SELECT annual_rate_percent FROM quotes WHERE customer_id = ?",
                BigDecimal.class, customerId);
    }

    public int storedTermMonthsFor(String customerId) {
        Integer term = this.jdbc.queryForObject("SELECT term_months FROM quotes WHERE customer_id = ?",
                Integer.class, customerId);
        return term == null ? 0 : term;
    }
}
