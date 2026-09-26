package com.example.quotes.rate;

/**
 * Outbound port for the rate service.
 *
 * <p>The quote use case depends on this interface, not on the HTTP adapter, so unit tests
 * can substitute a fake without touching the network.
 */
public interface RateGateway {

    /**
     * @return the rate for the given product and currency
     * @throws RateUnavailableException if the rate could not be obtained
     */
    Rate rateFor(String productCode, String currency);
}
