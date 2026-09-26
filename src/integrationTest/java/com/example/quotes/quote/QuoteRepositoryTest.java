package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION tier: persistence slice. Starts JPA + the datasource + Flyway, but no web layer.
 * Also proves the Flyway migration and the entity mapping still agree.
 */
@DataJpaTest
@DisplayName("QuoteRepository")
class QuoteRepositoryTest {

    @Autowired
    private QuoteRepository repository;

    @Test
    void round_trips_every_column_including_scale_and_offset() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-15T10:30:00.123456Z");
        Quote quote = Quote.create(id, "cust-1", "WIDGET", new BigDecimal("12345.6789"), "USD", 24,
                new BigDecimal("4.2500"), new BigDecimal("13744.1300"), createdAt);

        this.repository.saveAndFlush(quote);
        Quote reloaded = this.repository.findById(id).orElseThrow();

        assertThat(reloaded.getCustomerId()).isEqualTo("cust-1");
        assertThat(reloaded.getProductCode()).isEqualTo("WIDGET");
        assertThat(reloaded.getAmount()).isEqualByComparingTo("12345.6789");
        assertThat(reloaded.getCurrency()).isEqualTo("USD");
        assertThat(reloaded.getTermMonths()).isEqualTo(24);
        assertThat(reloaded.getAnnualRatePercent()).isEqualByComparingTo("4.2500");
        assertThat(reloaded.getTotal()).isEqualByComparingTo("13744.1300");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void finds_nothing_for_an_unknown_id() {
        assertThat(this.repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void keeps_two_quotes_for_the_same_customer_apart() {
        Quote first = quoteFor("cust-1", "100.00");
        Quote second = quoteFor("cust-1", "200.00");

        this.repository.saveAndFlush(first);
        this.repository.saveAndFlush(second);

        assertThat(this.repository.findAll())
                .extracting(Quote::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(this.repository.findById(first.getId()).orElseThrow().getTotal())
                .isEqualByComparingTo("100.00");
    }

    private static Quote quoteFor(String customerId, String total) {
        return Quote.create(UUID.randomUUID(), customerId, "WIDGET", new BigDecimal(total), "USD", 12,
                new BigDecimal("5.0000"), new BigDecimal(total), Instant.parse("2026-01-15T10:30:00Z"));
    }
}
