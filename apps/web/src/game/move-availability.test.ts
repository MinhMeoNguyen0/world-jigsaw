import { describe, expect, it } from 'vitest'
import type { Action, Group } from '@world-jigsaw/contracts'
import { canMoveGroup } from './move-availability.ts'

describe('move availability', () => {
  const transform = { translation: { x: 0, y: 0 }, rotation: 0 }
  const group: Group = {
    groupId: 'group',
    revision: 0,
    pieceIds: ['a', 'b'],
    transform,
    finalPosition: false,
  }
  const action = (actionId: string, affectedPieceIds: [string, ...string[]]): Action => ({
    actionId,
    kind: 'group',
    targetId: 'group',
    affectedPieceIds,
    baseRevisions: [{ groupId: 'group', revision: 0 }],
    intendedTransform: transform,
  })
  const first = action('first', ['a', 'b'])
  const second = action('second', ['a', 'b'])
  const other = action('other', ['c'])
  const costs = (additionalMoves: number) => [
    { actionId: 'first', additionalMoves },
    { actionId: 'second', additionalMoves },
  ]

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
    expect(canMoveGroup(1, 0, group, [first, second], costs(2))).toBe(false)
    expect(canMoveGroup(2, 0, group, [first, second], costs(2))).toBe(true)
    expect(canMoveGroup(2, 1, group, [first, second, other], costs(2))).toBe(false)
    expect(canMoveGroup(1, 0, group, [other], [])).toBe(true)
  })

  it('does not reserve a second charge for paid joined actions', () => {
    expect(canMoveGroup(4, 3, group, [first, second, other], costs(0))).toBe(true)
    expect(canMoveGroup(3, 3, group, [first, second, other], costs(0))).toBe(false)
    expect(canMoveGroup(2, 1, group, [first, second], costs(1))).toBe(true)
    expect(canMoveGroup(2, 1, group, [first, second], [])).toBe(false)
  })
})
