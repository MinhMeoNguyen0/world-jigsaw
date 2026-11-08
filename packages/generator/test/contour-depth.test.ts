import assert from 'node:assert/strict'
import test from 'node:test'
import { generate } from '../src/generator.ts'

test('rendered seam depths match the declared shape settings in both axes and directions', () => {
  const directions = new Set<string>()
  for (const dimensions of [
    { columns: 10, rows: 6, width: 1200000, height: 800000 },
    { columns: 3, rows: 4, width: 4001, height: 5003 },
  ]) {
    const { definition } = generate({ ...dimensions, seed: 'depth-test', image: 'fixture://test' })
    const cellSize = Math.min(
      dimensions.width / dimensions.columns,
      dimensions.height / dimensions.rows,
    )
    for (const piece of definition.pieces) {
      for (const edge of piece.edges) {
        if (edge.neighborPieceId === null) continue
        const axis = edge.edgeId === 'top' || edge.edgeId === 'bottom' ? 'y' : 'x'
        const baseline = edge.start[axis]
        let start = edge.start
        for (const [index, segment] of edge.segments.entries()) {
          assert.equal(segment.kind, 'cubic')
          if (segment.kind !== 'cubic') throw new Error('expected cubic seam')
          const midpoint =
            (start[axis] +
              3 * segment.control1[axis] +
              3 * segment.control2[axis] +
              segment.to[axis]) /
            8
          const offset = midpoint - baseline
          const permille =
            index === 1 ? definition.shape.tabDepthPermille : definition.shape.edgeWavePermille
          assert.ok(
            Math.abs(Math.abs(offset) - (cellSize * permille) / 1000) <= 1,
            `${edge.edgeId} segment ${index}: ${offset}`,
          )
          directions.add(`${axis}:${Math.sign(offset)}`)
          start = segment.to
        }
      }
    }
  }
  assert.deepEqual([...directions].sort(), ['x:-1', 'x:1', 'y:-1', 'y:1'])
})
