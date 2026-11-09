import { describe, expect, it } from 'vitest'
import type { Action, CurrentGame, PreviewState } from '@world-jigsaw/contracts'
import fixture from '../../../../packages/generator/fixtures/surround-60.json'
import balancedFixture from '../../../../packages/generator/fixtures/hoi-an-balanced-60.json'
import { suggestJoin } from './join'

const game = fixture as unknown as CurrentGame
const first = game.snapshot.groups[0]
const second = game.snapshot.groups[1]
if (!second) throw new Error('Expected two fixture groups')
const action: Action = {
  actionId: 'action-a',
  kind: 'piece',
  targetId: first.pieceIds[0],
  affectedPieceIds: first.pieceIds,
  baseRevisions: [{ groupId: first.groupId, revision: first.revision }],
  intendedTransform: first.transform,
}

describe('join proposal', () => {
  it('proposes a 5000-unit snap on the balanced fixture', () => {
    const balanced = balancedFixture as unknown as CurrentGame
    const moving = balanced.snapshot.groups[0]
    const neighbor = balanced.snapshot.groups[1]
    if (!neighbor) throw new Error('Expected two balanced fixture groups')
    const proposal: Action = {
      actionId: 'action-a',
      kind: 'piece',
      targetId: moving.pieceIds[0],
      affectedPieceIds: moving.pieceIds,
      baseRevisions: [{ groupId: moving.groupId, revision: moving.revision }],
      intendedTransform: moving.transform,
    }
    const near = {
      ...neighbor.transform,
      translation: {
        ...neighbor.transform.translation,
        x: neighbor.transform.translation.x + 5000,
      },
    }
    expect(suggestJoin(balanced, {}, proposal, near, [proposal]).affectedPieceIds).toEqual([
      ...moving.pieceIds,
      ...neighbor.pieceIds,
    ])
  })
  it.each([0, 90000, 180000, 270000])(
    'uses the sampled contour boundary at rotation %i',
    (rotation) => {
      const balanced = balancedFixture as unknown as CurrentGame
      const moving = balanced.snapshot.groups[1]
      const neighbor = balanced.snapshot.groups[2]
      if (!moving || !neighbor) throw new Error('Expected three balanced fixture groups')
      const oriented: CurrentGame = {
        ...balanced,
        snapshot: {
          ...balanced.snapshot,
          groups: [
            { ...moving, transform: { ...moving.transform, rotation } },
            { ...neighbor, transform: { ...neighbor.transform, rotation } },
          ],
        },
      }
      const proposal: Action = {
        actionId: 'a',
        kind: 'piece',
        targetId: moving.pieceIds[0],
        affectedPieceIds: moving.pieceIds,
        baseRevisions: [{ groupId: moving.groupId, revision: moving.revision }],
        intendedTransform: { ...moving.transform, rotation },
      }
      const transform = (offset: number) => ({ rotation, translation: { x: offset, y: 0 } })
      expect(
        suggestJoin(oriented, {}, proposal, transform(6600), [proposal]).affectedPieceIds,
      ).toContain(neighbor.pieceIds[0])
      expect(suggestJoin(oriented, {}, proposal, transform(6650), [proposal])).toBe(proposal)
    },
  )
  it('requests the whole adjacent group only within the conservative snap range', () => {
    const joined = suggestJoin(game, {}, action, second.transform, [action])
    expect(joined.affectedPieceIds).toEqual([...first.pieceIds, ...second.pieceIds])
    expect(joined.baseRevisions).toEqual([
      { groupId: first.groupId, revision: first.revision },
      { groupId: second.groupId, revision: second.revision },
    ])
    const far = {
      ...second.transform,
      translation: { ...second.transform.translation, x: second.transform.translation.x + 100000 },
    }
    expect(suggestJoin(game, {}, action, far, [action])).toBe(action)
  })
  it('does not join a peer preview or a different orientation', () => {
    const peer = {
      peer: {
        closed: false,
        actions: { peer: { ...action, actionId: 'peer', affectedPieceIds: second.pieceIds } },
      },
    } as unknown as Record<string, PreviewState>
    expect(suggestJoin(game, peer, action, second.transform, [action])).toBe(action)
    expect(suggestJoin(game, {}, action, { ...second.transform, rotation: 90000 }, [action])).toBe(
      action,
    )
  })
  it('joins another acknowledged draft using its original source revisions', () => {
    const other: Action = {
      ...action,
      actionId: 'action-b',
      targetId: second.pieceIds[0],
      affectedPieceIds: second.pieceIds,
      baseRevisions: [{ groupId: second.groupId, revision: second.revision }],
      intendedTransform: second.transform,
    }
    const joined = suggestJoin(game, {}, action, second.transform, [action, other])
    expect(joined.affectedPieceIds).toEqual([...first.pieceIds, ...second.pieceIds])
    expect(joined.baseRevisions).toEqual([...action.baseRevisions, ...other.baseRevisions])
  })
})
