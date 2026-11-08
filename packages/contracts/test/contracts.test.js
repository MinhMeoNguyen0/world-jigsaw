import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  applyBoardChanged,
  applyPreview,
  isInsideSurface,
  progressPieceCount,
  validate,
  validateBoard,
} from '../v1/validate.js'

const fixture = JSON.parse(
  readFileSync(new URL('../v1/fixtures/board-and-batch.json', import.meta.url)),
)
const chat = JSON.parse(readFileSync(new URL('../v1/fixtures/chat-text.json', import.meta.url)))
const geometry = JSON.parse(
  readFileSync(new URL('../v1/fixtures/geometry-vectors.json', import.meta.url)),
)
const wire = JSON.parse(readFileSync(new URL('../v1/fixtures/chat-wire.json', import.meta.url)))
const copy = structuredClone
const { definition, snapshot, confirm, preview } = fixture
const id = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`

test('fixture is a coherent unique board and a two-action batch', () => {
  assert.deepEqual(validateBoard(definition, snapshot), { valid: true, errors: [] })
  assert.equal(validate('Confirm', confirm).valid, true)
  assert.equal(validate('Preview', preview).valid, true)
  assert.equal(confirm.actions.length, 2)
  assert.equal(confirm.actions[1].affectedPieceIds.length, 2)
  assert.equal(
    fixture.expected.beforeRemaining - confirm.actions.length,
    fixture.expected.afterRemaining,
  )
})

test('wire compatibility rejects unknown versions, extra fields and unsafe revisions', () => {
  for (const changed of [
    { ...preview, schemaVersion: 2 },
    { ...preview, surprise: true },
    { ...preview, sequence: 9007199254740992 },
  ])
    assert.equal(validate('Preview', changed).valid, false)
  assert.equal(validate('Preview', { ...preview, type: 'boardChanged' }).valid, false)
  assert.throws(() => validate('MissingType', {}), /Unknown contract/)
})

test('unique identity, definition version, adjacency and angle bounds are enforced', () => {
  const duplicate = copy(snapshot)
  duplicate.groups[1].pieceIds.push(duplicate.groups[0].pieceIds[0])
  assert.equal(validateBoard(definition, duplicate).valid, false)
  const missing = copy(snapshot)
  missing.groups[0].pieceIds.pop()
  assert.equal(validateBoard(definition, missing).valid, false)
  const stale = { ...snapshot, definitionVersion: 2 }
  assert.equal(validateBoard(definition, stale).valid, false)
  const bad = copy(definition)
  bad.pieces[0].edges[1].neighborEdgeId = 'top'
  assert.equal(validateBoard(bad, snapshot).valid, false)
  const rotation = copy(snapshot)
  rotation.groups[0].transform.rotation = 31500
  assert.equal(validateBoard(definition, rotation).valid, true)
  rotation.groups[0].transform.rotation = 360000
  assert.equal(validateBoard(definition, rotation).valid, false)
})

test('duplicate actions and duplicate base revisions are rejected', () => {
  const duplicate = copy(confirm)
  duplicate.actions[1].actionId = duplicate.actions[0].actionId
  assert.equal(validate('Confirm', duplicate).valid, false)
  const repeatedBase = copy(confirm)
  repeatedBase.actions[0].baseRevisions.push(repeatedBase.actions[0].baseRevisions[0])
  assert.equal(validate('Confirm', repeatedBase).valid, false)
})

test('UUID identity ignores case throughout validation and previews', () => {
  const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const b = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  const c = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
  const batch = copy(confirm)
  batch.actions[0].actionId = a
  batch.actions[1].actionId = a.toUpperCase()
  assert.equal(validate('Confirm', batch).valid, false)
  batch.actions[1].actionId = b
  batch.actions[0].baseRevisions = [
    { groupId: a, revision: 1 },
    { groupId: a.toUpperCase(), revision: 1 },
  ]
  assert.equal(validate('Confirm', batch).valid, false)
  assert.equal(validate('Action', batch.actions[0]).valid, false)
  assert.equal(validate('Preview', { ...preview, action: batch.actions[0] }).valid, false)
  batch.actions[0].baseRevisions.pop()
  batch.actions[0].affectedPieceIds = [a, a.toUpperCase()]
  assert.equal(validate('Confirm', batch).valid, false)
  assert.equal(validate('Action', batch.actions[0]).valid, false)
  assert.equal(
    validate('Group', { ...snapshot.groups[0], pieceIds: [a, a.toUpperCase()] }).valid,
    false,
  )
  assert.equal(
    validate('BoardChanged', { ...fixture.boardChanged, removedGroupIds: [a, a.toUpperCase()] })
      .valid,
    false,
  )

  const renamed = JSON.parse(
    JSON.stringify({ definition, snapshot }).replaceAll(id(1), a).replaceAll(id(100), b),
  )
  renamed.snapshot.puzzleId = b.toUpperCase()
  renamed.snapshot.groups[0].pieceIds[0] = a.toUpperCase()
  assert.equal(validateBoard(renamed.definition, renamed.snapshot).valid, true)
  const repeated = copy(renamed.snapshot)
  repeated.groups[1].pieceIds.push(a)
  assert.equal(validateBoard(renamed.definition, repeated).valid, false)
  const invalidAdjacency = copy(renamed.definition)
  invalidAdjacency.pieces[0].edges[1].neighborPieceId = null
  assert.equal(validateBoard(invalidAdjacency, renamed.snapshot).valid, false)
  assert.equal(
    validate('CurrentGame', {
      schemaVersion: 1,
      day: '2026-11-08',
      serverTime: '2026-11-08T00:00:00Z',
      endsAt: '2026-11-09T00:00:00Z',
      definition: renamed.definition,
      snapshot: renamed.snapshot,
      allowance: null,
      ownTurn: null,
    }).valid,
    true,
  )
  const mixedPreview = {
    ...preview,
    puzzleId: b,
    turnId: c,
    action: { ...preview.action, actionId: a },
  }
  assert.equal(
    validate('Subscribed', {
      schemaVersion: 1,
      type: 'subscribed',
      snapshot: renamed.snapshot,
      watermark: renamed.snapshot.boardVersion,
      previews: [mixedPreview],
    }).valid,
    true,
  )
  let state = applyPreview(null, mixedPreview)
  state = applyPreview(state, {
    ...mixedPreview,
    puzzleId: b.toUpperCase(),
    turnId: c.toUpperCase(),
    sequence: mixedPreview.sequence + 1,
    action: { ...mixedPreview.action, actionId: a.toUpperCase() },
  })
  assert.deepEqual(Object.keys(state.actions), [a])
})

test('board events reject duplicate replacements and use indexed history', () => {
  const a = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const group = { ...snapshot.groups[0], groupId: a }
  const base = { ...snapshot, groups: [group, snapshot.groups[1]] }
  const event = {
    ...fixture.boardChanged,
    groups: [
      group,
      { ...group, groupId: a.toUpperCase(), transform: { ...group.transform, rotation: 1000 } },
    ],
  }
  assert.equal(validate('BoardChanged', event).valid, false)
  assert.equal(applyBoardChanged(definition, base, event).status, 'resync')
  const first = applyBoardChanged(definition, base, {
    ...event,
    groups: [{ ...group, groupId: a.toUpperCase() }],
  })
  assert.equal(first.status, 'applied')
  assert.equal(first.snapshot.groups.length, 2)
  const history = first.seenEvents
  assert.equal(
    applyBoardChanged(definition, first.snapshot, { ...event, groups: [group] }, history).status,
    'duplicate',
  )
  const second = applyBoardChanged(
    definition,
    first.snapshot,
    { ...event, boardVersion: 9, eventId: id(999), groups: [group] },
    history,
  )
  assert.equal(second.status, 'applied')
  assert.equal(second.seenEvents, history)
  assert.equal(history.byVersion.size, 2)
  assert.equal(history.eventIds.size, 2)
  assert.equal(
    applyBoardChanged(
      definition,
      second.snapshot,
      { ...event, boardVersion: 10, groups: [group] },
      history,
    ).status,
    'resync',
  )
})

test('confirmed event applies atomically; duplicates, gaps and invalid membership do not', () => {
  const event = {
    schemaVersion: 1,
    type: 'boardChanged',
    puzzleId: snapshot.puzzleId,
    boardVersion: 8,
    eventId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    commandId: confirm.commandId,
    removedGroupIds: [],
    groups: [
      { ...snapshot.groups[0], revision: 3, transform: confirm.actions[1].intendedTransform },
    ],
  }
  const applied = applyBoardChanged(definition, snapshot, event)
  assert.equal(applied.status, 'applied')
  assert.equal(applied.snapshot.groups[0].transform.translation.x, 0)
  assert.equal(
    applyBoardChanged(definition, applied.snapshot, event, applied.seenEvents).status,
    'duplicate',
  )
  assert.equal(
    applyBoardChanged(
      definition,
      applied.snapshot,
      { ...event, eventId: id(801) },
      applied.seenEvents,
    ).status,
    'resync',
  )
  const next = {
    ...event,
    boardVersion: 9,
    groups: [{ ...event.groups[0], transform: { ...event.groups[0].transform, rotation: 1000 } }],
  }
  assert.equal(
    applyBoardChanged(definition, applied.snapshot, next, applied.seenEvents).status,
    'resync',
  )
  assert.equal(
    applyBoardChanged(
      definition,
      applied.snapshot,
      { ...next, eventId: next.eventId.toUpperCase() },
      applied.seenEvents,
    ).status,
    'resync',
  )
  assert.equal(
    applyBoardChanged(
      definition,
      applied.snapshot,
      { ...next, eventId: id(801) },
      applied.seenEvents,
    ).status,
    'applied',
  )
  assert.equal(
    applyBoardChanged(definition, snapshot, { ...event, boardVersion: 9 }).status,
    'resync',
  )
  assert.equal(
    applyBoardChanged(definition, snapshot, {
      ...event,
      groups: [],
      removedGroupIds: [snapshot.groups[0].groupId],
    }).status,
    'resync',
  )
})

test('clear fences late frames and a later generation may start', () => {
  const clear = {
    schemaVersion: 1,
    type: 'previewCleared',
    puzzleId: preview.puzzleId,
    turnId: preview.turnId,
    generation: preview.generation,
    reason: 'canceled',
  }
  let state = applyPreview(null, preview)
  assert.equal(Object.keys(state.actions).length, 1)
  state = applyPreview(state, clear)
  state = applyPreview(state, { ...preview, sequence: 99 })
  assert.equal(state.closed, true)
  assert.deepEqual(state.actions, {})
  state = applyPreview(state, { ...preview, generation: 2, sequence: 0 })
  assert.equal(state.closed, false)
  assert.equal(Object.keys(state.actions).length, 1)
})

test('undo clears the old preview generation and restores surviving actions', () => {
  const [removed, surviving] = fixture.activePreviews
  let state = applyPreview(applyPreview(null, removed), surviving)
  const clear = {
    schemaVersion: 1,
    type: 'previewCleared',
    puzzleId: removed.puzzleId,
    turnId: removed.turnId,
    generation: removed.generation,
    reason: 'undone',
  }
  assert.equal(validate('PreviewCleared', clear).valid, true)
  state = applyPreview(state, clear)
  assert.equal(state.closed, true)
  assert.deepEqual(state.actions, {})
  state = applyPreview(state, { ...removed, sequence: surviving.sequence + 1 })
  assert.deepEqual(state.actions, {})
  state = applyPreview(state, { ...surviving, generation: clear.generation + 1, sequence: 0 })
  assert.equal(state.closed, false)
  assert.deepEqual(Object.keys(state.actions), [surviving.action.actionId])
})

test('outcomes distinguish unknown, confirmed and rejected; quota is committed', () => {
  const result = {
    schemaVersion: 1,
    puzzleId: confirm.puzzleId,
    commandId: confirm.commandId,
    outcome: 'unknown',
    boardVersion: null,
    allowance: null,
    error: null,
  }
  assert.equal(validate('CommandResult', result).valid, true)
  assert.equal(validate('CommandResult', { ...result, boardVersion: 8 }).valid, false)
  assert.equal(
    validate('CommandResult', {
      ...result,
      outcome: 'confirmed',
      boardVersion: 8,
      allowance: { limit: 5, used: 2, remaining: 3 },
    }).valid,
    true,
  )
  assert.equal(validate('Allowance', { limit: 5, used: 2, remaining: 4 }).valid, false)
  assert.equal(validate('CommandResult', { ...result, outcome: 'rejected' }).valid, false)
})

test('chat vectors cover normalization, boundary, controls and emoji', () => {
  const segmenter = new Intl.Segmenter('en', { granularity: 'grapheme' })
  for (const vector of chat) {
    const normalized = vector.input
      .normalize('NFC')
      .replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
    assert.equal(normalized, vector.normalized, vector.name)
    assert.equal([...segmenter.segment(normalized)].length, vector.clusters, vector.name)
    const valid = normalized.length > 0 && !/\p{Cc}/u.test(vector.input) && vector.clusters <= 40
    assert.equal(valid, vector.valid, vector.name)
  }
})

test('definition checksum is over canonical content excluding the checksum', async () => {
  const { createHash } = await import('node:crypto')
  const { checksum, ...content } = definition
  function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
    if (value !== null && typeof value === 'object')
      return `{${Object.keys(value)
        .sort()
        .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
        .join(',')}}`
    return JSON.stringify(value)
  }
  assert.equal(`sha256:${createHash('sha256').update(canonical(content)).digest('hex')}`, checksum)
})

test('subscription and lifecycle envelopes cannot mix puzzles or claim unavailable content', () => {
  const subscribed = {
    schemaVersion: 1,
    type: 'subscribed',
    snapshot,
    watermark: 7,
    previews: [preview],
  }
  assert.equal(validate('Subscribed', subscribed).valid, true)
  assert.equal(validate('Subscribed', { ...subscribed, watermark: 8 }).valid, false)
  assert.equal(
    validate('Subscribed', { ...subscribed, previews: [{ ...preview, puzzleId: id(999) }] }).valid,
    false,
  )
  assert.equal(
    validate('PuzzleChanged', {
      schemaVersion: 1,
      type: 'puzzleChanged',
      day: '2026-11-08',
      puzzleId: null,
      available: false,
    }).valid,
    true,
  )
  assert.equal(
    validate('PuzzleChanged', {
      schemaVersion: 1,
      type: 'puzzleChanged',
      day: '2026-11-08',
      puzzleId: id(100),
      available: false,
    }).valid,
    false,
  )
})

test('half-open play-surface vectors distinguish free table sorting and entry', () => {
  for (const sample of geometry.surface.samples)
    assert.equal(isInsideSurface(sample.point, geometry.surface.bounds), sample.inside)
})

test('shape validation leaves quota and no-op decisions to authoritative state', () => {
  const example = confirm.actions[0]
  const actions = Array.from({ length: 81 }, (_, i) => ({
    ...example,
    actionId: id(1000 + i),
    kind: i < 75 ? 'sort' : 'piece',
  }))
  assert.equal(validate('Confirm', { ...confirm, actions: actions.slice(75) }).valid, true)
  assert.equal(validate('Confirm', { ...confirm, actions }).valid, true)
  assert.equal(
    validate('Subscribed', {
      schemaVersion: 1,
      type: 'subscribed',
      snapshot,
      watermark: snapshot.boardVersion,
      previews: actions.map((action, sequence) => ({ ...preview, sequence, action })),
    }).valid,
    true,
  )
})

test('all allowance representations enforce committed quota arithmetic', () => {
  const good = { limit: 5, used: 5, remaining: 0 }
  const bad = { limit: 5, used: 5, remaining: 5 }
  const current = {
    schemaVersion: 1,
    day: '2026-11-08',
    serverTime: '2026-11-08T00:00:00Z',
    endsAt: '2026-11-09T00:00:00Z',
    definition,
    snapshot,
    allowance: good,
    ownTurn: null,
  }
  const canceled = {
    schemaVersion: 1,
    puzzleId: confirm.puzzleId,
    turnId: confirm.turnId,
    requestId: id(1400),
    outcome: 'canceled',
    boardVersion: snapshot.boardVersion,
    allowance: good,
    commandId: null,
  }
  const result = { ...fixture.commandResult, allowance: good }
  assert.equal(validate('Allowance', good).valid, true)
  assert.equal(validate('Allowance', bad).valid, false)
  for (const [type, value] of [
    ['CurrentGame', current],
    ['CancelResult', canceled],
    ['CommandResult', result],
  ]) {
    assert.equal(validate(type, value).valid, true, type)
    assert.equal(validate(type, { ...value, allowance: bad }).valid, false, type)
  }
  assert.equal(validate('CurrentGame', { ...current, allowance: null }).valid, true)
})

test('cancel response identifies an in-flight or completed confirm', () => {
  const result = {
    schemaVersion: 1,
    puzzleId: confirm.puzzleId,
    turnId: confirm.turnId,
    requestId: id(1402),
    outcome: 'canceled',
    boardVersion: snapshot.boardVersion,
    allowance: { limit: 5, used: 2, remaining: 3 },
    commandId: null,
  }
  for (const outcome of ['confirming', 'confirmed']) {
    assert.equal(validate('CancelResult', { ...result, outcome }).valid, false)
    assert.equal(
      validate('CancelResult', { ...result, outcome, commandId: confirm.commandId }).valid,
      true,
    )
  }
  for (const outcome of ['canceled', 'already_canceled']) {
    assert.equal(validate('CancelResult', { ...result, outcome }).valid, true)
  }
})

test('undo returns the one selected action identity', () => {
  const undo = {
    schemaVersion: 1,
    puzzleId: confirm.puzzleId,
    turnId: confirm.turnId,
    requestId: id(1401),
    turnRevision: 1,
    generation: 2,
    actionId: confirm.actions[0].actionId,
  }
  assert.equal(validate('Undo', undo).valid, true)
  assert.equal(validate('UndoResult', undo).valid, true)
  assert.equal(validate('UndoResult', { ...undo, actionId: null }).valid, false)
})

test('connected progress counts groups regardless of final position', () => {
  assert.equal(progressPieceCount(snapshot), 2)
  const correctSingleton = copy(snapshot)
  correctSingleton.groups[1].finalPosition = true
  assert.equal(progressPieceCount(correctSingleton), 2)
  const { connectedGroupSizes, loosePieceCount, expectedConnected, total } =
    geometry.connectedProgress
  let index = 0
  const groups = [...connectedGroupSizes, ...Array(loosePieceCount).fill(1)].map((size) => ({
    ...snapshot.groups[0],
    groupId: id(2000 + index),
    pieceIds: Array.from({ length: size }, () => id(3000 + index++)),
    finalPosition: false,
  }))
  assert.equal(index, total)
  assert.equal(progressPieceCount({ ...snapshot, groups }), expectedConnected)
})

test('rotation and cubic vectors fit the wire contract', () => {
  const { center, translation, expectedTranslation } = geometry.rotation
  assert.deepEqual(
    { x: center.x - (translation.y - center.y), y: center.y + (translation.x - center.x) },
    expectedTranslation,
  )
  const edge = {
    edgeId: 'east',
    neighborPieceId: id(2),
    neighborEdgeId: 'west',
    start: geometry.reversedCubic.start,
    segments: [geometry.reversedCubic.forward],
  }
  assert.equal(validate('Edge', edge).valid, true)
  assert.deepEqual(geometry.reversedCubic.forward.control1, geometry.reversedCubic.reverse.control2)
  assert.deepEqual(geometry.reversedCubic.forward.control2, geometry.reversedCubic.reverse.control1)
  assert.deepEqual(geometry.reversedCubic.start, geometry.reversedCubic.reverse.to)
})

test('chat wire accepts long graphemes and exposes HTTP errors', () => {
  assert.equal(validate('ChatSend', wire.post).valid, true)
  assert.equal(validate('ChatAccepted', wire.accepted).valid, true)
  assert.equal(validate('ChatMessage', wire.sse).valid, true)
  assert.equal(validate('ChatMessage', { ...wire.sse, type: 'message' }).valid, false)
  const text = 'a' + '\u0301'.repeat(5000)
  assert.equal([...new Intl.Segmenter('en', { granularity: 'grapheme' }).segment(text)].length, 1)
  assert.equal(validate('ChatSend', { ...wire.post, text }).valid, true)
  assert.equal(validate('ChatMessage', { ...wire.sse, text }).valid, true)
  for (const sample of wire.httpErrors) {
    assert.equal(validate('ErrorResponse', sample.envelope).valid, true)
    assert.equal(sample.envelope.error.retryable, false)
    assert.equal(
      sample.envelope.error.code,
      sample.status === 404 ? 'NOT_FOUND' : 'METHOD_NOT_ALLOWED',
    )
  }
})

test('identity assertion claims allow multi-codepoint display names and bound lifetime', () => {
  const claims = {
    schemaVersion: 1,
    iss: 'private-game',
    sub: id(3),
    aud: 'world-jigsaw-chat',
    iat: 1000,
    exp: 1300,
    jti: id(4),
    displayName: '👨‍👩‍👧‍👦'.repeat(10),
  }
  assert.equal(validate('ChatAssertionClaims', claims).valid, true)
  assert.equal(validate('ChatAssertionClaims', { ...claims, exp: 1301 }).valid, false)
  assert.equal(validate('ChatAssertionClaims', { ...claims, aud: 'game' }).valid, false)
  assert.equal(
    validate('ChatAssertionClaims', { ...claims, displayName: '😀'.repeat(65) }).valid,
    false,
  )
})

test('shared batch enters the surface, then commits both canonical groups and clears previews', () => {
  const piece = definition.pieces[2]
  const before = snapshot.groups[1].transform
  const x = piece.solvedPosition.x + piece.referencePoint.x
  const y = piece.solvedPosition.y + piece.referencePoint.y
  const radians = (before.rotation / 1000) * (Math.PI / 180)
  const initialReference = {
    x: before.translation.x + Math.cos(radians) * x - Math.sin(radians) * y,
    y: before.translation.y + Math.sin(radians) * x + Math.cos(radians) * y,
  }
  assert.equal(isInsideSurface(initialReference, definition.playSurface), false)
  assert.equal(isInsideSurface(fixture.expected.piece3ReferenceAfter, definition.playSurface), true)
  assert.deepEqual(
    {
      x: x + confirm.actions[0].intendedTransform.translation.x,
      y: y + confirm.actions[0].intendedTransform.translation.y,
    },
    fixture.expected.piece3ReferenceAfter,
  )
  const changed = applyBoardChanged(definition, snapshot, fixture.boardChanged)
  assert.equal(changed.status, 'applied')
  assert.deepEqual(changed.snapshot, fixture.confirmedSnapshot)
  assert.equal(validate('CommandResult', fixture.commandResult).valid, true)
  assert.equal(fixture.commandResult.allowance.remaining, 3)
  let state = null
  for (const overlay of fixture.activePreviews) state = applyPreview(state, overlay)
  assert.equal(Object.keys(state.actions).length, 2)
  state = applyPreview(state, fixture.previewCleared)
  assert.deepEqual(state.actions, {})
  assert.equal(applyPreview(state, fixture.activePreviews[1]).closed, true)
})

test('later confirmed move merges groups with a fresh ID and one charge', () => {
  const changed = applyBoardChanged(
    definition,
    fixture.confirmedSnapshot,
    fixture.mergeBoardChanged,
  )
  assert.equal(changed.status, 'applied')
  assert.deepEqual(changed.snapshot, fixture.mergedSnapshot)
  assert.equal(changed.snapshot.groups.length, 1)
  assert.equal(progressPieceCount(changed.snapshot), 3)
  assert.equal(validate('CommandResult', fixture.mergeCommandResult).valid, true)
  assert.equal(
    fixture.mergeCommandResult.allowance.remaining,
    fixture.commandResult.allowance.remaining - 1,
  )
})
