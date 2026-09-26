package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.quotes.rate.RateUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INTEGRATION tier: web slice. Only the controller and the web layer are started, so this stays
 * fast while still exercising JSON binding, bean validation and error mapping.
 */
@WebMvcTest(QuoteController.class)
@DisplayName("POST /api/v1/quotes")
class QuoteControllerTest {

    private static final String VALID_COMMAND = """
            {"customerId":"cust-1","productCode":"WIDGET","amount":1000.00,"currency":"USD","termMonths":12}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteService quoteService;

    @Test
    void creates_a_quote_and_points_at_it_with_a_location_header() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        given(this.quoteService.createQuote(any())).willReturn(new QuoteResponse(id, "cust-1", "WIDGET",
                new BigDecimal("1000.00"), "USD", 12, new BigDecimal("5.0000"), new BigDecimal("1050.00"),
                Instant.parse("2026-01-15T10:30:00Z")));

        this.mockMvc
                .perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(VALID_COMMAND))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/quotes/" + id)))
                .andExpect(jsonPath("$.quoteId").value(id.toString()))
                .andExpect(jsonPath("$.customerId").value("cust-1"))
                .andExpect(jsonPath("$.annualRatePercent").value(5.0))
                .andExpect(jsonPath("$.total").value(1050.00));
    }

    @Test
    void rejects_a_command_violating_the_validation_rules() throws Exception {
        String invalid = """
                {"customerId":"","productCode":"WIDGET","amount":0,"currency":"USD","termMonths":0}
                """;

        this.mockMvc
                .perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());

        then(this.quoteService).shouldHaveNoInteractions();
    }

    @Test
    void rejects_a_body_that_is_not_valid_json() throws Exception {
        this.mockMvc
                .perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());

        then(this.quoteService).shouldHaveNoInteractions();
    }

    @Test
    void reports_an_unreachable_rate_service_as_503() throws Exception {
        given(this.quoteService.createQuote(any()))
                .willThrow(new RateUnavailableException("rate service is down"));

        this.mockMvc
                .perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(VALID_COMMAND))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Rate service unavailable"))
                .andExpect(jsonPath("$.type").value("urn:problem:rate-unavailable"))
                .andExpect(jsonPath("$.detail").exists());
    }
}
