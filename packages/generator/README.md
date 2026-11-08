# Puzzle generator v1

This standalone Node 26 TypeScript generator produces a contract-v1 `{ definition, snapshot }` JSON document. The definition is immutable and its checksum is SHA-256 over RFC 8785 canonical JSON excluding `checksum`. Given the same inputs and generator version it produces identical bytes, IDs, contours and initial groups. Seed, grid, dimensions, image and definition version determine the puzzle ID; do not reuse an ID for a different definition. Publishing/importing, selecting a UTC day, and persisting a snapshot belong to the game service.

```sh
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://original-landscape --placed-pieces 3 > board.json
pnpm --filter @world-jigsaw/generator test
```

Dimensions are integer thousandths of puzzle-space units, not pixels; specify the image aspect ratio using width and height. Grid cells can be rectangular. Counts must be 1–80, each dimension at least 1,000 per cell and at most 10,000,000; CLI integers are decimal and nonnegative. The contour uses clockwise edges with shared, reversed cubic curves. The 100/340/60 permille depth/neck/shoulder settings are fixed for this generator version. There is no snap or collision authority here. Generator geometry must be reviewed with frontend and game owners before integration; the frontend collision approximation is not a server rule.

Initial loose pieces occupy a deterministic shared outer workspace, separated on a grid and at arbitrary millidegree rotations. A `placed-pieces` count places the first row-major pieces at their solved positions as independent groups; they are not permanently locked. It only changes the initial snapshot, not the immutable definition. The review seed uses three placed pieces. Initial state is supplied once to the authority, not generated independently per client. All transforms rotate solved coordinates about the puzzle-space origin, then translate.

`fixtures/original-landscape.svg` is original geometric artwork created for this repository; no external image or license is involved. `fixture://original-landscape` is a placeholder identifier, not a hosted URL. The 60-piece review and 80-piece examples can be reproduced by the commands in `fixtures/README.md`; they have no implied production image distribution or import contract.
