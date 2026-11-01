-- Inc 4: payment-service (spec §6.3). One payment per order; the amount always comes from commerce's order.total.

CREATE TABLE payments (
    id            uuid          PRIMARY KEY,
    order_id      uuid          NOT NULL UNIQUE,
    order_number  varchar(20)   NOT NULL,
    provider      varchar(16)   NOT NULL CHECK (provider IN ('STRIPE')),
    session_id    varchar(255)  NOT NULL UNIQUE,
    redirect_url  text          NOT NULL,
    amount        numeric(10,2) NOT NULL CHECK (amount > 0),
    currency      char(3)       NOT NULL,
    status        varchar(16)   NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED', 'AMOUNT_MISMATCH')),
    provider_ref  varchar(255),                       -- Stripe PaymentIntent id once paid
    expires_at    timestamptz   NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now()
);

-- Webhook idempotency: Stripe retries deliveries; each event id is processed once.
CREATE TABLE webhook_events (
    event_id     varchar(255) PRIMARY KEY,
    type         varchar(80)  NOT NULL,
    received_at  timestamptz  NOT NULL DEFAULT now()
);

-- Transactional outbox for payment.events
CREATE TABLE outbox_events (
    id            uuid         PRIMARY KEY,
    topic         varchar(80)  NOT NULL,
    aggregate_id  varchar(64)  NOT NULL,
    type          varchar(60)  NOT NULL,
    payload       jsonb        NOT NULL,
    occurred_at   timestamptz  NOT NULL,
    published_at  timestamptz
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;
