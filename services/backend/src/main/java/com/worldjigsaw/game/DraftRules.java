package com.worldjigsaw.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Internal local-v1 draft operations. No persistence, reservations or transport is implied. */
@Component
class DraftRules {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final BoardValidator validator;

  DraftRules(BoardValidator validator) {
    this.validator = validator;
  }

  static class RuleError extends RuntimeException {
    final String code;

    RuleError(String code, String message) {
      super(message);
      this.code = code;
    }
  }

  private static RuleError fail(String code, String message) {
    return new RuleError(code, message);
  }

  private static String key(String id) {
    return id.toLowerCase(Locale.ROOT);
  }

  private static String id(JsonNode value, String field) {
    String text = value.path(field).textValue();
    if (text == null
        || !text.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw fail("INVALID_ACTION", "Invalid UUID");
    return key(text);
  }

  private static String uuid(String value) {
    return id(MAPPER.createObjectNode().put("id", value), "id");
  }

  private static final class DraftGroup {
    ObjectNode group;
    List<String> sources;
    List<String> actions;
    List<DraftGroup> children;
    Map<String, Action> beforeJoin;

    DraftGroup(ObjectNode group, List<String> sources, List<String> actions) {
      this.group = group;
      this.sources = sources;
      this.actions = actions;
    }

    DraftGroup copy() {
      DraftGroup copy = new DraftGroup(group.deepCopy(), new ArrayList<>(sources), new ArrayList<>(actions));
      if (children != null) {
        copy.children = children.stream().map(DraftGroup::copy).toList();
        copy.beforeJoin = new HashMap<>();
        beforeJoin.forEach((id, action) -> copy.beforeJoin.put(id, action.copy()));
      }
      return copy;
    }
  }

  private static final class Action {
    String id, source;
    boolean entered;
    Boolean joinedCharge;

    Action(String id, String source) {
      this.id = id;
      this.source = source;
    }

    Action copy() {
      Action a = new Action(id, source);
      a.entered = entered;
      a.joinedCharge = joinedCharge;
      return a;
    }
  }

  static final class Draft {
    final JsonNode definition, base;
    final int remaining;
    int generation;
    List<DraftGroup> groups;
    List<Action> actions;

    Draft(JsonNode definition, JsonNode base, int remaining) {
      this.definition = definition;
      this.base = base;
      this.remaining = remaining;
      groups = new ArrayList<>();
      actions = new ArrayList<>();
    }

    Draft copy() {
      Draft d = new Draft(definition, base, remaining);
      d.generation = generation;
      d.groups =
          groups.stream()
              .map(DraftGroup::copy)
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      d.actions =
          actions.stream()
              .map(Action::copy)
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      return d;
    }
  }

  record Confirmation(JsonNode snapshot, int charged) {}

  private record Point(long x, long y) {}

  Draft begin(JsonNode definition, JsonNode snapshot, int remaining) {
    if (remaining < 0 || remaining > 5) throw fail("INVALID_ACTION", "Invalid allowance");
    validator.validate(definition, snapshot);
    checkConnected(definition, snapshot);
    Draft d = new Draft(definition.deepCopy(), snapshot.deepCopy(), remaining);
    for (JsonNode group : d.base.path("groups")) {
      d.groups.add(
          new DraftGroup(
              group.deepCopy(), new ArrayList<>(List.of(id(group, "groupId"))), new ArrayList<>()));
    }
    return d;
  }

  Draft move(Draft original, String groupId, String actionId, JsonNode transform) {
    checkOrientation(original.base);
    Draft d = original.copy();
    DraftGroup g = group(d, uuid(groupId));
    String actionKey = uuid(actionId);
    validateTransform(transform);
    Action action = action(d, actionKey);
    if (action != null && !g.actions.contains(actionKey))
      throw fail("INVALID_ACTION", "Action belongs to another group");
    if (action == null) {
      if (!g.actions.isEmpty() || g.sources.size() != 1)
        throw fail("INVALID_ACTION", "Use an existing action");
      action = new Action(actionKey, g.sources.getFirst());
      d.actions.add(action);
      g.actions.add(actionKey);
    }
    checkWorkspace(d, g.group.path("pieceIds"), transform);
    if (g.sources.size() > 1) chargeJoinedMovement(d, g, transform);
    g.group.set("transform", transform.deepCopy());
    JsonNode source = source(d, action.source);
    if (g.sources.size() == 1
        && source.path("pieceIds").size() == 1
        && !inside(
            reference(d, source.path("pieceIds").get(0).textValue(), source.path("transform")),
            d.definition.path("playSurface"))) {
      action.entered =
          inside(
              reference(d, source.path("pieceIds").get(0).textValue(), transform),
              d.definition.path("playSurface"));
    }
    checkQuota(d);
    return d;
  }

  Draft attach(Draft original, String movingId, String targetId, String mergedId) {
    checkOrientation(original.base);
    Draft d = original.copy();
    DraftGroup moving = group(d, uuid(movingId)), target = group(d, uuid(targetId));
    String merged = uuid(mergedId);
    if (moving == target
        || moving.actions.isEmpty()
        || d.groups.stream().anyMatch(g -> id(g.group, "groupId").equals(merged))
        || findSource(d, merged) != null) throw fail("INVALID_ACTION", "Invalid attachment");
    if (!canAttach(d, moving, target))
      throw fail("INVALID_ACTION", "Not adjacent or outside snap tolerance");
    ObjectNode mergedGroup = MAPPER.createObjectNode().put("groupId", merged).put("revision", 0);
    ArrayNode members = mergedGroup.putArray("pieceIds");
    moving.group.path("pieceIds").forEach(members::add);
    target.group.path("pieceIds").forEach(members::add);
    mergedGroup.set("transform", target.group.path("transform").deepCopy());
    mergedGroup.put("finalPosition", false);
    checkWorkspace(d, members, mergedGroup.path("transform"));
    Map<String, Action> beforeJoin = new HashMap<>();
    for (Action a : d.actions)
      if (moving.actions.contains(a.id) || target.actions.contains(a.id))
        beforeJoin.put(a.id, a.copy());
    for (Action a : d.actions)
      if (moving.actions.contains(a.id) || target.actions.contains(a.id))
        a.joinedCharge = counted(d, a);
    chargeJoinedMovement(d, moving, target.group.path("transform"));
    List<String> sources = new ArrayList<>(moving.sources);
    sources.addAll(target.sources);
    List<String> actions = new ArrayList<>(moving.actions);
    actions.addAll(target.actions);
    d.groups.remove(moving);
    d.groups.remove(target);
    DraftGroup joined = new DraftGroup(mergedGroup, sources, actions);
    joined.children = List.of(moving.copy(), target.copy());
    joined.beforeJoin = beforeJoin;
    d.groups.add(joined);
    checkQuota(d);
    return d;
  }

  boolean canAttach(Draft draft, String movingId, String targetId) {
    return canAttach(draft, group(draft, uuid(movingId)), group(draft, uuid(targetId)));
  }

  JsonNode groupTransform(Draft draft, String groupId) {
    return group(draft, uuid(groupId)).group.path("transform");
  }

  record JoinCharge(String id, Boolean charged) {}

  record JoinState(
      Set<String> sources, String firstAction, JsonNode transform, List<JoinCharge> charges) {}

  JoinState joinState(Draft draft, String movingId) {
    DraftGroup moving = group(draft, uuid(movingId));
    return new JoinState(
        Set.copyOf(moving.sources),
        moving.actions.getFirst(),
        moving.group.path("transform"),
        draft.actions.stream().map(a -> new JoinCharge(a.id, a.joinedCharge)).toList());
  }

  private boolean canAttach(Draft d, DraftGroup moving, DraftGroup target) {
    for (JsonNode m : moving.group.path("pieceIds"))
      for (JsonNode t : target.group.path("pieceIds")) {
        JsonNode p = piece(d, m.textValue()), q = piece(d, t.textValue());
        if (LocalGeometry.canSnap(
            p, moving.group.path("transform"), q, target.group.path("transform"))) return true;
      }
    return false;
  }

  Draft undo(Draft original, String actionId) {
    checkOrientation(original.base);
    Draft d = original.copy();
    Action a = action(d, uuid(actionId));
    if (a == null) throw fail("INVALID_ACTION", "Unknown action");
    DraftGroup g =
        d.groups.stream().filter(x -> x.actions.contains(a.id)).findFirst().orElseThrow();
    while (g.children != null) {
      d.groups.remove(g);
      d.groups.addAll(g.children.stream().map(DraftGroup::copy).toList());
      for (int i = 0; i < d.actions.size(); i++) {
        Action previous = g.beforeJoin.get(d.actions.get(i).id);
        if (previous != null) d.actions.set(i, previous.copy());
      }
      g = d.groups.stream().filter(x -> x.actions.contains(a.id)).findFirst().orElseThrow();
    }
    g.group = source(d, a.source).deepCopy();
    g.actions.clear();
    d.actions.removeIf(item -> item.id.equals(a.id));
    checkQuota(d);
    d.generation++;
    return d;
  }

  void requireSupported(Draft draft) {
    checkOrientation(draft.base);
  }

  int pendingCount(Draft d) {
    return (int) d.actions.stream().filter(a -> counted(d, a)).count();
  }

  int generation(Draft d) {
    return d.generation;
  }

  ArrayNode preview(Draft d) {
    ArrayNode out = MAPPER.createArrayNode();
    for (DraftGroup g : d.groups) if (!g.actions.isEmpty()) out.add(g.group.deepCopy());
    return out;
  }

  record GroupView(JsonNode group, List<String> sources, List<String> actions) {}

  record ActionView(
      String actionId, String sourceId, boolean charged, String kind, GroupView component) {}

  List<GroupView> groups(Draft d) {
    return d.groups.stream()
        .map(g -> new GroupView(g.group.deepCopy(), List.copyOf(g.sources), List.copyOf(g.actions)))
        .toList();
  }

  List<ActionView> actions(Draft d) {
    List<ActionView> result = new ArrayList<>();
    for (Action a : d.actions) {
      DraftGroup g =
          d.groups.stream().filter(item -> item.actions.contains(a.id)).findFirst().orElseThrow();
      result.add(
          new ActionView(
              a.id,
              a.source,
              counted(d, a),
              kind(d, a, g),
              new GroupView(g.group.deepCopy(), List.copyOf(g.sources), List.copyOf(g.actions))));
    }
    return List.copyOf(result);
  }

  private String kind(Draft d, Action action, DraftGroup component) {
    JsonNode original = source(d, action.source);
    if (original.path("pieceIds").size() > 1) return "group";
    String piece = original.path("pieceIds").get(0).textValue();
    JsonNode surface = d.definition.path("playSurface");
    return counted(d, action)
            || inside(reference(d, piece, original.path("transform")), surface)
            || inside(reference(d, piece, component.group.path("transform")), surface)
        ? "piece"
        : "sort";
  }

  JsonNode baseGroup(Draft d, String groupId) {
    return source(d, uuid(groupId)).deepCopy();
  }

  String resolveGroup(Draft d, String targetId) {
    String target = uuid(targetId);
    for (DraftGroup g : d.groups) if (id(g.group, "groupId").equals(target)) return target;
    for (DraftGroup g : d.groups)
      for (JsonNode piece : g.group.path("pieceIds"))
        if (uuid(piece.textValue()).equals(target)) return id(g.group, "groupId");
    throw fail("INVALID_ACTION", "Unknown target");
  }

  Confirmation confirm(Draft d, JsonNode latest, int remaining) {
    if (remaining < 0 || remaining > 5) throw fail("INVALID_ACTION", "Invalid allowance");
    validator.validate(d.definition, latest);
    checkConnected(d.definition, latest);
    checkOrientation(d.base);
    checkOrientation(latest);
    if (latest.path("boardVersion").longValue() < d.base.path("boardVersion").longValue())
      throw fail("STALE_REVISION", "Older board");
    List<DraftGroup> changed = new ArrayList<>();
    Set<String> removed = new HashSet<>();
    for (DraftGroup g : d.groups)
      if (!g.actions.isEmpty()) {
        for (String source : g.sources) {
          JsonNode old = source(d, source), now = findGroup(latest, source);
          if (now == null || !sameGroup(old, now))
            throw fail("STALE_REVISION", "Touched group changed");
        }
        if (g.sources.size() > 1
            || !sameTransform(
                g.group.path("transform"), source(d, g.sources.getFirst()).path("transform"))) {
          changed.add(g);
          removed.addAll(g.sources);
        }
      }
    int charged = pendingCount(d);
    if (charged > remaining) throw fail("QUOTA_EXCEEDED", "Allowance changed");
    ObjectNode snapshot = latest.deepCopy();
    ArrayNode groups = MAPPER.createArrayNode();
    for (JsonNode group : latest.path("groups"))
      if (!removed.contains(id(group, "groupId"))) groups.add(group.deepCopy());
    for (DraftGroup g : changed) {
      ObjectNode result = g.group.deepCopy();
      if (g.sources.size() == 1) {
        long revision = source(d, g.sources.getFirst()).path("revision").longValue();
        if (revision == 9_007_199_254_740_991L) throw fail("INVALID_ACTION", "Revision exhausted");
        result.put("revision", revision + 1);
      }
      JsonNode transform = result.path("transform");
      result.put(
          "finalPosition",
          transform.path("rotation").intValue() == 0
              && transform.path("translation").path("x").longValue() == 0
              && transform.path("translation").path("y").longValue() == 0);
      groups.add(result);
    }
    snapshot.set("groups", groups);
    long version = latest.path("boardVersion").longValue();
    if (!changed.isEmpty()) {
      if (version == 9_007_199_254_740_991L)
        throw fail("INVALID_ACTION", "Board version exhausted");
      snapshot.put("boardVersion", version + 1);
    }
    validator.validate(d.definition, snapshot);
    checkConnected(d.definition, snapshot);
    return new Confirmation(snapshot, charged);
  }

  private static void checkQuota(Draft d) {
    if (d.actions.stream().filter(a -> counted(d, a)).count() > d.remaining)
      throw fail("QUOTA_EXCEEDED", "Daily allowance exceeded");
  }

  private static boolean counted(Draft d, Action action) {
    if (action.joinedCharge != null) return action.joinedCharge;
    DraftGroup g =
        d.groups.stream().filter(x -> x.actions.contains(action.id)).findFirst().orElseThrow();
    JsonNode source = source(d, action.source);
    if (sameTransform(source.path("transform"), g.group.path("transform"))) return false;
    return source.path("pieceIds").size() > 1
        || inside(
            reference(d, source.path("pieceIds").get(0).textValue(), source.path("transform")),
            d.definition.path("playSurface"))
        || action.entered;
  }

  private static void chargeJoinedMovement(Draft d, DraftGroup moving, JsonNode to) {
    JsonNode from = moving.group.path("transform");
    if (sameTransform(from, to)) return;
    List<Action> actions = d.actions.stream().filter(a -> moving.actions.contains(a.id)).toList();
    for (Action a : actions) {
      if (Boolean.TRUE.equals(a.joinedCharge)) continue;
      JsonNode src = source(d, a.source);
      String p = src.path("pieceIds").get(0).textValue();
      if (src.path("pieceIds").size() == 1
          && !inside(reference(d, p, src.path("transform")), d.definition.path("playSurface"))
          && !inside(reference(d, p, from), d.definition.path("playSurface"))
          && inside(reference(d, p, to), d.definition.path("playSurface"))) a.joinedCharge = true;
    }
    if (actions.stream().noneMatch(a -> Boolean.TRUE.equals(a.joinedCharge))) {
      boolean needsCharge = false;
      for (String id : moving.sources) {
        JsonNode src = source(d, id);
        if (src.path("pieceIds").size() > 1) needsCharge = true;
        for (JsonNode p : src.path("pieceIds"))
          if (inside(reference(d, p.textValue(), from), d.definition.path("playSurface"))
              || inside(reference(d, p.textValue(), to), d.definition.path("playSurface")))
            needsCharge = true;
      }
      if (needsCharge) action(d, moving.actions.getFirst()).joinedCharge = true;
    }
  }

  private static JsonNode piece(Draft d, String id) {
    String k = uuid(id);
    for (JsonNode p : d.definition.path("pieces")) if (id(p, "pieceId").equals(k)) return p;
    throw fail("INVALID_ACTION", "Unknown piece");
  }

  private static DraftGroup group(Draft d, String id) {
    return d.groups.stream()
        .filter(g -> id(g.group, "groupId").equals(id))
        .findFirst()
        .orElseThrow(() -> fail("INVALID_ACTION", "Unknown group"));
  }

  private static Action action(Draft d, String id) {
    return d.actions.stream().filter(a -> a.id.equals(id)).findFirst().orElse(null);
  }

  private static JsonNode findSource(Draft d, String id) {
    return findGroup(d.base, id);
  }

  private static JsonNode source(Draft d, String id) {
    JsonNode g = findSource(d, id);
    if (g == null) throw fail("INVALID_ACTION", "Unknown source");
    return g;
  }

  private static JsonNode findGroup(JsonNode board, String groupId) {
    for (JsonNode g : board.path("groups")) if (id(g, "groupId").equals(groupId)) return g;
    return null;
  }

  private static boolean sameGroup(JsonNode a, JsonNode b) {
    Set<String> left = new HashSet<>(), right = new HashSet<>();
    for (JsonNode p : a.path("pieceIds")) left.add(uuid(p.textValue()));
    for (JsonNode p : b.path("pieceIds")) right.add(uuid(p.textValue()));
    return id(a, "groupId").equals(id(b, "groupId"))
        && a.path("revision").longValue() == b.path("revision").longValue()
        && a.path("finalPosition").booleanValue() == b.path("finalPosition").booleanValue()
        && sameTransform(a.path("transform"), b.path("transform"))
        && left.equals(right);
  }

  /** Quarter-turn control about a visible puzzle-space pivot; no browser coordinates are used. */
  static ObjectNode rotateAround(JsonNode transform, JsonNode pivot, int delta) {
    JsonNode rotation = transform.path("rotation");
    if (rotation.isIntegralNumber()
        && rotation.canConvertToLong()
        && rotation.longValue() >= 0
        && rotation.longValue() < 360000
        && rotation.longValue() % 90000 != 0) {
      throw fail("UNSUPPORTED_LEGACY_ORIENTATION", "Legacy orientations cannot be mutated");
    }
    validateTransform(transform);
    JsonNode px = pivot.path("x"), py = pivot.path("y");
    if (!px.isIntegralNumber()
        || !px.canConvertToLong()
        || px.longValue() < -1_000_000_000L
        || px.longValue() > 1_000_000_000L
        || !py.isIntegralNumber()
        || !py.canConvertToLong()
        || py.longValue() < -1_000_000_000L
        || py.longValue() > 1_000_000_000L
        || delta % 90000 != 0) throw fail("INVALID_ACTION", "Invalid pivot or turn");
    long x = transform.path("translation").path("x").longValue() - px.longValue();
    long y = transform.path("translation").path("y").longValue() - py.longValue();
    int turn = Math.floorMod(delta, 360000);
    Point rotated =
        switch (turn) {
          case 0 -> new Point(x, y);
          case 90000 -> new Point(-y, x);
          case 180000 -> new Point(-x, -y);
          case 270000 -> new Point(y, -x);
          default -> throw fail("INVALID_ACTION", "Invalid turn");
        };
    ObjectNode result = MAPPER.createObjectNode();
    result
        .putObject("translation")
        .put("x", px.longValue() + rotated.x)
        .put("y", py.longValue() + rotated.y);
    result.put("rotation", Math.floorMod(rotation.intValue() + turn, 360000));
    validateTransform(result);
    return result;
  }

  private static boolean sameTransform(JsonNode a, JsonNode b) {
    return a.path("rotation").longValue() == b.path("rotation").longValue()
        && a.path("translation").path("x").longValue()
            == b.path("translation").path("x").longValue()
        && a.path("translation").path("y").longValue()
            == b.path("translation").path("y").longValue();
  }

  private static void checkOrientation(JsonNode snapshot) {
    for (JsonNode g : snapshot.path("groups"))
      if (g.path("transform").path("rotation").intValue() % 90000 != 0)
        throw fail("UNSUPPORTED_LEGACY_ORIENTATION", "Legacy orientations cannot be mutated");
  }

  private static void validateTransform(JsonNode transform) {
    JsonNode r = transform.path("rotation"),
        x = transform.path("translation").path("x"),
        y = transform.path("translation").path("y");
    if (!r.isIntegralNumber()
        || !r.canConvertToLong()
        || r.longValue() < 0
        || r.longValue() >= 360000
        || r.longValue() % 90000 != 0
        || !x.isIntegralNumber()
        || !x.canConvertToLong()
        || x.longValue() < -1_000_000_000L
        || x.longValue() > 1_000_000_000L
        || !y.isIntegralNumber()
        || !y.canConvertToLong()
        || y.longValue() < -1_000_000_000L
        || y.longValue() > 1_000_000_000L) throw fail("INVALID_ACTION", "Invalid transform");
  }

  private static Point reference(Draft d, String id, JsonNode transform) {
    JsonNode p = piece(d, id);
    long x =
        p.path("solvedPosition").path("x").longValue()
            + p.path("referencePoint").path("x").longValue();
    long y =
        p.path("solvedPosition").path("y").longValue()
            + p.path("referencePoint").path("y").longValue();
    long tx = transform.path("translation").path("x").longValue(),
        ty = transform.path("translation").path("y").longValue();
    return switch (transform.path("rotation").intValue()) {
      case 0 -> new Point(x + tx, y + ty);
      case 90000 -> new Point(-y + tx, x + ty);
      case 180000 -> new Point(-x + tx, -y + ty);
      case 270000 -> new Point(y + tx, -x + ty);
      default -> throw fail("INVALID_ACTION", "Invalid rotation");
    };
  }

  private static boolean inside(Point p, JsonNode rect) {
    return p.x >= rect.path("min").path("x").longValue()
        && p.x < rect.path("max").path("x").longValue()
        && p.y >= rect.path("min").path("y").longValue()
        && p.y < rect.path("max").path("y").longValue();
  }

  private static void checkWorkspace(Draft d, JsonNode pieces, JsonNode transform) {
    for (JsonNode p : pieces)
      if (!inside(reference(d, p.textValue(), transform), d.definition.path("workspace")))
        throw fail("INVALID_ACTION", "Group outside workspace");
  }

  private static void checkConnected(JsonNode definition, JsonNode snapshot) {
    Map<String, JsonNode> pieces = new HashMap<>();
    for (JsonNode p : definition.path("pieces")) pieces.put(id(p, "pieceId"), p);
    for (JsonNode group : snapshot.path("groups")) {
      Set<String> unseen = new HashSet<>();
      for (JsonNode p : group.path("pieceIds")) unseen.add(uuid(p.textValue()));
      List<String> queue = new ArrayList<>();
      String first = unseen.iterator().next();
      unseen.remove(first);
      queue.add(first);
      while (!queue.isEmpty()) {
        JsonNode p = pieces.get(queue.removeLast());
        for (JsonNode edge : p.path("edges"))
          if (edge.path("neighborPieceId").isTextual()) {
            String next = uuid(edge.path("neighborPieceId").textValue());
            if (unseen.remove(next)) queue.add(next);
          }
      }
      if (!unseen.isEmpty()) throw fail("INVALID_ACTION", "Disconnected group");
    }
  }
}
