package com.example.quotes.rate;

/**
 * Raised when the downstream rate service cannot be reached or answers with something we
 * cannot use. Translated to {@code 503 Service Unavailable} by the web layer.
 */
public class RateUnavailableException extends RuntimeException {

    public RateUnavailableException(String message) {
        super(message);
    }

    public RateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
