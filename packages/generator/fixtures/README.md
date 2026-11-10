# Reproducible fixtures

These commands write contract-v1 JSON to standard output. The SVG in this directory is original artwork made for this project; the fixture URI must be mapped to that asset by a consumer. The review fixtures with `--placed-pieces 3` have three pieces already in the play surface; the rest start in the shared outer workspace. The compact fixture starts all 60 pieces around the play surface.

```sh
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://original-landscape --placed-pieces 3 --layout tray > review-60.json
pnpm --silent --filter @world-jigsaw/generator generate --seed sample-80 --columns 10 --rows 8 --width 1200000 --height 800000 --image fixture://original-landscape --layout tray > sample-80.json
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://original-landscape --placed-pieces 3 --layout surround > surround-60.json
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://hoi-an-generated --placed-pieces 3 --layout surround > hoi-an-surround-60.json
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://hoi-an-generated --placed-pieces 3 --layout balanced > hoi-an-balanced-60.json
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://hoi-an-generated --placed-pieces 3 --layout expanded > hoi-an-expanded-60.json
pnpm --silent --filter @world-jigsaw/generator generate --seed review-60 --columns 10 --rows 6 --width 1200000 --height 800000 --image fixture://hoi-an-generated --layout compact > hoi-an-compact-60.json
```
