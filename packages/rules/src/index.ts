import { validateBoard } from '@world-jigsaw/contracts'
import type {
  Allowance,
  Definition,
  Edge,
  Group,
  Piece,
  Point,
  Snapshot,
  Transform,
} from '@world-jigsaw/contracts'

// All operations return new values. No transport, leases, clock, or persistence is implied.
export type RuleCode =
  'INVALID_ACTION' | 'STALE_REVISION' | 'QUOTA_EXCEEDED' | 'UNSUPPORTED_LEGACY_ORIENTATION'
export class RuleError extends Error {
  readonly code: RuleCode
  constructor(code: RuleCode, message: string) {
    super(message)
    this.code = code
  }
}
type DraftGroup = {
  group: Group
  sources: string[]
  actions: string[]
  children?: DraftGroup[]
  beforeJoin?: PendingAction[]
  adjustments?: { actionId: string; transform: Transform }[]
}
type PendingAction = { id: string; source: string; entered: boolean; joinedCharge?: boolean }
export type Draft = {
  definition: Definition
  base: Snapshot
  allowance: Allowance
  groups: DraftGroup[]
  actions: PendingAction[]
  generation: number
}
const fail = (code: RuleCode, message: string): never => {
  throw new RuleError(code, message)
}
const key = (id: string) => id.toLowerCase()
const same = (a: string, b: string) => key(a) === key(b)
const copy = <T>(x: T): T => structuredClone(x)
const integer = (n: number) => Number.isSafeInteger(n)
const round = (n: number) => Math.floor(n + 0.5)
export const normalizeAngle = (angle: number): number => ((angle % 360000) + 360000) % 360000
const validTransform = (t: Transform) =>
  integer(t.rotation) &&
  t.rotation >= 0 &&
  t.rotation < 360000 &&
  t.rotation % 90000 === 0 &&
  integer(t.translation.x) &&
  integer(t.translation.y) &&
  Math.abs(t.translation.x) <= 1e9 &&
  Math.abs(t.translation.y) <= 1e9
const rotate = (p: Point, angle: number): Point => {
  const rad = (angle * Math.PI) / 180000,
    c = Math.cos(rad),
    s = Math.sin(rad)
  return { x: p.x * c - p.y * s, y: p.x * s + p.y * c }
}
export function transformPoint(p: Point, t: Transform): Point {
  const r = rotate(p, t.rotation)
  return { x: round(r.x + t.translation.x), y: round(r.y + t.translation.y) }
}
export function rotateAround(t: Transform, pivot: Point, delta: number): Transform {
  if (integer(t.rotation) && t.rotation >= 0 && t.rotation < 360000 && t.rotation % 90000 !== 0)
    return fail(
      'UNSUPPORTED_LEGACY_ORIENTATION',
      'Legacy orientations are not supported for interaction',
    )
  if (
    !validTransform(t) ||
    !integer(delta) ||
    delta % 90000 !== 0 ||
    !integer(pivot.x) ||
    !integer(pivot.y)
  )
    return fail('INVALID_ACTION', 'Invalid rotation')
  const turn = normalizeAngle(delta)
  const r = rotate({ x: t.translation.x - pivot.x, y: t.translation.y - pivot.y }, turn)
  const result = {
    translation: { x: round(pivot.x + r.x), y: round(pivot.y + r.y) },
    rotation: normalizeAngle(t.rotation + turn),
  }
  if (!validTransform(result)) return fail('INVALID_ACTION', 'Rotation outside coordinate bounds')
  return result
}
const equalTransform = (a: Transform, b: Transform) =>
  a.rotation === b.rotation &&
  a.translation.x === b.translation.x &&
  a.translation.y === b.translation.y
const checkOrientation = (snapshot: Snapshot) => {
  if (snapshot.groups.some((g) => g.transform.rotation % 90000 !== 0))
    fail('UNSUPPORTED_LEGACY_ORIENTATION', 'Legacy orientations are not supported for interaction')
}
const inside = (p: Point, rect: Definition['playSurface']) =>
  p.x >= rect.min.x && p.x < rect.max.x && p.y >= rect.min.y && p.y < rect.max.y
const piece = (d: Draft, id: string) =>
  d.definition.pieces.find((p) => same(p.pieceId, id)) ?? fail('INVALID_ACTION', 'Unknown piece')
const reference = (d: Draft, id: string, t: Transform) => {
  const p = piece(d, id)
  return transformPoint(
    { x: p.solvedPosition.x + p.referencePoint.x, y: p.solvedPosition.y + p.referencePoint.y },
    t,
  )
}
const checkWorkspace = (d: Draft, g: Group) => {
  if (
    !validTransform(g.transform) ||
    g.pieceIds.some((id) => !inside(reference(d, id, g.transform), d.definition.workspace))
  )
    fail('INVALID_ACTION', 'Group outside workspace')
}
const findGroup = (d: Draft, id: string) =>
  d.groups.find((g) => same(g.group.groupId, id)) ?? fail('INVALID_ACTION', 'Unknown group')
const original = (d: Draft, id: string) =>
  d.base.groups.find((g) => same(g.groupId, id)) ?? fail('INVALID_ACTION', 'Unknown source')
const counted = (d: Draft, a: PendingAction) => {
  if (a.joinedCharge !== undefined) return a.joinedCharge
  const component = d.groups.find((g) => g.actions.some((id) => same(id, a.id)))!
  const src = original(d, a.source)
  if (equalTransform(src.transform, component.group.transform)) return false
  return (
    src.pieceIds.length > 1 ||
    inside(reference(d, src.pieceIds[0], src.transform), d.definition.playSurface) ||
    a.entered
  )
}
const chargeJoinedMovement = (d: Draft, moving: DraftGroup, to: Transform) => {
  const from = moving.group.transform
  if (equalTransform(from, to)) return
  const actions = d.actions.filter((a) => moving.actions.some((id) => same(id, a.id)))
  for (const action of actions) {
    if (action.joinedCharge) continue
    const src = original(d, action.source)
    if (
      src.pieceIds.length === 1 &&
      !inside(reference(d, src.pieceIds[0], src.transform), d.definition.playSurface) &&
      !inside(reference(d, src.pieceIds[0], from), d.definition.playSurface) &&
      inside(reference(d, src.pieceIds[0], to), d.definition.playSurface)
    )
      action.joinedCharge = true
  }
  if (
    !actions.some((a) => a.joinedCharge) &&
    moving.sources.some((id) => {
      const src = original(d, id)
      return (
        src.pieceIds.length > 1 ||
        src.pieceIds.some(
          (pieceId) =>
            inside(reference(d, pieceId, from), d.definition.playSurface) ||
            inside(reference(d, pieceId, to), d.definition.playSurface),
        )
      )
    })
  )
    d.actions.find((a) => same(a.id, moving.actions[0]))!.joinedCharge = true
}
export const pendingCount = (d: Draft) => d.actions.filter((a) => counted(d, a)).length
const checkQuota = (d: Draft) => {
  if (pendingCount(d) > d.allowance.remaining) fail('QUOTA_EXCEEDED', 'Daily allowance exceeded')
}
// Compare each reciprocal contour in solved coordinates, reversing cubic controls.
function reversedEdgeMatches(a: Piece, edge: Edge, b: Piece, back: Edge): boolean {
  const absolute = (p: Point, origin: Point) => ({ x: p.x + origin.x, y: p.y + origin.y })
  const eq = (p: Point, q: Point) => p.x === q.x && p.y === q.y
  const segments = (e: Edge, p: Piece) => {
    let start = absolute(e.start, p.solvedPosition)
    return e.segments.map((s) => {
      const end = absolute(s.to, p.solvedPosition)
      const result =
        s.kind === 'line'
          ? { kind: s.kind, start, end }
          : {
              kind: s.kind,
              start,
              end,
              c1: absolute(s.control1, p.solvedPosition),
              c2: absolute(s.control2, p.solvedPosition),
            }
      start = end
      return result
    })
  }
  const left = segments(edge, a),
    right = segments(back, b).reverse()
  return (
    left.length === right.length &&
    left.every((v, i) => {
      const r = right[i]
      return (
        v.kind === r.kind &&
        eq(v.start, r.end) &&
        eq(v.end, r.start) &&
        (v.kind === 'line' || (r.kind === 'cubic' && eq(v.c1!, r.c2!) && eq(v.c2!, r.c1!)))
      )
    })
  )
}
function validGeometry(definition: Definition, snapshot: Snapshot): boolean {
  const pieces = new Map(definition.pieces.map((p) => [key(p.pieceId), p]))
  for (const p of definition.pieces)
    for (const e of p.edges)
      if (e.neighborPieceId) {
        const other = pieces.get(key(e.neighborPieceId)),
          back = other?.edges.find((x) => x.edgeId === e.neighborEdgeId)
        if (!other || !back || !reversedEdgeMatches(p, e, other, back)) return false
      }
  for (const group of snapshot.groups) {
    const remaining = new Set(group.pieceIds.map(key)),
      queue = [key(group.pieceIds[0])]
    remaining.delete(queue[0])
    while (queue.length)
      for (const edge of pieces.get(queue.pop()!)!.edges) {
        if (edge.neighborPieceId && remaining.delete(key(edge.neighborPieceId)))
          queue.push(key(edge.neighborPieceId))
      }
    if (remaining.size) return false
  }
  return true
}
const validBoard = (definition: Definition, snapshot: Snapshot) =>
  validateBoard(definition, snapshot).valid && validGeometry(definition, snapshot)
const sameGroup = (a: Group, b: Group) =>
  same(a.groupId, b.groupId) &&
  a.revision === b.revision &&
  a.finalPosition === b.finalPosition &&
  equalTransform(a.transform, b.transform) &&
  a.pieceIds.length === b.pieceIds.length &&
  a.pieceIds.every((id) => b.pieceIds.some((other) => same(id, other)))
const uuid = (id: string) =>
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
export function begin(definition: Definition, snapshot: Snapshot, allowance: Allowance): Draft {
  if (
    !validBoard(definition, snapshot) ||
    allowance.limit !== 5 ||
    !integer(allowance.used) ||
    !integer(allowance.remaining) ||
    allowance.used < 0 ||
    allowance.remaining < 0 ||
    allowance.used + allowance.remaining !== 5
  )
    return fail('INVALID_ACTION', 'Invalid board or allowance')
  return {
    definition: copy(definition),
    base: copy(snapshot),
    allowance: copy(allowance),
    groups: snapshot.groups.map((group) => ({
      group: copy(group),
      sources: [group.groupId],
      actions: [],
    })),
    actions: [],
    generation: 0,
  }
}
const applyMove = (
  d: Draft,
  g: DraftGroup,
  action: PendingAction,
  transform: Transform,
  replay: boolean,
) => {
  checkWorkspace(d, { ...g.group, transform })
  if (g.sources.length > 1) {
    const changed = !equalTransform(g.group.transform, transform)
    if (changed) chargeJoinedMovement(d, g, transform)
    if (changed || replay)
      g.adjustments = [
        ...(g.adjustments ?? []).filter((item) => !same(item.actionId, action.id)),
        { actionId: action.id, transform: copy(transform) },
      ]
  }
  g.group.transform = copy(transform)
  const src = original(d, action.source)
  if (
    g.sources.length === 1 &&
    src.pieceIds.length === 1 &&
    !inside(reference(d, src.pieceIds[0], src.transform), d.definition.playSurface)
  ) {
    action.entered = inside(reference(d, src.pieceIds[0], transform), d.definition.playSurface)
  }
}
/** Start an action on a canonical group, or adjust an existing draft group with its action ID. */
export function move(draft: Draft, groupId: string, actionId: string, transform: Transform): Draft {
  checkOrientation(draft.base)
  const d = copy(draft),
    g = findGroup(d, groupId)
  if (!uuid(actionId) || !validTransform(transform))
    return fail('INVALID_ACTION', 'Invalid transform')
  let action = d.actions.find((a) => same(a.id, actionId))
  if (action && !g.actions.some((id) => same(id, actionId)))
    return fail('INVALID_ACTION', 'Action belongs to another group')
  if (!action) {
    if (g.actions.length || g.sources.length !== 1)
      return fail('INVALID_ACTION', 'Use an existing action to adjust a pending group')
    action = { id: actionId, source: g.sources[0], entered: false }
    d.actions.push(action)
    g.actions.push(actionId)
  }
  applyMove(d, g, action, transform, false)
  checkQuota(d)
  return d
}
/** Snap moving group to an adjacent target when rotation and reference displacement are within tolerance. */
export function attach(
  draft: Draft,
  movingId: string,
  targetId: string,
  mergedId: string,
  distance: number,
  angle: number,
): Draft {
  checkOrientation(draft.base)
  const d = copy(draft),
    moving = findGroup(d, movingId),
    target = findGroup(d, targetId)
  if (
    !uuid(mergedId) ||
    moving === target ||
    !moving.actions.length ||
    !integer(distance) ||
    distance < 0 ||
    !integer(angle) ||
    angle < 0 ||
    angle > 180000 ||
    d.groups.some((g) => same(g.group.groupId, mergedId)) ||
    d.base.groups.some((g) => same(g.groupId, mergedId))
  )
    return fail('INVALID_ACTION', 'Invalid attachment')
  const neighbor = moving.group.pieceIds.find((id) =>
    piece(d, id).edges.some(
      (e) => e.neighborPieceId && target.group.pieceIds.some((p) => same(p, e.neighborPieceId!)),
    ),
  )
  if (!neighbor) return fail('INVALID_ACTION', 'Pieces are not adjacent')
  const delta = Math.abs(moving.group.transform.rotation - target.group.transform.rotation)
  const a = reference(d, neighbor, moving.group.transform),
    b = reference(d, neighbor, target.group.transform)
  if (Math.min(delta, 360000 - delta) > angle || Math.hypot(a.x - b.x, a.y - b.y) > distance)
    return fail('INVALID_ACTION', 'Outside snap tolerance')
  const group: Group = {
    groupId: mergedId,
    revision: 0,
    pieceIds: [...moving.group.pieceIds, ...target.group.pieceIds] as Group['pieceIds'],
    transform: copy(target.group.transform),
    finalPosition: false,
  }
  checkWorkspace(d, group)
  const children = [copy(moving), copy(target)]
  const beforeJoin = copy(
    d.actions.filter((action) =>
      [...moving.actions, ...target.actions].some((id) => same(id, action.id)),
    ),
  )
  for (const action of d.actions)
    if ([...moving.actions, ...target.actions].some((id) => same(id, action.id)))
      action.joinedCharge = counted(d, action)
  chargeJoinedMovement(d, moving, target.group.transform)
  d.groups = d.groups.filter((g) => g !== moving && g !== target)
  d.groups.push({
    group,
    sources: [...moving.sources, ...target.sources],
    actions: [...moving.actions, ...target.actions],
    children,
    beforeJoin,
  })
  checkQuota(d)
  return d
}
export function undo(draft: Draft, actionId: string): Draft {
  checkOrientation(draft.base)
  const d = copy(draft),
    action = d.actions.find((a) => same(a.id, actionId)) ?? fail('INVALID_ACTION', 'Unknown action')
  let g = d.groups.find((g) => g.actions.some((id) => same(id, action.id)))!
  while (g.children) {
    const previous = g.beforeJoin!
    const children = copy(g.children)
    d.groups = [...d.groups.filter((group) => group !== g), ...children]
    d.actions = d.actions.map((item) =>
      copy(previous.find((saved) => same(saved.id, item.id)) ?? item),
    )
    for (const adjustment of g.adjustments ?? []) {
      if (same(adjustment.actionId, action.id)) continue
      const child = children.find((item) =>
        item.actions.some((id) => same(id, adjustment.actionId)),
      )!
      const survivor = d.actions.find((item) => same(item.id, adjustment.actionId))!
      applyMove(d, child, survivor, adjustment.transform, true)
    }
    g = d.groups.find((group) => group.actions.some((id) => same(id, action.id)))!
  }
  g.group = copy(original(d, action.source))
  g.actions = []
  d.actions = d.actions.filter((a) => !same(a.id, actionId))
  checkQuota(d)
  d.generation++
  return d
}
/** A cancel clears overlays; caller displays its latest canonical snapshot. */
export function cancel(_draft: Draft, latest: Snapshot): Snapshot {
  return copy(latest)
}
/** Pure proposed result; caller must atomically revalidate and commit it with quota. */
export function confirm(
  d: Draft,
  latest: Snapshot,
  allowance: Allowance,
): { snapshot: Snapshot; allowance: Allowance; charged: number } {
  if (
    !validBoard(d.definition, latest) ||
    allowance.limit !== 5 ||
    !integer(allowance.used) ||
    !integer(allowance.remaining) ||
    allowance.used < 0 ||
    allowance.remaining < 0 ||
    allowance.used + allowance.remaining !== 5
  )
    return fail('INVALID_ACTION', 'Invalid latest board or allowance')
  checkOrientation(d.base)
  checkOrientation(latest)
  if (latest.boardVersion < d.base.boardVersion)
    return fail('STALE_REVISION', 'Latest board is older than draft')
  const touched = d.groups.filter((g) => g.actions.length > 0)
  const changed = touched.filter(
    (g) =>
      g.sources.length > 1 ||
      !equalTransform(g.group.transform, original(d, g.sources[0]).transform),
  )
  for (const g of touched)
    for (const source of g.sources) {
      const old = original(d, source),
        now = latest.groups.find((x) => same(x.groupId, source))
      if (!now || now.revision !== old.revision || !sameGroup(now, old))
        return fail('STALE_REVISION', 'Touched group changed')
    }
  const charged = pendingCount(d)
  if (charged > allowance.remaining) return fail('QUOTA_EXCEEDED', 'Daily allowance changed')
  const removed = new Set(changed.flatMap((g) => g.sources.map(key)))
  const groups = latest.groups.filter((g) => !removed.has(key(g.groupId))).map(copy)
  for (const g of changed) {
    const item = copy(g.group)
    if (g.sources.length === 1)
      item.revision =
        original(d, g.sources[0]).revision +
        (equalTransform(item.transform, original(d, g.sources[0]).transform) ? 0 : 1)
    item.finalPosition = equalTransform(item.transform, {
      rotation: 0,
      translation: { x: 0, y: 0 },
    })
    groups.push(item)
  }
  const snapshot = {
    ...copy(latest),
    boardVersion: latest.boardVersion + (changed.length ? 1 : 0),
    groups: groups as Snapshot['groups'],
  }
  if (!validBoard(d.definition, snapshot)) return fail('INVALID_ACTION', 'Invalid result')
  return {
    snapshot,
    allowance: {
      limit: 5,
      used: allowance.used + charged,
      remaining: allowance.remaining - charged,
    },
    charged,
  }
}
export function preview(d: Draft): Group[] {
  return d.groups.filter((g) => g.actions.length > 0).map((g) => copy(g.group))
}
