export function canMoveGroup(committedRemaining: number | null, pendingMoves: number | null): boolean {
  return committedRemaining !== null && pendingMoves !== null && committedRemaining > pendingMoves
}
