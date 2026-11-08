import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import schema from './schema.json' with { type: 'json' }
const ajv = new Ajv({ allErrors: true, strict: false })
addFormats(ajv)
ajv.addSchema(schema)

const uuid = (id) => id.toLowerCase()
const uniqueIds = (ids) => new Set(ids.map(uuid)).size === ids.length
const uuidArrays = new Set(['pieceIds', 'affectedPieceIds', 'removedGroupIds'])
function uniqueUuidArrays(value) {
  if (Array.isArray(value)) return value.every(uniqueUuidArrays)
  if (value === null || typeof value !== 'object') return true
  return Object.entries(value).every(([key, item]) => {
    if (uuidArrays.has(key)) return uniqueIds(item)
    if (key === 'baseRevisions') return uniqueIds(item.map((base) => base.groupId))
    return uniqueUuidArrays(item)
  })
}

/** Validate shape and selected invariants; game authority checks are separate. */
export function validate(type, value) {
  if (!Object.hasOwn(schema.$defs, type)) throw new Error(`Unknown contract type: ${type}`)
  const check = ajv.getSchema(`${schema.$id}#/$defs/${type}`)
  if (!check(value)) return { valid: false, errors: structuredClone(check.errors) }
  const errors = []
  if (!uniqueUuidArrays(value)) errors.push('duplicate UUID in array')
  if (type === 'Allowance' && value.used + value.remaining !== value.limit)
    errors.push('allowance must add up to limit')
  if (
    ['CurrentGame', 'SubscribedV2', 'CancelResult', 'CommandResult'].includes(type) &&
    value.allowance !== null &&
    !validate('Allowance', value.allowance).valid
  )
    errors.push('invalid allowance')
  if (['Confirm', 'ConfirmV2', 'PreviewAcceptedV2', 'SubscribedV2'].includes(type)) {
    const ids = new Set(value.actions.map((action) => uuid(action.actionId)))
    if (ids.size !== value.actions.length) errors.push('duplicate action ID')
  }
  if (
    ['Subscribed', 'SubscribedV2'].includes(type) &&
    (value.snapshot.boardVersion !== value.watermark ||
      value.previews.some((preview) => uuid(preview.puzzleId) !== uuid(value.snapshot.puzzleId)))
  )
    errors.push('inconsistent subscription handoff')
  if (type === 'PuzzleChanged' && value.available !== (value.puzzleId !== null))
    errors.push('inconsistent puzzle availability')
  if (
    type === 'CurrentGame' &&
    (uuid(value.definition.puzzleId) !== uuid(value.snapshot.puzzleId) ||
      value.definition.definitionVersion !== value.snapshot.definitionVersion)
  )
    errors.push('inconsistent current game')
  if (type === 'BoardChanged' && !uniqueIds(value.groups.map((group) => group.groupId)))
    errors.push('duplicate event group ID')
  if (type === 'ChatAssertionClaims') {
    if (value.exp <= value.iat || value.exp - value.iat > 300)
      errors.push('invalid assertion lifetime')
    if (value.displayName.length > 128) errors.push('display name exceeds UTF-16 limit')
  }
  if (['Session', 'ChatMessage'].includes(type) && value.displayName.length > 128)
    errors.push('display name exceeds UTF-16 limit')
  if (type === 'CommandResult') {
    if (
      value.outcome === 'confirmed' &&
      (value.boardVersion === null || value.allowance === null || value.error !== null)
    )
      errors.push('confirmed result requires board version and allowance')
    if (value.outcome === 'rejected' && value.error === null)
      errors.push('rejected result requires error')
    if (
      ['pending', 'unknown'].includes(value.outcome) &&
      (value.boardVersion !== null || value.allowance !== null || value.error !== null)
    )
      errors.push('unresolved outcome cannot assert a result')
  }
  return { valid: errors.length === 0, errors }
}

/** Validate definition and canonical membership as one coherent board. */
export function validateBoard(definition, snapshot) {
  const errors = []
  for (const [name, value] of [
    ['Definition', definition],
    ['Snapshot', snapshot],
  ]) {
    const result = validate(name, value)
    if (!result.valid) errors.push(`${name}: ${ajv.errorsText(result.errors)}`)
  }
  if (errors.length) return { valid: false, errors }
  const fail = (message) => errors.push(message)
  if (
    uuid(definition.puzzleId) !== uuid(snapshot.puzzleId) ||
    definition.definitionVersion !== snapshot.definitionVersion
  )
    fail('snapshot definition identity mismatch')
  for (const rect of [definition.imageBounds, definition.playSurface, definition.workspace]) {
    if (rect.min.x >= rect.max.x || rect.min.y >= rect.max.y) fail('invalid definition bounds')
  }
  for (const rect of [definition.imageBounds, definition.playSurface]) {
    if (
      rect.min.x < definition.workspace.min.x ||
      rect.min.y < definition.workspace.min.y ||
      rect.max.x > definition.workspace.max.x ||
      rect.max.y > definition.workspace.max.y
    )
      fail('bounds outside workspace')
  }
  const pieces = new Map()
  for (const piece of definition.pieces) {
    if (pieces.has(uuid(piece.pieceId))) fail('duplicate piece ID')
    pieces.set(uuid(piece.pieceId), piece)
    const edges = new Set()
    for (const edge of piece.edges) {
      if (edges.has(edge.edgeId)) fail('duplicate edge ID on piece')
      edges.add(edge.edgeId)
      if ((edge.neighborPieceId === null) !== (edge.neighborEdgeId === null))
        fail('incomplete adjacency')
    }
  }
  for (const piece of definition.pieces) {
    for (const edge of piece.edges) {
      if (edge.neighborPieceId === null) continue
      const other = pieces.get(uuid(edge.neighborPieceId))
      const back = other?.edges.find((e) => e.edgeId === edge.neighborEdgeId)
      if (
        other === piece ||
        !back ||
        back.neighborPieceId === null ||
        uuid(back.neighborPieceId) !== uuid(piece.pieceId) ||
        back.neighborEdgeId !== edge.edgeId
      )
        fail('non-reciprocal adjacency')
    }
  }
  const groups = new Set()
  const seen = new Set()
  for (const group of snapshot.groups) {
    if (groups.has(uuid(group.groupId))) fail('duplicate group ID')
    groups.add(uuid(group.groupId))
    for (const id of group.pieceIds) {
      if (!pieces.has(uuid(id))) fail('unknown piece in board')
      if (seen.has(uuid(id))) fail('piece appears in multiple groups')
      seen.add(uuid(id))
    }
  }
  if (seen.size !== pieces.size) fail('board must contain each definition piece exactly once')
  return { valid: errors.length === 0, errors }
}

const eventIdFields = new Set([
  'puzzleId',
  'eventId',
  'commandId',
  'groupId',
  'pieceIds',
  'removedGroupIds',
])
function canonical(value, key = '') {
  if (Array.isArray(value)) return `[${value.map((item) => canonical(item, key)).join(',')}]`
  if (value !== null && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key], key)}`)
      .join(',')}}`
  return JSON.stringify(typeof value === 'string' && eventIdFields.has(key) ? uuid(value) : value)
}

/** Apply a durable event only if it is the next version or a known duplicate. */
export function applyBoardChanged(
  definition,
  snapshot,
  event,
  seenEvents = { byVersion: new Map(), eventIds: new Set() },
) {
  if (
    !validateBoard(definition, snapshot).valid ||
    !validate('BoardChanged', event).valid ||
    uuid(event.puzzleId) !== uuid(snapshot.puzzleId)
  )
    return { status: 'resync' }
  if (event.boardVersion <= snapshot.boardVersion) {
    return {
      status:
        seenEvents.byVersion.get(event.boardVersion) === canonical(event) ? 'duplicate' : 'resync',
    }
  }
  if (event.boardVersion !== snapshot.boardVersion + 1) return { status: 'resync' }
  if (seenEvents.eventIds.has(uuid(event.eventId))) return { status: 'resync' }
  const groups = new Map(snapshot.groups.map((group) => [uuid(group.groupId), group]))
  for (const id of event.removedGroupIds) groups.delete(uuid(id))
  for (const group of event.groups) groups.set(uuid(group.groupId), group)
  const next = { ...snapshot, boardVersion: event.boardVersion, groups: [...groups.values()] }
  if (!validateBoard(definition, next).valid) return { status: 'resync' }
  seenEvents.byVersion.set(event.boardVersion, canonical(event))
  seenEvents.eventIds.add(uuid(event.eventId))
  return { status: 'applied', snapshot: next, seenEvents }
}

/** Reducer for one turn's transient overlay and generation fence. */
export function applyPreview(state, event) {
  if (!['preview', 'previewCleared'].includes(event?.type)) return state
  if (!validate(event.type === 'preview' ? 'Preview' : 'PreviewCleared', event).valid) return state
  if (
    state &&
    (uuid(event.puzzleId) !== uuid(state.puzzleId) || uuid(event.turnId) !== uuid(state.turnId))
  )
    return state
  const current = state ?? {
    puzzleId: event.puzzleId,
    turnId: event.turnId,
    generation: event.generation,
    sequence: -1,
    closed: false,
    actions: {},
  }
  if (
    event.generation < current.generation ||
    (event.generation === current.generation && current.closed)
  )
    return current
  const next =
    event.generation > current.generation
      ? { ...current, generation: event.generation, sequence: -1, closed: false, actions: {} }
      : current
  if (event.type === 'previewCleared') return { ...next, closed: true, actions: {} }
  if (event.sequence <= next.sequence) return next
  return {
    ...next,
    sequence: event.sequence,
    actions: { ...next.actions, [uuid(event.action.actionId)]: event.action },
  }
}

/** Half-open play-surface classification in puzzle coordinates. */
export function isInsideSurface(point, surface) {
  return (
    point.x >= surface.min.x &&
    point.x < surface.max.x &&
    point.y >= surface.min.y &&
    point.y < surface.max.y
  )
}

/** Distinct pieces in connected groups in a validated canonical snapshot. */
export function progressPieceCount(snapshot) {
  return snapshot.groups
    .filter((group) => group.pieceIds.length > 1)
    .reduce((total, group) => total + group.pieceIds.length, 0)
}
