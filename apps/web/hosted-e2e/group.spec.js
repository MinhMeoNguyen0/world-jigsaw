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
const scenario = process.env.GROUP_SCENARIO || 'continuity'
test('a three-piece group stays together when its middle is clicked and dragged', async ({
  page,
  browser,
}, testInfo) => {
  const receipts = []
  const wire = []
  let socket
  let holdReceipt = false
  let holdHandoff = false
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
      if (
        (holdReceipt && type === 'preview.accepted') ||
        (holdHandoff && type === 'subscribed.v2') ||
        queued.length
      )
        queued.push(message)
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
    async function expectSources() {
      const matching = await page
        .locator('[data-piece-ids], [data-preview-piece-ids]')
        .evaluateAll(
          (elements, ids) =>
            elements
              .map((e) => (e.dataset.pieceIds || e.dataset.previewPieceIds || '').split(','))
              .filter((group) => group.some((id) => ids.includes(id))),
          pieces,
        )
      expect(matching).toHaveLength(3)
      expect(matching.flat().sort()).toEqual(pieces.slice().sort())
      await expect(page.locator('[data-local-ghost=true]')).toHaveCount(0)
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
            .evaluateAll(
              (elements, ids) =>
                elements.some((e) =>
                  ids.every((id) => e.dataset.previewPieceIds.split(',').includes(id)),
                ),
              pieces,
            ),
        { timeout: 20000 },
      )
      .toBe(true)
    const beforeClick = await rendered('[data-preview-piece-ids]')
    await middleClick()
    expect(await rendered('[data-preview-piece-ids]')).toBe(beforeClick)
    if (scenario === 'undo') {
      const undoResponse = page.waitForResponse(
        (r) => r.url().endsWith('/undo') && r.request().method() === 'POST',
      )
      await page.getByRole('button', { name: 'Undo last change' }).click()
      const response = await undoResponse
      expect(response.ok()).toBe(true)
      const result = await response.json()
      expect(result.actions.length).toBeGreaterThan(0)
      expect(result.actions.some((action) => action.actionId === result.actionId)).toBe(false)
      const surviving = [
        ...new Set(result.actions.flatMap((action) => action.affectedPieceIds)),
      ].sort()
      const previewIds = (target) =>
        target
          .locator('[data-preview-piece-ids]')
          .evaluateAll((elements) =>
            [
              ...new Set(elements.flatMap((element) => element.dataset.previewPieceIds.split(','))),
            ].sort(),
          )
      await page.locator('[data-connection=ready]').waitFor({ timeout: 20000 })
      await expect.poll(() => previewIds(page), { timeout: 20000 }).toEqual(surviving)
      await expect.poll(() => previewIds(peer), { timeout: 20000 }).toEqual(surviving)
    }
    if (scenario === 'cancel' || scenario === 'undo') {
      const response = page.waitForResponse(
        (r) => r.url().endsWith('/cancel') && r.request().method() === 'POST',
      )
      await page.getByRole('button', { name: 'Cancel batch' }).click()
      const result = await (await response).json()
      expect(['canceled', 'already_canceled']).toContain(result.outcome)
      await page.locator('[data-connection=ready]').waitFor({ timeout: 20000 })
      await expect(page.locator('[data-preview-piece-ids]')).toHaveCount(0)
      await expectSources()
      await expect
        .poll(async () => peer.locator('[data-preview-piece-ids]').count(), { timeout: 20000 })
        .toBe(0)
      const afterCancel = await page.evaluate(async () =>
        (await fetch('/api/v1/game/current')).json(),
      )
      expect(
        afterCancel.snapshot.groups.filter((g) => g.pieceIds.some((id) => pieces.includes(id))),
      ).toHaveLength(3)
      return
    }
    // Hold the real receipt and every subsequent server frame in order, never fabricate a reply.
    holdReceipt = true
    const pendingPoint = await drag(pieces[1], { x: 800000, y: 600000 }, false)
    await expect.poll(() => queued.length, { timeout: 20000 }).toBeGreaterThan(0)
    await expect(page.locator('[data-preview-pending=true]')).toBeAttached()
    const sentBeforeClick = wire.filter(
      (entry) => entry.direction === 'sent' && entry.message.includes('preview.submit'),
    ).length
    await expect(page.locator('[data-local-ghost=true]')).toHaveCount(1)
    const frameStart = await page.evaluate(() => performance.now())
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    )
    const pendingFrames = await page.evaluate(
      (start) => window.groupSamples.filter((s) => s.reason === 'frame' && s.at >= start),
      frameStart,
    )
    expect(pendingFrames.length).toBeGreaterThan(0)
    for (const frame of pendingFrames) {
      expect(frame.pending).toBe('true')
      expect(frame.elements.filter((e) => e.ids === 'ghost')).toHaveLength(1)
      expect(
        frame.elements.filter(
          (e) => e.ids !== 'ghost' && e.ids.split(',').some((id) => pieces.includes(id)),
        ),
      ).toHaveLength(0)
    }
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
    // An accepted draft is tentative while disconnected; it must not become actionable.
    const stableTransform = await rendered('[data-preview-piece-ids]')
    const recoveryStart = await page.evaluate(() => performance.now())
    await socket.close({ code: 1001, reason: 'hosted reconnect test' })
    await page.locator('[data-connection=disconnected]').waitFor({ timeout: 20000 })
    const disconnected = await page.evaluate(async () =>
      (await fetch('/api/v1/game/current')).json(),
    )
    expect(
      disconnected.snapshot.groups.filter((g) => g.pieceIds.some((id) => pieces.includes(id))),
    ).toHaveLength(3)

    if (scenario === 'expiry') {
      // No heartbeat is sent while disconnected. Wait past the 30-second lease and grace.
      await page.waitForTimeout(35000)
      await page.locator('button.reconnect').click()
      await page.locator('[data-connection=ready]').waitFor({ timeout: 20000 })
      await expect(page.locator('[data-preview-piece-ids]')).toHaveCount(0)
      await expectSources()
      await expect
        .poll(async () => peer.locator('[data-preview-piece-ids]').count(), { timeout: 20000 })
        .toBe(0)
      const afterExpiry = await page.evaluate(async () =>
        (await fetch('/api/v1/game/current')).json(),
      )
      expect(afterExpiry.ownTurn).toBeFalsy()
      expect(
        afterExpiry.snapshot.groups.filter((g) => g.pieceIds.some((id) => pieces.includes(id))),
      ).toHaveLength(3)
      return
    }
    if (scenario === 'failed-recovery') {
      await page.route('**/api/v1/game/current', (route) => route.abort('failed'))
      const attempted = page.waitForRequest((request) =>
        request.url().endsWith('/api/v1/game/current'),
      )
      await page.locator('button.reconnect').click()
      await attempted
      await expect(page.locator('[data-connection=disconnected]')).toBeAttached({ timeout: 20000 })
      await expect(page.locator('[data-preview-piece-ids]')).toHaveCount(0)
      await expectSources()
      const afterFailure = await peer.evaluate(async () =>
        (await fetch('/api/v1/game/current')).json(),
      )
      expect(
        afterFailure.snapshot.groups.filter((g) => g.pieceIds.some((id) => pieces.includes(id))),
      ).toHaveLength(3)
      return
    }

    expect(await rendered('[data-piece-ids], [data-preview-piece-ids]')).toBe(stableTransform)
    await expect(page.getByRole('button', { name: 'Confirm changes' })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Undo last change' })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Cancel batch' })).toBeDisabled()
    const sentBeforeDisconnectedInput = wire.filter((entry) => entry.direction === 'sent').length
    await page.mouse.move(pendingPoint.tx, pendingPoint.ty)
    await page.mouse.down()
    await page.mouse.move(pendingPoint.tx + 30, pendingPoint.ty + 30, { steps: 4 })
    await page.mouse.up()
    expect(await rendered('[data-piece-ids], [data-preview-piece-ids]')).toBe(stableTransform)
    expect(wire.filter((entry) => entry.direction === 'sent').length).toBe(
      sentBeforeDisconnectedInput,
    )

    // Delay the genuine v2 handoff, including all subsequent frames, to observe recovery.
    holdHandoff = true
    await page.locator('button.reconnect').click()
    await expect.poll(() => queued.length, { timeout: 20000 }).toBeGreaterThan(0)
    expect(JSON.parse(String(queued[0])).type).toBe('subscribed.v2')
    expect(await rendered('[data-piece-ids], [data-preview-piece-ids]')).toBe(stableTransform)
    await expect(page.getByRole('button', { name: 'Confirm changes' })).toBeDisabled()
    holdHandoff = false
    for (const message of queued) socket.send(message)
    queued = []
    await page.locator('[data-connection=ready]').waitFor({ timeout: 20000 })
    expect(await rendered('[data-preview-piece-ids]')).toBe(stableTransform)
    const recoveryFrames = await page.evaluate(
      (start) => window.groupSamples.filter((s) => s.reason === 'frame' && s.at >= start),
      recoveryStart,
    )
    expect(recoveryFrames.length).toBeGreaterThan(0)
    for (const frame of recoveryFrames) {
      const matching = frame.elements.filter(
        (e) => e.ids !== 'ghost' && e.ids.split(',').some((id) => pieces.includes(id)),
      )
      expect(matching).toHaveLength(1)
      expect(matching[0].ids.split(',').sort()).toEqual(pieces.slice().sort())
      expect(matching[0].transform).toBe(stableTransform)
    }
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
