import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync, spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { validateBoard, isInsideSurface } from '@world-jigsaw/contracts'
import type { ContourSegment, Point } from '../../contracts/v1/index.js'
import { canonicalJson, generate } from '../src/generator.ts'

const options = {
  seed: 'review-60',
  columns: 10,
  rows: 6,
  width: 1200000,
  height: 800000,
  image: 'fixture://original-landscape',
  placedPieces: 3,
}
const fixture = (name: string) =>
  readFileSync(new URL(`../fixtures/${name}.json`, import.meta.url), 'utf8')
const globalPoint = (p: Point, origin: Point) => ({ x: p.x + origin.x, y: p.y + origin.y })
const globalPath = (start: Point, segments: ContourSegment[], origin: Point) => ({
  start: globalPoint(start, origin),
  segments: segments.map((s) =>
    s.kind === 'line'
      ? { kind: s.kind, to: globalPoint(s.to, origin) }
      : {
          kind: s.kind,
          control1: globalPoint(s.control1, origin),
          control2: globalPoint(s.control2, origin),
          to: globalPoint(s.to, origin),
        },
  ),
})
function reversed(start: Point, segments: ContourSegment[]) {
  const starts = [start, ...segments.map((s) => s.to)]
  return {
    start: starts.at(-1),
    segments: segments
      .map((s, i) =>
        s.kind === 'line'
          ? { kind: s.kind, to: starts[i] }
          : { kind: s.kind, control1: s.control2, control2: s.control1, to: starts[i] },
      )
      .reverse(),
  }
}
test('fixtures are byte-reproducible and conform to the board contract', () => {
  const review = generate(options)
  const sample = generate({ ...options, seed: 'sample-80', rows: 8, placedPieces: 0 })
  assert.equal(`${JSON.stringify(review, null, 2)}\n`, fixture('review-60'))
  assert.equal(`${JSON.stringify(sample, null, 2)}\n`, fixture('sample-80'))
  assert.equal(review.definition.pieces.length, 60)
  assert.equal(sample.definition.pieces.length, 80)
  for (const [board, expectedChecksum] of [
    [review, 'sha256:5a40345ae9f39c4c92d5e095966798e3f0eead90b2a546f722609e93f6cf70be'],
    [sample, 'sha256:4983eb0f748fe074f570fa2aeff523dc635b354bee1a797ebc5cd3edb33f2af7'],
  ] as const) {
    assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
    assert.equal(board.definition.checksum, expectedChecksum)
  }
  assert.notDeepEqual(generate({ ...options, seed: 'different' }), review)
  assert.equal(
    generate({ ...options, placedPieces: 0 }).definition.checksum,
    review.definition.checksum,
  )
})
test('each contour closes and every shared edge has exact reversed solved geometry', () => {
  for (const opts of [
    options,
    { ...options, columns: 8, rows: 10, width: 1000001, height: 1700003 },
    { ...options, columns: 1, rows: 1, placedPieces: 0 },
  ]) {
    const { definition } = generate(opts)
    const pieces = new Map(definition.pieces.map((p) => [p.pieceId, p]))
    let shared = 0
    for (const piece of definition.pieces)
      for (const [i, edge] of piece.edges.entries()) {
        assert.deepEqual(edge.segments.at(-1)?.to, piece.edges[(i + 1) % piece.edges.length].start)
        if (edge.neighborPieceId === null) {
          assert.equal(edge.neighborEdgeId, null)
          assert.equal(edge.segments[0].kind, 'line')
          continue
        }
        shared++
        const neighbor = pieces.get(edge.neighborPieceId)!
        const other = neighbor.edges.find((e) => e.edgeId === edge.neighborEdgeId)!
        assert.equal(other.neighborPieceId, piece.pieceId)
        assert.equal(other.neighborEdgeId, edge.edgeId)
        const path = globalPath(edge.start, edge.segments, piece.solvedPosition)
        const back = globalPath(other.start, other.segments, neighbor.solvedPosition)
        assert.deepEqual(back, reversed(path.start, path.segments))
      }
    assert.equal(shared, 2 * ((opts.columns - 1) * opts.rows + (opts.rows - 1) * opts.columns))
  }
})
test('review placements and rotated tray references lie in their intended zones', () => {
  const { definition, snapshot } = generate(options)
  const centers: Point[] = []
  snapshot.groups.forEach((g, i) => {
    const p = definition.pieces[i]
    const ref = globalPoint(p.referencePoint, p.solvedPosition)
    const a = (g.transform.rotation * Math.PI) / 180000
    const center = {
      x: Math.round(Math.cos(a) * ref.x - Math.sin(a) * ref.y + g.transform.translation.x),
      y: Math.round(Math.sin(a) * ref.x + Math.cos(a) * ref.y + g.transform.translation.y),
    }
    assert.equal(isInsideSurface(center, definition.playSurface), i < 3)
    assert.equal(isInsideSurface(center, definition.workspace), true)
    assert.equal(g.finalPosition, i < 3)
    if (i >= 3) centers.push(center)
  })
  assert.equal(new Set(centers.map((p) => `${p.x},${p.y}`)).size, centers.length)
  assert.deepEqual(
    new Set(snapshot.groups.slice(3).map((g) => g.transform.rotation)),
    new Set([0, 90000, 180000, 270000]),
  )
})
test('canonical JSON and SHA-256 match a worked example', () => {
  const canonical = canonicalJson({ z: [1, true, null], a: 'a\n"' })
  assert.equal(canonical, '{"a":"a\\n\\\"","z":[1,true,null]}')
  assert.equal(
    createHash('sha256').update(canonical).digest('hex'),
    'd93430ef8a41c1f6c5531640c9d1fa619b675225fa6eb1da719962f82f924b99',
  )
})
test('CLI produces fixture and rejects invalid inputs', () => {
  const cli = new URL('../src/cli.ts', import.meta.url).pathname
  const args = [
    '--seed',
    'review-60',
    '--columns',
    '10',
    '--rows',
    '6',
    '--width',
    '1200000',
    '--height',
    '800000',
    '--image',
    'fixture://original-landscape',
    '--placed-pieces',
    '3',
  ]
  assert.equal(
    execFileSync(process.execPath, [cli, ...args], { encoding: 'utf8' }),
    fixture('review-60'),
  )
  for (const value of ['0', '81', '1.5']) {
    const invalidArgs = [...args]
    invalidArgs[invalidArgs.indexOf('--columns') + 1] = value
    assert.notEqual(spawnSync(process.execPath, [cli, ...invalidArgs]).status, 0)
  }
  for (const bad of [
    ['--bad', '1'],
    ['--seed', 'again'],
    ['--columns', '10'],
  ]) {
    assert.notEqual(spawnSync(process.execPath, [cli, ...args, ...bad]).status, 0)
  }
  for (const invalid of [
    { ...options, columns: 0 },
    { ...options, rows: 9 },
    { ...options, width: 1 },
    { ...options, placedPieces: 61 },
    { ...options, seed: '\ud800' },
  ])
    assert.throws(() => generate(invalid))
})

test('extreme valid aspect ratios and sizes remain within contract bounds', () => {
  for (const opts of [
    { ...options, columns: 1, rows: 80, width: 10000000, height: 80000, placedPieces: 0 },
    { ...options, columns: 80, rows: 1, width: 80000, height: 10000000, placedPieces: 0 },
    { ...options, columns: 1, rows: 1, width: 1000, height: 1000, placedPieces: 0 },
  ]) {
    const board = generate(opts)
    assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
    for (const group of board.snapshot.groups) {
      const piece = board.definition.pieces.find((p) => p.pieceId === group.pieceIds[0])!
      const ref = globalPoint(piece.referencePoint, piece.solvedPosition)
      const a = (group.transform.rotation * Math.PI) / 180000
      const center = {
        x: Math.round(Math.cos(a) * ref.x - Math.sin(a) * ref.y + group.transform.translation.x),
        y: Math.round(Math.sin(a) * ref.x + Math.cos(a) * ref.y + group.transform.translation.y),
      }
      assert.equal(isInsideSurface(center, board.definition.workspace), true)
      assert.equal(isInsideSurface(center, board.definition.playSurface), false)
    }
  }
})

test('seeded orientations use four repeatable quarter turns', () => {
  for (const seed of ['review-60', 'sample-80', 'another-seed']) {
    const first = generate({ ...options, seed, placedPieces: 0 })
    const second = generate({ ...options, seed, placedPieces: 0 })
    const angles = first.snapshot.groups.map((g) => g.transform.rotation)
    assert.deepEqual(
      angles,
      second.snapshot.groups.map((g) => g.transform.rotation),
    )
    assert.deepEqual(new Set(angles), new Set([0, 90000, 180000, 270000]))
    assert.ok(angles.every((angle) => [0, 90000, 180000, 270000].includes(angle)))
  }
  const angles = (seed: string) =>
    generate({ ...options, seed, placedPieces: 0 }).snapshot.groups.map((g) => g.transform.rotation)
  assert.notDeepEqual(angles('review-60'), angles('another-seed'))
})
