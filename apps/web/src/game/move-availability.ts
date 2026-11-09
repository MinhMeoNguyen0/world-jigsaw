import type { Action, Group } from '@world-jigsaw/contracts'

/** Existing draft actions can be adjusted without spending another move. */
export function canMoveGroup(
  committedRemaining: number | null,
  pendingMoves: number | null,
  group: Group,
  actions: Action[],
): boolean {
  if (committedRemaining === null || pendingMoves === null || committedRemaining <= 0) return false
  return (
    committedRemaining > pendingMoves ||
    actions.some((action) =>
      action.affectedPieceIds.some((id) =>
        group.pieceIds.some((piece) => piece.toLowerCase() === id.toLowerCase()),
      ),
    )
  )
}
