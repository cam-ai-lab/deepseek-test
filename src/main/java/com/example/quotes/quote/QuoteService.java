package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.example.quotes.rate.Rate;
import com.example.quotes.rate.RateGateway;
import org.springframework.stereotype.Service;

@Service
class QuoteService {

    private final RateGateway rateGateway;

    private final QuoteCalculator calculator;

    private final QuoteRepository repository;

    private final Clock clock;

    QuoteService(RateGateway rateGateway, QuoteCalculator calculator, QuoteRepository repository, Clock clock) {
        this.rateGateway = rateGateway;
        this.calculator = calculator;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Deliberately <em>not</em> {@code @Transactional}: the remote rate lookup would otherwise
     * hold a database connection open for the length of an HTTP call. The single insert is
     * already transactional inside the repository.
     */
    QuoteResponse createQuote(QuoteRequest request) {
        Rate rate = this.rateGateway.rateFor(request.productCode(), request.currency());
        BigDecimal total = this.calculator.totalFor(request.amount(), rate.annualPercentage(),
                request.termMonths());
        Quote quote = Quote.create(UUID.randomUUID(), request.customerId(), request.productCode(),
                request.amount(), request.currency(), request.termMonths(), rate.annualPercentage(), total,
                this.clock.instant());
        return QuoteResponse.from(this.repository.save(quote));
    }

    /**
     * Read path. Present so the black-box tier can verify what was persisted without reaching into
     * the database - which its classpath would not allow anyway.
     */
    QuoteResponse findQuote(UUID quoteId) {
        return this.repository.findById(quoteId)
                .map(QuoteResponse::from)
                .orElseThrow(() -> new QuoteNotFoundException(quoteId));
    }
}
