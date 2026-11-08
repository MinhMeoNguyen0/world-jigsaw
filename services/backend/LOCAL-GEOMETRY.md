# Provisional local geometry policy

This document and `packages/contracts/v1/fixtures/local-geometry-v1.json` describe a provisional
engineering policy for private local integration. They do not assert frontend agreement, public
security, or a production deployment. The private-local service uses these rules;
coordinate this fixture and its thresholds through Firstmate before changing the frontend.

The Java geometry helper is deliberately internal and is not an endpoint or a complete validator.
The caller must first verify the definition, checksum, membership, connected groups, workspace,
revisions and actions. Any invalid geometry, unsupported orientation, topology exception or resource
failure must reject the operation without saving or charging. For legacy arbitrary-angle boards,
the interaction API reports `UNSUPPORTED_LEGACY_ORIENTATION` without rewriting the
board. All pieces in a candidate state must be checked together after locking the board, including
pieces changed simultaneously and the result of snapping; a previous validation is not sufficient.

- Coordinates are integer thousandths. Each cubic contour segment is sampled at 32 equal parameter
  intervals using integer arithmetic and rounding `floor(x + 0.5)` to the nearest coordinate unit.
  Line segments use their endpoints. Reversed matching cubics therefore share sampled vertices.
  The contour must be closed, simple and nonempty, with at most 4,096 sampled vertices per piece.
  Quarter turns use exact integer arithmetic.
- A piece's scale is the smaller dimension of its sampled contour's axis-aligned bounding box.
  Scale must be at least 1,000 units. For each pair, both polygons are inset by
  `floor(min(scaleA, scaleB) / 10)` coordinate units, using JTS 1.20.0 fixed precision and eight
  segments per quadrant for round buffer joins. If either original polygon intersects the interior of the other polygon’s inset, the candidate
  is rejected; touching inset boundaries are allowed. Checking both directions prevents a narrow
  protrusion disappearing from its own inset and hiding deep intrusion into the other piece. Empty inset polygons are unsupported and
  rejected. This corresponds to up to 10% penetration for two aligned squares, but is a
  _contour-core_ rule for curved or concave shapes, not an exact penetration-distance guarantee.
  It is deliberately distinct from the prototype's 8/100 comparison and needs play testing.
- A snap candidate requires reciprocal neighboring edge IDs, equal quarter-turn orientations and
  Euclidean displacement of the moving piece's reference point no greater than
  `floor(min(scaleA, scaleB) / 20)`. The boundary is inclusive. The rules engine must still
  validate the whole snapped board before accepting it. Invalid snap must not turn an invalid
  unsnapped release into an accepted move.

The fixture's expected square outcomes are specified from simple independent rectangle arithmetic,
including the inclusive thresholds. Java tests consume those shared cases and also check the real
60-piece generated contour set in solved arrangement. A separate JavaScript interval-arithmetic
oracle checks the square examples independently. This is not a complete independent proof of
curved-edge collision behavior: further cross-language and real-client validation remains necessary
before production use.
