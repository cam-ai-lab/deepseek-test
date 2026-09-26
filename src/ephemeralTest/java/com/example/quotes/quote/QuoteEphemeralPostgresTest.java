package com.example.quotes.quote;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 4: ephemeral infrastructure. The database is a throwaway Postgres container created for
 * this run and destroyed afterwards, so the tests are identical to production in a way H2 never
 * is — same engine, same migrations, same SQL. Skipped automatically where Docker is missing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("quote API on throwaway Postgres")
class QuoteEphemeralPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    private static final WireMockServer RATE_SERVICE = startRateService();

    private static WireMockServer startRateService() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void pointTheAppAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("app.rate.base-url", RATE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopRateService() {
        RATE_SERVICE.stop();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private QuoteRepository repository;

    @BeforeEach
    void resetStubs() {
        RATE_SERVICE.resetAll();
    }

    @Test
    void really_is_a_postgres_database_and_not_an_in_memory_stand_in() throws SQLException {
        try (Connection connection = this.dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }

    @Test
    void the_flyway_migration_created_the_table_on_postgres() throws SQLException {
        try (Connection connection = this.dataSource.getConnection();
                ResultSet tables = connection.getMetaData().getTables(null, "public", "quotes", null)) {
            assertThat(tables.next()).as("public.quotes should exist").isTrue();
        }
    }

    @Test
    void prices_and_persists_a_quote_against_the_container() {
        RATE_SERVICE.stubFor(get(urlPathEqualTo("/rates/WIDGET")).willReturn(okJson("""
                {"productCode":"WIDGET","annualPercentage":5.00,"currency":"USD"}
                """)));

        ResponseEntity<QuoteResponse> response = this.rest.postForEntity("/api/v1/quotes",
                jsonEntity("""
                        {"customerId":"cust-container","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}
                        """),
                QuoteResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        QuoteResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.total()).isEqualByComparingTo("10500.00");

        Quote persisted = this.repository.findById(body.quoteId()).orElseThrow();
        assertThat(persisted.getCustomerId()).isEqualTo("cust-container");
        assertThat(persisted.getAmount()).isEqualByComparingTo("10000.00");
    }

    private static HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
