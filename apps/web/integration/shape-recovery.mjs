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
  const before = await current(a)
  assert.equal(
    before.snapshot.puzzleId,
    fixture.snapshot.puzzleId,
    'Import the disposable Hoi An fixture',
  )
  let sent, intercepted, completed
  const interception = new Promise((resolve) => {
    completed = resolve
  })
  await a.route(
    '**/api/v1/game/shape',
    async (route) => {
      sent = route.request().postDataJSON()
      intercepted = await route.fetch()
      await route.abort('failed')
      completed()
    },
    { times: 1 },
  )
  await a.getByRole('button', { name: 'Piece shape settings' }).click()
  await a.locator('#shape-depth').focus()
  await a.keyboard.press('ArrowRight')
  await a.getByRole('button', { name: 'Apply shape' }).click()
  const timeout = new Promise((_, reject) => {
    setTimeout(() => reject(new Error('Shape response interception timed out')), 20000).unref()
  })
  await Promise.race([interception, timeout])
  await a.getByRole('button', { name: 'Retry shape change' }).waitFor({ timeout: 20000 })
  assert.equal(intercepted.status(), 200)
  await b.waitForFunction(
    (v) =>
      fetch('/api/v1/game/current')
        .then((r) => r.json())
        .then((x) => x.definition.definitionVersion === v),
    before.definition.definitionVersion + 1,
    { timeout: 20000 },
  )
  const updated = await current(b)
  assert.equal(updated.snapshot.boardVersion, before.snapshot.boardVersion + 1)
  const stored = JSON.parse(
    await a.evaluate(() => sessionStorage.getItem('world-jigsaw-pending-shape')),
  )
  assert.equal(stored.requestId, sent.requestId)
  await a.reload()
  await a.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
  await a.getByRole('button', { name: 'Retry shape change' }).waitFor()
  const source = fixture.snapshot.groups[0]
  await move(b, source, source.pieceIds[0], { x: 500000, y: 200000 })
  await b.waitForFunction(
    () => document.querySelector('.pill-count')?.textContent?.includes('1 pending'),
    null,
    { timeout: 20000 },
  )
  const retry = a.waitForResponse(
    (r) => r.url().endsWith('/api/v1/game/shape') && r.request().method() === 'POST',
  )
  await a.getByRole('button', { name: 'Retry shape change' }).click()
  const rr = await retry
  assert.equal(rr.status(), 200)
  assert.equal(rr.request().postDataJSON().requestId, sent.requestId)
  await a.getByRole('button', { name: 'Retry shape change' }).waitFor({ state: 'detached' })
  assert.equal(
    (await current(a)).definition.definitionVersion,
    updated.definition.definitionVersion,
  )
  assert.equal((await current(b)).ownTurn !== null, true)
  console.log(
    'lost shape response survives reload and same request retries once while peer draft remains active',
  )
  await b.getByRole('button', { name: 'Cancel batch' }).click()
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
