package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.valid.IsValidOp;

/** Provisional local-v1 geometry. This is not a transport or a complete rules engine. */
final class LocalGeometry {
  private static final int SUBDIVISIONS = 32;
  private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(1));
  private static final BufferParameters BUFFER = new BufferParameters(8);
  private static final long COORDINATE_LIMIT = 1_000_000_000L;

  private record Point(long x, long y) {}

  private record Transform(long x, long y, int rotation) {
    static Transform read(JsonNode value) {
      JsonNode rotationNode = value.path("rotation");
      JsonNode translation = value.path("translation");
      if (!rotationNode.isIntegralNumber()
          || !rotationNode.canConvertToLong()
          || !translation.path("x").isIntegralNumber()
          || !translation.path("x").canConvertToLong()
          || !translation.path("y").isIntegralNumber()
          || !translation.path("y").canConvertToLong()) {
        throw new IllegalArgumentException("invalid transform");
      }
      long rotation = rotationNode.longValue();
      long x = translation.path("x").longValue();
      long y = translation.path("y").longValue();
      if (rotation < 0
          || rotation >= 360000
          || rotation % 90000 != 0
          || x < -COORDINATE_LIMIT
          || x > COORDINATE_LIMIT
          || y < -COORDINATE_LIMIT
          || y > COORDINATE_LIMIT) {
        throw new IllegalArgumentException("unsupported transform");
      }
      return new Transform(x, y, (int) rotation);
    }

    Point apply(Point point) {
      return switch (rotation) {
        case 0 -> new Point(point.x + x, point.y + y);
        case 90000 -> new Point(-point.y + x, point.x + y);
        case 180000 -> new Point(-point.x + x, -point.y + y);
        case 270000 -> new Point(point.y + x, -point.x + y);
        default -> throw new IllegalStateException("invalid quarter turn");
      };
    }
  }

  private record Shape(Geometry polygon, double scale) {}

  private LocalGeometry() {}

  static double snapToleranceUpperBound(JsonNode definition, Set<UUID> selected) {
    double maxScale = 0;
    for (JsonNode piece : definition.path("pieces")) {
      if (!selected.contains(BoardValidator.id(piece, "pieceId"))) continue;
      var envelope = polygon(piece, new Transform(0, 0, 0)).getEnvelopeInternal();
      maxScale = Math.max(maxScale, Math.min(envelope.getWidth(), envelope.getHeight()));
    }
    return Math.floor(maxScale / 20.0);
  }

  /**
   * Tests the complete proposed board, including collisions between simultaneous changes. The
   * caller must separately validate board membership, workspace, revisions and the game rules.
   */
  static boolean hasForbiddenOverlap(JsonNode definition, JsonNode snapshot) {
    Map<UUID, JsonNode> pieces = new HashMap<>();
    for (JsonNode piece : definition.path("pieces")) {
      if (pieces.put(BoardValidator.id(piece, "pieceId"), piece) != null) {
        throw new IllegalArgumentException("duplicate piece");
      }
    }
    Set<UUID> seen = new HashSet<>();
    List<Shape> shapes = new ArrayList<>();
    int groupCount = 0;
    for (JsonNode group : snapshot.path("groups")) {
      Transform transform = Transform.read(group.path("transform"));
      int before = shapes.size();
      for (JsonNode pieceId : group.path("pieceIds")) {
        UUID id = UUID.fromString(pieceId.textValue());
        JsonNode piece = pieces.get(id);
        if (piece == null || !seen.add(id))
          throw new IllegalArgumentException("invalid membership");
        Geometry polygon = polygon(piece, transform);
        var envelope = polygon.getEnvelopeInternal();
        double scale = Math.min(envelope.getWidth(), envelope.getHeight());
        if (scale < 1000) throw new IllegalArgumentException("unsupported piece size");
        shapes.add(new Shape(polygon, scale));
      }
      if (shapes.size() == before) throw new IllegalArgumentException("empty group");
      groupCount++;
    }
    if (seen.size() != pieces.size() || groupCount == 0) {
      throw new IllegalArgumentException("incomplete board");
    }
    for (int i = 0; i < shapes.size(); i++) {
      for (int j = i + 1; j < shapes.size(); j++) {
        Shape a = shapes.get(i), b = shapes.get(j);
        if (!a.polygon.getEnvelopeInternal().intersects(b.polygon.getEnvelopeInternal())) continue;
        // Check intrusion into either piece's core so thin protrusions cannot disappear.
        double inset = Math.floor(Math.min(a.scale, b.scale) * 18.0 / 100.0);
        Geometry innerA = BufferOp.bufferOp(a.polygon, -inset, BUFFER);
        Geometry innerB = BufferOp.bufferOp(b.polygon, -inset, BUFFER);
        if (innerA.isEmpty() || innerB.isEmpty()) {
          throw new IllegalArgumentException("unsupported thin contour");
        }
        if (innerA.relate(b.polygon, "T********") || innerB.relate(a.polygon, "T********"))
          return true;
      }
    }
    return false;
  }

  /** Snap only matching neighbors with equal quarter-turn orientation and within 5% of scale. */
  static boolean canSnap(
      JsonNode movingPiece,
      JsonNode movingTransform,
      JsonNode targetPiece,
      JsonNode targetTransform) {
    UUID movingId = BoardValidator.id(movingPiece, "pieceId");
    UUID targetId = BoardValidator.id(targetPiece, "pieceId");
    boolean adjacent = false;
    for (JsonNode edge : movingPiece.path("edges")) {
      if (edge.path("neighborPieceId").isTextual()
          && targetId.equals(UUID.fromString(edge.path("neighborPieceId").textValue()))) {
        for (JsonNode back : targetPiece.path("edges")) {
          if (back.path("neighborPieceId").isTextual()
              && movingId.equals(UUID.fromString(back.path("neighborPieceId").textValue()))
              && edge.path("neighborEdgeId").equals(back.path("edgeId"))
              && back.path("neighborEdgeId").equals(edge.path("edgeId"))) adjacent = true;
        }
      }
    }
    Transform moving = Transform.read(movingTransform);
    Transform target = Transform.read(targetTransform);
    if (!adjacent || moving.rotation != target.rotation) return false;
    Geometry a = polygon(movingPiece, moving);
    Geometry b = polygon(targetPiece, target);
    double scale =
        Math.min(
            Math.min(a.getEnvelopeInternal().getWidth(), a.getEnvelopeInternal().getHeight()),
            Math.min(b.getEnvelopeInternal().getWidth(), b.getEnvelopeInternal().getHeight()));
    if (scale < 1000) throw new IllegalArgumentException("unsupported piece size");
    Point reference =
        add(
            readPoint(movingPiece.path("solvedPosition")),
            readPoint(movingPiece.path("referencePoint")));
    Point from = moving.apply(reference);
    Point to = target.apply(reference);
    return Math.hypot((double) from.x - to.x, (double) from.y - to.y) <= Math.floor(scale / 20.0);
  }

  private static Geometry polygon(JsonNode piece, Transform transform) {
    Point origin = readPoint(piece.path("solvedPosition"));
    List<Coordinate> points = new ArrayList<>();
    Point first = null;
    Point previous = null;
    for (JsonNode edge : piece.path("edges")) {
      Point start = readPoint(edge.path("start"));
      if (previous != null && !previous.equals(start))
        throw new IllegalArgumentException("open contour");
      if (first == null) {
        first = start;
        points.add(coordinate(transform.apply(add(origin, start))));
      }
      Point current = start;
      for (JsonNode segment : edge.path("segments")) {
        Point end = readPoint(segment.path("to"));
        if ("line".equals(segment.path("kind").asText())) {
          points.add(coordinate(transform.apply(add(origin, end))));
        } else if ("cubic".equals(segment.path("kind").asText())) {
          Point control1 = readPoint(segment.path("control1"));
          Point control2 = readPoint(segment.path("control2"));
          for (int i = 1; i <= SUBDIVISIONS; i++) {
            points.add(
                coordinate(
                    transform.apply(add(origin, cubic(current, control1, control2, end, i)))));
          }
        } else throw new IllegalArgumentException("unsupported segment");
        if (points.size() > 4096)
          throw new IllegalArgumentException("unsupported contour complexity");
        current = end;
      }
      previous = current;
    }
    if (first == null || !first.equals(previous) || points.size() < 4) {
      throw new IllegalArgumentException("invalid contour");
    }
    Geometry polygon = FACTORY.createPolygon(points.toArray(Coordinate[]::new));
    if (polygon.isEmpty() || polygon.getArea() == 0 || !new IsValidOp(polygon).isValid()) {
      throw new IllegalArgumentException("invalid contour polygon");
    }
    return polygon;
  }

  private static Point cubic(Point a, Point b, Point c, Point d, int i) {
    long n = SUBDIVISIONS, j = n - i;
    long denominator = n * n * n;
    return new Point(
        Math.floorDiv(
            a.x * j * j * j
                + 3 * b.x * j * j * i
                + 3 * c.x * j * i * i
                + d.x * i * i * i
                + denominator / 2,
            denominator),
        Math.floorDiv(
            a.y * j * j * j
                + 3 * b.y * j * j * i
                + 3 * c.y * j * i * i
                + d.y * i * i * i
                + denominator / 2,
            denominator));
  }

  private static Point readPoint(JsonNode point) {
    if (!point.path("x").isIntegralNumber()
        || !point.path("x").canConvertToLong()
        || !point.path("y").isIntegralNumber()
        || !point.path("y").canConvertToLong()) {
      throw new IllegalArgumentException("invalid point");
    }
    long x = point.path("x").longValue(), y = point.path("y").longValue();
    if (x < -COORDINATE_LIMIT
        || x > COORDINATE_LIMIT
        || y < -COORDINATE_LIMIT
        || y > COORDINATE_LIMIT) {
      throw new IllegalArgumentException("point outside limits");
    }
    return new Point(x, y);
  }

  private static Point add(Point a, Point b) {
    return new Point(a.x + b.x, a.y + b.y);
  }

  private static Coordinate coordinate(Point p) {
    return new Coordinate(p.x, p.y);
  }
}
