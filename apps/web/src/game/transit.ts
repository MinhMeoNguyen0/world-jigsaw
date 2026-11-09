import type { Piece } from '@world-jigsaw/contracts'

export function sampledScale(piece: Piece): number {
  const points: { x: number; y: number }[] = []
  for (const edge of piece.edges) {
    points.push(edge.start)
    let start = edge.start
    for (const segment of edge.segments) {
      if (segment.kind === 'line') points.push(segment.to)
      else {
        for (let i = 1; i <= 32; i++) {
          const j = 32 - i
          const coordinate = (axis: 'x' | 'y') =>
            Math.floor(
              (start[axis] * j * j * j +
                3 * segment.control1[axis] * j * j * i +
                3 * segment.control2[axis] * j * i * i +
                segment.to[axis] * i * i * i) /
                32768 +
                0.5,
            )
          points.push({ x: coordinate('x'), y: coordinate('y') })
        }
      }
      start = segment.to
    }
  }
  const xs = points.map((point) => point.x),
    ys = points.map((point) => point.y)
  return Math.min(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys))
}
