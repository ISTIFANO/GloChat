# Changelog

## 1.1.0
- README with demo recordings, screenshots, architecture diagram, key flows and tech stack

## 1.0.0
- Continue with Google (Keycloak identity brokering), FR/AR/EN login pages
- Restaurants list with delivery terms, open ones first
- All service URLs come from environment variables; services fail fast when one is missing
- README with setup, configuration and branching

## 0.3.1
- Stripe checkout shows the GloChat order label

## 0.3.0 — Ordering and payment
- Cart: one merchant per cart, configurable products, saved addresses, repriced on read
- Checkout: 10-minute quotes, idempotent confirmation, price-change detection
- Orders: state machine, numbering, cancel rules, unpaid expiry, transactional outbox to Kafka
- payment-service: Stripe Checkout, signed webhooks, payment events

## 0.2.0 — Catalog
- Gemini chat model wired in agent-service
- Cross-merchant search with accent/Arabic normalisation and ranking
- Product detail, delivery terms (fee, ETA, zone), opening hours
- Merchant branding, photos, categories and merchant page

## 0.1.0 — Foundation
- Maven monorepo: shared kernel, api-gateway, commerce, payment and agent services
- Local stack: PostgreSQL (one database per service), Kafka, Redis, Keycloak, Grafana LGTM
- Keycloak resource servers, gateway routing, ArchUnit layering rules
- OpenTelemetry traces, metrics and JSON logs in every service
- CI: secret scan, build and tests, end-to-end checks
