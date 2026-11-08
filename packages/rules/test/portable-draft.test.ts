import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { attach, begin, confirm, move, pendingCount, undo } from '../src/index.ts'

const read = (path: string) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
const vectors = read('../vectors/draft-v1.json')
for (const scenario of vectors.scenarios) {
  test(`portable draft: ${scenario.name}`, () => {
    const { definition, snapshot } = read(`../../generator/fixtures/${scenario.fixture}`)
    const allowance = { limit: 5 as const, used: 0, remaining: 5 }
    let draft = begin(definition, snapshot, allowance)
    const groupId = (value: string) =>
      /^\d+$/.test(value) ? snapshot.groups[Number(value)].groupId : value
    for (const op of scenario.operations) {
      if (op.kind === 'move')
        draft = move(draft, groupId(op.group), op.action, {
          translation: { x: op.x, y: op.y },
          rotation: op.rotation,
        })
      else if (op.kind === 'attach')
        draft = attach(draft, groupId(op.group), groupId(op.target), op.merged, 0, 0)
      else if (op.kind === 'undo') draft = undo(draft, op.action)
      else throw new Error('unknown operation')
      assert.equal(pendingCount(draft), op.pending)
    }
    const result = confirm(draft, snapshot, allowance)
    assert.equal(result.charged, scenario.charged)
    assert.equal(result.snapshot.groups.length, snapshot.groups.length + scenario.groupDelta)
    assert.equal(draft.generation, scenario.generation)
  })
}
