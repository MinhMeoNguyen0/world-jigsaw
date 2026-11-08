package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Opt-in, local administrative import at startup; never exposed as a public HTTP endpoint. */
@Component
class BoardImport implements ApplicationRunner {
  private final GameBoards boards;

  BoardImport(GameBoards boards) {
    this.boards = boards;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (!args.containsOption("game-import-file") && !args.containsOption("game-import-day")) return;
    if (!args.containsOption("game-import-file")
        || !args.containsOption("game-import-day")
        || args.getOptionValues("game-import-file") == null
        || args.getOptionValues("game-import-day") == null
        || args.getOptionValues("game-import-file").size() != 1
        || args.getOptionValues("game-import-day").size() != 1) {
      throw new IllegalArgumentException("Supply one --game-import-file and one --game-import-day");
    }
    var input =
        new ObjectMapper()
            .readTree(
                Files.readString(Path.of(args.getOptionValues("game-import-file").getFirst())));
    boards.importBoard(
        LocalDate.parse(args.getOptionValues("game-import-day").getFirst()),
        input.path("definition"),
        input.path("snapshot"));
  }
}
