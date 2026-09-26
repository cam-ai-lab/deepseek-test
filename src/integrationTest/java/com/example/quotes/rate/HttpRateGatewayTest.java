package com.example.quotes.rate;

import java.net.URI;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INTEGRATION tier: adapter/contract test. A real HTTP server is stubbed so the wire format,
 * status handling and timeouts are exercised without touching the network.
 */
@DisplayName("HttpRateGateway")
class HttpRateGatewayTest {

    private static WireMockServer server;

    private HttpRateGateway gateway;

    @BeforeAll
    static void startStubServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopStubServer() {
        server.stop();
    }

    @BeforeEach
    void buildGatewayAgainstTheStub() {
        server.resetAll();
        RateProperties properties = new RateProperties(URI.create(server.baseUrl()), Duration.ofSeconds(2),
                Duration.ofMillis(400));
        this.gateway = new HttpRateGateway(RestClient.builder(), properties);
    }

    @Test
    void maps_the_upstream_payload_onto_a_rate() {
        server.stubFor(get(urlPathEqualTo("/rates/WIDGET"))
                .withQueryParam("currency", equalTo("USD"))
                .willReturn(okJson("""
                        {"productCode":"WIDGET","annualPercentage":4.25,"currency":"USD"}
                        """)));

        Rate rate = this.gateway.rateFor("WIDGET", "USD");

        assertThat(rate.annualPercentage()).isEqualByComparingTo("4.25");
        assertThat(rate.currency()).isEqualTo("USD");
        server.verify(getRequestedFor(urlPathEqualTo("/rates/WIDGET")).withQueryParam("currency",
                equalTo("USD")));
    }

    @Test
    void ignores_unknown_fields_in_the_upstream_payload() {
        server.stubFor(get(urlPathEqualTo("/rates/WIDGET")).willReturn(okJson("""
                {"productCode":"WIDGET","annualPercentage":4.25,"currency":"USD","internalNote":"ignore me"}
                """)));

        assertThat(this.gateway.rateFor("WIDGET", "USD").annualPercentage()).isEqualByComparingTo("4.25");
    }

    @Test
    void treats_a_server_error_as_rate_unavailable() {
        server.stubFor(get(urlPathEqualTo("/rates/WIDGET")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> this.gateway.rateFor("WIDGET", "USD"))
                .isInstanceOf(RateUnavailableException.class)
                .hasMessageContaining("WIDGET");
    }

    @Test
    void treats_an_unknown_product_as_rate_unavailable() {
        server.stubFor(get(urlPathEqualTo("/rates/NOPE")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> this.gateway.rateFor("NOPE", "USD"))
                .isInstanceOf(RateUnavailableException.class);
    }

    @Test
    void treats_a_payload_without_a_rate_as_rate_unavailable() {
        server.stubFor(get(urlPathEqualTo("/rates/WIDGET"))
                .willReturn(okJson("{\"productCode\":\"WIDGET\",\"currency\":\"USD\"}")));

        assertThatThrownBy(() -> this.gateway.rateFor("WIDGET", "USD"))
                .isInstanceOf(RateUnavailableException.class);
    }

    @Test
    void gives_up_when_the_rate_service_is_slower_than_the_read_timeout() {
        server.stubFor(get(urlPathEqualTo("/rates/SLOW"))
                .willReturn(okJson("""
                        {"productCode":"SLOW","annualPercentage":1.00,"currency":"USD"}
                        """).withFixedDelay(2_000)));

        assertThatThrownBy(() -> this.gateway.rateFor("SLOW", "USD"))
                .isInstanceOf(RateUnavailableException.class);
    }
}
