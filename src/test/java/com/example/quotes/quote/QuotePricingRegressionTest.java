package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;

import com.example.quotes.rate.StubRateGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * UNIT tier, regression purpose: these values were correct when they were frozen. If a change
 * makes one fail you have re-priced an existing product, which is a commercial decision rather
 * than a refactor - so the failure should be reviewed, not "fixed" by editing the expectation.
 */
@DisplayName("pricing regression anchors")
class QuotePricingRegressionTest {

    private final QuoteCalculator calculator = new QuoteCalculator();

    @ParameterizedTest(name = "golden: {0} at {1}% over {2} months = {3}")
    @CsvSource({
            " 10000.00,  4.25,  12,  10425.00",
            " 10000.00,  5.00,  12,  10500.00",
            " 25000.00,  3.75,  60,  29687.50",
            "   500.00, 12.00,   6,    530.00",
            "   999.99,  0.99,   3,   1002.46",
            "123456.78,  7.99,  48, 162913.57",
    })
    void frozen_prices_do_not_drift(String amount, String annualPercentage, int termMonths,
            String expectedTotal) {
        BigDecimal total = this.calculator.totalFor(new BigDecimal(amount), new BigDecimal(annualPercentage),
                termMonths);

        assertThat(total).isEqualTo(new BigDecimal(expectedTotal));
    }

    @Test
    void the_use_case_keeps_its_frozen_shape_for_a_known_command() {
        QuoteRepository repository = mock(QuoteRepository.class);
        given(repository.save(any(Quote.class))).willAnswer(invocation -> invocation.getArgument(0));
        QuoteService service = new QuoteService(StubRateGateway.returning("4.25"), new QuoteCalculator(),
                repository, Clock.fixed(QuoteTestData.FROZEN_NOW, ZoneOffset.UTC));

        QuoteResponse response = service.createQuote(
                QuoteTestData.request("golden-customer", "WIDGET", "10000.00", "USD", 12));

        assertThat(response).usingRecursiveComparison()
                .ignoringFields("quoteId")
                .isEqualTo(new QuoteResponse(null, "golden-customer", "WIDGET", new BigDecimal("10000.00"), "USD",
                        12, new BigDecimal("4.25"), new BigDecimal("10425.00"), QuoteTestData.FROZEN_NOW));
        assertThat(response.quoteId()).isNotNull();
    }
}
