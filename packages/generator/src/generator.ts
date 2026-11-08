import { createHash } from 'node:crypto'
import type {
  ContourSegment,
  Definition,
  Edge,
  Group,
  Piece,
  Point,
  Snapshot,
} from '@world-jigsaw/contracts'

/** Versioned inputs: changing the algorithm requires a new generatorVersion. */
export interface Options {
  seed: string
  columns: number
  rows: number
  width: number
  height: number
  image: string
  definitionVersion?: number
  placedPieces?: number
}
export interface GeneratedBoard {
  definition: Definition
  snapshot: Snapshot
}
const VERSION = 'world-jigsaw-grid-3'
const digest = (s: string) => createHash('sha256').update(s).digest('hex')
function uuid(s: string): string {
  const h = digest(s).slice(0, 32).split('')
  h[12] = '5'
  h[16] = ((parseInt(h[16], 16) & 3) | 8).toString(16)
  const x = h.join('')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
// RFC 8785 key ordering and JSON serialization for this integer-only schema.
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`
  const object = value as Record<string, unknown>
  return `{${Object.keys(object)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalJson(object[key])}`)
    .join(',')}}`
}
export function checksum(definition: Omit<Definition, 'checksum'>): string {
  return `sha256:${digest(canonicalJson(definition))}`
}
const point = (x: number, y: number): Point => ({ x, y })
const add = (a: Point, b: Point): Point => point(a.x + b.x, a.y + b.y)
const sub = (a: Point, b: Point): Point => point(a.x - b.x, a.y - b.y)

// Shared edge is built once in solved coordinates; its neighbor reverses the exact curve.
function seam(start: Point, end: Point, key: string, depth: number): ContourSegment[] {
  const dx = end.x - start.x,
    dy = end.y - start.y
  const sign = parseInt(digest(key).slice(0, 2), 16) % 2 ? 1 : -1
  const at = (along: number, normal: number): Point =>
    point(
      Math.round(start.x + dx * along - Math.sign(dy) * normal * sign),
      Math.round(start.y + dy * along + Math.sign(dx) * normal * sign),
    )
  // 340 permille neck width, 100 permille depth, 60 permille shoulder wave.
  return [
    {
      kind: 'cubic',
      control1: at(0.12, depth * 0.8),
      control2: at(0.25, depth * 0.8),
      to: at(0.33, 0),
    },
    {
      kind: 'cubic',
      control1: at(0.32, (depth * 4) / 3),
      control2: at(0.68, (depth * 4) / 3),
      to: at(0.67, 0),
    },
    { kind: 'cubic', control1: at(0.75, depth * 0.8), control2: at(0.88, depth * 0.8), to: end },
  ]
}
function reverse(
  start: Point,
  segments: ContourSegment[],
): { start: Point; segments: ContourSegment[] } {
  const starts = [start, ...segments.map((segment) => segment.to)]
  return {
    start: starts[starts.length - 1],
    segments: segments
      .map((segment, i) =>
        segment.kind === 'line'
          ? { kind: 'line' as const, to: starts[i] }
          : {
              kind: 'cubic' as const,
              control1: segment.control2,
              control2: segment.control1,
              to: starts[i],
            },
      )
      .reverse(),
  }
}
function localize(p: Point, origin: Point): Point {
  return sub(p, origin)
}
function localSegments(segments: ContourSegment[], origin: Point): Edge['segments'] {
  return segments.map((s) =>
    s.kind === 'line'
      ? { kind: 'line' as const, to: localize(s.to, origin) }
      : {
          kind: 'cubic' as const,
          control1: localize(s.control1, origin),
          control2: localize(s.control2, origin),
          to: localize(s.to, origin),
        },
  ) as Edge['segments']
}

export function generate(options: Options): GeneratedBoard {
  const { seed, columns, rows, width, height, image } = options
  const version = options.definitionVersion ?? 1,
    placed = options.placedPieces ?? 0
  if (
    typeof seed !== 'string' ||
    seed.length < 1 ||
    seed.length > 128 ||
    Array.from(seed).some(
      (c) => c.length === 1 && c.charCodeAt(0) >= 0xd800 && c.charCodeAt(0) <= 0xdfff,
    )
  )
    throw new Error('seed must be 1–128 non-surrogate characters')
  if (
    typeof image !== 'string' ||
    image.length < 1 ||
    image.length > 2048 ||
    Array.from(image).some(
      (c) => c.length === 1 && c.charCodeAt(0) >= 0xd800 && c.charCodeAt(0) <= 0xdfff,
    )
  )
    throw new Error('image must be 1–2048 non-surrogate characters')
  if (
    ![columns, rows, width, height, version, placed].every(Number.isSafeInteger) ||
    columns < 1 ||
    rows < 1 ||
    columns * rows > 80 ||
    width < columns * 1000 ||
    height < rows * 1000 ||
    width > 10000000 ||
    height > 10000000 ||
    version < 0 ||
    placed < 0 ||
    placed > columns * rows
  )
    throw new Error('invalid grid, dimensions, definition version or placed count')
  const identity = canonicalJson({
    generatorVersion: VERSION,
    seed,
    columns,
    rows,
    width,
    height,
    image,
    version,
  })
  const puzzleId = uuid(`puzzle:${identity}`)
  const pieceId = (r: number, c: number) => uuid(`${puzzleId}:piece:${r}:${c}`)
  const xs = Array.from({ length: columns + 1 }, (_, c) => Math.round((width * c) / columns))
  const ys = Array.from({ length: rows + 1 }, (_, r) => Math.round((height * r) / rows))
  const depth = Math.round(Math.min(width / columns, height / rows) * 0.1)
  const pieces: Piece[] = []
  for (let r = 0; r < rows; r++)
    for (let c = 0; c < columns; c++) {
      const origin = point(xs[c], ys[r]),
        x0 = xs[c],
        x1 = xs[c + 1],
        y0 = ys[r],
        y1 = ys[r + 1]
      const specs: [string, Point, Point, number, number, string, boolean][] = [
        ['top', point(x0, y0), point(x1, y0), r - 1, c, 'bottom', false],
        ['right', point(x1, y0), point(x1, y1), r, c + 1, 'left', false],
        ['bottom', point(x0, y1), point(x1, y1), r + 1, c, 'top', true],
        ['left', point(x0, y0), point(x0, y1), r, c - 1, 'right', true],
      ]
      const edges = specs.map(([edgeId, start, end, nr, nc, neighborEdgeId, backwards]) => {
        const inside = nr >= 0 && nr < rows && nc >= 0 && nc < columns
        const key = `${identity}:${edgeId === 'top' || edgeId === 'bottom' ? `h:${edgeId === 'top' ? r : r + 1}:${c}` : `v:${r}:${edgeId === 'left' ? c : c + 1}`}`
        const segments: ContourSegment[] = inside
          ? seam(start, end, key, depth)
          : [{ kind: 'line', to: end }]
        const path = backwards ? reverse(start, segments) : { start, segments }
        return {
          edgeId,
          neighborPieceId: inside ? pieceId(nr, nc) : null,
          neighborEdgeId: inside ? neighborEdgeId : null,
          start: localize(path.start, origin),
          segments: localSegments(path.segments, origin),
        }
      }) as Piece['edges']
      pieces.push({
        pieceId: pieceId(r, c),
        solvedPosition: origin,
        edges,
        referencePoint: point(Math.round((x1 - x0) / 2), Math.round((y1 - y0) / 2)),
      })
    }
  const margin = Math.max(width, height, 100000)
  const trayStep = Math.ceil(Math.max(width / columns, height / rows) * 2.5)
  const trayColumns = Math.ceil(Math.sqrt(pieces.length))
  const trayRows = Math.ceil(pieces.length / trayColumns)
  const trayX = width + margin
  const workspace = {
    min: point(-margin, -margin),
    max: point(
      trayX + (trayColumns + 1) * trayStep,
      Math.max(height + margin, (trayRows + 1) * trayStep),
    ),
  }
  const base: Omit<Definition, 'checksum'> = {
    schemaVersion: 1,
    puzzleId,
    definitionVersion: version,
    image,
    pieces: pieces as Definition['pieces'],
    imageBounds: { min: point(0, 0), max: point(width, height) },
    playSurface: { min: point(0, 0), max: point(width, height) },
    workspace,
    shape: {
      generatorVersion: VERSION,
      seed,
      tabDepthPermille: 100,
      tabWidthPermille: 340,
      edgeWavePermille: 60,
    },
  }
  const definition: Definition = { ...base, checksum: checksum(base) }
  const groups: Group[] = pieces.map((piece, i) => {
    const angle = (parseInt(digest(`${identity}:rotation:${i}`).slice(0, 8), 16) % 4) * 90000
    const rad = (angle * Math.PI) / 180000
    const ref = add(piece.solvedPosition, piece.referencePoint)
    const target = point(
      trayX + ((i % trayColumns) + 1) * trayStep,
      (Math.floor(i / trayColumns) + 1) * trayStep,
    )
    const translation = point(
      Math.round(target.x - (Math.cos(rad) * ref.x - Math.sin(rad) * ref.y)),
      Math.round(target.y - (Math.sin(rad) * ref.x + Math.cos(rad) * ref.y)),
    )
    return {
      groupId: uuid(`${puzzleId}:group:${i}`),
      revision: 0,
      pieceIds: [piece.pieceId],
      transform:
        i < placed ? { translation: point(0, 0), rotation: 0 } : { translation, rotation: angle },
      finalPosition: i < placed,
    }
  })
  return {
    definition,
    snapshot: {
      schemaVersion: 1,
      puzzleId,
      definitionVersion: version,
      boardVersion: 0,
      groups: groups as Snapshot['groups'],
    },
  }
}
