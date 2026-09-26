package com.example.quotes.blackbox.perf;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import com.example.quotes.blackbox.stack.BlackboxStack;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Performance SMOKE test: create a quote, then fetch it, at a modest open-model arrival rate.
 *
 * <p>It catches pathologies - a missing index, an exhausted connection pool, a blocking call that
 * should not be there - not capacity numbers. The stack runs on whatever machine executes it, so the
 * thresholds are deliberately loose and the job is nightly rather than a merge gate.
 */
public class QuoteSmokeSimulation extends Simulation {

    // The stack must be up before the fields below are initialised, because the base URL is read
    // at construction time. A static initialiser runs first.
    static {
        BlackboxStack.ensureStarted();
        URI admin = URI.create(BlackboxStack.wiremockAdminUrl());
        new WireMock(admin.getHost(), admin.getPort()).register(get(urlPathEqualTo("/rates/WIDGET"))
                .willReturn(okJson("{\"productCode\":\"WIDGET\",\"annualPercentage\":4.25}")));
    }

    private final HttpProtocolBuilder protocol = http
            .baseUrl(BlackboxStack.baseUrl())
            .contentTypeHeader("application/json")
            .acceptHeader("application/json");

    private final ScenarioBuilder createThenFetch = scenario("create then fetch")
            .exec(http("create quote")
                    .post("/api/v1/quotes")
                    // A unique customer per virtual user, following the suite's isolation rule.
                    .body(StringBody(session -> """
                            {"customerId":"perf-%s","productCode":"WIDGET","amount":1000.00,
                             "currency":"USD","termMonths":12}
                            """.formatted(UUID.randomUUID())))
                    .check(status().is(201), jsonPath("$.quoteId").saveAs("quoteId")))
            .exec(http("fetch quote")
                    .get("/api/v1/quotes/#{quoteId}")
                    .check(status().is(200)));

    {
        setUp(createThenFetch.injectOpen(
                rampUsersPerSec(1).to(20).during(Duration.ofSeconds(15)),
                constantUsersPerSec(20).during(Duration.ofSeconds(60))))
                .protocols(protocol)
                .assertions(
                        global().failedRequests().percent().is(0.0),
                        global().responseTime().percentile(95.0).lt(300),
                        global().responseTime().percentile(99.0).lt(800));
    }
}
