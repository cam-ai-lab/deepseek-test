package com.example.quotes.rate;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP adapter for the rate service. Talks the wire format and translates every transport
 * or payload failure into {@link RateUnavailableException}, so callers never see
 * {@code RestClient} types.
 */
@Component
class HttpRateGateway implements RateGateway {

    private final RestClient client;

    HttpRateGateway(RestClient.Builder builder, RateProperties properties) {
        this.client = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(RateProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Override
    public Rate rateFor(String productCode, String currency) {
        RateResponse response;
        try {
            response = client.get()
                    .uri(uri -> uri.path("/rates/{productCode}")
                            .queryParam("currency", currency)
                            .build(productCode))
                    .retrieve()
                    .body(RateResponse.class);
        }
        catch (RestClientException ex) {
            throw new RateUnavailableException("Rate lookup failed for product " + productCode, ex);
        }
        if (response == null || response.annualPercentage() == null) {
            throw new RateUnavailableException("Rate service returned no rate for product " + productCode);
        }
        return new Rate(response.annualPercentage(), currency);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RateResponse(String productCode, BigDecimal annualPercentage, String currency) {
    }
}
