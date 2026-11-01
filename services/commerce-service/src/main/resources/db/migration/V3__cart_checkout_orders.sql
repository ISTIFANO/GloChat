-- Inc 2 (cart) + Inc 3 (checkout, orders, outbox) — spec §5, §6, §7.

-- ───────────── cart ─────────────
CREATE TABLE customer_addresses (
    id           uuid         PRIMARY KEY,
    customer_id  varchar(64)  NOT NULL,
    label        varchar(60)  NOT NULL,
    line         varchar(200) NOT NULL,
    city         varchar(80)  NOT NULL,
    lat          double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lng          double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customer_addresses_customer ON customer_addresses (customer_id);

CREATE TABLE carts (
    id              uuid         PRIMARY KEY,
    customer_id     varchar(64)  NOT NULL,
    merchant_id     varchar(64)  NULL,                -- null until the first item
    status          varchar(12)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'ABANDONED')),
    version         bigint       NOT NULL DEFAULT 1,  -- optimistic locking, incremented by every change
    delivery_label  varchar(60),
    delivery_line   varchar(200),
    delivery_city   varchar(80),
    delivery_lat    double precision,
    delivery_lng    double precision,
    payment_method  varchar(8)   CHECK (payment_method IN ('CASH', 'CARD')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);
-- one ACTIVE cart per customer
CREATE UNIQUE INDEX uq_carts_one_active_per_customer ON carts (customer_id) WHERE status = 'ACTIVE';

-- no price columns: the cart is re-priced on every read (spec §5)
CREATE TABLE cart_items (
    id          uuid         PRIMARY KEY,
    cart_id     uuid         NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id  varchar(64)  NOT NULL,
    quantity    int          NOT NULL CHECK (quantity BETWEEN 1 AND 50),
    note        varchar(200),
    line_key    varchar(64)  NOT NULL,                -- hash(product + sorted selections + note): merges identical lines
    position    int          NOT NULL,
    UNIQUE (cart_id, line_key)
);

CREATE TABLE cart_item_selections (
    cart_item_id     uuid        NOT NULL REFERENCES cart_items(id) ON DELETE CASCADE,
    option_value_id  varchar(64) NOT NULL,
    PRIMARY KEY (cart_item_id, option_value_id)
);

-- ───────────── checkout ─────────────
CREATE TABLE quotes (
    id              uuid          PRIMARY KEY,
    cart_id         uuid          NOT NULL REFERENCES carts(id),
    cart_version    bigint        NOT NULL,
    customer_id     varchar(64)   NOT NULL,
    merchant_id     varchar(64)   NOT NULL,
    snapshot        jsonb         NOT NULL,           -- frozen lines, unit prices, selections, delivery
    subtotal        numeric(10,2) NOT NULL,
    delivery_fee    numeric(10,2) NOT NULL,
    discount        numeric(10,2) NOT NULL DEFAULT 0,
    total           numeric(10,2) NOT NULL,
    currency        char(3)       NOT NULL,
    payment_method  varchar(8)    NOT NULL CHECK (payment_method IN ('CASH', 'CARD')),
    token_hash      bytea         NOT NULL,           -- SHA-256 of the 128-bit confirmation token (never stored in clear)
    status          varchar(12)   NOT NULL CHECK (status IN ('OPEN', 'CONSUMED', 'EXPIRED', 'INVALIDATED')),
    expires_at      timestamptz   NOT NULL,
    order_id        uuid          UNIQUE,
    created_at      timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_quotes_open_by_cart ON quotes (cart_id) WHERE status = 'OPEN';

CREATE TABLE checkout_requests (
    idempotency_key  uuid         PRIMARY KEY,
    customer_id      varchar(64)  NOT NULL,
    quote_id         uuid         NOT NULL,
    order_id         uuid         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now()
);

-- ───────────── orders ─────────────
CREATE SEQUENCE order_number_seq;

CREATE TABLE orders (
    id                  uuid          PRIMARY KEY,
    number              varchar(20)   NOT NULL UNIQUE,   -- human-readable, e.g. MC-26-000123
    customer_id         varchar(64)   NOT NULL,
    merchant_id         varchar(64)   NOT NULL,
    merchant_name       varchar(120)  NOT NULL,
    merchant_emoji      varchar(16)   NOT NULL,
    merchant_logo_url   varchar(255),
    status              varchar(20)   NOT NULL CHECK (status IN
                          ('AWAITING_PAYMENT', 'PLACED', 'ACCEPTED', 'PREPARING', 'ON_THE_WAY', 'DELIVERED', 'CANCELED')),
    payment_method      varchar(8)    NOT NULL CHECK (payment_method IN ('CASH', 'CARD')),
    payment_status      varchar(20)   NOT NULL CHECK (payment_status IN
                          ('PENDING', 'PAID', 'FAILED', 'EXPIRED', 'CASH_ON_DELIVERY')),
    payment_session_id  varchar(255),
    payment_url         text,
    payment_ref         varchar(255),
    subtotal            numeric(10,2) NOT NULL,
    delivery_fee        numeric(10,2) NOT NULL,
    discount            numeric(10,2) NOT NULL DEFAULT 0,
    total               numeric(10,2) NOT NULL CHECK (total >= 0),
    currency            char(3)       NOT NULL,
    delivery_label      varchar(60)   NOT NULL,
    delivery_line       varchar(200)  NOT NULL,
    delivery_city       varchar(80)   NOT NULL,
    delivery_lat        double precision NOT NULL,
    delivery_lng        double precision NOT NULL,
    eta_text            varchar(40)   NOT NULL,
    quote_id            uuid          NOT NULL UNIQUE,   -- one order per quote, whatever happens
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_customer ON orders (customer_id, created_at DESC);
CREATE INDEX idx_orders_awaiting_payment ON orders (created_at) WHERE status = 'AWAITING_PAYMENT';

CREATE TABLE order_items (
    id             uuid          PRIMARY KEY,
    order_id       uuid          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    position       int           NOT NULL,
    product_id     varchar(64)   NOT NULL,
    name_snapshot  varchar(160)  NOT NULL,
    image_url      varchar(255),
    emoji          varchar(16),
    unit_price     numeric(10,2) NOT NULL,
    quantity       int           NOT NULL CHECK (quantity BETWEEN 1 AND 50),
    line_total     numeric(10,2) NOT NULL,
    note           varchar(200)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE order_item_selections (
    order_item_id  uuid          NOT NULL REFERENCES order_items(id) ON DELETE CASCADE,
    position       int           NOT NULL,
    group_name     varchar(80)   NOT NULL,
    label          varchar(80)   NOT NULL,
    price_delta    numeric(10,2) NOT NULL,
    PRIMARY KEY (order_item_id, position)
);

CREATE TABLE order_status_history (
    id           bigserial    PRIMARY KEY,
    order_id     uuid         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status  varchar(20),
    to_status    varchar(20)  NOT NULL,
    at           timestamptz  NOT NULL,
    actor        varchar(10)  NOT NULL CHECK (actor IN ('SYSTEM', 'MERCHANT', 'CUSTOMER', 'PAYMENT'))
);
CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, id);

-- ───────────── events ─────────────
-- Transactional outbox: written in the same transaction as the state change, relayed to Kafka afterwards.
CREATE TABLE outbox_events (
    id            uuid         PRIMARY KEY,
    topic         varchar(80)  NOT NULL,
    aggregate_id  varchar(64)  NOT NULL,
    type          varchar(60)  NOT NULL,
    payload       jsonb        NOT NULL,              -- full envelope {eventId, type, occurredAt, aggregateId, version, payload}
    occurred_at   timestamptz  NOT NULL,
    published_at  timestamptz
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;

-- Consumer idempotency for payment.events (at-least-once delivery)
CREATE TABLE processed_events (
    event_id      varchar(64)  PRIMARY KEY,
    processed_at  timestamptz  NOT NULL DEFAULT now()
);
