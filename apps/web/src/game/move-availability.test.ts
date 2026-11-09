import { describe, expect, it } from 'vitest'
import type { Action, Group } from '@world-jigsaw/contracts'
import { canMoveGroup } from './move-availability.ts'

describe('move availability', () => {
  const group = { pieceIds: ['a', 'b'] } as Group
  const action = { affectedPieceIds: ['a', 'b'] } as Action

  it('blocks interaction at zero committed moves and while availability is unknown', () => {
    expect(canMoveGroup(0, 0, group, [])).toBe(false)
    expect(canMoveGroup(null, 0, group, [])).toBe(false)
    expect(canMoveGroup(1, null, group, [])).toBe(false)
  })

  it('blocks all actions when the batch uses the remaining moves', () => {
    expect(canMoveGroup(1, 1, group, [])).toBe(false)
    expect(canMoveGroup(1, 2, group, [])).toBe(false)
    expect(canMoveGroup(1, 0, group, [])).toBe(true)
  })

  it('reserves enough moves for all actions in a joined group', () => {
    expect(canMoveGroup(1, 0, group, [action, action])).toBe(false)
    expect(canMoveGroup(2, 0, group, [action, action])).toBe(true)
    expect(canMoveGroup(2, 1, group, [action, action])).toBe(false)
    expect(canMoveGroup(1, 0, group, [{ affectedPieceIds: ['c'] } as Action])).toBe(true)
  })
})
