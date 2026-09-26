package com.example.quotes.quote;

import java.util.UUID;

import com.example.quotes.testsupport.RateServiceStub;
import com.example.quotes.testsupport.SystemTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SYSTEM tier: black box. Real HTTP into a running application, every remote dependency stubbed at
 * the network boundary.
 *
 * <p>Note what this class cannot do: it cannot touch {@code QuoteRepository}. The suite has no
 * Spring Data JPA on its compile classpath, so persistence is verified through the public API.
 * That constraint is the tier doing its job - a black-box test that reaches into the database is
 * not a black-box test, and here the compiler says so.
 */
@DisplayName("quote API, black box")
class QuoteApiIntegrationTest extends SystemTestBase {

    @Autowired
    private TestRestTemplate rest;

    @Value("${local.server.port}")
    private int port;

    @BeforeEach
    void resetStubs() {
        RateServiceStub.reset();
    }

    @Test
    void prices_the_command_with_the_real_rate_lookup_and_persists_it() {
        RateServiceStub.stubRate("WIDGET", "5.00");

        ResponseEntity<QuoteResponse> created = create("""
                {"customerId":"cust-e2e","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}
                """);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        QuoteResponse body = created.getBody();
        assertThat(body).isNotNull();
        assertThat(body.total()).isEqualByComparingTo("10500.00");
        assertThat(body.annualRatePercent()).isEqualByComparingTo("5.00");
        assertThat(created.getHeaders().getLocation()).isNotNull()
                .hasToString("http://localhost:" + this.port + "/api/v1/quotes/" + body.quoteId());

        ResponseEntity<QuoteResponse> fetched = this.rest
                .getForEntity("/api/v1/quotes/" + body.quoteId(), QuoteResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().customerId()).isEqualTo("cust-e2e");
        assertThat(fetched.getBody().total()).isEqualByComparingTo("10500.00");
    }

    @Test
    void reports_404_as_a_problem_detail_for_an_unknown_quote() {
        ResponseEntity<String> response = this.rest.getForEntity("/api/v1/quotes/" + UUID.randomUUID(),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("urn:problem:quote-not-found");
    }

    @Test
    void really_makes_an_http_call_to_the_rate_service() {
        RateServiceStub.stubRate("GIZMO", "1.50");

        create("""
                {"customerId":"cust-e2e","productCode":"GIZMO","amount":100.00,"currency":"EUR","termMonths":6}
                """);

        RateServiceStub.verifyRequested("GIZMO");
    }

    @Test
    void answers_503_when_the_rate_service_is_down() {
        RateServiceStub.stubFailure("WIDGET", 503);

        ResponseEntity<String> response = this.rest.postForEntity("/api/v1/quotes", jsonEntity("""
                {"customerId":"cust-e2e","productCode":"WIDGET","amount":10000.00,"currency":"USD","termMonths":12}
                """), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("urn:problem:rate-unavailable");
        // "Nothing was written" is asserted in the UNIT tier, where the repository is a mock and
        // the failure can be observed directly: QuoteServiceTest.saves_nothing_when_the_rate_...
    }

    @Test
    void answers_400_without_calling_the_rate_service_for_an_invalid_command() {
        ResponseEntity<String> response = this.rest.postForEntity("/api/v1/quotes", jsonEntity("""
                {"customerId":"cust-e2e","productCode":"WIDGET","amount":-5,"currency":"USD","termMonths":12}
                """), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        RateServiceStub.verifyNotRequested("WIDGET");
    }

    private ResponseEntity<QuoteResponse> create(String body) {
        return this.rest.postForEntity("/api/v1/quotes", jsonEntity(body), QuoteResponse.class);
    }
}
