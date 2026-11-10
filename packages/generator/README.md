# Puzzle generator

This standalone Node 26 TypeScript generator produces a contract-v1 `{ definition, snapshot }` JSON document. The definition is immutable and its checksum is SHA-256 over RFC 8785 canonical JSON excluding `checksum`. Given the same inputs and generator version it produces identical bytes, IDs, contours and initial groups. Seed, grid, dimensions, image and definition version determine the puzzle ID; do not reuse an ID for a different definition. Publishing/importing, selecting a UTC day, and persisting a snapshot belong to the game service.

```sh
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://original-landscape --placed-pieces 3 > board.json
pnpm --filter @world-jigsaw/generator test
```

Dimensions are integer thousandths of puzzle-space units, not pixels; specify the image aspect ratio using width and height. Grid cells can be rectangular. Counts must be 1–80, each dimension at least 1,000 per cell and at most 10,000,000; CLI integers are decimal and nonnegative. The contour uses clockwise edges with shared, reversed cubic curves. The 100/340/60 permille depth/neck/shoulder settings are fixed for this generator version. Depth and shoulder are measured against the shorter average cell dimension, subject to integer-coordinate rounding. There is no snap or collision authority here. Generator geometry must be reviewed with frontend and game owners before integration; the frontend collision approximation is not a server rule.

Initial loose pieces occupy a deterministic shared outer workspace, separated on a grid and at seeded random quarter-turn rotations (0, 90, 180 or 270 degrees). The default compact layout is `world-jigsaw-grid-9`: it puts loose pieces around the play surface with closer spacing, so more of the picture fits in a desktop view. Its default has no pre-placed pieces. The play surface is also the move-counting boundary. Only newly generated and imported puzzles use it; an existing board keeps its persisted boundary and moves. A `placed-pieces` count places the first row-major pieces at their solved positions as independent groups; they are not permanently locked. It only changes the initial snapshot, not the immutable definition. The review seed uses three placed pieces. Pass `--layout tray` to reproduce the legacy generator version `world-jigsaw-grid-3`; a new layout has a new immutable definition and identity, not an update to an active board. Initial state is supplied once to the authority, not generated independently per client. All transforms rotate solved coordinates about the puzzle-space origin, then translate.

Pass `--layout surround` to generate version `world-jigsaw-grid-6`, which places the shared loose
pieces around all sides of the picture. The layout participates in puzzle identity and does not
rewrite an existing board. `fixtures/surround-60.json` preserves that example.

Pass `--layout balanced` for version `world-jigsaw-grid-7`, which spreads pieces farther vertically
so a uniform shared-board fit uses more of a portrait viewport. The version has its own puzzle
identity; it never changes an existing board. The [local setup](../../README.md#local-setup) identifies
the fixture used by the web UI.

The CLI defaults to `--layout compact`. Pass `--layout expanded` for the prior `world-jigsaw-grid-8` layout, or specify another legacy layout explicitly to reproduce its fixture. Explicit `--placed-pieces` still places the requested number on the surface; omit it for a fully scattered board.

`fixtures/original-landscape.svg` is original geometric artwork created for this repository; no external image or license is involved. `fixture://original-landscape` is a placeholder identifier, not a hosted URL. The 60-piece review and 80-piece examples can be reproduced by the commands in `fixtures/README.md`; they have no implied production image distribution or import contract.
