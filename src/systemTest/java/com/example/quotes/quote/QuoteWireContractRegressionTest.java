package com.example.quotes.quote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.example.quotes.testsupport.RateServiceStub;
import com.example.quotes.testsupport.SystemTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM tier, regression purpose: the external contract is frozen in
 * {@code testFixtures/resources/golden/quote-response.json}. Any consumer parsing this JSON is
 * affected by a change here, including the scale of the numbers.
 *
 * <p>This class shares one ApplicationContext with {@link QuoteApiIntegrationTest}, because both
 * inherit the same canonical configuration and neither adds a {@code @MockitoBean}, a
 * {@code @TestConfiguration} or its own {@code @DynamicPropertySource} - all of which are part of
 * the context cache key and would silently split it. The build's context budget guard prints the
 * evidence.
 */
@DisplayName("wire contract regression")
class QuoteWireContractRegressionTest extends SystemTestBase {

    private static final String GOLDEN_COMMAND = """
            {"customerId":"golden-customer","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}
            """;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void the_json_body_for_a_known_command_matches_the_golden_file() throws IOException {
        RateServiceStub.reset();
        RateServiceStub.stubRate("WIDGET", "4.25");

        String actual = this.rest.postForEntity("/api/v1/quotes", jsonEntity(GOLDEN_COMMAND), String.class)
                .getBody();

        assertThat(flatten(actual)).isEqualTo(flatten(goldenFile()));
    }

    private static String goldenFile() throws IOException {
        try (InputStream in = QuoteWireContractRegressionTest.class.getClassLoader()
                .getResourceAsStream("golden/quote-response.json")) {
            if (in == null) {
                throw new IOException("golden/quote-response.json is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Compares content rather than formatting, and blanks the values that legitimately differ on
     * every run. Insensitive to whitespace but sensitive to number scale, so 10425.00 quietly
     * becoming 10425.0 is still caught.
     */
    private static String flatten(String json) {
        return json.replaceAll("\\s+", "")
                .replaceAll("\"quoteId\":\"[^\"]*\"", "\"quoteId\":\"<quoteId>\"")
                .replaceAll("\"createdAt\":\"[^\"]*\"", "\"createdAt\":\"<createdAt>\"");
    }
}
