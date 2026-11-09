import { describe, expect, it } from 'vitest'
import type { Action, Group } from '@world-jigsaw/contracts'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import { canMoveGroup } from './move-availability.ts'

describe('move availability', () => {
  const group = fixture.snapshot.groups[0] as Group
  const action = { affectedPieceIds: group.pieceIds } as Action

  it('blocks interaction at zero committed moves and while availability is unknown', () => {
    expect(canMoveGroup(0, 0, group, [])).toBe(false)
    expect(canMoveGroup(0, 0, group, [action])).toBe(false)
    expect(canMoveGroup(null, 0, group, [])).toBe(false)
    expect(canMoveGroup(1, null, group, [])).toBe(false)
  })

  it('allows adjustments to an existing action when the batch uses all remaining moves', () => {
    expect(canMoveGroup(1, 1, group, [])).toBe(false)
    expect(canMoveGroup(1, 1, group, [action])).toBe(true)
    expect(canMoveGroup(1, 0, group, [])).toBe(true)
  })
})
