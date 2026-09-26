package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.example.quotes.rate.Rate;
import com.example.quotes.rate.RateGateway;
import com.example.quotes.rate.RateUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Tier 1: unit tests for the use case, with the collaborators replaced by mocks. The clock is
 * fixed, so even the timestamp is asserted exactly.
 */
@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");

    @Mock
    private RateGateway rateGateway;

    @Mock
    private QuoteRepository repository;

    private QuoteService service;

    @BeforeEach
    void setUp() {
        service = new QuoteService(rateGateway, new QuoteCalculator(), repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void prices_the_command_with_the_rate_from_the_gateway() {
        given(rateGateway.rateFor("WIDGET", "USD")).willReturn(new Rate(new BigDecimal("5.00"), "USD"));
        given(repository.save(any(Quote.class))).willAnswer(invocation -> invocation.getArgument(0));

        QuoteResponse response = service.createQuote(new QuoteRequest("cust-1", "WIDGET",
                new BigDecimal("10000.00"), "USD", 12));

        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.annualRatePercent()).isEqualByComparingTo("5.00");
        assertThat(response.total()).isEqualByComparingTo("10500.00");
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.quoteId()).isNotNull();
    }

    @Test
    void persists_exactly_what_it_returns() {
        given(rateGateway.rateFor("WIDGET", "USD")).willReturn(new Rate(new BigDecimal("6.25"), "USD"));
        given(repository.save(any(Quote.class))).willAnswer(invocation -> invocation.getArgument(0));

        QuoteResponse response = service.createQuote(new QuoteRequest("cust-1", "WIDGET",
                new BigDecimal("2000.00"), "USD", 6));

        ArgumentCaptor<Quote> saved = ArgumentCaptor.forClass(Quote.class);
        then(repository).should().save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(response.quoteId());
        assertThat(saved.getValue().getTotal()).isEqualByComparingTo(response.total());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void saves_nothing_when_the_rate_service_cannot_be_reached() {
        given(rateGateway.rateFor("WIDGET", "USD"))
                .willThrow(new RateUnavailableException("boom"));

        assertThatThrownBy(() -> service.createQuote(new QuoteRequest("cust-1", "WIDGET",
                new BigDecimal("1000.00"), "USD", 12)))
                .isInstanceOf(RateUnavailableException.class);

        then(repository).should(never()).save(any(Quote.class));
    }

    @Test
    void asks_the_rate_service_for_the_requested_product_and_currency() {
        given(rateGateway.rateFor("GIZMO", "EUR")).willReturn(new Rate(new BigDecimal("1.00"), "EUR"));
        given(repository.save(any(Quote.class))).willAnswer(invocation -> invocation.getArgument(0));

        service.createQuote(new QuoteRequest("cust-9", "GIZMO", new BigDecimal("500.00"), "EUR", 3));

        then(rateGateway).should().rateFor("GIZMO", "EUR");
    }

    @Test
    void reads_back_a_stored_quote_by_id() {
        UUID id = UUID.randomUUID();
        Quote stored = QuoteTestData.quote(id, "cust-1", "WIDGET", "10000.00", "USD", 12, "4.2500",
                "10425.00");
        given(repository.findById(id)).willReturn(Optional.of(stored));

        QuoteResponse response = service.findQuote(id);

        assertThat(response.quoteId()).isEqualTo(id);
        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.total()).isEqualByComparingTo("10425.00");
        assertThat(response.createdAt()).isEqualTo(QuoteTestData.FROZEN_NOW);
    }

    @Test
    void reports_a_missing_quote_as_not_found() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findQuote(id))
                .isInstanceOf(QuoteNotFoundException.class)
                .hasMessageContaining(id.toString());

        then(rateGateway).shouldHaveNoInteractions();
    }

    @Test
    void stores_the_rate_it_actually_used() {
        given(rateGateway.rateFor("WIDGET", "USD")).willReturn(new Rate(new BigDecimal("9.99"), "USD"));
        given(repository.save(any(Quote.class))).willAnswer(invocation -> invocation.getArgument(0));

        QuoteResponse response = service.createQuote(new QuoteRequest("cust-1", "WIDGET",
                new BigDecimal("1000.00"), "USD", 12));

        assertThat(response.annualRatePercent()).isEqualByComparingTo("9.99");
        assertThat(response.quoteId()).isNotNull().isInstanceOf(UUID.class);
    }
}
