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
