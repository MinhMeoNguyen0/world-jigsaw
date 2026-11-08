package com.worldjigsaw.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PreviewDemoServerTest {
  private static final String A = "30000000-0000-4000-8000-000000000001";
  private static final String B = "30000000-0000-4000-8000-000000000002";

  private static final class Receiver implements WebSocket.Listener {
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      socket.request(1);
      return null;
    }

    String next() throws InterruptedException {
      String message = messages.poll(5, TimeUnit.SECONDS);
      assertThat(message).as("WebSocket message").isNotNull();
      return message;
    }
  }

  @Test
  void independentClientsObserveReconnectOrderingAndCancellationFence() throws Exception {
    PreviewDemoServer server = new PreviewDemoServer(0);
    server.start();
    try (HttpClient http = HttpClient.newHttpClient()) {
      server.awaitStart();
      URI uri = URI.create("ws://127.0.0.1:" + server.getPort());
      Receiver writerEvents = new Receiver();
      Receiver observerEvents = new Receiver();
      WebSocket writer = http.newWebSocketBuilder().buildAsync(uri, writerEvents).join();
      WebSocket observer = http.newWebSocketBuilder().buildAsync(uri, observerEvents).join();
      assertThat(writerEvents.next()).isEqualTo("active");
      assertThat(observerEvents.next()).isEqualTo("active");

      sendAndExpect(
          writer, writerEvents, observerEvents, "preview|1|" + A + "|10", "active|1," + A + ",10");
      sendAndExpect(
          writer,
          writerEvents,
          observerEvents,
          "preview|2|" + B + "|20",
          "active|1," + A + ",10|2," + B + ",20");
      sendAndExpect(
          writer,
          writerEvents,
          observerEvents,
          "preview|3|" + A + "|30",
          "active|2," + B + ",20|3," + A + ",30");

      observer.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect").join();
      Receiver reconnectedEvents = new Receiver();
      observer = http.newWebSocketBuilder().buildAsync(uri, reconnectedEvents).join();
      String latest = "active|2," + B + ",20|3," + A + ",30";
      assertThat(reconnectedEvents.next()).isEqualTo(latest);

      sendAndExpect(writer, writerEvents, reconnectedEvents, "preview|2|" + B + "|999", latest);
      sendAndExpect(writer, writerEvents, reconnectedEvents, "preview|3|" + A + "|999", latest);
      sendAndExpect(writer, writerEvents, reconnectedEvents, "cancel", "cleared");
      sendAndExpect(writer, writerEvents, reconnectedEvents, "preview|4|" + A + "|40", "cleared");

      observer.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
      Receiver afterCancel = new Receiver();
      observer = http.newWebSocketBuilder().buildAsync(uri, afterCancel).join();
      assertThat(afterCancel.next()).isEqualTo("cleared");
      observer.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
      writer.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    } finally {
      server.stop(1000);
    }
  }

  private static void sendAndExpect(
      WebSocket writer,
      Receiver writerEvents,
      Receiver observerEvents,
      String message,
      String expected)
      throws Exception {
    writer.sendText(message, true).join();
    assertThat(writerEvents.next()).isEqualTo(expected);
    assertThat(observerEvents.next()).isEqualTo(expected);
  }
}
