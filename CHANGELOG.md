# Changelog

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
