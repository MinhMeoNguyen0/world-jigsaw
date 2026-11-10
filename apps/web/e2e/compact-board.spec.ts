/// <reference lib="dom" />
import { expect, test } from '@playwright/test'
import fixture from '../../../packages/generator/fixtures/hoi-an-compact-60.json' with { type: 'json' }

const allowance = { limit: 5, used: 0, remaining: 5 }

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 700, height: 500 },
  { width: 375, height: 700 },
  { width: 320, height: 300 },
  { width: 350, height: 320 },
  { width: 374, height: 320 },
  { width: 375, height: 320 },
  { width: 376, height: 320 },
  { width: 399, height: 320 },
  { width: 400, height: 320 },
  { width: 460, height: 320 },
  { width: 461, height: 320 },
  { width: 700, height: 320 },
  { width: 400, height: 399 },
  { width: 400, height: 400 },
  { width: 400, height: 401 },
  { width: 700, height: 400 },
  { width: 700, height: 401 },
]) {
  test(`compact board is visible and selectable at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport)
    await page.route('**/api/v1/session', (route) =>
      route.fulfill({
        json: { schemaVersion: 1, displayName: 'Player', expiresAt: '2030-01-02T00:00:00Z' },
      }),
    )
    await page.route('**/api/v1/game/current', (route) =>
      route.fulfill({
        json: {
          schemaVersion: 1,
          day: '2030-01-01',
          serverTime: '2030-01-01T12:00:00Z',
          endsAt: '2030-01-02T00:00:00Z',
          ...fixture,
          allowance,
          ownTurn: null,
        },
      }),
    )
    await page.routeWebSocket('**/api/v1/game/stream', (socket) => {
      socket.onMessage((message) => {
        if (JSON.parse(String(message)).type !== 'subscribe.v2') return
        socket.send(
          JSON.stringify({
            schemaVersion: 1,
            type: 'subscribed.v2',
            snapshot: fixture.snapshot,
            watermark: fixture.snapshot.boardVersion,
            previews: [],
            allowance,
            ownTurn: null,
            actions: [],
            nextSequence: 0,
            draftRevision: null,
          }),
        )
      })
    })
    await page.goto('/')
    await expect(page.locator('[data-connection=ready]')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Zoom out' })).toBeDisabled()
    const pieces = await page.locator('[data-group-id]').evaluateAll(
      (groups, definition) =>
        groups.map((group) => {
          const id = (group as SVGGElement).dataset.pieceIds!
          const piece = definition.pieces.find((item) => item.pieceId === id)!
          const path = group.querySelector(':scope > path')!
          const rect = path.getBoundingClientRect()
          const point = new DOMPoint(
            piece.solvedPosition.x + piece.referencePoint.x,
            piece.solvedPosition.y + piece.referencePoint.y,
          ).matrixTransform((group as SVGGElement).getScreenCTM()!)
          return {
            id,
            visible:
              rect.width > 0 &&
              rect.height > 0 &&
              rect.left >= 0 &&
              rect.top >= 0 &&
              rect.right <= innerWidth &&
              rect.bottom <= innerHeight,
            selectable:
              document.elementFromPoint(point.x, point.y)?.closest('[data-group-id]') === group,
          }
        }),
      fixture.definition,
    )
    expect(pieces).toHaveLength(60)
    expect(new Set(pieces.map((piece) => piece.id))).toEqual(
      new Set(fixture.definition.pieces.map((piece) => piece.pieceId)),
    )
    expect(pieces.every((piece) => piece.visible && piece.selectable)).toBe(true)
    const controls = await page.locator('.board-controls button').evaluateAll((buttons) =>
      buttons.map((button) => {
        const rect = button.getBoundingClientRect()
        const target = document.elementFromPoint(
          rect.left + rect.width / 2,
          rect.top + rect.height / 2,
        )
        return {
          label: button.getAttribute('aria-label'),
          usable:
            rect.left >= 0 &&
            rect.top >= 0 &&
            rect.right <= innerWidth &&
            rect.bottom <= innerHeight &&
            (target === button || button.contains(target)),
        }
      }),
    )
    expect(controls).toHaveLength(8)
    expect(controls.filter((control) => !control.usable)).toEqual([])
    if (viewport.height <= 401) {
      const pictureWidth = await page.locator('[data-picture-overlay]').evaluate((image) => {
        return Number(image.getAttribute('width')) * (image as SVGImageElement).getScreenCTM()!.a
      })
      expect(pictureWidth).toBeGreaterThan(63)
    }
    if (
      (viewport.width === 375 && viewport.height === 320) ||
      (viewport.width === 1440 && viewport.height === 900)
    ) {
      await page.getByRole('button', { name: 'Zoom in' }).click()
      await expect(page.getByRole('button', { name: 'Zoom out' })).toBeEnabled()
      await page.getByRole('button', { name: 'Fit whole canvas' }).click()
      await expect(page.getByRole('button', { name: 'Zoom out' })).toBeDisabled()
      const pill = page.locator('.move-pill')
      const handle = page.getByRole('button', { name: 'Move controls' })
      const before = (await pill.boundingBox())!
      const grip = (await handle.boundingBox())!
      await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2)
      await page.mouse.down()
      await page.mouse.move(grip.x + grip.width / 2 + 1, grip.y + grip.height / 2 + 1)
      const moved = (await pill.boundingBox())!
      expect(moved.x).toBeCloseTo(before.x + 1, 0)
      expect(moved.y).toBeCloseTo(before.y + 1, 0)
      await page.mouse.move(-100, -100)
      const upperLeft = (await pill.boundingBox())!
      expect(upperLeft.x).toBeCloseTo(0, 0)
      expect(upperLeft.y).toBeCloseTo(0, 0)
      await page.mouse.move(viewport.width + 100, viewport.height + 100)
      const clamped = (await pill.boundingBox())!
      expect(clamped.x + clamped.width).toBeCloseTo(viewport.width, 0)
      expect(clamped.y + clamped.height).toBeCloseTo(viewport.height, 0)
      await page.mouse.up()
      await page.setViewportSize({ width: 320, height: 700 })
      await expect
        .poll(async () => {
          const rect = (await pill.boundingBox())!
          return (
            rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= 320 && rect.y + rect.height <= 700
          )
        })
        .toBe(true)
    }
  })
}
