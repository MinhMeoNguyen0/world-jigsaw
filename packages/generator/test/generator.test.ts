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
  layout: 'tray' as const,
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
test('surround layout is shared, reproducible and distributes loose pieces around the picture', () => {
  const board = generate({ ...options, layout: 'surround' })
  assert.equal(`${JSON.stringify(board, null, 2)}\n`, fixture('surround-60'))
  const hoiAn = generate({ ...options, image: 'fixture://hoi-an-generated', layout: 'surround' })
  assert.equal(`${JSON.stringify(hoiAn, null, 2)}\n`, fixture('hoi-an-surround-60'))
  assert.notEqual(hoiAn.definition.puzzleId, board.definition.puzzleId)
  assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
  assert.notEqual(board.definition.puzzleId, generate(options).definition.puzzleId)
  const centers = board.snapshot.groups.slice(3).map((group) => {
    const piece = board.definition.pieces.find((item) => item.pieceId === group.pieceIds[0])!
    const ref = globalPoint(piece.referencePoint, piece.solvedPosition)
    const angle = (group.transform.rotation * Math.PI) / 180000
    return {
      x: Math.round(
        Math.cos(angle) * ref.x - Math.sin(angle) * ref.y + group.transform.translation.x,
      ),
      y: Math.round(
        Math.sin(angle) * ref.x + Math.cos(angle) * ref.y + group.transform.translation.y,
      ),
    }
  })
  assert.ok(centers.some((p) => p.x < 0))
  assert.ok(centers.some((p) => p.x > options.width))
  assert.ok(centers.some((p) => p.y < 0))
  assert.ok(centers.some((p) => p.y > options.height))
  for (const [i, center] of centers.entries()) {
    assert.equal(isInsideSurface(center, board.definition.playSurface), false)
    assert.equal(isInsideSurface(center, board.definition.workspace), true)
    for (const other of centers.slice(i + 1))
      assert.ok(Math.hypot(center.x - other.x, center.y - other.y) >= 200000)
  }
})
test('balanced layout preserves a shared board with a taller spread for mobile fit', () => {
  const opts = { ...options, image: 'fixture://hoi-an-generated' }
  const original = generate({ ...opts, layout: 'surround' })
  const board = generate({ ...opts, layout: 'balanced' })
  assert.equal(`${JSON.stringify(board, null, 2)}\n`, fixture('hoi-an-balanced-60'))
  assert.notEqual(board.definition.puzzleId, original.definition.puzzleId)
  assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
  const size = (b: typeof board) => ({
    width: b.definition.workspace.max.x - b.definition.workspace.min.x,
    height: b.definition.workspace.max.y - b.definition.workspace.min.y,
  })
  assert.ok(size(board).height / size(board).width > size(original).height / size(original).width)
  const centers = board.snapshot.groups.slice(3).map((group, i) => {
    const piece = board.definition.pieces[i + 3]
    const ref = globalPoint(piece.referencePoint, piece.solvedPosition)
    const angle = (group.transform.rotation * Math.PI) / 180000
    return {
      x: Math.round(
        Math.cos(angle) * ref.x - Math.sin(angle) * ref.y + group.transform.translation.x,
      ),
      y: Math.round(
        Math.sin(angle) * ref.x + Math.cos(angle) * ref.y + group.transform.translation.y,
      ),
    }
  })
  for (const [i, center] of centers.entries()) {
    assert.equal(isInsideSurface(center, board.definition.playSurface), false)
    assert.equal(isInsideSurface(center, board.definition.workspace), true)
    for (const other of centers.slice(i + 1))
      assert.ok(Math.hypot(center.x - other.x, center.y - other.y) >= 200000)
  }
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
    '--layout',
    'tray',
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

test('expanded layout enlarges the shared surface while keeping loose contours outside', () => {
  const opts = { ...options, image: 'fixture://hoi-an-generated', placedPieces: 0 }
  const legacy = generate({ ...opts, layout: 'balanced' })
  const board = generate({ ...opts, layout: 'expanded' })
  assert.equal(board.definition.shape.generatorVersion, 'world-jigsaw-grid-8')
  assert.notEqual(board.definition.puzzleId, legacy.definition.puzzleId)
  assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
  const size = (r: typeof board.definition.playSurface) => ({
    width: r.max.x - r.min.x,
    height: r.max.y - r.min.y,
  })
  const surface = size(board.definition.playSurface),
    workspace = size(board.definition.workspace)
  const oldSurface = size(legacy.definition.playSurface),
    oldWorkspace = size(legacy.definition.workspace)
  assert.ok(surface.width / workspace.width > oldSurface.width / oldWorkspace.width)
  assert.ok(surface.height / workspace.height > oldSurface.height / oldWorkspace.height)
  const ids = board.snapshot.groups.flatMap((group) => group.pieceIds)
  assert.equal(ids.length, board.definition.pieces.length)
  assert.equal(new Set(ids).size, ids.length)
  for (const group of board.snapshot.groups) {
    const piece = board.definition.pieces.find((item) => item.pieceId === group.pieceIds[0])!
    const angle = (group.transform.rotation * Math.PI) / 180000
    const transform = (p: Point) => {
      const x = p.x + piece.solvedPosition.x,
        y = p.y + piece.solvedPosition.y
      return {
        x: Math.cos(angle) * x - Math.sin(angle) * y + group.transform.translation.x,
        y: Math.sin(angle) * x + Math.cos(angle) * y + group.transform.translation.y,
      }
    }
    // A cubic lies within the convex hull of its endpoints and control points.
    const points = piece.edges
      .flatMap((edge) => [
        edge.start,
        ...edge.segments.flatMap((segment) =>
          segment.kind === 'line' ? [segment.to] : [segment.control1, segment.control2, segment.to],
        ),
      ])
      .map(transform)
    const bounds = {
      minX: Math.min(...points.map((p) => p.x)),
      maxX: Math.max(...points.map((p) => p.x)),
      minY: Math.min(...points.map((p) => p.y)),
      maxY: Math.max(...points.map((p) => p.y)),
    }
    const rect = board.definition.playSurface
    assert.ok(
      bounds.maxX < rect.min.x ||
        bounds.minX > rect.max.x ||
        bounds.maxY < rect.min.y ||
        bounds.minY > rect.max.y,
    )
  }
})

test('playable fixture has the expanded surface and a valid initial board', () => {
  const serialized = fixture('hoi-an-expanded-60')
  const board = JSON.parse(serialized)
  assert.equal(
    serialized,
    `${JSON.stringify(generate({ ...options, image: 'fixture://hoi-an-generated', layout: 'expanded' }), null, 2)}\n`,
  )
  assert.deepEqual(board.definition.playSurface, {
    min: { x: -780000, y: -520000 },
    max: { x: 1980000, y: 1320000 },
  })
  assert.equal(board.definition.image, 'fixture://hoi-an-generated')
  assert.equal(board.snapshot.groups.length, 60)
  assert.equal(
    board.snapshot.groups.filter((group: { finalPosition: boolean }) => group.finalPosition).length,
    3,
  )
  assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
})

test('default compact 60-piece boards surround the surface without overlapping contour bounds', () => {
  const { layout: _layout, placedPieces: _placed, ...defaults } = options
  for (const seed of ['review-60', 'another-seed', 'third-seed']) {
    const board = generate({ ...defaults, seed })
    const expanded = generate({ ...defaults, seed, layout: 'expanded' })
    assert.equal(board.definition.shape.generatorVersion, 'world-jigsaw-grid-9')
    assert.notEqual(board.definition.puzzleId, expanded.definition.puzzleId)
    assert.deepEqual(board, generate({ ...defaults, seed }))
    assert.deepEqual(validateBoard(board.definition, board.snapshot), { valid: true, errors: [] })
    const members = board.snapshot.groups.flatMap((g) => g.pieceIds)
    assert.equal(members.length, 60)
    assert.equal(new Set(members).size, 60)
    assert.ok(board.snapshot.groups.every((g) => !g.finalPosition))
    const size = (r: typeof board.definition.workspace) => ({
      width: r.max.x - r.min.x,
      height: r.max.y - r.min.y,
    })
    assert.ok(size(board.definition.workspace).height < size(expanded.definition.workspace).height)
    const sides = new Set<string>()
    const boxes = board.snapshot.groups.map((group) => {
      const piece = board.definition.pieces.find((p) => p.pieceId === group.pieceIds[0])!
      const angle = (group.transform.rotation * Math.PI) / 180000
      const transform = (p: Point) => {
        const x = p.x + piece.solvedPosition.x,
          y = p.y + piece.solvedPosition.y
        return {
          x: Math.cos(angle) * x - Math.sin(angle) * y + group.transform.translation.x,
          y: Math.sin(angle) * x + Math.cos(angle) * y + group.transform.translation.y,
        }
      }
      const points = piece.edges
        .flatMap((edge) => [
          edge.start,
          ...edge.segments.flatMap((segment) =>
            segment.kind === 'line'
              ? [segment.to]
              : [segment.control1, segment.control2, segment.to],
          ),
        ])
        .map(transform)
      const box = {
        minX: Math.min(...points.map((p) => p.x)),
        maxX: Math.max(...points.map((p) => p.x)),
        minY: Math.min(...points.map((p) => p.y)),
        maxY: Math.max(...points.map((p) => p.y)),
      }
      const surface = board.definition.playSurface,
        workspace = board.definition.workspace
      if (box.maxY < surface.min.y) sides.add('top')
      if (box.minY > surface.max.y) sides.add('bottom')
      if (box.maxX < surface.min.x) sides.add('left')
      if (box.minX > surface.max.x) sides.add('right')
      assert.ok(
        box.maxY < surface.min.y ||
          box.minY > surface.max.y ||
          box.maxX < surface.min.x ||
          box.minX > surface.max.x,
      )
      assert.ok(
        box.minX >= workspace.min.x &&
          box.maxX <= workspace.max.x &&
          box.minY >= workspace.min.y &&
          box.maxY <= workspace.max.y,
      )
      return box
    })
    assert.deepEqual(sides, new Set(['top', 'right', 'bottom', 'left']))
    for (const [i, a] of boxes.entries())
      for (const b of boxes.slice(i + 1))
        assert.ok(a.maxX < b.minX || b.maxX < a.minX || a.maxY < b.minY || b.maxY < a.minY)
  }
})
