package com.worldjigsaw;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts the whole application against a real PostgreSQL container. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationSmokeTest {

  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort int port;

  @Autowired JdbcClient jdbcClient;

  @Test
  void flywayAppliedBaselineMigration() {
    Integer applied =
        jdbcClient
            .sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
            .query(Integer.class)
            .single();
    assertThat(applied).isEqualTo(1);
  }

  @Test
  void healthEndpointIsUp() {
    String body =
        RestClient.create("http://localhost:" + port)
            .get()
            .uri("/actuator/health")
            .retrieve()
            .body(String.class);
    assertThat(body).contains("\"status\":\"UP\"");
  }
}
