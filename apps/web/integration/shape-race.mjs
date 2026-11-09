import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
const fixture = JSON.parse(
  await readFile(
    new URL('../../../packages/generator/fixtures/hoi-an-balanced-60.json', import.meta.url),
    'utf8',
  ),
)
const base = 'http://127.0.0.1:5173',
  origin = base
async function session() {
  let r = await fetch(base + '/api/v1/session', {
    method: 'POST',
    headers: { origin, 'content-type': 'application/json' },
    body: '{}',
  })
  assert.equal(r.status, 200)
  return r.headers.get('set-cookie').split(';')[0]
}
async function get(cookie) {
  let r = await fetch(base + '/api/v1/game/current', { headers: { cookie } })
  assert.equal(r.status, 200)
  return r.json()
}
async function post(cookie, path, payload) {
  let r = await fetch(base + path, {
    method: 'POST',
    headers: { origin, cookie, 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return { status: r.status, body: await r.json() }
}
const a = await session(),
  b = await session()
assert.equal(
  (await get(a)).snapshot.puzzleId,
  fixture.snapshot.puzzleId,
  'Import the disposable Hoi An fixture',
)
let won = 0,
  blocked = 0
for (let i = 0; i < 10; i++) {
  const before = await get(a),
    id = before.snapshot.puzzleId,
    shape = before.definition.shape
  const req = {
    schemaVersion: 1,
    puzzleId: id,
    requestId: randomUUID(),
    definitionVersion: before.definition.definitionVersion,
    tabDepthPermille: shape.tabDepthPermille === 130 ? 140 : 130,
    tabWidthPermille: shape.tabWidthPermille,
    edgeWavePermille: shape.edgeWavePermille,
  }
  const begin = { schemaVersion: 1, puzzleId: id, requestId: randomUUID() }
  const [s, t] = await Promise.all([
    post(a, '/api/v1/game/shape', req),
    post(b, '/api/v1/game/turns', begin),
  ])
  assert.equal(t.status, 200, JSON.stringify(t))
  if (s.status === 200) {
    won++
    assert.equal(s.body.outcome, 'updated')
  } else {
    blocked++
    assert.equal(s.status, 409)
    assert.equal(s.body.error.code, 'PIECE_BUSY')
  }
  const now = await get(b)
  assert.equal(now.ownTurn.turnId, t.body.turnId)
  assert.equal(now.snapshot.boardVersion, t.body.boardVersion)
  assert.equal(
    now.definition.definitionVersion,
    before.definition.definitionVersion + (s.status === 200 ? 1 : 0),
  )
  const cancel = {
    schemaVersion: 1,
    puzzleId: id,
    turnId: t.body.turnId,
    requestId: randomUUID(),
    turnRevision: t.body.turnRevision,
    generation: t.body.generation,
  }
  const c = await post(b, `/api/v1/game/turns/${t.body.turnId}/cancel`, cancel)
  assert.equal(c.status, 200, JSON.stringify(c))
}
console.log(
  `10 concurrent begin/shape races preserved admitted turns: shape won ${won}, draft blocked shape ${blocked}`,
)
