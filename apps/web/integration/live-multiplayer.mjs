import { chromium } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const fixture = JSON.parse(
  await readFile(
    new URL('../../../packages/generator/fixtures/hoi-an-balanced-60.json', import.meta.url),
    'utf8',
  ),
)
const ids = fixture.snapshot.groups.slice(0, 5).map((g) => g.groupId)
const pieces = fixture.snapshot.groups.slice(0, 5).map((g) => g.pieceIds[0])
const origin = new URL(process.env.WORLD_JIGSAW_TEST_ORIGIN ?? 'http://127.0.0.1:5173')
if (
  origin.protocol !== 'http:' ||
  !['localhost', '127.0.0.1'].includes(origin.hostname) ||
  origin.username ||
  origin.password ||
  origin.pathname !== '/' ||
  origin.search ||
  origin.hash
)
  throw new Error('Use a private loopback test origin')
const current = await fetch(new URL('/api/v1/game/current', origin)).then((response) =>
  response.json(),
)
assert.equal(
  current.snapshot?.puzzleId,
  fixture.snapshot.puzzleId,
  'Import the hoi-an-balanced-60 fixture into a disposable database',
)
assert.equal(current.snapshot?.boardVersion, 0, 'A fresh, unmodified test board is required')
const browser = await chromium.launch({ headless: true })
const ac = await browser.newContext({ viewport: { width: 1280, height: 720 } })
const bc = await browser.newContext({ viewport: { width: 1280, height: 720 } })
const a = await ac.newPage(),
  b = await bc.newPage()
const errors = []
for (const page of [a, b]) page.on('pageerror', (e) => errors.push(String(e)))
const status = (page) => page.locator('[role=status]')
async function ready(page) {
  await page.goto(origin.href)
  await page.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
}
async function settle(page, expected) {
  await page.waitForFunction(
    (n) =>
      document.querySelector('.pill-count')?.textContent?.includes(`${n} pending`) &&
      !document.querySelector('.pill-confirm')?.disabled,
    expected,
    { timeout: 20000 },
  )
}
async function dragTo(page, groupId, pieceId, target, hold = false) {
  const selector = `[data-group-id="${groupId}"] > path`
  const points = await page.locator(selector).evaluate(
    (path, { pieceId, target, definition }) => {
      const group = path.parentElement,
        board = document.querySelector('svg.board'),
        rect = path.getBoundingClientRect()
      const piece = definition.pieces.find((p) => p.pieceId === pieceId)
      if (!group || !board || !piece || !rect.width) throw new Error('missing drag source')
      const local = new DOMPoint(
        piece.solvedPosition.x + piece.referencePoint.x,
        piece.solvedPosition.y + piece.referencePoint.y,
      )
      const current = local.matrixTransform(path.getScreenCTM())
      let desired
      if (target.groupId) {
        const neighbor = document.querySelector(`[data-group-id="${target.groupId}"]`)
        if (!neighbor) throw new Error('missing join target')
        desired = local.matrixTransform(neighbor.getScreenCTM())
      } else desired = new DOMPoint(target.x, target.y).matrixTransform(board.getScreenCTM())
      const sx = rect.x + rect.width / 2,
        sy = rect.y + rect.height / 2
      return { sx, sy, tx: desired.x + sx - current.x, ty: desired.y + sy - current.y }
    },
    { pieceId, target, definition: fixture.definition },
  )
  await page.mouse.move(points.sx, points.sy)
  await page.mouse.down()
  await page.mouse.move(points.tx, points.ty, { steps: 12 })
  if (!hold) await page.mouse.up()
  return points
}
try {
  await ready(a)
  await ready(b)
  const original = await b.locator(`[data-group-id="${ids[0]}"]`).getAttribute('transform')
  await dragTo(a, ids[0], pieces[0], { x: 500000, y: 200000 }, true)
  await b.locator('[data-preview-action]').first().waitFor({ state: 'attached', timeout: 20000 })
  console.log('peer saw preview while pointer held')
  await a.mouse.up()
  await settle(a, 1)
  await a.getByRole('button', { name: 'Cancel batch' }).click()
  await b.waitForFunction(() => document.querySelectorAll('[data-preview-action]').length === 0)
  assert.equal(await b.locator(`[data-group-id="${ids[0]}"]`).getAttribute('transform'), original)
  console.log('cancel restored peer canonical position')
  const targets = [
    { x: 500000, y: 200000 },
    { x: 800000, y: 200000 },
    { x: 500000, y: 600000 },
    { x: 800000, y: 600000 },
    { x: 1000000, y: 400000 },
  ]
  for (let i = 0; i < 5; i++) {
    await dragTo(b, ids[i], pieces[i], targets[i])
    await settle(b, i + 1)
  }
  await a.waitForFunction(() => document.querySelectorAll('[data-preview-action]').length === 5)
  console.log('five independent previews visible to peer')
  await b.getByRole('button', { name: 'Confirm changes' }).click()
  await b.waitForFunction(
    () =>
      document.querySelector('.pill-count')?.textContent?.includes('0 left') &&
      document.querySelector('.pill-count')?.textContent?.includes('0 pending'),
  )
  const saved = await Promise.all(
    ids.map((id) => b.locator(`[data-group-id="${id}"]`).getAttribute('transform')),
  )
  await a.reload()
  await a.locator('[data-connection=ready]').waitFor()
  assert.deepEqual(
    await Promise.all(
      ids.map((id) => a.locator(`[data-group-id="${id}"]`).getAttribute('transform')),
    ),
    saved,
  )
  console.log('five-action confirmation persisted and peer reload matched')
  await dragTo(a, ids[0], pieces[0], { groupId: ids[1] })
  await settle(a, 1)
  const peerPreview = b.locator('[data-preview-piece-ids]')
  await peerPreview.first().waitFor({ state: 'attached' })
  const members = await peerPreview.first().getAttribute('data-preview-piece-ids')
  assert.ok(
    members.includes(pieces[0]) && members.includes(pieces[1]),
    `join preview members: ${members}`,
  )
  await a.getByRole('button', { name: 'Confirm changes' }).click()
  await a.waitForFunction(() =>
    document.querySelector('.pill-count')?.textContent?.includes('4 left'),
  )
  await b.reload()
  await b.locator('[data-connection=ready]').waitFor()
  const merged = await b
    .locator('[data-group-id]')
    .evaluateAll(
      (groups, ids) =>
        groups.find((g) => ids.every((id) => g.dataset.pieceIds.split(',').includes(id)))?.dataset
          .groupId,
      pieces.slice(0, 2),
    )
  assert.ok(merged, 'merged component after reload')
  console.log('adjacent pieces joined and persisted; one move charged')
  await a.locator('[data-connection=ready]').waitFor()
  await dragTo(a, merged, pieces[0], { x: -200000, y: 0 })
  await settle(a, 1)
  await b.locator('[data-preview-piece-ids]').first().waitFor({ state: 'attached' })
  const groupPreview = await b
    .locator('[data-preview-piece-ids]')
    .first()
    .getAttribute('data-preview-piece-ids')
  assert.ok(
    pieces.slice(0, 2).every((id) => groupPreview.includes(id)),
    'whole group previewed',
  )
  await a.getByRole('button', { name: 'Confirm changes' }).click()
  await a.waitForFunction(() =>
    document.querySelector('.pill-count')?.textContent?.includes('3 left'),
  )
  const moved = await a.locator(`[data-group-id="${merged}"]`).getAttribute('transform')
  await b.reload()
  await b.locator('[data-connection=ready]').waitFor()
  assert.equal(await b.locator(`[data-group-id="${merged}"]`).getAttribute('transform'), moved)
  console.log('connected group moved as one charged action and persisted')
  for (const angle of [0, 90000, 180000, 270000]) {
    const candidate = fixture.snapshot.groups
      .slice(5)
      .find((group) => group.transform.rotation === angle)
    assert.ok(candidate, `fixture contains ${angle} orientation`)
    await a.locator('[data-connection=ready]').waitFor()
    await a.locator(`[data-group-id="${candidate.groupId}"] > path`).click()
    await a.getByRole('button', { name: 'Rotate selected piece' }).click()
    await settle(a, 0)
    const preview = b.locator(`[data-preview-piece-ids="${candidate.pieceIds[0]}"]`)
    await preview.waitFor({ state: 'attached' })
    assert.ok(
      (await preview.getAttribute('transform')).includes(
        `rotate(${((angle + 90000) % 360000) / 1000})`,
      ),
    )
    await a.getByRole('button', { name: 'Cancel batch' }).click()
    await b.waitForFunction(() => document.querySelectorAll('[data-preview-action]').length === 0)
    await a.locator('[data-connection=ready]').waitFor()
  }
  console.log('all four initial orientations rotated and canceled')
  await a.getByRole('button', { name: /Live chat/ }).click()
  await b.getByRole('button', { name: /Live chat/ }).click()
  await a.getByRole('textbox', { name: 'Message' }).fill('A real shared message')
  await a.getByRole('button', { name: 'Send', exact: true }).click()
  await b.getByText('A real shared message').waitFor()
  console.log('mounted chat delivered to peer')
  assert.deepEqual(errors, [])
} catch (error) {
  console.error('FAILED', error)
  console.error(
    'A',
    await status(a)
      .textContent()
      .catch(() => ''),
  )
  console.error(
    'B',
    await status(b)
      .textContent()
      .catch(() => ''),
  )
  process.exitCode = 1
} finally {
  await browser.close()
}
