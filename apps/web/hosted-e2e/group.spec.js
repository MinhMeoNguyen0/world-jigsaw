import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
const fixture = JSON.parse(
  readFileSync(
    new URL('../../../packages/generator/fixtures/hoi-an-expanded-60.json', import.meta.url),
    'utf8',
  ),
)
const groups = fixture.snapshot.groups.slice(0, 3)
const pieces = groups.map((group) => group.pieceIds[0])
test('a three-piece group stays together when its middle is clicked and dragged', async ({
  page,
  browser,
}, testInfo) => {
  const receipts = []
  const wire = []
  let socket
  let holdReceipt = false
  let queued = []
  await page.routeWebSocket(/.*/, (route) => {
    socket = route
    const server = route.connectToServer()
    server.onMessage((message) => {
      let type = 'non-json'
      try {
        type = JSON.parse(String(message)).type
      } catch {
        /* retain opaque frames */
      }
      wire.push({ at: Date.now(), direction: 'received', type, message: String(message) })
      if (holdReceipt && (type === 'preview.accepted' || queued.length)) queued.push(message)
      else {
        if (type === 'preview.accepted') receipts.push(JSON.parse(String(message)))
        route.send(message)
      }
    })
    route.onMessage((message) => {
      wire.push({ at: Date.now(), direction: 'sent', message: String(message) })
      server.send(message)
    })
  })
  await page.addInitScript(() => {
    window.groupSamples = []
    const sample = (reason) => {
      const root = document.querySelector('[data-connection]')
      if (!root) return
      window.groupSamples.push({
        at: performance.now(),
        reason,
        connection: root.dataset.connection,
        pending: root.dataset.previewPending,
        status: document.querySelector('[role=status]')?.textContent,
        elements: [
          ...document.querySelectorAll(
            '[data-piece-ids], [data-preview-piece-ids], [data-local-ghost]',
          ),
        ].map((e) => ({
          ids: e.dataset.pieceIds ?? e.dataset.previewPieceIds ?? 'ghost',
          transform: e.getAttribute('transform'),
          bounds: e.getBoundingClientRect().toJSON(),
        })),
      })
    }
    new MutationObserver(() => sample('mutation')).observe(document, {
      subtree: true,
      childList: true,
      attributes: true,
    })
    const frame = () => {
      sample('frame')
      requestAnimationFrame(frame)
    }
    requestAnimationFrame(frame)
  })
  testInfo.setTimeout(180000)
  const peerContext = await browser.newContext()
  const peer = await peerContext.newPage()
  await peer.goto('http://127.0.0.1:5173/')
  await peer.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
  try {
    page.on('console', (message) => console.log(`browser ${message.type()}: ${message.text()}`))
    page.on('pageerror', (error) => console.log(`browser error: ${error.stack}`))
    await page.goto('/')
    await page.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
    const initial = await page.evaluate(async () => (await fetch('/api/v1/game/current')).json())
    expect(initial.snapshot.puzzleId).toBe(fixture.snapshot.puzzleId)
    expect(initial.snapshot.boardVersion).toBe(0)
    async function drag(piece, target, wait = true) {
      const previousReceipts = receipts.length
      const point = await page.evaluate(
        ({ piece, target, definition }) => {
          const source = [
            ...document.querySelectorAll('[data-piece-ids], [data-preview-piece-ids]'),
          ].find((element) =>
            (element.dataset.pieceIds || element.dataset.previewPieceIds || '')
              .split(',')
              .includes(piece),
          )
          const board = document.querySelector('svg.board')
          const def = definition.pieces.find((item) => item.pieceId === piece)
          const local = new DOMPoint(
            def.solvedPosition.x + def.referencePoint.x,
            def.solvedPosition.y + def.referencePoint.y,
          )
          if (!source) throw new Error(`Missing source for ${piece}`)
          const start = local.matrixTransform(source.getScreenCTM())
          const end =
            'selector' in target
              ? local.matrixTransform(document.querySelector(target.selector).getScreenCTM())
              : new DOMPoint(target.x, target.y).matrixTransform(board.getScreenCTM())
          return { sx: start.x, sy: start.y, tx: end.x, ty: end.y }
        },
        { piece, target, definition: fixture.definition },
      )
      await page.mouse.move(point.sx, point.sy)
      await page.mouse.down()
      await page.mouse.move(point.tx, point.ty, { steps: 12 })
      await page.mouse.up()
      if (!wait) return point
      await expect.poll(() => receipts.length, { timeout: 20000 }).toBeGreaterThan(previousReceipts)
      await expect(page.locator('[data-preview-pending=false]')).toBeAttached({ timeout: 20000 })
      await expect(page.getByRole('button', { name: 'Confirm changes' })).toBeEnabled({
        timeout: 20000,
      })
    }
    async function rendered(selector) {
      const components = await page.locator(selector).evaluateAll((elements) =>
        elements.map((element) => ({
          ids: (element.dataset.previewPieceIds || element.dataset.pieceIds || '').split(','),
          transform: element.getAttribute('transform'),
        })),
      )
      const matching = components.filter((component) =>
        component.ids.some((id) => pieces.includes(id)),
      )
      expect(matching).toHaveLength(1)
      expect(matching[0].ids.slice().sort()).toEqual(pieces.slice().sort())
      console.log('rendered component', JSON.stringify(matching[0]))
      return matching[0].transform
    }
    async function middleClick() {
      const point = await page.evaluate(
        ({ piece, definition }) => {
          const element = [
            ...document.querySelectorAll('[data-piece-ids], [data-preview-piece-ids]'),
          ].find((item) =>
            (item.dataset.pieceIds || item.dataset.previewPieceIds || '')
              .split(',')
              .includes(piece),
          )
          const def = definition.pieces.find((item) => item.pieceId === piece)
          const point = new DOMPoint(
            def.solvedPosition.x + def.referencePoint.x,
            def.solvedPosition.y + def.referencePoint.y,
          ).matrixTransform(element.getScreenCTM())
          const hit = document
            .elementFromPoint(point.x, point.y)
            ?.closest('[data-piece-ids], [data-preview-piece-ids]')
          if (hit !== element) throw new Error('Middle piece is not the hit target')
          return { x: point.x, y: point.y }
        },
        { piece: pieces[1], definition: fixture.definition },
      )
      await page.mouse.click(point.x, point.y)
    }
    async function accepted() {
      await expect
        .poll(
          () =>
            receipts
              .at(-1)
              ?.actions?.some((action) =>
                pieces.every((piece) => action.affectedPieceIds.includes(piece)),
              ),
          { timeout: 20000 },
        )
        .toBe(true)
      console.log('authoritative receipt', JSON.stringify(receipts.at(-1)))
    }
    await drag(pieces[0], { x: 500000, y: 200000 })
    await drag(pieces[1], { selector: `[data-preview-piece-ids="${pieces[0]}"]` })
    await drag(pieces[2], { selector: '[data-preview-piece-ids]' })
    await accepted()
    // The server has accepted the draft, while the durable snapshot still has three sources.
    const canonical = await page.evaluate(async () => (await fetch('/api/v1/game/current')).json())
    expect(
      canonical.snapshot.groups.filter((g) => g.pieceIds.some((id) => pieces.includes(id))),
    ).toHaveLength(3)
    await expect
      .poll(
        async () =>
          peer
            .locator('[data-preview-piece-ids]')
            .evaluateAll((elements) =>
              elements.some((e) =>
                pieces.every((id) => e.dataset.previewPieceIds.split(',').includes(id)),
              ),
            ),
        { timeout: 20000 },
      )
      .toBe(true)
    const beforeClick = await rendered('[data-preview-piece-ids]')
    await middleClick()
    expect(await rendered('[data-preview-piece-ids]')).toBe(beforeClick)
    // Hold the real receipt and every subsequent server frame in order, never fabricate a reply.
    holdReceipt = true
    const pendingPoint = await drag(pieces[1], { x: 800000, y: 600000 }, false)
    await expect.poll(() => queued.length, { timeout: 20000 }).toBeGreaterThan(0)
    await expect(page.locator('[data-preview-pending=true]')).toBeAttached()
    const sentBeforeClick = wire.filter(
      (entry) => entry.direction === 'sent' && entry.message.includes('preview.submit'),
    ).length
    await expect(page.locator('[data-local-ghost=true]')).toHaveCount(1)
    await page.mouse.click(pendingPoint.tx, pendingPoint.ty)
    await expect(page.locator('[data-local-ghost=true]')).toHaveCount(1)
    expect(
      wire.filter((entry) => entry.direction === 'sent' && entry.message.includes('preview.submit'))
        .length,
    ).toBe(sentBeforeClick)
    holdReceipt = false
    for (const message of queued) {
      const parsed = JSON.parse(String(message))
      if (parsed.type === 'preview.accepted') receipts.push(parsed)
      socket.send(message)
    }
    queued = []
    await expect(page.locator('[data-preview-pending=false]')).toBeAttached({ timeout: 20000 })
    await accepted()
    await rendered('[data-preview-piece-ids]')
    // Socket loss clears overlays. The separated canonical sources are expected until handoff.
    await socket.close({ code: 1001, reason: 'hosted reconnect test' })
    await page.locator('[data-connection=disconnected]').waitFor({ timeout: 20000 })
    expect(await page.locator('[data-preview-piece-ids]').count()).toBe(0)
    const disconnected = await page.evaluate(async () =>
      (await fetch('/api/v1/game/current')).json(),
    )
    expect(
      disconnected.snapshot.groups.filter((g) => g.pieceIds.some((id) => pieces.includes(id))),
    ).toHaveLength(3)
    await page.locator('button.reconnect').click()
    await page.locator('[data-connection=ready]').waitFor({ timeout: 20000 })
    await rendered('[data-preview-piece-ids]')
    await page.getByRole('button', { name: 'Confirm changes' }).click()
    await expect
      .poll(
        async () => {
          const game = await page.evaluate(async () => (await fetch('/api/v1/game/current')).json())
          return game.snapshot.groups
            .find((group) => pieces.every((piece) => group.pieceIds.includes(piece)))
            ?.pieceIds.slice()
            .sort()
        },
        { timeout: 20000 },
      )
      .toEqual(pieces.slice().sort())
    await expect(page.locator('[data-preview-piece-ids]')).toHaveCount(0)
    const confirmed = await rendered('[data-piece-ids]')
    await middleClick()
    expect(await rendered('[data-piece-ids]')).toBe(confirmed)
    await drag(pieces[1], { x: 500000, y: 600000 })
    await accepted()
    await rendered('[data-preview-piece-ids]')
  } finally {
    const samples = await page.evaluate(() => window.groupSamples).catch(() => [])
    await testInfo.attach('group-timeline.json', {
      body: Buffer.from(JSON.stringify({ pieces, wire, samples }, null, 2)),
      contentType: 'application/json',
    })
    await peerContext.close()
  }
})
