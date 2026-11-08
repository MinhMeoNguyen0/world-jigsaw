package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class GameWriterProcess {
  public static void main(String[] args) throws Exception {
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(args[0]);
    source.setUsername(args[1]);
    source.setPassword(args[2]);
    try (var guard = new GuardResource(new GameWriterGuard(source))) {
      JdbcTemplate jdbc = new JdbcTemplate(source);
      BoardValidator validator = new BoardValidator();
      DraftRules rules = new DraftRules(validator);
      ConfirmationJournal journal = new ConfirmationJournal(jdbc, validator, Clock.systemUTC(), guard.value);
      TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
      ObjectMapper mapper = new ObjectMapper();
      CountDownLatch proceed = new CountDownLatch(1);
      System.out.println("READY");
      System.out.flush();
      try (var input = new BufferedReader(new InputStreamReader(System.in))) {
        String line;
        while ((line = input.readLine()) != null) {
          String[] fields = line.split(" ");
          if ("CONTINUE".equals(fields[0])) proceed.countDown();
          else if ("EXIT".equals(fields[0])) break;
          else if ("CONFIRM".equals(fields[0]) || "RETRY".equals(fields[0])) {
            UUID puzzle = UUID.fromString(fields[1]), subject = UUID.fromString(fields[2]);
            UUID command = UUID.fromString(fields[3]), group = UUID.fromString(fields[4]);
            ObjectNode payload = mapper.createObjectNode().put("puzzleId", puzzle.toString())
                .put("commandId", command.toString()).put("turnId", fields[5]);
            boolean first = "CONFIRM".equals(fields[0]);
            Thread.ofPlatform().start(() -> {
              try {
                JsonNode result = transaction.execute(status -> journal.confirm(subject, puzzle, command, payload,
                    (definition, latest, remaining) -> {
                      if (!first) throw new AssertionError("duplicate command executed");
                      System.out.println("PLANNING");
                      System.out.flush();
                      try { proceed.await(); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                      }
                      ObjectNode transform = mapper.createObjectNode();
                      transform.putObject("translation").put("x", 0).put("y", 0);
                      transform.put("rotation", 0);
                      var draft = rules.move(rules.begin(definition, latest, remaining),
                          group.toString(), UUID.randomUUID().toString(), transform);
                      var plan = rules.confirm(draft, latest, remaining);
                      return new ConfirmationJournal.Plan(plan.snapshot(), plan.charged());
                    }));
                System.out.println("RESULT " + result);
                System.out.flush();
              } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                System.out.println("FAILED");
                System.out.flush();
              }
            });
          }
        }
      }
    }
  }

  private record GuardResource(GameWriterGuard value) implements AutoCloseable {
    @Override public void close() { value.close(); }
  }
}
