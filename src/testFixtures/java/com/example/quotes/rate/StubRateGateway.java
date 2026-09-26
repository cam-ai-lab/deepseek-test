package com.example.quotes.rate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * A hand-written fake for the {@link RateGateway} port.
 *
 * <p>Preferred over a mocking framework for anything beyond interaction verification: it behaves
 * like the real thing, so a test that uses it is testing an object graph rather than a script. A
 * single-method interface is trivial to fake, which is the payoff for depending on a port instead
 * of on {@code RestClient}.
 */
public final class StubRateGateway implements RateGateway {

    private final BigDecimal defaultRate;

    private final Map<String, BigDecimal> ratesByProduct = new HashMap<>();

    public StubRateGateway(String defaultRate) {
        this.defaultRate = new BigDecimal(defaultRate);
    }

    public static StubRateGateway returning(String annualPercentage) {
        return new StubRateGateway(annualPercentage);
    }

    public StubRateGateway withRate(String productCode, String annualPercentage) {
        this.ratesByProduct.put(productCode, new BigDecimal(annualPercentage));
        return this;
    }

    @Override
    public Rate rateFor(String productCode, String currency) {
        return new Rate(this.ratesByProduct.getOrDefault(productCode, this.defaultRate), currency);
    }
}
