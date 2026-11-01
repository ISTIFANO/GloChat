-- Inc 1b: product photos, merchant branding, menu categories (spec §3.1). Additive only (expand rule).
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS description text NOT NULL DEFAULT '';
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS logo_url    text NULL;
ALTER TABLE merchants ADD COLUMN IF NOT EXISTS cover_url   text NULL;

CREATE TABLE IF NOT EXISTS categories (
    id           varchar(64)  PRIMARY KEY,
    merchant_id  varchar(64)  NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    name         varchar(80)  NOT NULL,
    position     int          NOT NULL DEFAULT 0,
    active       boolean      NOT NULL DEFAULT true
);
CREATE INDEX IF NOT EXISTS idx_categories_merchant ON categories (merchant_id) WHERE active;

ALTER TABLE products ADD COLUMN IF NOT EXISTS image_url   text NULL;
ALTER TABLE products ADD COLUMN IF NOT EXISTS category_id varchar(64) NULL REFERENCES categories(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_products_category ON products (category_id);
