package com.example.quotes.quote;

import java.util.UUID;

/**
 * Raised when a quote is asked for by an id that does not exist. Translated to {@code 404} by the
 * web layer.
 */
public class QuoteNotFoundException extends RuntimeException {

    public QuoteNotFoundException(UUID quoteId) {
        super("No quote with id " + quoteId);
    }
}
