package com.example.quotes.blackbox.support;

import java.util.UUID;

import io.cucumber.spring.ScenarioScope;
import io.restassured.response.Response;
import org.springframework.stereotype.Component;

/**
 * Per-scenario state: the response we last received, the quote we last created, and an identifier
 * unique to this scenario.
 *
 * <p>The unique {@code customerId} is how scenarios stay independent. Every scenario writes its own
 * customer's rows and asserts only on those, so rows left behind by earlier scenarios cannot make a
 * later one fail - and nothing ever needs deleting.
 *
 * <p>{@code @ScenarioScope} means a fresh instance per scenario, so no state leaks between them
 * either. Cucumber injects a scoped proxy, which is why the field types are ordinary.
 */
@Component
@ScenarioScope
public class ScenarioContext {

    private final String customerId = "bb-" + UUID.randomUUID();

    private Response response;

    private UUID quoteId;

    private String createdJson;

    public String customerId() {
        return this.customerId;
    }

    public Response response() {
        return this.response;
    }

    public void response(Response response) {
        this.response = response;
    }

    public UUID quoteId() {
        return this.quoteId;
    }

    public void quoteId(UUID quoteId) {
        this.quoteId = quoteId;
    }

    public String createdJson() {
        return this.createdJson;
    }

    public void createdJson(String createdJson) {
        this.createdJson = createdJson;
    }
}
