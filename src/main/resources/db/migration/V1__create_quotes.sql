CREATE TABLE quotes
(
    id                  UUID                        NOT NULL,
    customer_id         VARCHAR(64)                 NOT NULL,
    product_code        VARCHAR(32)                 NOT NULL,
    amount              NUMERIC(19, 4)              NOT NULL,
    currency            VARCHAR(3)                  NOT NULL,
    term_months         INTEGER                     NOT NULL,
    annual_rate_percent NUMERIC(9, 4)               NOT NULL,
    total               NUMERIC(19, 4)              NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version             BIGINT                      NOT NULL DEFAULT 0,
    CONSTRAINT pk_quotes PRIMARY KEY (id)
);

CREATE INDEX idx_quotes_customer_id ON quotes (customer_id);
