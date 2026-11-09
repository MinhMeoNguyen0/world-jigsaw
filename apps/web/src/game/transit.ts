import type { CurrentGame, Piece, PreviewState, Transform } from '@world-jigsaw/contracts'

interface Bounds {
  left: number
  right: number
  top: number
  bottom: number
}

// The cubic lies inside the convex hull of its endpoints and controls. Transforming
// that hull gives a conservative box: it can suppress safe frames but cannot prove
// a drop valid. Only the server makes the placement decision.
export function controlBounds(piece: Piece, transform: Transform): Bounds | null {
  const angle = transform.rotation
  if (![0, 90000, 180000, 270000].includes(angle)) return null
  const points: { x: number; y: number }[] = []
  for (const edge of piece.edges) {
    points.push(edge.start)
    for (const segment of edge.segments) {
      points.push(segment.to)
      if (segment.kind === 'cubic') points.push(segment.control1, segment.control2)
    }
  }
  if (!points.length) return null
  const values = points.map((p) => {
    const x = p.x + piece.solvedPosition.x,
      y = p.y + piece.solvedPosition.y
    const rotated =
      angle === 0
        ? { x, y }
        : angle === 90000
          ? { x: -y, y: x }
          : angle === 180000
            ? { x: -x, y: -y }
            : { x: y, y: -x }
    return { x: rotated.x + transform.translation.x, y: rotated.y + transform.translation.y }
  })
  return {
    left: Math.min(...values.map((p) => p.x)),
    right: Math.max(...values.map((p) => p.x)),
    top: Math.min(...values.map((p) => p.y)),
    bottom: Math.max(...values.map((p) => p.y)),
  }
}
const overlaps = (a: Bounds, b: Bounds) =>
  a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

/** Fail closed for transit. A false result never rejects the final pointer release. */
export function transitIsClear(
  game: CurrentGame,
  previews: Record<string, PreviewState>,
  movingIds: readonly string[],
  candidate: Transform,
): boolean {
  const moving = new Set(movingIds.map((id) => id.toLowerCase()))
  if (!moving.size) return false
  const pieces = new Map(
    game.definition.pieces.map((piece) => [piece.pieceId.toLowerCase(), piece]),
  )
  const movingBoxes = movingIds.map((id) => {
    const piece = pieces.get(id.toLowerCase())
    return piece ? controlBounds(piece, candidate) : null
  })
  if (movingBoxes.some((box) => box === null)) return false
  const others: { id: string; transform: Transform }[] = []
  // Include canonical AND peer preview positions. This is conservative during races.
  for (const group of game.snapshot.groups)
    for (const id of group.pieceIds)
      if (!moving.has(id.toLowerCase())) others.push({ id, transform: group.transform })
  for (const state of Object.values(previews))
    if (!state.closed)
      for (const action of Object.values(state.actions))
        for (const id of action.affectedPieceIds)
          if (!moving.has(id.toLowerCase()))
            others.push({ id, transform: action.intendedTransform })
  for (const other of others) {
    const piece = pieces.get(other.id.toLowerCase())
    const box = piece ? controlBounds(piece, other.transform) : null
    if (!box || movingBoxes.some((movingBox) => movingBox !== null && overlaps(movingBox, box)))
      return false
  }
  return true
}
