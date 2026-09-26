package com.example.quotes.web;

import java.net.URI;

import com.example.quotes.quote.QuoteNotFoundException;
import com.example.quotes.rate.RateUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain failures into RFC 9457 problem responses. Bean-validation failures are
 * already turned into {@code 400} problem details by Spring Boot itself.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RateUnavailableException.class)
    ProblemDetail handleRateUnavailable(RateUnavailableException exception) {
        log.warn("Rate service call failed", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The rate service is currently unavailable, please retry later.");
        problem.setTitle("Rate service unavailable");
        problem.setType(URI.create("urn:problem:rate-unavailable"));
        return problem;
    }

    @ExceptionHandler(QuoteNotFoundException.class)
    ProblemDetail handleQuoteNotFound(QuoteNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Quote not found");
        problem.setType(URI.create("urn:problem:quote-not-found"));
        return problem;
    }
}
