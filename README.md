# GloChat

AI-first food marketplace: the customer says what they want, the assistant finds it and orders.

## Modules

| Module | Role |
|---|---|
| `marketchat-common` | shared kernel (money, token validation) |
| `services/api-gateway` | single entry point, JWT check, routing |
| `services/commerce-service` | catalog, cart, checkout, orders |
| `services/payment-service` | payments (Stripe) |
| `services/agent-service` | conversational agent |

Java 17, Spring Boot 4, Maven.

## Run locally

Prerequisites: JDK 17, Docker, Python 3 (test scripts).

```bash
bash infra/generate-dev-env.sh            # infra/.env with random dev passwords + local URLs
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d
./mvnw package -DskipTests
bash infra/run-service.sh commerce-service   # same for api-gateway, payment-service, agent-service
```

| Service | Port |
|---|---|
| api-gateway | 8080 |
| commerce-service | 8081 |
| payment-service | 8084 |
| agent-service | 8085 |
| Keycloak | 8180 |
| Grafana | 3001 |
