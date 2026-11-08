package com.worldjigsaw.game;

import com.worldjigsaw.game.PreviewGeneration.Action;
import com.worldjigsaw.game.PreviewGeneration.Point;
import com.worldjigsaw.game.PreviewGeneration.Transform;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

/** Private, disposable transport for exercising one provisional turn, not a game endpoint. */
public final class PreviewDemoServer extends WebSocketServer {
  private final PreviewGeneration previews =
      new PreviewGeneration(UUID.randomUUID(), UUID.randomUUID(), 0);
  private final Set<WebSocket> clients = new HashSet<>();
  private final CountDownLatch started = new CountDownLatch(1);
  private boolean cleared;

  public PreviewDemoServer(int port) throws Exception {
    super(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
    setMaxPendingConnections(16);
  }

  @Override
  public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(
      WebSocket connection, Draft draft, ClientHandshake request) throws InvalidDataException {
    // Browsers supply Origin; this unauthenticated demo is for local non-browser clients only.
    if (request.hasFieldValue("Origin")) {
      throw new InvalidDataException(1008, "Browser origins are not accepted");
    }
    return super.onWebsocketHandshakeReceivedAsServer(connection, draft, request);
  }

  @Override
  public synchronized void onOpen(WebSocket connection, ClientHandshake handshake) {
    clients.add(connection);
    connection.send(state());
  }

  @Override
  public synchronized void onMessage(WebSocket connection, String message) {
    if (message.length() > 256) {
      connection.close(1009, "Frame too large");
      return;
    }
    if (message.equals("cancel")) {
      previews.clear();
      cleared = true;
    } else {
      String[] fields = message.split("\\|", -1);
      if (fields.length != 4 || !fields[0].equals("preview")) {
        connection.send("error");
        return;
      }
      try {
        long sequence = Long.parseLong(fields[1]);
        UUID actionId = UUID.fromString(fields[2]);
        long x = Long.parseLong(fields[3]);
        Action action =
            new Action(
                actionId,
                "piece",
                actionId,
                List.of(actionId),
                List.of(),
                new Transform(new Point(x, 0), 0));
        previews.publish(sequence, action, Instant.parse("9999-12-31T23:59:59Z"));
      } catch (IllegalArgumentException exception) {
        connection.send("error");
        return;
      }
    }
    String state = state();
    clients.forEach(client -> client.send(state));
  }

  private String state() {
    if (cleared) {
      return "cleared";
    }
    StringBuilder result = new StringBuilder("active");
    for (var preview : previews.snapshot()) {
      result
          .append('|')
          .append(preview.sequence())
          .append(',')
          .append(preview.action().actionId())
          .append(',')
          .append(preview.action().intendedTransform().translation().x());
    }
    return result.toString();
  }

  @Override
  public synchronized void onClose(WebSocket connection, int code, String reason, boolean remote) {
    clients.remove(connection);
  }

  @Override
  public void onError(WebSocket connection, Exception exception) {
    System.err.println("Preview demo transport error: " + exception.getClass().getSimpleName());
  }

  @Override
  public void onStart() {
    started.countDown();
  }

  void awaitStart() throws InterruptedException {
    if (!started.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Preview demo failed to start");
    }
  }

  public static void main(String[] args) throws Exception {
    PreviewDemoServer server = new PreviewDemoServer(0);
    server.start();
    server.awaitStart();
    System.out.println("Preview demo listening on ws://127.0.0.1:" + server.getPort());
  }
}
