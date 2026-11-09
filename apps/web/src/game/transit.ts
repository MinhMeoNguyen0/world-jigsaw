import type { Piece, Transform } from '@world-jigsaw/contracts'

interface Bounds {
  left: number
  right: number
  top: number
  bottom: number
}

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
