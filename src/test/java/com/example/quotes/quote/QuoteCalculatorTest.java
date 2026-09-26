package com.example.quotes.quote;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 1: pure unit tests. No Spring, no database, no clock — so these run in milliseconds
 * and can afford to be exhaustive about arithmetic and rounding.
 */
class QuoteCalculatorTest {

    private final QuoteCalculator calculator = new QuoteCalculator();

    @Nested
    @DisplayName("simple interest")
    class SimpleInterest {

        @ParameterizedTest(name = "{0} at {1}% over {2} months = {3}")
        @CsvSource({
                "10000.00, 5.00,   12, 10500.00",
                "10000.00, 4.25,   24, 10850.00",
                " 1500.55, 3.75,   18,  1584.96",
                "  123.4567, 1.50,  7,   124.54",
                "    0.01, 100.00,  1,     0.01",
        })
        void scales_with_amount_rate_and_term(String amount, String annualPercentage, int termMonths,
                String expectedTotal) {
            BigDecimal total = calculator.totalFor(new BigDecimal(amount), new BigDecimal(annualPercentage),
                    termMonths);

            assertThat(total).isEqualTo(new BigDecimal(expectedTotal));
        }

        @Test
        void charges_nothing_extra_when_the_term_is_zero_months() {
            BigDecimal total = calculator.totalFor(new BigDecimal("1000.00"), new BigDecimal("12.5"), 0);

            assertThat(total).isEqualTo(new BigDecimal("1000.00"));
        }

        @Test
        void rounds_half_up_so_a_half_cent_goes_to_the_customer() {
            // 10.00 * 0.6% for 1 month = 0.005 interest -> exactly a half cent.
            // HALF_UP gives 10.01; banker's rounding would wrongly give 10.00.
            BigDecimal total = calculator.totalFor(new BigDecimal("10.00"), new BigDecimal("0.6"), 1);

            assertThat(total).isEqualTo(new BigDecimal("10.01"));
        }
    }

    @Nested
    @DisplayName("result shape")
    class ResultShape {

        @Test
        void always_returns_two_decimal_places() {
            BigDecimal total = calculator.totalFor(new BigDecimal("100"), BigDecimal.ZERO, 12);

            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        void is_deterministic_across_repeated_calls() {
            BigDecimal first = calculator.totalFor(new BigDecimal("999.99"), new BigDecimal("7.35"), 36);
            BigDecimal second = calculator.totalFor(new BigDecimal("999.99"), new BigDecimal("7.35"), 36);

            assertThat(first).isEqualTo(second);
        }
    }
}
