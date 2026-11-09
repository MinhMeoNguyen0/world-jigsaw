package com.worldjigsaw.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;

class FixedShapeRouteTest {
  @Test
  void playerCannotSubmitShapeChanges() throws Exception {
    var controller = new GameCommandController(null, null, null, null);
    standaloneSetup(controller)
        .build()
        .perform(post("/api/v1/game/shape").contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
  }
}
