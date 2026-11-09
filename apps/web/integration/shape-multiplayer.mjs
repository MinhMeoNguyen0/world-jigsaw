import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const fixture = JSON.parse(
  await readFile(
    new URL('../../../packages/generator/fixtures/hoi-an-balanced-60.json', import.meta.url),
    'utf8',
  ),
)
const browser = await chromium.launch({ headless: true })
const ac = await browser.newContext({ viewport: { width: 1280, height: 720 } }),
  bc = await browser.newContext({ viewport: { width: 1280, height: 720 } })
const a = await ac.newPage(),
  b = await bc.newPage(),
  errors = []
for (const p of [a, b]) p.on('pageerror', (e) => errors.push(String(e)))
async function ready(p) {
  await p.goto('http://127.0.0.1:5173/')
  await p.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
}
async function current(p) {
  return p.evaluate(async () => await (await fetch('/api/v1/game/current')).json())
}
async function move(p, group, pieceId, world) {
  const v = await p.locator(`[data-group-id="${group.groupId}"] > path`).evaluate(
    (el, { pieceId, world, definition }) => {
      const r = el.getBoundingClientRect(),
        board = document.querySelector('svg.board'),
        piece = definition.pieces.find((p) => p.pieceId === pieceId),
        ref = new DOMPoint(
          piece.solvedPosition.x + piece.referencePoint.x,
          piece.solvedPosition.y + piece.referencePoint.y,
        ).matrixTransform(el.getScreenCTM()),
        target = new DOMPoint(world.x, world.y).matrixTransform(board.getScreenCTM()),
        sx = r.x + r.width / 2,
        sy = r.y + r.height / 2
      return { sx, sy, tx: target.x + sx - ref.x, ty: target.y + sy - ref.y }
    },
    { pieceId, world, definition: fixture.definition },
  )
  await p.mouse.move(v.sx, v.sy)
  await p.mouse.down()
  await p.mouse.move(v.tx, v.ty, { steps: 12 })
  await p.mouse.up()
}
try {
  await ready(a)
  await ready(b)
  await a.locator('.players-full', { hasText: '2 connected' }).waitFor({ timeout: 15000 })
  await b.locator('.players-full', { hasText: '2 connected' }).waitFor({ timeout: 15000 })
  console.log('presence reports two actual game sessions')
  const old = await current(a)
  assert.equal(
    old.snapshot.puzzleId,
    fixture.snapshot.puzzleId,
    'Import the disposable Hoi An fixture',
  )
  assert.equal(old.snapshot.boardVersion, 0, 'A fresh, unmodified test board is required')
  await a.getByRole('button', { name: 'Piece shape settings' }).click()
  await a.locator('#shape-depth').focus()
  for (let i = 0; i < 2; i++) await a.keyboard.press('ArrowRight')
  const applied = a.waitForResponse(
    (r) => r.url().endsWith('/api/v1/game/shape') && r.request().method() === 'POST',
  )
  await a.getByRole('button', { name: 'Apply shape' }).click()
  const r = await applied
  assert.equal(r.status(), 200)
  const result = await r.json()
  assert.equal(result.outcome, 'updated')
  await b.waitForFunction(
    (v) =>
      fetch('/api/v1/game/current')
        .then((r) => r.json())
        .then((x) => x.definition.definitionVersion === v),
    old.definition.definitionVersion + 1,
    { timeout: 20000 },
  )
  await a.locator('[data-connection=ready]').waitFor()
  await b.locator('[data-connection=ready]').waitFor()
  const after = await current(b)
  assert.equal(after.definition.shape.tabDepthPermille, 120)
  assert.equal(after.snapshot.boardVersion, 1)
  assert.deepEqual(after.snapshot.groups, old.snapshot.groups)
  await b.getByRole('button', { name: 'Piece shape settings' }).click()
  assert.equal(await b.locator('#shape-depth').inputValue(), '12')
  console.log(
    'shape update reached peer, advanced definition/board version and kept all group transforms',
  )
  const source = fixture.snapshot.groups[0]
  await move(b, source, source.pieceIds[0], { x: 500000, y: 200000 })
  await b.waitForFunction(
    () =>
      document.querySelector('.pill-count')?.textContent?.includes('4 left') &&
      document.querySelector('.pill-count')?.textContent?.includes('1 pending'),
    null,
    { timeout: 20000 },
  )
  console.log('first move projects four remaining from authoritative pending charge')
  if (!(await a.locator('#shape-width').isVisible()))
    await a.getByRole('button', { name: 'Piece shape settings' }).click()
  await a.locator('#shape-width').focus()
  await a.keyboard.press('ArrowRight')
  const rejected = a.waitForResponse(
    (r) => r.url().endsWith('/api/v1/game/shape') && r.request().method() === 'POST',
  )
  await a.getByRole('button', { name: 'Apply shape' }).click()
  const rr = await rejected
  assert.equal(rr.status(), 409)
  assert.equal((await rr.json()).error.code, 'PIECE_BUSY')
  assert.equal((await current(a)).definition.definitionVersion, after.definition.definitionVersion)
  console.log('another player draft blocks shape change without changing state')
  await b.getByRole('button', { name: 'Cancel batch' }).click()
  await b.locator('[data-connection=ready]').waitFor()
  await a.getByRole('button', { name: 'Apply shape' }).click()
  await a.waitForFunction(
    (v) =>
      fetch('/api/v1/game/current')
        .then((r) => r.json())
        .then((x) => x.definition.definitionVersion === v),
    after.definition.definitionVersion + 1,
    { timeout: 20000 },
  )
  console.log('shape applied after draft canceled')
  assert.deepEqual(errors, [])
} catch (e) {
  console.error('FAILED', e)
  console.error(
    'A',
    await a
      .locator('[role=status]')
      .textContent()
      .catch(() => ''),
  )
  console.error(
    'B',
    await b
      .locator('[role=status]')
      .textContent()
      .catch(() => ''),
  )
  process.exitCode = 1
} finally {
  await browser.close()
}
