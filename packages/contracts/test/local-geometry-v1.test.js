import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// Independent rectangle oracle for the shared square vectors. The Java implementation operates on
// sampled arbitrary polygons; this test intentionally uses only axis-aligned interval arithmetic.
const fixture = JSON.parse(
  readFileSync(new URL('../v1/fixtures/local-geometry-v1.json', import.meta.url)),
)
const rotate = ({ x, y }, angle) => {
  switch (angle) {
    case 0:
      return { x, y }
    case 90000:
      return { x: -y, y: x }
    case 180000:
      return { x: -x, y: -y }
    case 270000:
      return { x: y, y: -x }
    default:
      throw new Error('not a quarter turn')
  }
}
const point = (p, transform) => {
  const r = rotate(p, transform.rotation)
  return { x: r.x + transform.translation.x, y: r.y + transform.translation.y }
}
const bounds = (piece, transform) => {
  const origin = piece.solvedPosition
  const corners = [
    { x: origin.x, y: origin.y },
    { x: origin.x + 100000, y: origin.y },
    { x: origin.x + 100000, y: origin.y + 100000 },
    { x: origin.x, y: origin.y + 100000 },
  ].map((p) => point(p, transform))
  return {
    minX: Math.min(...corners.map((p) => p.x)),
    minY: Math.min(...corners.map((p) => p.y)),
    maxX: Math.max(...corners.map((p) => p.x)),
    maxY: Math.max(...corners.map((p) => p.y)),
  }
}

test('local geometry square collision vectors match independent rectangle arithmetic', () => {
  for (const example of fixture.collisionCases) {
    const boxes = fixture.pieces.map((piece, i) => bounds(piece, example.transforms[i]))
    let forbidden = false
    for (let i = 0; i < boxes.length; i++)
      for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i],
          b = boxes[j]
        const overlapsInset = (outer, inner) =>
          Math.min(outer.maxX, inner.maxX - 10000) > Math.max(outer.minX, inner.minX + 10000) &&
          Math.min(outer.maxY, inner.maxY - 10000) > Math.max(outer.minY, inner.minY + 10000)
        forbidden ||= overlapsInset(a, b) || overlapsInset(b, a)
      }
    assert.equal(forbidden, example.forbidden, example.name)
  }
})

test('local geometry square snap vectors use reciprocal neighbors and inclusive displacement', () => {
  for (const example of fixture.snapCases) {
    const moving = fixture.pieces[example.moving],
      target = fixture.pieces[example.target]
    const adjacent = moving.edges.some(
      (edge) =>
        edge.neighborPieceId === target.pieceId &&
        target.edges.some(
          (back) =>
            back.edgeId === edge.neighborEdgeId &&
            back.neighborPieceId === moving.pieceId &&
            back.neighborEdgeId === edge.edgeId,
        ),
    )
    const reference = {
      x: moving.solvedPosition.x + moving.referencePoint.x,
      y: moving.solvedPosition.y + moving.referencePoint.y,
    }
    const a = point(reference, example.movingTransform),
      b = point(reference, example.targetTransform)
    const allowed =
      adjacent &&
      example.movingTransform.rotation === example.targetTransform.rotation &&
      Math.hypot(a.x - b.x, a.y - b.y) <= 5000
    assert.equal(allowed, example.allowed, example.name)
  }
})

test('narrow tab intrusion vectors match independent rectangular interval arithmetic', () => {
  for (const example of fixture.concaveCases.squareLeft) {
    // The other square's inset starts at x + 10000; its y interval contains the tab.
    // The main body and its inset lie left of x=100000, far from every example square.
    assert.equal(160000 > example.x + 10000, example.forbidden, String(example.x))
  }
})
