package com.example.quotes.quote;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Pure quote arithmetic: no Spring context, no clock, no I/O. Because it is pure it is also
 * the cheapest thing in the codebase to test exhaustively.
 */
@Component
class QuoteCalculator {

    private static final int MONEY_SCALE = 2;

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    /**
     * Simple-interest quote: {@code total = amount * (1 + annualPercentage/100 * termMonths/12)},
     * rounded half-up to whole cents.
     */
    BigDecimal totalFor(BigDecimal amount, BigDecimal annualPercentage, int termMonths) {
        BigDecimal interest = amount.multiply(annualPercentage)
                .multiply(BigDecimal.valueOf(termMonths))
                .divide(MONTHS_PER_YEAR.multiply(PERCENT), MathContext.DECIMAL64);
        return amount.add(interest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
