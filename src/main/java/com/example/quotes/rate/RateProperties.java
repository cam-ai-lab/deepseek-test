package com.example.quotes.rate;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the rate service. Bound once at startup and validated,
 * so a typo in {@code application.yml} fails fast instead of at request time.
 */
@Validated
@ConfigurationProperties(prefix = "app.rate")
public record RateProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
