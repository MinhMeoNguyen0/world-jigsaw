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
}) => {
  const receipts = []
  page.on('websocket', (socket) => {
    socket.on('framereceived', ({ payload }) => {
      try {
        const message = JSON.parse(String(payload))
        if (message.type === 'preview.accepted') receipts.push(message)
      } catch {
        // Non-JSON frames are irrelevant to this assertion.
      }
    })
  })
  page.on('console', (message) => console.log(`browser ${message.type()}: ${message.text()}`))
  page.on('pageerror', (error) => console.log(`browser error: ${error.stack}`))
  await page.goto('/')
  await page.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
  const initial = await page.evaluate(async () => (await fetch('/api/v1/game/current')).json())
  expect(initial.snapshot.puzzleId).toBe(fixture.snapshot.puzzleId)
  expect(initial.snapshot.boardVersion).toBe(0)
  async function drag(piece, target) {
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
          (item.dataset.pieceIds || item.dataset.previewPieceIds || '').split(',').includes(piece),
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
  const beforeClick = await rendered('[data-preview-piece-ids]')
  await middleClick()
  expect(await rendered('[data-preview-piece-ids]')).toBe(beforeClick)
  await drag(pieces[1], { x: 800000, y: 600000 })
  await accepted()
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
})
