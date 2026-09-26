package com.example.quotes.blackbox.steps;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.example.quotes.blackbox.support.Json;
import com.example.quotes.blackbox.support.RateStub;
import com.example.quotes.blackbox.support.ScenarioContext;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verbs of the suite: ask for a quote, read a quote, and look at what came back.
 *
 * <p>Every assertion reads the raw JSON. The suite has no access to the application's response type,
 * so it cannot accidentally agree with it - which is the whole reason a contract drift is caught
 * here rather than inherited.
 */
public class QuoteSteps {

    private static final String QUOTES = "/api/v1/quotes";

    @Autowired
    private RequestSpecification api;

    @Autowired
    private ScenarioContext scenario;

    @Autowired
    private RateStub rateStub;

    @When("I request a quote for {bigdecimal} {word} over {int} month(s) of product {string}")
    public void requestQuote(BigDecimal amount, String currency, int termMonths, String productCode) {
        post(Map.of(
                "productCode", productCode,
                "amount", amount,
                "currency", currency,
                "termMonths", termMonths));
    }

    @When("I request a quote:")
    public void requestQuoteFromTable(DataTable table) {
        Map<String, String> row = table.asMaps().get(0);
        postTyped(row);
    }

    @When("I fetch that quote")
    public void fetchThatQuote() {
        assertThat(this.scenario.quoteId()).as("a quote must have been created first").isNotNull();
        this.scenario.response(given(this.api).get(QUOTES + "/" + this.scenario.quoteId()));
    }

    @When("I fetch a quote that does not exist")
    public void fetchAQuoteThatDoesNotExist() {
        this.scenario.response(given(this.api).get(QUOTES + "/" + UUID.randomUUID()));
    }

    @Then("the response status is {int}")
    public void responseStatusIs(int expected) {
        assertThat(status()).isEqualTo(expected);
    }

    @Then("the response has a Location header pointing at the new quote")
    public void locationHeader() {
        String location = header("Location");
        assertThat(location).isNotNull();
        assertThat(location).endsWith(QUOTES + "/" + this.scenario.quoteId());
    }

    @Then("the quote total is {string}")
    public void quoteTotalIs(String expectedToken) {
        Json.hasNumberToken(body(), "total", expectedToken);
    }

    @Then("the quote amount is {string}")
    public void quoteAmountIs(String expectedToken) {
        Json.hasNumberToken(body(), "amount", expectedToken);
    }

    @Then("the quote rate is {string}")
    public void quoteRateIs(String expectedToken) {
        Json.hasNumberToken(body(), "annualRatePercent", expectedToken);
    }

    /**
     * The whole documented shape, in order, with the two values that legitimately differ per run
     * masked. This is what replaces the golden file: the field set is pinned as tightly as the
     * values are.
     */
    @Then("the response has exactly the documented fields")
    public void responseHasTheDocumentedFields() {
        assertThat(Json.flatten(body())).matches(
                "\\{\"quoteId\":\"[0-9a-f-]{36}\",\"customerId\":\"[^\"]+\",\"productCode\":\"[^\"]+\","
                        + "\"amount\":-?[0-9]+(\\.[0-9]+)?,\"currency\":\"[A-Z]{3}\",\"termMonths\":[0-9]+,"
                        + "\"annualRatePercent\":-?[0-9]+(\\.[0-9]+)?,\"total\":-?[0-9]+(\\.[0-9]+)?,"
                        + "\"createdAt\":\"[^\"]+\"\\}");
    }

    @Then("the fetched JSON equals the created JSON")
    public void fetchedJsonEqualsCreatedJson() {
        assertThat(Json.flatten(body())).isEqualTo(Json.flatten(this.scenario.createdJson()));
    }

    @Then("the response is a problem of type {string} with status {int}")
    public void responseIsAProblemOfType(String type, int expectedStatus) {
        assertThat(status()).isEqualTo(expectedStatus);
        assertThat(body()).contains("\"type\":\"" + type + "\"");
        assertThat(body()).contains("\"title\":");
    }

    /**
     * The stored column is {@code TIMESTAMP(6) WITH TIME ZONE}, so a value read back has six
     * fractional digits. A create response with more than six is echoing the caller's clock rather
     * than the stored value.
     */
    @Then("the created timestamp has microsecond precision")
    public void createdTimestampHasMicrosecondPrecision() {
        String createdAt = response().jsonPath().getString("createdAt");
        assertThat(createdAt).matches(".*\\.\\d{6}Z");
    }

    @Then("the response carries no quote")
    public void responseCarriesNoQuote() {
        Json.hasNoField(body(), "quoteId");
    }

    @Then("the rate service was never asked for product {string}")
    public void rateServiceNeverAsked(String productCode) {
        assertThat(this.rateStub.requestCountFor(productCode)).isZero();
    }

    private void post(Map<String, Object> body) {
        Map<String, Object> command = new HashMap<>(body);
        command.put("customerId", this.scenario.customerId());
        send(command);
    }

    private void postTyped(Map<String, String> row) {
        Map<String, Object> command = new HashMap<>();
        command.put("customerId", this.scenario.customerId());
        command.put("productCode", row.get("productCode"));
        command.put("amount", new BigDecimal(row.get("amount")));
        command.put("currency", row.get("currency"));
        command.put("termMonths", Integer.parseInt(row.get("termMonths")));
        send(command);
    }

    private void send(Map<String, Object> command) {
        Response response = given(this.api).body(command).post(QUOTES);
        this.scenario.response(response);
        if (response.statusCode() == 201) {
            this.scenario.quoteId(UUID.fromString(response.jsonPath().getString("quoteId")));
            this.scenario.createdJson(response.asString());
        }
    }

    private Response response() {
        return this.scenario.response();
    }

    private int status() {
        return response().statusCode();
    }

    private String body() {
        return response().asString();
    }

    private String header(String name) {
        return response().getHeader(name);
    }
}
