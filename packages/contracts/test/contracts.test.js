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

test('duplicate actions and cyclic or missing dependencies are rejected', () => {
  const duplicate = copy(confirm)
  duplicate.actions[1].actionId = duplicate.actions[0].actionId
  assert.equal(validate('Confirm', duplicate).valid, false)
  const cyclic = copy(confirm)
  cyclic.actions[0].dependsOn = [cyclic.actions[1].actionId]
  cyclic.actions[1].dependsOn = [cyclic.actions[0].actionId]
  assert.equal(validate('Confirm', cyclic).valid, false)
  const missing = copy(confirm)
  missing.actions[0].dependsOn = [id(999)]
  assert.equal(validate('Confirm', missing).valid, false)
})

test('confirmed event applies atomically; duplicates, gaps and invalid membership do not', () => {
  const event = {
    schemaVersion: 1,
    type: 'boardChanged',
    puzzleId: snapshot.puzzleId,
    boardVersion: 8,
    eventId: id(800),
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

test('batch can contain free sorts alongside at most five counted actions', () => {
  const example = confirm.actions[0]
  const sorts = Array.from({ length: 8 }, (_, i) => ({
    ...example,
    actionId: id(1000 + i),
    kind: 'sort',
  }))
  assert.equal(validate('Confirm', { ...confirm, actions: sorts }).valid, true)
  const counted = Array.from({ length: 5 }, (_, i) => ({ ...example, actionId: id(1100 + i) }))
  assert.equal(validate('Confirm', { ...confirm, actions: [...sorts, ...counted] }).valid, true)
  assert.equal(
    validate('Confirm', {
      ...confirm,
      actions: [...sorts, ...counted, { ...example, actionId: id(1200) }],
    }).valid,
    false,
  )
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

test('chat POST, SSE and byte fixtures express independent bounds and errors', () => {
  assert.equal(validate('ChatSend', wire.post).valid, true)
  assert.equal(validate('ChatAccepted', wire.accepted).valid, true)
  assert.equal(validate('ChatMessage', wire.sse).valid, true)
  assert.equal(validate('ChatMessage', { ...wire.sse, type: 'message' }).valid, false)
  for (const sample of wire.textByteCases) {
    assert.equal(Buffer.byteLength(sample.input, 'utf8'), sample.utf8Bytes)
    assert.equal(sample.utf8Bytes <= 1024, sample.withinByteLimit)
  }
  for (const sample of wire.bodyByteCases) {
    assert.deepEqual(JSON.parse(sample.body), wire.post)
    assert.equal(Buffer.byteLength(sample.body, 'utf8'), sample.utf8Bytes)
    assert.equal(sample.utf8Bytes <= 4096, sample.withinByteLimit)
  }
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
