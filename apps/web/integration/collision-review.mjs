import { chromium } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
const fixture = JSON.parse(
  await readFile(
    new URL('../../../packages/generator/fixtures/hoi-an-surround-60.json', import.meta.url),
    'utf8',
  ),
)
import assert from 'node:assert/strict'
const base = 'http://127.0.0.1:5173/',
  sid = '0a0e463c-c7d9-5bd8-88c4-c63e1e638336',
  tid = '1022d802-6f82-55df-bafb-1e9b744fd95b'
const canonical = (value) =>
  Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === 'object'
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonical(value[key])]),
        )
      : value
const browser = await chromium.launch({ headless: true }),
  oc = await browser.newContext({ viewport: { width: 1280, height: 720 } }),
  observer = await oc.newPage()
async function ready(p) {
  await p.goto(base)
  await p.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
}
async function wait(cond, desc) {
  const end = Date.now() + 25000
  while (Date.now() < end) {
    const v = cond()
    if (v) return v
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(`timeout ${desc}`)
}
try {
  await ready(observer)
  let baseline = null
  const outcomes = []
  for (const [mode, fraction] of [
    ['owner', 0.05],
    ['owner', 0.18],
    ['owner', 1],
    ['reviewer', 0.05],
    ['reviewer', 0.18],
  ]) {
    await observer.waitForFunction(
      () => document.querySelectorAll('[data-preview-action]').length === 0,
      null,
      { timeout: 20000 },
    )
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 } }),
      p = await ctx.newPage(),
      sent = [],
      received = []
    let socketId = 0
    p.on('websocket', (ws) => {
      const connection = ++socketId
      ws.on('framesent', (e) => {
        try {
          const m = JSON.parse(e.payload)
          if (m.type === 'preview.submit') sent.push({ ...m, connection })
        } catch {}
      })
      ws.on('framereceived', (e) => {
        try {
          const m = JSON.parse(e.payload)
          if (m.type === 'preview.accepted' || m.type === 'preview.rejected') received.push({ ...m, connection })
        } catch {}
      })
    })
    try {
      await ready(p)
      const current = await p.evaluate(
        async () => await (await fetch('/api/v1/game/current')).json(),
      )
      const snap = current.snapshot
      assert.equal(
        snap.puzzleId,
        fixture.snapshot.puzzleId,
        'Import the disposable Hoi An surround fixture',
      )
      const hash = createHash('sha256')
        .update(JSON.stringify(canonical(snap)))
        .digest('hex')
      const source = snap.groups.find((g) => g.groupId === sid),
        target = snap.groups.find((g) => g.groupId === tid)
      assert.ok(source && target)
      const signature = JSON.stringify({
        hash,
        version: snap.boardVersion,
        source: source.transform,
        target: target.transform,
      })
      if (baseline === null) baseline = signature
      else assert.equal(signature, baseline, 'canonical board changed')
      const data = await p.evaluate(
        ({ sid, tid, fraction, source }) => {
          const s = document
              .querySelector(`[data-group-id="${sid}"] > path`)
              .getBoundingClientRect(),
            t = document.querySelector(`[data-group-id="${tid}"] > path`).getBoundingClientRect(),
            x = s.x + s.width / 2,
            y = s.y + s.height / 2,
            tx =
              fraction === 1
                ? t.x + t.width / 2
                : t.x + t.width + s.width / 2 - Math.min(s.width, t.width) * fraction,
            ty = t.y + t.height / 2,
            inv = document.querySelector('svg.board').getScreenCTM().inverse(),
            start = new DOMPoint(x, y).matrixTransform(inv),
            end = new DOMPoint(tx, ty).matrixTransform(inv)
          return {
            sourceBox: { x: s.x, y: s.y, w: s.width, h: s.height },
            targetBox: { x: t.x, y: t.y, w: t.width, h: t.height },
            x,
            y,
            tx,
            ty,
            expected: {
              rotation: source.transform.rotation,
              translation: {
                x: Math.round(source.transform.translation.x + end.x - start.x),
                y: Math.round(source.transform.translation.y + end.y - start.y),
              },
            },
          }
        },
        { sid, tid, fraction, source },
      )
      console.log(
        'before',
        JSON.stringify({
          mode,
          fraction,
          boardVersion: snap.boardVersion,
          hash,
          source: source.transform,
          target: target.transform,
          observerPreviews: await observer.locator('[data-preview-action]').count(),
          geometry: data,
        }),
      )
      await p.mouse.move(data.x, data.y)
      await p.mouse.down()
      if (mode === 'owner' && fraction === 0.18) {
        await p.mouse.move(data.targetBox.x + data.targetBox.w / 2, data.targetBox.y + data.targetBox.h / 2)
        await wait(() => received.some((m) => m.type === 'preview.rejected'), 'intermediate rejection')
        await p.locator('[data-connection=ready]').waitFor({ timeout: 30000 })
      }
      await p.mouse.move(data.tx, data.ty, { steps: 20 })
      await p.waitForTimeout(300)
      await p.mouse.up()
      const final = await wait(
        () =>
          sent.find((m) => {
            let a = m.action.intendedTransform,
              e = data.expected
            return (
              a.rotation === e.rotation &&
              Math.abs(a.translation.x - e.translation.x) <= 1 &&
              Math.abs(a.translation.y - e.translation.y) <= 1
            )
          }),
        'final submitted transform',
      )
      const reply = await wait(
        () =>
          received.find(
            (m) => m.connection === final.connection && m.sequence === final.sequence && m.actionId === final.action.actionId,
          ),
        'receipt matching final sequence',
      )
      console.log(
        'final',
        JSON.stringify({
          mode,
          fraction,
          sequence: final.sequence,
          transform: final.action.intendedTransform,
          affected: final.action.affectedPieceIds,
          reply: reply.type,
          error: reply.error?.message ?? '',
          sent: sent.map((m) => ({ sequence: m.sequence, transform: m.action.intendedTransform })),
          received: received.map((m) => ({
            sequence: m.sequence,
            type: m.type,
            error: m.error?.message ?? '',
          })),
        }),
      )
      outcomes.push({
        mode,
        fraction,
        reply: reply.type,
        transform: final.action.intendedTransform,
      })
      assert.equal(
        reply.type,
        fraction === 1 ? 'preview.rejected' : 'preview.accepted',
        `final reply for ${mode} ${fraction}`,
      )
    } finally {
      await p
        .locator('[data-connection=ready]')
        .waitFor({ timeout: 30000 })
        .catch(() => {})
      const cancel = p.getByRole('button', { name: 'Cancel batch' })
      // A final rejection can begin resynchronization after the earlier ready check.
      // Wait for the draft to become cancelable instead of skipping cleanup mid-transition.
      const deadline = Date.now() + 30000
      while (Date.now() < deadline && !(await cancel.isEnabled()) &&
        (await observer.locator('[data-preview-action]').count()) > 0)
        await p.waitForTimeout(100)
      if (await cancel.isEnabled().catch(() => false)) {
        const response = p.waitForResponse(
          (r) => r.url().endsWith('/cancel') && r.request().method() === 'POST',
        )
        await cancel.click()
        let r = await response
        console.log('cancel', r.status())
        assert.equal(r.status(), 200)
      }
      await observer.waitForFunction(
        () => document.querySelectorAll('[data-preview-action]').length === 0,
        null,
        { timeout: 20000 },
      )
      console.log('cleanup peer previews', await observer.locator('[data-preview-action]').count())
      await ctx.close()
    }
  }
  const a = outcomes.find((x) => x.mode === 'owner' && x.fraction === 0.18),
    b = outcomes.find((x) => x.mode === 'reviewer' && x.fraction === 0.18)
  assert.deepEqual(a.transform, b.transform)
  assert.equal(a.reply, b.reply)
  console.log('same canonical board, same final action and correlated replies agree')
} catch (e) {
  console.error('FAILED', e)
  process.exitCode = 1
} finally {
  await browser.close()
}
