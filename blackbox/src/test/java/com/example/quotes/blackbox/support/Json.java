package com.example.quotes.blackbox.support;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions over the raw JSON text.
 *
 * <p>The suite deliberately never deserialises into the application's types - it cannot, and that
 * independence is what lets it catch a contract drift rather than inherit it. So assertions read the
 * bytes the service actually sent.
 *
 * <p>{@link #hasNumberToken} exists because a value comparison is not enough for money. Jackson
 * serialises {@code 10425.00} and {@code 10425.0} to different bytes, and a downstream consumer that
 * parses strictly will notice; a numeric equality check would not. Comparing the token catches it.
 */
public final class Json {

    private Json() {
    }

    /** Removes insignificant whitespace so a comparison is about content, not formatting. */
    public static String flatten(String json) {
        return json.replaceAll("\\s+", "");
    }

    /**
     * Asserts the JSON contains this field with exactly this numeric token, scale included, so
     * {@code 10425.00} does not satisfy an expectation of {@code 10425.0}.
     */
    public static void hasNumberToken(String json, String field, String expectedToken) {
        new BigDecimal(expectedToken);
        assertThat(flatten(json))
                .as("field '%s' should appear as the token %s", field, expectedToken)
                .contains("\"" + field + "\":" + expectedToken);
    }

    /** Asserts a field is absent - used to prove a rejected request did not echo anything back. */
    public static void hasNoField(String json, String field) {
        assertThat(flatten(json)).doesNotContain("\"" + field + "\":");
    }
}
