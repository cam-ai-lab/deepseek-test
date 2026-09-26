package com.example.quotes.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A persisted quote. Instances are created through {@link #create} and never mutated, which
 * keeps the calculation result and the stored row impossible to drift apart.
 */
@Entity
@Table(name = "quotes")
public class Quote {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "annual_rate_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal annualRatePercent;

    @Column(name = "total", nullable = false, precision = 19, scale = 4)
    private BigDecimal total;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Quote() {
        // required by JPA
    }

    private Quote(UUID id, String customerId, String productCode, BigDecimal amount, String currency,
            int termMonths, BigDecimal annualRatePercent, BigDecimal total, Instant createdAt) {
        this.id = id;
        this.customerId = customerId;
        this.productCode = productCode;
        this.amount = amount;
        this.currency = currency;
        this.termMonths = termMonths;
        this.annualRatePercent = annualRatePercent;
        this.total = total;
        this.createdAt = createdAt;
    }

    static Quote create(UUID id, String customerId, String productCode, BigDecimal amount, String currency,
            int termMonths, BigDecimal annualRatePercent, BigDecimal total, Instant createdAt) {
        return new Quote(id, customerId, productCode, amount, currency, termMonths, annualRatePercent, total,
                createdAt);
    }

    public UUID getId() {
        return this.id;
    }

    public String getCustomerId() {
        return this.customerId;
    }

    public String getProductCode() {
        return this.productCode;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public String getCurrency() {
        return this.currency;
    }

    public int getTermMonths() {
        return this.termMonths;
    }

    public BigDecimal getAnnualRatePercent() {
        return this.annualRatePercent;
    }

    public BigDecimal getTotal() {
        return this.total;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public long getVersion() {
        return this.version;
    }
}
