package com.example.quotes.testsupport;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for black-box tests.
 *
 * <p>The {@code @DynamicPropertySource} method lives here rather than on each test class for a
 * subtle reason: that method is part of the context cache key, and a method declared separately in
 * each class would give each class a different key - so they would boot two contexts instead of
 * sharing one.
 *
 * <p>Note there is deliberately no {@code @AfterAll} stopping the stub server: with several test
 * classes inheriting it, the first class to finish would tear down the server the others still
 * need. The server lives for the JVM.
 */
@FullStackTest
public abstract class SystemTestBase {

    @DynamicPropertySource
    static void externalDependencies(DynamicPropertyRegistry registry) {
        registry.add("app.rate.base-url", () -> RateServiceStub.baseUrl());
        registry.add("spring.datasource.url", () -> PostgresContainer.jdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresContainer.username());
        registry.add("spring.datasource.password", () -> PostgresContainer.password());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    protected static HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
