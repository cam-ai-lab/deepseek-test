package com.example.quotes.blackbox.steps;

import java.math.BigDecimal;

import com.example.quotes.blackbox.support.QuoteDb;
import com.example.quotes.blackbox.support.ScenarioContext;
import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions about what reached the database.
 *
 * <p>These read through the {@code blackbox_reader} role, which can only {@code SELECT}, so a step
 * here physically cannot set up state - setup goes through the API. They exist for the questions the
 * API cannot answer: that nothing was persisted, and that a stored number kept its scale.
 */
public class PersistenceSteps {

    @Autowired
    private QuoteDb quoteDb;

    @Autowired
    private ScenarioContext scenario;

    /**
     * The stored value is compared with {@code BigDecimal.equals}, which is scale-sensitive, so
     * {@code 10425.0000} does not satisfy an expectation of {@code 10425.00}. The column is
     * {@code NUMERIC(19,4)}, so the stored scale is part of what this asserts.
     */
    @Then("the stored quote has total {string} and rate {string}")
    public void storedQuoteHasTotalAndRate(String expectedTotal, String expectedRate) {
        String customerId = this.scenario.customerId();
        assertThat(this.quoteDb.storedTotalFor(customerId)).isEqualByComparingTo(new BigDecimal(expectedTotal));
        assertThat(this.quoteDb.storedTotalFor(customerId).scale())
                .as("stored totals keep the column's scale")
                .isEqualTo(new BigDecimal(expectedTotal).scale());
        assertThat(this.quoteDb.storedRateFor(customerId)).isEqualByComparingTo(new BigDecimal(expectedRate));
        assertThat(this.quoteDb.storedRateFor(customerId).scale())
                .as("stored rates keep the column's scale")
                .isEqualTo(new BigDecimal(expectedRate).scale());
    }

    @Then("the stored quote is for {int} months")
    public void storedQuoteIsForMonths(int termMonths) {
        assertThat(this.quoteDb.storedTermMonthsFor(this.scenario.customerId())).isEqualTo(termMonths);
    }

    @Then("no quote is stored for my customer")
    public void noQuoteIsStored() {
        assertThat(this.quoteDb.countFor(this.scenario.customerId())).isZero();
    }

    @Then("{int} quote(s) is/are stored for my customer")
    public void quotesAreStoredForMyCustomer(int expected) {
        assertThat(this.quoteDb.countFor(this.scenario.customerId())).isEqualTo(expected);
    }
}
