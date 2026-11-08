package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class LocalSessionsTest {
  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired GameWriterGuard writer;
  private final ObjectMapper mapper = new ObjectMapper();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  void twoClientsGetPrivatePersistentSessionsAndCrossOriginIsRejected() throws Exception {
    String origin = "http://127.0.0.1:" + port;
    URI uri = URI.create(origin + "/api/v1/session");
    HttpClient first = HttpClient.newHttpClient(), second = HttpClient.newHttpClient();
    HttpResponse<String> blocked =
        first.send(
            HttpRequest.newBuilder(uri)
                .header("Origin", "http://attacker.test")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(blocked.statusCode()).isEqualTo(403);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM game_sessions", Integer.class)).isZero();
    HttpResponse<String> a =
        first.send(
            HttpRequest.newBuilder(uri)
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> b =
        second.send(
            HttpRequest.newBuilder(uri)
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(a.statusCode()).isEqualTo(200);
    assertThat(b.statusCode()).isEqualTo(200);
    String cookieA = a.headers().firstValue("set-cookie").orElseThrow();
    String cookieB = b.headers().firstValue("set-cookie").orElseThrow();
    assertThat(cookieA).contains("HttpOnly", "SameSite=Strict", "Path=/").doesNotContain("Domain=");
    assertThat(cookieA).isNotEqualTo(cookieB);
    String token = cookieA.substring("wj_session=".length(), cookieA.indexOf(';'));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM game_sessions WHERE token_hash = ?", Integer.class, token))
        .isZero();
    LocalSessions afterRestart = new LocalSessions(jdbc, clock, writer);
    assertThat(afterRestart.lookup(token)).isNotNull();
    HttpResponse<String> again =
        first.send(
            HttpRequest.newBuilder(uri)
                .header("Origin", origin)
                .header("Cookie", cookieA.substring(0, cookieA.indexOf(';')))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(mapper.readTree(again.body())).isEqualTo(mapper.readTree(a.body()));
    assertThat(again.headers().firstValue("set-cookie")).isEmpty();
    assertThat(mapper.readTree(a.body()).path("displayName"))
        .isNotEqualTo(mapper.readTree(b.body()).path("displayName"));
  }
}
