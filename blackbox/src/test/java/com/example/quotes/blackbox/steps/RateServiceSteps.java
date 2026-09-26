package com.example.quotes.blackbox.steps;

import java.math.BigDecimal;

import com.example.quotes.blackbox.support.RateStub;
import io.cucumber.java.en.Given;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Programs the stubbed upstream. These are {@code Given} steps because they describe the world the
 * scenario starts in, not something the service does.
 */
public class RateServiceSteps {

    @Autowired
    private RateStub rateStub;

    @Given("the rate service quotes {bigdecimal}% for product {string} in {string}")
    public void quotesPercentageForProduct(BigDecimal annualPercentage, String productCode, String currency) {
        // The currency is accepted for readability - the stub answers any currency, and asserting
        // that the application asked for the right one is the job of a separate step.
        this.rateStub.quotes(productCode, annualPercentage.toPlainString());
    }

    @Given("the rate service quotes {bigdecimal} for product {string}")
    public void quotesForProduct(BigDecimal annualPercentage, String productCode) {
        this.rateStub.quotes(productCode, annualPercentage.toPlainString());
    }

    @Given("the rate service answers {int} for product {string}")
    public void answersStatusForProduct(int status, String productCode) {
        this.rateStub.answers(productCode, status);
    }

    @Given("the rate service takes {int} seconds to answer for product {string}")
    public void takesSecondsForProduct(int seconds, String productCode) {
        this.rateStub.takes(seconds, productCode);
    }
}
