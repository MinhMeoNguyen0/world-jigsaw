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

test('legacy boards remain inspectable and cannot be mutated', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const legacy = structuredClone(snapshot)
  legacy.groups[0].transform.rotation = 12345
  const bytes = JSON.stringify(legacy)
  const old = begin(definition, legacy, allowance)
  assert.equal(JSON.stringify(old.base), bytes)
  assert.deepEqual(preview(old), [])
  assert.equal(JSON.stringify(cancel(old, legacy)), bytes)
  rejects(
    () => move(old, legacy.groups[0].groupId, action(1), solved),
    'UNSUPPORTED_LEGACY_ORIENTATION',
  )
  rejects(
    () => attach(old, legacy.groups[0].groupId, legacy.groups[1].groupId, action(99), 0, 0),
    'UNSUPPORTED_LEGACY_ORIENTATION',
  )
  rejects(() => undo(old, action(1)), 'UNSUPPORTED_LEGACY_ORIENTATION')
  rejects(() => confirm(old, legacy, allowance), 'UNSUPPORTED_LEGACY_ORIENTATION')
  const d = begin(definition, snapshot, allowance)
  rejects(() => confirm(d, legacy, allowance), 'UNSUPPORTED_LEGACY_ORIENTATION')
  rejects(
    () => move(d, snapshot.groups[0].groupId, action(1), legacy.groups[0].transform),
    'INVALID_ACTION',
  )
  rejects(() => rotateAround(solved, { x: 0, y: 0 }, 12345), 'INVALID_ACTION')
  rejects(
    () => rotateAround(legacy.groups[0].transform, { x: 0, y: 0 }, 90000),
    'UNSUPPORTED_LEGACY_ORIENTATION',
  )
  assert.equal(JSON.stringify(legacy), bytes)
})

test('moving an unpaid joined group costs one move and respects quota', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const a = snapshot.groups[0],
    b = snapshot.groups[1]
  const zero = { limit: 5 as const, used: 5, remaining: 0 }
  let draft = move(begin(definition, snapshot, zero), a.groupId, action(1), solved)
  draft = attach(draft, a.groupId, b.groupId, action(99), 0, 0)
  assert.equal(pendingCount(draft), 0)
  const shifted = { rotation: 0, translation: { x: 100, y: 0 } }
  rejects(() => move(draft, action(99), action(1), shifted), 'QUOTA_EXCEEDED')
  assert.equal(confirm(draft, snapshot, zero).charged, 0)
  let paid = move(begin(definition, snapshot, allowance), a.groupId, action(1), solved)
  paid = move(paid, b.groupId, action(2), solved)
  paid = attach(paid, a.groupId, b.groupId, action(99), 0, 0)
  paid = move(paid, action(99), action(2), shifted)
  assert.equal(pendingCount(paid), 1)
  paid = move(paid, action(99), action(1), solved)
  assert.equal(confirm(paid, snapshot, allowance).charged, 1)
  const connected = confirm(draft, snapshot, zero).snapshot
  let group = move(begin(definition, connected, zero), action(99), action(3), solved)
  group = attach(group, action(99), snapshot.groups[2].groupId, action(100), 0, 0)
  rejects(() => move(group, action(100), action(3), shifted), 'QUOTA_EXCEEDED')
})

test('snapping an independently moved component charges it beside a paid target', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  let setup = move(
    begin(definition, snapshot, allowance),
    snapshot.groups[0].groupId,
    action(10),
    solved,
  )
  setup = attach(setup, snapshot.groups[0].groupId, snapshot.groups[1].groupId, action(90), 0, 0)
  const connected = confirm(setup, snapshot, allowance).snapshot
  const scenarios = [
    { board: snapshot, moving: snapshot.groups[0].groupId, target: snapshot.groups[1].groupId },
    { board: snapshot, moving: snapshot.groups[1].groupId, target: snapshot.groups[0].groupId },
    { board: connected, moving: action(90), target: snapshot.groups[2].groupId },
  ]
  const transforms: Transform[] = [
    { rotation: 0, translation: { x: 100, y: 0 } },
    { rotation: 0, translation: { x: -100, y: 0 } },
    { rotation: 0, translation: { x: 0, y: 100 } },
    { rotation: 0, translation: { x: 0, y: -100 } },
    { rotation: 90000, translation: { x: 400000, y: 0 } },
  ]
  const oneMove = { limit: 5 as const, used: 4, remaining: 1 }
  for (const { board, moving, target } of scenarios) {
    for (const transform of transforms) {
      let draft = move(begin(definition, board, oneMove), target, action(1), transform)
      draft = move(draft, moving, action(2), solved)
      assert.equal(pendingCount(draft), 1)
      rejects(() => attach(draft, moving, target, action(99), 10000000, 180000), 'QUOTA_EXCEEDED')
      assert.equal(pendingCount(draft), 1)

      let paid = move(begin(definition, board, allowance), target, action(1), transform)
      paid = move(paid, moving, action(2), solved)
      paid = attach(paid, moving, target, action(99), 10000000, 180000)
      assert.equal(pendingCount(paid), 2)
      rejects(() => confirm(paid, board, oneMove), 'QUOTA_EXCEEDED')
      paid = move(paid, action(99), action(2), solved)
      assert.equal(pendingCount(paid), 2)
      assert.equal(confirm(paid, board, allowance).charged, 2)
    }
  }
})

test('an outer sort entering after a join is charged and keeps that charge', () => {
  const { definition, snapshot } = load('../../generator/fixtures/sample-80.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const a = snapshot.groups[0],
    b = snapshot.groups[1]
  let draft = move(begin(definition, snapshot, allowance), a.groupId, action(1), a.transform)
  draft = attach(draft, a.groupId, b.groupId, action(99), 10000000, 180000)
  assert.equal(pendingCount(draft), 0)
  draft = move(draft, action(99), action(1), solved)
  assert.equal(pendingCount(draft), 1)
  draft = move(draft, action(99), action(1), b.transform)
  assert.equal(confirm(draft, snapshot, allowance).charged, 1)
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
    assert.equal(pendingCount(d), freeMoves ? 2 : 1)
    assert.equal(confirm(d, snapshot, allowance).charged, freeMoves ? 2 : 1)
    if (!freeMoves) {
      d = move(d, action(99), action(2), solved)
      assert.equal(pendingCount(d), 2)
      assert.equal(confirm(d, snapshot, allowance).charged, 2)
    }
  }
})

test('a snap that brings a free outer piece in respects quota', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const outside = snapshot.groups[3],
    target = snapshot.groups[2],
    p = definition.pieces[3]
  const sort: Transform = {
    rotation: 0,
    translation: { x: -p.solvedPosition.x - p.referencePoint.x - 10000, y: 0 },
  }
  const zero = { limit: 5 as const, used: 5, remaining: 0 }
  const draft = move(begin(definition, snapshot, zero), outside.groupId, action(1), sort)
  rejects(
    () => attach(draft, outside.groupId, target.groupId, action(99), 10000000, 0),
    'QUOTA_EXCEEDED',
  )
  assert.equal(pendingCount(draft), 0)
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
  const undone = undo(d, action(1))
  assert.deepEqual(
    undone.actions.map((item) => item.id),
    [action(2)],
  )
  assert.deepEqual(
    undone.groups.find((item) => item.group.groupId === g2.groupId)?.group.transform,
    solved,
  )
  assert.deepEqual(
    undone.groups.find((item) => item.group.groupId === g1.groupId)?.group.transform,
    g1.transform,
  )
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

test('undo retains surviving joined adjustments and their charge in every orientation', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const first = snapshot.groups[0],
    second = snapshot.groups[1]
  for (const rotation of [0, 90000, 180000, 270000]) {
    let draft = begin(definition, snapshot, allowance)
    draft = move(draft, first.groupId, action(1), solved)
    draft = move(draft, second.groupId, action(2), solved)
    draft = attach(draft, first.groupId, second.groupId, action(99), 0, 0)
    const movedA = { rotation, translation: { x: 500, y: -300 } }
    draft = move(draft, action(99), action(1), movedA)
    const undoneB = undo(draft, action(2))
    assert.deepEqual(
      undoneB.groups.find((item) => item.group.groupId === first.groupId)?.group.transform,
      movedA,
    )
    assert.deepEqual(
      undoneB.groups.find((item) => item.group.groupId === second.groupId)?.group.transform,
      second.transform,
    )
    assert.equal(pendingCount(undoneB), 1)
    assert.equal(confirm(undoneB, snapshot, allowance).charged, 1)
    assert.deepEqual(
      undo(draft, action(1)).groups.find((item) => item.group.groupId === second.groupId)?.group
        .transform,
      solved,
    )
    const movedB = { rotation, translation: { x: -700, y: 900 } }
    draft = move(draft, action(99), action(2), movedB)
    assert.deepEqual(
      undo(draft, action(2)).groups.find((item) => item.group.groupId === first.groupId)?.group
        .transform,
      movedA,
    )
    assert.deepEqual(
      undo(draft, action(1)).groups.find((item) => item.group.groupId === second.groupId)?.group
        .transform,
      movedB,
    )
  }
})

test('undo preserves a survivor adjustment through nested joins', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const first = snapshot.groups[0],
    second = snapshot.groups[1],
    third = snapshot.groups[2]
  let draft = begin(definition, snapshot, allowance)
  for (const [index, group] of [first, second, third].entries())
    draft = move(draft, group.groupId, action(index + 1), solved)
  draft = attach(draft, first.groupId, second.groupId, action(98), 0, 0)
  draft = attach(draft, action(98), third.groupId, action(99), 0, 0)
  const moved = { rotation: 0, translation: { x: 1500, y: -2500 } }
  draft = move(draft, action(99), action(1), moved)
  const undone = undo(draft, action(2))
  assert.deepEqual(
    undone.actions.map((item) => item.id),
    [action(1), action(3)],
  )
  assert.deepEqual(
    undone.groups.find((item) => item.group.groupId === first.groupId)?.group.transform,
    moved,
  )
  assert.deepEqual(
    undone.groups.find((item) => item.group.groupId === third.groupId)?.group.transform,
    solved,
  )
})

test('equal-transform replay preserves surviving adjustments across nested undo', () => {
  const { definition, snapshot } = load('../../generator/fixtures/review-60.json') as {
    definition: Definition
    snapshot: Snapshot
  }
  const first = snapshot.groups[0],
    second = snapshot.groups[1],
    third = snapshot.groups[2]
  for (const rotation of [0, 90000, 180000, 270000]) {
    for (const reverse of [false, true]) {
      const removed = reverse ? action(1) : action(2)
      const survivor = reverse ? action(2) : action(1)
      const removedGroup = reverse ? first : second
      const survivorGroup = reverse ? second : first
      const base = { rotation, translation: { x: 0, y: 0 } }
      const direction = reverse ? -1 : 1
      const moved = { rotation, translation: { x: direction * 1300, y: direction * -700 } }
      let draft = begin(definition, snapshot, allowance)
      for (const [index, group] of [first, second, third].entries())
        draft = move(draft, group.groupId, action(index + 1), base)
      draft = attach(draft, first.groupId, second.groupId, action(98), 0, 0)
      draft = move(draft, action(98), removed, moved)
      const unchanged = move(draft, action(98), survivor, moved)
      assert.deepEqual(
        undo(unchanged, removed).groups.find((item) => item.group.groupId === survivorGroup.groupId)
          ?.group.transform,
        base,
      )
      draft = attach(draft, action(98), third.groupId, action(99), 5000, 0)
      draft = move(draft, action(99), survivor, moved)
      const undone = undo(draft, removed)
      assert.deepEqual(
        undone.groups.find((item) => item.group.groupId === survivorGroup.groupId)?.group.transform,
        moved,
      )
      assert.deepEqual(
        undone.groups.find((item) => item.group.groupId === removedGroup.groupId)?.group.transform,
        removedGroup.transform,
      )
      assert.deepEqual(
        undone.groups.find((item) => item.group.groupId === third.groupId)?.group.transform,
        base,
      )
      const charged = rotation === 0 ? 1 : 2
      assert.equal(pendingCount(undone), charged)
      assert.equal(confirm(undone, snapshot, allowance).charged, charged)
    }
  }
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
      layout: 'tray',
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

test('new expanded surface charges entry while sorting outside stays free', () => {
  const { definition, snapshot } = generate({
    seed: 'expanded-charging',
    columns: 10,
    rows: 6,
    width: 1200000,
    height: 800000,
    image: 'fixture://hoi-an-generated',
    placedPieces: 0,
  })
  const group = snapshot.groups[0]
  const piece = definition.pieces.find((p) => p.pieceId === group.pieceIds[0])!
  const ref = {
    x: piece.solvedPosition.x + piece.referencePoint.x,
    y: piece.solvedPosition.y + piece.referencePoint.y,
  }
  const at = (x: number, y: number): Transform => ({
    rotation: 0,
    translation: { x: x - ref.x, y: y - ref.y },
  })
  const edge = definition.playSurface.min.x
  const y = Math.round((definition.playSurface.min.y + definition.playSurface.max.y) / 2)
  let draft = move(
    begin(definition, snapshot, allowance),
    group.groupId,
    action(1),
    at(edge - 1, y),
  )
  assert.equal(pendingCount(draft), 0)
  draft = move(draft, group.groupId, action(1), at(edge, y))
  assert.equal(pendingCount(draft), 1)
  assert.equal(confirm(draft, snapshot, allowance).charged, 1)
})
