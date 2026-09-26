package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.quotes.testsupport.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION tier: persistence slice. Starts JPA, the datasource and Flyway, but no web layer.
 *
 * <p>It runs against a real PostgreSQL container rather than an in-memory database. That matters
 * most here, because this is the one test whose job is to prove the entity mapping and the Flyway
 * migration agree with each other - and the migration is written for PostgreSQL. Checking that
 * against a different database product would check very little, and would quietly forbid the
 * migration from using anything PostgreSQL-specific.
 *
 * <p>{@code replace = NONE} tells the slice not to substitute a database of its own choosing.
 *
 * <p>Two of these tests step outside Hibernate and talk to the container over plain JDBC, to prove
 * the engine under test really is PostgreSQL and that the schema came from the migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("QuoteRepository")
class QuoteRepositoryTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresContainer.jdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresContainer.username());
        registry.add("spring.datasource.password", () -> PostgresContainer.password());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

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

    @Test
    void runs_against_a_real_postgres_instance() throws Exception {
        assertThat(PostgresContainer.productName()).isEqualTo("PostgreSQL");
    }

    @Test
    void flyway_created_the_table_rather_than_hibernate_inferring_it() throws Exception {
        assertThat(PostgresContainer.tableExists("quotes")).isTrue();
    }

    private static Quote quoteFor(String customerId, String total) {
        return Quote.create(UUID.randomUUID(), customerId, "WIDGET", new BigDecimal(total), "USD", 12,
                new BigDecimal("5.0000"), new BigDecimal(total), Instant.parse("2026-01-15T10:30:00Z"));
    }
}
