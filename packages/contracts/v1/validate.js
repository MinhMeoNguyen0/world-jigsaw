import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import schema from './schema.json' with { type: 'json' }
const ajv = new Ajv({ allErrors: true, strict: false })
addFormats(ajv)
ajv.addSchema(schema)

/** Shape validation only. Cross-record and game authority checks are separate. */
export function validate(type, value) {
  if (!Object.hasOwn(schema.$defs, type)) throw new Error(`Unknown contract type: ${type}`)
  const check = ajv.getSchema(`${schema.$id}#/$defs/${type}`)
  if (!check(value)) return { valid: false, errors: structuredClone(check.errors) }
  const errors = []
  if (type === 'Allowance' && value.used + value.remaining !== value.limit)
    errors.push('allowance must add up to limit')
  if (type === 'Confirm') {
    const ids = new Set(value.actions.map((action) => action.actionId))
    if (ids.size !== value.actions.length) errors.push('duplicate action ID')
    for (const action of value.actions) {
      if (action.dependsOn.includes(action.actionId) || action.dependsOn.some((id) => !ids.has(id)))
        errors.push('invalid action dependency')
      if (
        new Set(action.baseRevisions.map((base) => base.groupId)).size !==
        action.baseRevisions.length
      )
        errors.push('duplicate base revision')
    }
    const visiting = new Set(),
      visited = new Set()
    const actions = new Map(value.actions.map((action) => [action.actionId, action]))
    function visit(id) {
      if (visiting.has(id)) return false
      if (visited.has(id)) return true
      visiting.add(id)
      for (const dependency of actions.get(id)?.dependsOn ?? [])
        if (actions.has(dependency) && !visit(dependency)) return false
      visiting.delete(id)
      visited.add(id)
      return true
    }
    if ([...ids].some((id) => !visit(id))) errors.push('cyclic action dependencies')
  }
  if (
    type === 'Subscribed' &&
    (value.snapshot.boardVersion !== value.watermark ||
      value.previews.some((preview) => preview.puzzleId !== value.snapshot.puzzleId))
  )
    errors.push('inconsistent subscription handoff')
  if (type === 'PuzzleChanged' && value.available !== (value.puzzleId !== null))
    errors.push('inconsistent puzzle availability')
  if (
    type === 'CurrentGame' &&
    (value.definition.puzzleId !== value.snapshot.puzzleId ||
      value.definition.definitionVersion !== value.snapshot.definitionVersion)
  )
    errors.push('inconsistent current game')
  if (type === 'CommandResult') {
    if (value.allowance !== null && !validate('Allowance', value.allowance).valid)
      errors.push('invalid allowance')
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
    definition.puzzleId !== snapshot.puzzleId ||
    definition.definitionVersion !== snapshot.definitionVersion
  )
    fail('snapshot definition identity mismatch')
  const { min, max } = definition.bounds
  if (min.x >= max.x || min.y >= max.y) fail('invalid definition bounds')
  const pieces = new Map()
  for (const piece of definition.pieces) {
    if (pieces.has(piece.pieceId)) fail('duplicate piece ID')
    pieces.set(piece.pieceId, piece)
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
      const other = pieces.get(edge.neighborPieceId)
      const back = other?.edges.find((e) => e.edgeId === edge.neighborEdgeId)
      if (
        other === piece ||
        !back ||
        back.neighborPieceId !== piece.pieceId ||
        back.neighborEdgeId !== edge.edgeId
      )
        fail('non-reciprocal adjacency')
    }
  }
  const groups = new Set()
  const seen = new Set()
  for (const group of snapshot.groups) {
    if (groups.has(group.groupId)) fail('duplicate group ID')
    groups.add(group.groupId)
    if (!definition.allowedRotations.includes(group.transform.rotation))
      fail('rotation not allowed')
    for (const id of group.pieceIds) {
      if (!pieces.has(id)) fail('unknown piece in board')
      if (seen.has(id)) fail('piece appears in multiple groups')
      seen.add(id)
    }
  }
  if (seen.size !== pieces.size) fail('board must contain each definition piece exactly once')
  return { valid: errors.length === 0, errors }
}

/** Apply a durable event only if it is the next version or a known duplicate. */
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value !== null && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
      .join(',')}}`
  return JSON.stringify(value)
}

export function applyBoardChanged(definition, snapshot, event, seenEvents = {}) {
  if (
    !validateBoard(definition, snapshot).valid ||
    !validate('BoardChanged', event).valid ||
    event.puzzleId !== snapshot.puzzleId
  )
    return { status: 'resync' }
  if (event.boardVersion <= snapshot.boardVersion) {
    return { status: seenEvents[event.boardVersion] === canonical(event) ? 'duplicate' : 'resync' }
  }
  if (event.boardVersion !== snapshot.boardVersion + 1) return { status: 'resync' }
  const groups = new Map(snapshot.groups.map((group) => [group.groupId, group]))
  for (const id of event.removedGroupIds) groups.delete(id)
  for (const group of event.groups) groups.set(group.groupId, group)
  const next = { ...snapshot, boardVersion: event.boardVersion, groups: [...groups.values()] }
  if (!validateBoard(definition, next).valid) return { status: 'resync' }
  return {
    status: 'applied',
    snapshot: next,
    seenEvents: { ...seenEvents, [event.boardVersion]: canonical(event) },
  }
}

/** Reducer for one turn's transient overlay and generation fence. */
export function applyPreview(state, event) {
  if (!['preview', 'previewCleared'].includes(event?.type)) return state
  if (!validate(event.type === 'preview' ? 'Preview' : 'PreviewCleared', event).valid) return state
  if (state && (event.puzzleId !== state.puzzleId || event.turnId !== state.turnId)) return state
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
    actions: { ...next.actions, [event.action.actionId]: event.action },
  }
}
