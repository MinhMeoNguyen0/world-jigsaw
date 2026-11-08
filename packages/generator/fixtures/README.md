# Reproducible fixtures

These commands write contract-v1 JSON to standard output. The SVG in this directory is original artwork made for this project; the fixture URI must be mapped to that asset by a consumer. The review board has three pieces already in the play surface; the rest start in the shared outer workspace.

```sh
pnpm --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://original-landscape --placed-pieces 3 > review-60.json
pnpm --filter @world-jigsaw/generator generate --seed sample-80 --columns 10 --rows 8 --width 1200000 --height 800000 --image fixture://original-landscape > sample-80.json
```
