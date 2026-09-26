package com.example.quotes.blackbox.config;

import java.net.URI;

import com.example.quotes.blackbox.stack.BlackboxStack;
import com.example.quotes.blackbox.support.QuoteDb;
import com.example.quotes.blackbox.support.RateStub;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.cucumber.spring.CucumberContextConfiguration;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ContextConfiguration;

/**
 * Dependency injection for the step classes.
 *
 * <p>This is ordinary Spring - {@code @Autowired} in a step class works as the team expects - but it
 * is emphatically <em>not</em> the application's context. Nothing here imports a class from the
 * service; the endpoints, the database and the stub are all reached over the network.
 *
 * <p>{@code @CucumberContextConfiguration} is what tells Cucumber to build this context once per
 * run and inject from it.
 */
@Configuration
@ComponentScan(basePackages = "com.example.quotes.blackbox")
@CucumberContextConfiguration
@ContextConfiguration(classes = BlackboxConfig.class)
public class BlackboxConfig {

    @Bean
    public RequestSpecification api() {
        return new RequestSpecBuilder()
                .setBaseUri(BlackboxStack.baseUrl())
                .setContentType(ContentType.JSON)
                .build();
    }

    @Bean
    public WireMock wireMock() {
        URI admin = URI.create(BlackboxStack.wiremockAdminUrl());
        return new WireMock(admin.getHost(), admin.getPort());
    }

    /**
     * The assertions connection.
     *
     * <p>Read-only is not asserted here: it is granted by the database. The {@code blackbox_reader}
     * role has no write privileges at all, which is a stronger guarantee than any driver-level flag,
     * because it holds even if a step goes around this connection.
     */
    @Bean
    public DriverManagerDataSource assertionDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(BlackboxStack.jdbcUrl());
        dataSource.setUsername("blackbox_reader");
        dataSource.setPassword("blackbox_reader");
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @Bean
    public JdbcTemplate assertionJdbcTemplate(DriverManagerDataSource assertionDataSource) {
        return new JdbcTemplate(assertionDataSource);
    }

    @Bean
    public QuoteDb quoteDb(JdbcTemplate assertionJdbcTemplate) {
        return new QuoteDb(assertionJdbcTemplate);
    }

    @Bean
    public RateStub rateStub(WireMock wireMock) {
        return new RateStub(wireMock);
    }
}
