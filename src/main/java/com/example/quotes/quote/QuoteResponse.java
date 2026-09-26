package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuoteResponse(
        UUID quoteId,
        String customerId,
        String productCode,
        BigDecimal amount,
        String currency,
        int termMonths,
        BigDecimal annualRatePercent,
        BigDecimal total,
        Instant createdAt) {

    static QuoteResponse from(Quote quote) {
        return new QuoteResponse(quote.getId(), quote.getCustomerId(), quote.getProductCode(), quote.getAmount(),
                quote.getCurrency(), quote.getTermMonths(), quote.getAnnualRatePercent(), quote.getTotal(),
                quote.getCreatedAt());
    }
}
