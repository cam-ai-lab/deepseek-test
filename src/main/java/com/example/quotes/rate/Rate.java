package com.example.quotes.rate;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The annual interest rate (as a percentage) offered by the downstream rate service
 * for a given product and currency.
 */
public record Rate(BigDecimal annualPercentage, String currency) {

    public Rate {
        Objects.requireNonNull(annualPercentage, "annualPercentage");
        Objects.requireNonNull(currency, "currency");
    }
}
