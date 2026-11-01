-- Inc 1: catalog bounded context (spec §3.1).
-- pg_trgm is created by the infra init script (superuser); unaccent is a trusted extension the DB owner may create.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE merchants (
    id                  varchar(64)  PRIMARY KEY,
    name                varchar(120) NOT NULL,
    type                varchar(20)  NOT NULL CHECK (type IN ('RESTAURANT', 'GROCERY', 'BAKERY', 'CAFE', 'OTHER')),
    emoji               varchar(16)  NOT NULL DEFAULT '🏪',
    cuisine_tags        text[]       NOT NULL DEFAULT '{}',
    city                varchar(80)  NOT NULL,
    lat                 double precision NOT NULL,
    lng                 double precision NOT NULL,
    avg_prep_minutes    int          NOT NULL DEFAULT 15 CHECK (avg_prep_minutes > 0),
    delivery_radius_km  numeric(5,2) NOT NULL DEFAULT 8 CHECK (delivery_radius_km > 0),
    base_delivery_fee   numeric(10,2) NOT NULL CHECK (base_delivery_fee >= 0),
    fee_per_km          numeric(10,2) NOT NULL CHECK (fee_per_km >= 0),
    min_order_amount    numeric(10,2) NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    accepts_cash        boolean      NOT NULL DEFAULT true,
    accepts_card        boolean      NOT NULL DEFAULT true,
    active              boolean      NOT NULL DEFAULT true,
    created_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE merchant_hours (
    merchant_id  varchar(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    day_of_week  smallint    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),  -- ISO: 1 = Monday
    opens_at     time        NOT NULL,
    closes_at    time        NOT NULL,                                       -- < opens_at means past midnight
    PRIMARY KEY (merchant_id, day_of_week, opens_at)
);

CREATE TABLE products (
    id            varchar(64)  PRIMARY KEY,
    merchant_id   varchar(64)  NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    name          varchar(160) NOT NULL,
    description   text         NOT NULL DEFAULT '',
    base_price    numeric(10,2) NOT NULL CHECK (base_price >= 0),
    currency      char(3)      NOT NULL DEFAULT 'MAD',
    visual_emoji  varchar(16)  NOT NULL DEFAULT '🍽️',
    visual_from   varchar(9)   NOT NULL DEFAULT '#f2efff',
    visual_to     varchar(9)   NOT NULL DEFAULT '#c9bcff',
    tags          text[]       NOT NULL DEFAULT '{}',
    serves        smallint     NOT NULL DEFAULT 1 CHECK (serves >= 1),
    in_stock      boolean      NOT NULL DEFAULT true,
    stock_qty     int          NULL CHECK (stock_qty IS NULL OR stock_qty >= 0),  -- NULL = unlimited
    active        boolean      NOT NULL DEFAULT true,
    position      int          NOT NULL DEFAULT 0
);

CREATE TABLE option_groups (
    id          varchar(64)  PRIMARY KEY,
    product_id  varchar(64)  NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    name        varchar(80)  NOT NULL,
    type        varchar(10)  NOT NULL CHECK (type IN ('SINGLE', 'MULTI')),
    min_select  smallint     NOT NULL DEFAULT 0 CHECK (min_select >= 0),
    max_select  smallint     NOT NULL DEFAULT 1 CHECK (max_select >= 1),
    required    boolean      NOT NULL DEFAULT false,
    position    int          NOT NULL DEFAULT 0,
    CHECK (min_select <= max_select),
    CHECK (type = 'MULTI' OR max_select = 1)
);

CREATE TABLE option_values (
    id           varchar(64)  PRIMARY KEY,
    group_id     varchar(64)  NOT NULL REFERENCES option_groups(id) ON DELETE CASCADE,
    label        varchar(80)  NOT NULL,
    price_delta  numeric(10,2) NOT NULL DEFAULT 0,
    available    boolean      NOT NULL DEFAULT true,
    position     int          NOT NULL DEFAULT 0
);

CREATE INDEX idx_products_merchant ON products (merchant_id) WHERE active;
CREATE INDEX idx_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);
CREATE INDEX idx_products_tags ON products USING gin (tags);
CREATE INDEX idx_option_groups_product ON option_groups (product_id);
CREATE INDEX idx_option_values_group ON option_values (group_id);
