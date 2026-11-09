import { describe, expect, it } from 'vitest'
import type { Action, Group } from '@world-jigsaw/contracts'
import { canMoveGroup } from './move-availability.ts'

describe('move availability', () => {
  const group = { pieceIds: ['a', 'b'] } as Group
  const first = { actionId: 'first', affectedPieceIds: ['a', 'b'] } as Action
  const second = { actionId: 'second', affectedPieceIds: ['a', 'b'] } as Action
  const other = { actionId: 'other', affectedPieceIds: ['c'] } as Action

  it('blocks interaction at zero committed moves and while availability is unknown', () => {
    expect(canMoveGroup(0, 0, group, [], [])).toBe(false)
    expect(canMoveGroup(null, 0, group, [], [])).toBe(false)
    expect(canMoveGroup(1, null, group, [], null)).toBe(false)
    expect(canMoveGroup(1, 0, group, [], null)).toBe(false)
  })

  it('blocks all actions when the batch uses the remaining moves', () => {
    expect(canMoveGroup(1, 1, group, [], [])).toBe(false)
    expect(canMoveGroup(1, 2, group, [], [])).toBe(false)
    expect(canMoveGroup(1, 0, group, [], [])).toBe(true)
  })

  it('reserves enough moves for all actions in a joined group', () => {
    expect(canMoveGroup(1, 0, group, [first, second], [])).toBe(false)
    expect(canMoveGroup(2, 0, group, [first, second], [])).toBe(true)
    expect(canMoveGroup(2, 1, group, [first, second, other], ['other'])).toBe(false)
    expect(canMoveGroup(1, 0, group, [other], [])).toBe(true)
  })

  it('does not reserve a second charge for paid joined actions', () => {
    expect(canMoveGroup(4, 3, group, [first, second, other], ['FIRST', 'second', 'other'])).toBe(
      true,
    )
    expect(canMoveGroup(3, 3, group, [first, second, other], ['first', 'second', 'other'])).toBe(
      false,
    )
    expect(canMoveGroup(2, 1, group, [first, second], ['first'])).toBe(true)
  })
})
