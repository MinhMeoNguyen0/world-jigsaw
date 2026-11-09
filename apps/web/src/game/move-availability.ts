import type { Action, Group } from '@world-jigsaw/contracts'

export function canMoveGroup(
  committedRemaining: number | null,
  pendingMoves: number | null,
  group: Group,
  actions: readonly Action[],
  movementCosts: readonly { actionId: string; additionalMoves: number }[] | null,
): boolean {
  if (committedRemaining === null || pendingMoves === null || movementCosts === null) return false
  const pieces = new Set(group.pieceIds.map((id) => id.toLowerCase()))
  const matching = actions.filter((action) =>
    action.affectedPieceIds.some((id) => pieces.has(id.toLowerCase())),
  )
  const costs = new Map(
    movementCosts.map((cost) => [cost.actionId.toLowerCase(), cost.additionalMoves]),
  )
  const potentialCharges = Math.max(
    0,
    ...matching.map((action) => costs.get(action.actionId.toLowerCase()) ?? Infinity),
  )
  return committedRemaining - pendingMoves >= Math.max(1, potentialCharges)
}
