import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'

const fixture = JSON.parse(
  readFileSync(
    new URL('../../../packages/generator/fixtures/hoi-an-expanded-60.json', import.meta.url),
    'utf8',
  ),
)
const scenario = process.env.GROUP_SCENARIO
const [moving, neighbor] = fixture.snapshot.groups.slice(0, 2).map((g) => g.pieceIds[0])
const unrelated = fixture.snapshot.groups[10].pieceIds[0]

test('held drag uses only a neighbor unchanged since the turn base', async ({
  page,
  browser,
}, testInfo) => {
  test.skip(!['changed-neighbor', 'unrelated-change'].includes(scenario), 'Neighbor matrix only')
  test.setTimeout(180000)
  const peerContext = await browser.newContext({
    recordVideo: { dir: testInfo.outputPath('peer-video') },
  })
  const peer = await peerContext.newPage()
  const events = []
  const receipts = []
  await page.addInitScript(() => {
    window.pointerEvidence = []
    for (const type of ['pointerdown', 'pointerup', 'pointercancel']) {
      document.addEventListener(
        type,
        (event) =>
          window.pointerEvidence.push({
            type,
            at: performance.now(),
            trusted: event.isTrusted,
            buttons: event.buttons,
          }),
        true,
      )
    }
  })
  await page.routeWebSocket(/.*/, (route) => {
    const server = route.connectToServer()
    server.onMessage((message) => {
      const value = JSON.parse(String(message))
      events.push({ at: Date.now(), direction: 'received', value })
      if (value.type === 'preview.accepted') receipts.push(value)
      route.send(message)
    })
    route.onMessage((message) => {
      events.push({ at: Date.now(), direction: 'sent', value: JSON.parse(String(message)) })
      server.send(message)
    })
  })
  const current = (p) => p.evaluate(async () => (await fetch('/api/v1/game/current')).json())
  const group = (game, id) => game.snapshot.groups.find((g) => g.pieceIds.includes(id))
  async function points(p, id, target) {
    return p.evaluate(
      ({ id, target, definition }) => {
        const element = [
          ...document.querySelectorAll('[data-piece-ids], [data-preview-piece-ids]'),
        ].find((e) =>
          (e.dataset.pieceIds || e.dataset.previewPieceIds || '').split(',').includes(id),
        )
        const def = definition.pieces.find((item) => item.pieceId === id)
        const local = new DOMPoint(
          def.solvedPosition.x + def.referencePoint.x,
          def.solvedPosition.y + def.referencePoint.y,
        )
        const start = local.matrixTransform(element.getScreenCTM())
        const end = target.selector
          ? local.matrixTransform(document.querySelector(target.selector).getScreenCTM())
          : new DOMPoint(target.x, target.y).matrixTransform(
              document.querySelector('svg.board').getScreenCTM(),
            )
        return { sx: start.x, sy: start.y, tx: end.x, ty: end.y }
      },
      { id, target, definition: fixture.definition },
    )
  }
  async function drag(p, id, target) {
    const pt = await points(p, id, target)
    await p.mouse.move(pt.sx, pt.sy)
    await p.mouse.down()
    await p.mouse.move(pt.tx, pt.ty, { steps: 12 })
    await p.mouse.up()
    await expect(p.locator('[data-preview-pending=false]')).toBeAttached({ timeout: 20000 })
    await expect(p.getByRole('button', { name: 'Confirm changes' })).toBeEnabled({ timeout: 20000 })
  }
  try {
    await Promise.all([page.goto('/'), peer.goto('http://127.0.0.1:5173/')])
    await Promise.all([
      page.locator('[data-connection=ready]').waitFor(),
      peer.locator('[data-connection=ready]').waitFor(),
    ])
    const identities = await Promise.all(
      [page, peer].map((p) =>
        p.evaluate(async () => (await fetch('/api/v1/session/identity')).json()),
      ),
    )
    expect(identities[0].subject).toBeTruthy()
    expect(identities[1].subject).toBeTruthy()
    expect(identities[0].subject).not.toBe(identities[1].subject)
    const initial = await current(page)
    expect(initial.snapshot.boardVersion).toBe(0)
    await drag(page, moving, { x: 500000, y: 200000 })
    const base = await current(page)
    expect(base.ownTurn).toBeTruthy()
    const before = structuredClone(group(base, neighbor))
    const pt = await points(page, moving, { selector: `[data-piece-ids="${neighbor}"]` })
    await page.mouse.move(pt.sx, pt.sy)
    await page.mouse.down()
    await page.mouse.move(pt.tx, pt.ty, { steps: 12 })
    await expect(page.locator('[data-local-ghost=true]')).toHaveCount(1)
    events.push({ at: Date.now(), marker: 'owner pointer held before peer move', before })
    const changed = scenario === 'changed-neighbor' ? neighbor : unrelated
    await drag(peer, changed, { x: 800000, y: 600000 })
    const confirmation = peer.waitForResponse(
      (r) => r.url().endsWith('/confirm') && r.request().method() === 'POST',
    )
    await peer.getByRole('button', { name: 'Confirm changes' }).click()
    const response = await confirmation
    expect(response.ok()).toBe(true)
    const result = await response.json()
    events.push({ at: Date.now(), marker: 'peer confirmation', result })
    expect(result.outcome).toBe('confirmed')
    await expect
      .poll(async () => (await current(page)).snapshot.boardVersion, { timeout: 20000 })
      .toBeGreaterThan(base.snapshot.boardVersion)
    await expect(page.locator('[data-connection=ready]')).toBeAttached({ timeout: 20000 })
    const after = await current(page)
    const afterNeighbor = group(after, neighbor)
    if (scenario === 'changed-neighbor') expect(afterNeighbor).not.toEqual(before)
    else expect(afterNeighbor).toEqual(before)
    expect(group(after, changed)).not.toEqual(group(base, changed))
    events.push({
      at: Date.now(),
      marker: 'owner resynced before pointer release',
      afterNeighbor,
      boardVersion: after.snapshot.boardVersion,
    })
    const held = await page.evaluate(() => window.pointerEvidence)
    events.push({ at: Date.now(), marker: 'pointer evidence before release', held })
    const lastDown = held.findLastIndex((event) => event.type === 'pointerdown')
    expect(lastDown).toBeGreaterThanOrEqual(0)
    expect(held[lastDown].trusted).toBe(true)
    expect(
      held
        .slice(lastDown + 1)
        .filter((event) => ['pointerup', 'pointercancel'].includes(event.type)),
    ).toEqual([])
    const count = receipts.length
    await page.mouse.up()
    await expect.poll(() => receipts.length, { timeout: 20000 }).toBeGreaterThan(count)
    await expect(page.locator('[data-preview-pending=false]')).toBeAttached({ timeout: 20000 })
    const last = receipts.at(-1)
    events.push({ at: Date.now(), marker: 'post-release receipt', last })
    const action = last.actions.find((item) => item.affectedPieceIds.includes(moving))
    expect(action).toBeTruthy()
    if (scenario === 'changed-neighbor') expect(action.affectedPieceIds).not.toContain(neighbor)
    else expect(action.affectedPieceIds).toContain(neighbor)
    const previewContains = (p) =>
      p
        .locator('[data-preview-piece-ids]')
        .evaluateAll(
          (elements, ids) =>
            elements.some((e) =>
              ids.every((id) => e.dataset.previewPieceIds.split(',').includes(id)),
            ),
          action.affectedPieceIds,
        )
    await expect.poll(() => previewContains(page), { timeout: 20000 }).toBe(true)
    await expect.poll(() => previewContains(peer), { timeout: 20000 }).toBe(true)
    const ownerGeometry = await page
      .locator('[data-piece-ids], [data-preview-piece-ids], [data-local-ghost]')
      .evaluateAll((elements) =>
        elements.map((e) => ({
          ids: e.dataset.pieceIds || e.dataset.previewPieceIds || 'ghost',
          transform: e.getAttribute('transform'),
        })),
      )
    const peerGeometry = await peer
      .locator('[data-piece-ids], [data-preview-piece-ids]')
      .evaluateAll((elements) =>
        elements.map((e) => ({
          ids: e.dataset.pieceIds || e.dataset.previewPieceIds,
          transform: e.getAttribute('transform'),
        })),
      )
    events.push({ at: Date.now(), marker: 'rendered geometry', ownerGeometry, peerGeometry })
    await expect(page.getByRole('button', { name: 'Confirm changes' })).toBeEnabled({
      timeout: 20000,
    })
    const ownConfirmation = page.waitForResponse(
      (r) => r.url().endsWith('/confirm') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: 'Confirm changes' }).click()
    const ownResult = await (await ownConfirmation).json()
    events.push({ at: Date.now(), marker: 'owner confirmation', ownResult })
    expect(ownResult.outcome).toBe('confirmed')
    await expect
      .poll(
        async () =>
          group(await current(peer), moving)
            ?.pieceIds.slice()
            .sort(),
        { timeout: 20000 },
      )
      .toEqual(action.affectedPieceIds.slice().sort())
    await peer.reload()
    await peer.locator('[data-connection=ready]').waitFor()
    expect(
      group(await current(peer), moving)
        .pieceIds.slice()
        .sort(),
    ).toEqual(action.affectedPieceIds.slice().sort())
  } finally {
    await testInfo.attach('neighbor-timeline.json', {
      body: Buffer.from(JSON.stringify({ scenario, moving, neighbor, unrelated, events }, null, 2)),
      contentType: 'application/json',
    })
    await peerContext.close()
  }
})
