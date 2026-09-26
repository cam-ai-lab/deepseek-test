package com.example.quotes.blackbox.support;

import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Programs the stubbed rate service through WireMock's admin API.
 *
 * <p>The stub runs as a container alongside the application, so these are real HTTP calls to a real
 * (if fake) service. The application has no idea it is not the genuine article - which is the point:
 * the suite controls exactly what the upstream says, including how it fails and how slowly.
 *
 * <p>Stubs are global to the container rather than per scenario, so scenarios run serially and the
 * suite resets between them.
 */
public class RateStub {

    private final WireMock wireMock;

    public RateStub(WireMock wireMock) {
        this.wireMock = wireMock;
    }

    /** Answers with a rate for this product. */
    public void quotes(String productCode, String annualPercentage) {
        this.wireMock.register(get(urlPathEqualTo("/rates/" + productCode)).willReturn(okJson("""
                {"productCode":"%s","annualPercentage":%s,"currency":"USD"}
                """.formatted(productCode, annualPercentage))));
    }

    /** Answers with an error status, e.g. 500 or 503. */
    public void answers(String productCode, int status) {
        this.wireMock.register(get(urlPathEqualTo("/rates/" + productCode))
                .willReturn(aResponse().withStatus(status)));
    }

    /** Answers correctly, but only after a delay - for exercising the client's read timeout. */
    public void takes(int seconds, String productCode) {
        this.wireMock.register(get(urlPathEqualTo("/rates/" + productCode))
                .willReturn(okJson("""
                        {"productCode":"%s","annualPercentage":1.00,"currency":"USD"}
                        """.formatted(productCode)).withFixedDelay(seconds * 1000)));
    }

    /**
     * Clears stubs and the request journal. Called before every scenario, because stubs live on the
     * shared container rather than per scenario.
     */
    public void reset() {
        this.wireMock.resetMappings();
        this.wireMock.resetRequests();
    }

    /** How many times the application asked for this product's rate. */
    public int requestCountFor(String productCode) {
        return this.wireMock.find(getRequestedFor(urlPathEqualTo("/rates/" + productCode))).size();
    }
}
