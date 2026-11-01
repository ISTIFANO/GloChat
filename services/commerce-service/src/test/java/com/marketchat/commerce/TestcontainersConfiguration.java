package com.marketchat.commerce;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL for integration tests — same image as infra/docker-compose.yml (pgvector on PG 17),
 * so pg_trgm/unaccent behave exactly as in dev. Spring's context cache shares one container across tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie")
                .asCompatibleSubstituteFor("postgres"));
    }
}
