import { test } from 'node:test'
import { generate } from '../../generator/src/generator.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import type { Definition, Snapshot, Transform } from '@world-jigsaw/contracts'
import {
  attach,
  begin,
  cancel,
  confirm,
  move,
  pendingCount,
  preview,
  rotateAround,
  RuleError,
  transformPoint,
  undo,
} from '../src/index.ts'

const load = (path: string) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
const allowance = { limit: 5 as const, used: 0, remaining: 5 }
const solved: Transform = { translation: { x: 0, y: 0 }, rotation: 0 }
const action = (i: number) => `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`
const rejects = (fn: () => unknown, code: string) =>
  assert.throws(fn, (e: unknown) => e instanceof RuleError && e.code === code)
const fixtures = ['review-60.json', 'sample-80.json']
for (const name of fixtures)
  test(`${name}: entry, quota, confirmation and cancellation`, () => {
    const { definition, snapshot } = load(`../../generator/fixtures/${name}`) as {
      definition: Definition
      snapshot: Snapshot
    }
    const initial = begin(definition, snapshot, allowance)
    let d = initial
    const outside = snapshot.groups.filter((g) => {
      const p = definition.pieces.find((p) => p.pieceId === g.pieceIds[0])!
      const r = transformPoint(
        { x: p.solvedPosition.x + p.referencePoint.x, y: p.solvedPosition.y + p.referencePoint.y },
        g.transform,
      )
      return (
        r.x < definition.playSurface.min.x ||
        r.x >= definition.playSurface.max.x ||
        r.y < definition.playSurface.min.y ||
        r.y >= definition.playSurface.max.y
      )
    })
    assert.ok(outside.length >= 6)
    for (let i = 0; i < 5; i++) d = move(d, outside[i].groupId, action(i + 1), solved)
    assert.equal(pendingCount(d), 5)
    rejects(() => move(d, outside[5].groupId, action(6), solved), 'QUOTA_EXCEEDED')
    d = move(d, outside[0].groupId, action(1), { ...solved, translation: { x: 100, y: 0 } })
    assert.equal(pendingCount(d), 5)
    assert.equal(preview(d).length, 5)
    assert.deepEqual(initial.base, snapshot)
    const result = confirm(d, snapshot, allowance)
    assert.equal(result.charged, 5)
    assert.equal(result.allowance.remaining, 0)
    assert.equal(result.snapshot.boardVersion, snapshot.boardVersion + 1)
    assert.deepEqual(cancel(d, snapshot), snapshot)
    rejects(() => confirm(d, snapshot, { limit: 5, used: 1, remaining: 4 }), 'QUOTA_EXCEEDED')
  })

test('portable rotation vectors cover quarter turns in both directions', () => {
  const vectors = load('../vectors/behavior.json')
  for (const v of vectors.rotation)
    assert.deepEqual(
      rotateAround({ translation: v.translation, rotation: v.rotation }, v.pivot, v.delta),
      v.expected,
    )
})

test('settled snapshots and player rotations reject other angles', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const invalid = structuredClone(snapshot)
  invalid.groups[0].transform.rotation = 12345
  rejects(() => begin(definition, invalid, allowance), 'INVALID_ACTION')
  const d = begin(definition, snapshot, allowance)
  rejects(() => confirm(d, invalid, allowance), 'INVALID_ACTION')
  rejects(
    () => move(d, snapshot.groups[0].groupId, action(1), invalid.groups[0].transform),
    'INVALID_ACTION',
  )
  rejects(() => rotateAround(solved, { x: 0, y: 0 }, 12345), 'INVALID_ACTION')
  rejects(() => rotateAround(invalid.groups[0].transform, { x: 0, y: 0 }, 90000), 'INVALID_ACTION')
})

test('joining preserves free actions in either role and through another join', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const placed = snapshot.groups[2],
    incoming = snapshot.groups[3],
    next = snapshot.groups[4]
  const oneMove = { limit: 5 as const, used: 4, remaining: 1 }
  for (const freeMoves of [false, true]) {
    let d = begin(definition, snapshot, oneMove)
    d = move(d, placed.groupId, action(1), placed.transform)
    d = move(d, incoming.groupId, action(2), solved)
    assert.equal(pendingCount(d), 1)
    d = attach(
      d,
      freeMoves ? placed.groupId : incoming.groupId,
      freeMoves ? incoming.groupId : placed.groupId,
      action(99),
      0,
      0,
    )
    assert.equal(pendingCount(d), 1)
    d = attach(d, action(99), next.groupId, action(100), 10000000, 180000)
    assert.equal(pendingCount(d), 1)
    d = move(d, action(100), action(1), solved)
    assert.equal(pendingCount(d), 1)
    const result = confirm(d, snapshot, oneMove)
    assert.equal(result.charged, 1)
    assert.equal(result.allowance.remaining, 0)
  }
})

test('joining a free outer sort with an entered neighbor keeps one charge', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const outer = snapshot.groups[0],
    incoming = snapshot.groups[1]
  const p = definition.pieces[0]
  const outside: Transform = {
    rotation: 0,
    translation: { x: -p.solvedPosition.x - p.referencePoint.x - 10000, y: 0 },
  }
  for (const freeMoves of [false, true]) {
    let d = begin(definition, snapshot, allowance)
    d = move(d, outer.groupId, action(1), outside)
    d = move(d, incoming.groupId, action(2), solved)
    assert.equal(pendingCount(d), 1)
    d = attach(
      d,
      freeMoves ? outer.groupId : incoming.groupId,
      freeMoves ? incoming.groupId : outer.groupId,
      action(99),
      10000000,
      0,
    )
    assert.equal(pendingCount(d), 1)
    assert.equal(confirm(d, snapshot, allowance).charged, 1)
  }
})

test('attachment, independent action charges, group movement and undo', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const p = definition.pieces[0],
    neighborId = p.edges.find((e) => e.neighborPieceId)!.neighborPieceId!
  const g1 = snapshot.groups.find((g) => g.pieceIds.includes(p.pieceId))!,
    g2 = snapshot.groups.find((g) => g.pieceIds.includes(neighborId))!
  let d = begin(definition, snapshot, allowance)
  d = move(d, g1.groupId, action(1), solved)
  d = move(d, g2.groupId, action(2), solved)
  assert.equal(pendingCount(d), 2)
  const before = d
  d = attach(d, g1.groupId, g2.groupId, action(99), 0, 0)
  assert.equal(pendingCount(d), 2)
  rejects(() => undo(d, action(1)), 'INVALID_ACTION')
  assert.equal(pendingCount(before), 2)
  d = move(d, action(99), action(1), { rotation: 0, translation: { x: 100, y: 100 } })
  const result = confirm(d, snapshot, allowance)
  assert.equal(result.charged, 2)
  const merged = result.snapshot.groups.find((g) => g.groupId === action(99))!
  assert.equal(merged.pieceIds.length, 2)
  let next = begin(definition, result.snapshot, allowance)
  const pivot = transformPoint(
    { x: p.solvedPosition.x + p.referencePoint.x, y: p.solvedPosition.y + p.referencePoint.y },
    merged.transform,
  )
  const rotated = rotateAround(merged.transform, pivot, 90000)
  next = move(next, merged.groupId, action(3), rotated)
  assert.equal(pendingCount(next), 1)
  next = move(next, merged.groupId, action(3), {
    rotation: rotated.rotation,
    translation: { x: rotated.translation.x + 100, y: rotated.translation.y },
  })
  const moved = confirm(next, result.snapshot, allowance)
  assert.equal(moved.charged, 1)
  assert.equal(moved.snapshot.groups.find((g) => g.groupId === merged.groupId)!.pieceIds.length, 2)
  assert.equal(
    moved.snapshot.groups.find((g) => g.groupId === merged.groupId)!.transform.rotation,
    90000,
  )
})

test('undo, stale touched group and unrelated changes', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  let d = begin(definition, snapshot, allowance)
  d = move(d, snapshot.groups[0].groupId, action(1), solved)
  d = move(d, snapshot.groups[1].groupId, action(2), solved)
  d = undo(d, action(1))
  assert.equal(d.generation, 1)
  assert.equal(preview(d).length, 1)
  const latest = structuredClone(snapshot)
  latest.boardVersion++
  latest.groups[0].revision++
  assert.equal(
    confirm(d, latest, allowance).snapshot.groups.find(
      (g) => g.groupId === latest.groups[0].groupId,
    )!.revision,
    latest.groups[0].revision,
  )
  assert.deepEqual(cancel(d, latest), latest)
  latest.groups[1].revision++
  rejects(() => confirm(d, latest, allowance), 'STALE_REVISION')
})

test('invalid snap rejects non-neighbors and distant or rotated neighbors', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const first = snapshot.groups[0],
    p = definition.pieces.find((p) => p.pieceId === first.pieceIds[0])!
  const neighbor = snapshot.groups.find((g) =>
    p.edges.some((e) => e.neighborPieceId === g.pieceIds[0]),
  )!
  const nonneighbor = snapshot.groups.find(
    (g) =>
      g !== first && g !== neighbor && !p.edges.some((e) => e.neighborPieceId === g.pieceIds[0]),
  )!
  let d = begin(definition, snapshot, allowance)
  d = move(d, first.groupId, action(1), solved)
  rejects(
    () => attach(d, first.groupId, nonneighbor.groupId, action(99), 1000000000, 180000),
    'INVALID_ACTION',
  )
  rejects(() => attach(d, first.groupId, neighbor.groupId, action(99), 0, 0), 'INVALID_ACTION')
})

test('outer sorting is free, entry is remembered, return demotes, and unchanged costs zero', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const g = snapshot.groups[0]
  const t = structuredClone(g.transform)
  t.translation.x += 1
  let d = move(begin(definition, snapshot, allowance), g.groupId, action(1), t)
  assert.equal(pendingCount(d), 0)
  assert.equal(confirm(d, snapshot, allowance).charged, 0)
  d = move(d, g.groupId, action(1), solved)
  assert.equal(pendingCount(d), 1)
  d = move(d, g.groupId, action(1), t)
  assert.equal(pendingCount(d), 0)
  d = move(d, g.groupId, action(1), g.transform)
  const result = confirm(d, snapshot, allowance)
  assert.equal(result.charged, 0)
  assert.equal(result.snapshot.boardVersion, snapshot.boardVersion)
  const latest = structuredClone(snapshot)
  latest.boardVersion++
  latest.groups[0].revision++
  rejects(() => confirm(d, latest, allowance), 'STALE_REVISION')
})

test('rejects disconnected groups and mismatched reciprocal geometry', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const invalid = structuredClone(snapshot)
  invalid.groups[0].pieceIds.push(invalid.groups[79].pieceIds[0])
  invalid.groups.pop()
  rejects(() => begin(definition, invalid, allowance), 'INVALID_ACTION')
  const malformed = structuredClone(definition)
  const edge = malformed.pieces[0].edges.find((e) => e.neighborPieceId)!
  edge.segments[0].to.x++
  rejects(() => begin(malformed, snapshot, allowance), 'INVALID_ACTION')
})

for (const name of fixtures)
  test(`${name}: generated quarter turns can be corrected and assembled`, () => {
    const board = generate({
      seed: name === 'review-60.json' ? 'review-60' : 'sample-80',
      columns: 10,
      rows: name === 'review-60.json' ? 6 : 8,
      width: 1200000,
      height: 800000,
      image: 'fixture://original-landscape',
      placedPieces: name === 'review-60.json' ? 3 : 0,
    })
    assert.deepEqual(board, load(`../../generator/fixtures/${name}`))
    const { definition, snapshot: initial } = board
    let snapshot = initial
    const root = snapshot.groups[0]
    let draft = begin(definition, snapshot, allowance)
    const rootPiece = definition.pieces.find((p) => p.pieceId === root.pieceIds[0])!
    const rootPivot = transformPoint(
      {
        x: rootPiece.solvedPosition.x + rootPiece.referencePoint.x,
        y: rootPiece.solvedPosition.y + rootPiece.referencePoint.y,
      },
      root.transform,
    )
    let rootTransform = root.transform
    while (rootTransform.rotation !== 0) {
      rootTransform = rotateAround(rootTransform, rootPivot, 90000)
      draft = move(draft, root.groupId, action(1000), rootTransform)
    }
    draft = move(draft, root.groupId, action(1000), solved)
    snapshot = confirm(draft, snapshot, allowance).snapshot
    let assembledId = root.groupId
    for (let i = 1; i < definition.pieces.length; i++) {
      const assembled = snapshot.groups.find((g) => g.groupId === assembledId)!
      const next = snapshot.groups.find(
        (g) =>
          g.groupId !== assembledId &&
          g.pieceIds.some((id) =>
            definition.pieces
              .find((p) => p.pieceId === id)!
              .edges.some(
                (e) => e.neighborPieceId && assembled.pieceIds.includes(e.neighborPieceId),
              ),
          ),
      )!
      assert.ok(next, 'there is an adjacent unassembled group')
      draft = begin(definition, snapshot, allowance) // a fresh player's allowance for each confirmed turn
      const nextPiece = definition.pieces.find((p) => p.pieceId === next.pieceIds[0])!
      const pivot = transformPoint(
        {
          x: nextPiece.solvedPosition.x + nextPiece.referencePoint.x,
          y: nextPiece.solvedPosition.y + nextPiece.referencePoint.y,
        },
        next.transform,
      )
      let nextTransform = next.transform
      while (nextTransform.rotation !== 0) {
        nextTransform = rotateAround(nextTransform, pivot, 90000)
        draft = move(draft, next.groupId, action(2000 + i), nextTransform)
      }
      draft = move(draft, next.groupId, action(2000 + i), solved)
      const mergedId = action(3000 + i)
      draft = attach(draft, next.groupId, assembledId, mergedId, 0, 0)
      const result = confirm(draft, snapshot, allowance)
      assert.equal(result.charged, next.finalPosition ? 0 : 1)
      snapshot = result.snapshot
      assembledId = mergedId
    }
    assert.equal(snapshot.groups.length, 1)
    assert.equal(snapshot.groups[0].pieceIds.length, definition.pieces.length)
    assert.equal(snapshot.groups[0].finalPosition, true)
  })

test('snap tolerance is inclusive and selects the target transform exactly', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const p = definition.pieces[0],
    a = snapshot.groups.find((g) => g.pieceIds.includes(p.pieceId))!
  const neighbor = p.edges.find((e) => e.neighborPieceId)!.neighborPieceId!
  const b = snapshot.groups.find((g) => g.pieceIds.includes(neighbor))!
  let d = begin(definition, snapshot, allowance)
  d = move(d, b.groupId, action(2), solved)
  d = move(d, a.groupId, action(1), { rotation: 0, translation: { x: 1000, y: 0 } })
  rejects(() => attach(d, a.groupId, b.groupId, action(99), 999, 0), 'INVALID_ACTION')
  const joined = attach(d, a.groupId, b.groupId, action(99), 1000, 0)
  assert.deepEqual(preview(joined)[0].transform, solved)
})
