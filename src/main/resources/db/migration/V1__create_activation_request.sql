CREATE TABLE activation_request (
    id              UUID                     NOT NULL,
    client_id       VARCHAR(100)             NOT NULL,
    external_id     VARCHAR(64)              NOT NULL,
    customer_id     VARCHAR(64)              NOT NULL,
    product_code    VARCHAR(64)              NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    attempts        INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    failure_reason  VARCHAR(200),
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT pk_activation_request PRIMARY KEY (id),
    -- Garantia de unicidade feita pelo banco: é o que resolve a corrida entre requisições simultâneas.
    CONSTRAINT uk_activation_request_client_external UNIQUE (client_id, external_id),
    CONSTRAINT ck_activation_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Worker: busca PENDING/PROCESSING com next_attempt_at vencido.
CREATE INDEX ix_activation_request_status_next_attempt
    ON activation_request (status, next_attempt_at);

-- Listagem: sempre filtrada pelo consumidor (BOLA), opcionalmente por status ou cliente, ordenada por data.
CREATE INDEX ix_activation_request_client_status_created
    ON activation_request (client_id, status, created_at);

CREATE INDEX ix_activation_request_client_customer_created
    ON activation_request (client_id, customer_id, created_at);
