package com.example.quotes.testsupport;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * One WireMock server for the whole run, started lazily and shared.
 *
 * <p>A single static instance means every Spring suite points at the same base URL, which is what
 * lets their contexts be shared.
 *
 * <p>Two deliberate properties, both enforced by {@code verifyTierClasspaths}:
 *
 * <ul>
 * <li>No Spring import. This lives in testFixtures, which the unit tier also compiles against, so
 * a Spring type in a public signature would drag Spring test support onto the unit classpath and
 * break the invariant that unit tests cannot boot a context.
 * <li>No WireMock type in a public signature either. WireMock is a {@code testFixturesImplementation}
 * dependency, not an {@code api} one, so it never reaches a consumer's compile classpath - callers
 * get intent-revealing methods instead of the raw server.
 * </ul>
 */
public final class RateServiceStub {

    private static WireMockServer server;

    private RateServiceStub() {
    }

    private static synchronized WireMockServer server() {
        if (server == null) {
            server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            server.start();
        }
        return server;
    }

    /** The base URL the stub is listening on; Spring suites wire this into their properties. */
    public static String baseUrl() {
        return server().baseUrl();
    }

    public static void stubRate(String productCode, String annualPercentage) {
        server().stubFor(get(urlPathEqualTo("/rates/" + productCode)).willReturn(okJson("""
                {"productCode":"%s","annualPercentage":%s,"currency":"USD"}
                """.formatted(productCode, annualPercentage))));
    }

    public static void stubFailure(String productCode, int status) {
        server().stubFor(get(urlPathEqualTo("/rates/" + productCode))
                .willReturn(aResponse().withStatus(status)));
    }

    public static void reset() {
        server().resetAll();
    }

    public static void verifyRequested(String productCode) {
        server().verify(getRequestedFor(urlPathEqualTo("/rates/" + productCode)));
    }

    public static void verifyNotRequested(String productCode) {
        server().verify(0, getRequestedFor(urlPathEqualTo("/rates/" + productCode)));
    }
}
