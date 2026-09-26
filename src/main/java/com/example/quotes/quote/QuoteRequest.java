package com.example.quotes.quote;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The command received by {@code POST /api/v1/quotes}. Validation lives here so an invalid
 * command never reaches the service or the rate service.
 */
public record QuoteRequest(
        @NotBlank @Size(max = 64) String customerId,

        @NotBlank @Size(max = 32) String productCode,

        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,

        @NotBlank @Size(min = 3, max = 3) String currency,

        @Min(1) @Max(600) int termMonths) {
}
