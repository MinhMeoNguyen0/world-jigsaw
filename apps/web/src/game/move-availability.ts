import type { Action, Group } from '@world-jigsaw/contracts'

export function canMoveGroup(
  committedRemaining: number | null,
  pendingMoves: number | null,
  group: Group,
  actions: readonly Action[],
  chargedActionIds: readonly string[] | null,
): boolean {
  if (committedRemaining === null || pendingMoves === null || chargedActionIds === null) return false
  const pieces = new Set(group.pieceIds.map((id) => id.toLowerCase()))
  const charged = new Set(chargedActionIds.map((id) => id.toLowerCase()))
  const potentialCharges = actions.filter((action) =>
    !charged.has(action.actionId.toLowerCase()) &&
      action.affectedPieceIds.some((id) => pieces.has(id.toLowerCase())),
  ).length
  return committedRemaining - pendingMoves >= Math.max(1, potentialCharges)
}
