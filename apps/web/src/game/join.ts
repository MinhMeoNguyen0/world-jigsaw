import type {
  Action,
  BaseRevision,
  CurrentGame,
  PreviewState,
  Transform,
} from '@world-jigsaw/contracts'
import { sampledScale } from './transit'

interface Candidate {
  ids: string[]
  revisions: BaseRevision[]
  transform: Transform
}

/** Suggest one adjacent draft component; the server decides whether the join is valid. */
export function suggestJoin(
  game: CurrentGame,
  previews: Record<string, PreviewState>,
  action: Action,
  transform: Transform,
  ownActions: readonly Action[],
): Action {
  const pieces = new Map(game.definition.pieces.map((piece) => [piece.pieceId, piece]))
  const moving = new Set(action.affectedPieceIds)
  const ownIds = new Set(ownActions.map((item) => item.actionId))
  const occupied = new Set(
    Object.values(previews).flatMap((preview) =>
      preview.closed
        ? []
        : Object.values(preview.actions).flatMap((item) =>
            ownIds.has(item.actionId) || item.actionId === action.actionId
              ? []
              : item.affectedPieceIds,
          ),
    ),
  )
  const drafted = new Set(ownActions.flatMap((item) => item.affectedPieceIds))
  const candidates: Candidate[] = [
    ...ownActions
      .filter((item) => item.actionId !== action.actionId)
      .map((item) => ({
        ids: item.affectedPieceIds,
        revisions: item.baseRevisions,
        transform: item.intendedTransform,
      })),
    ...game.snapshot.groups
      .filter((group) => group.pieceIds.every((id) => !drafted.has(id) && !occupied.has(id)))
      .map((group) => ({
        ids: group.pieceIds,
        revisions: [{ groupId: group.groupId, revision: group.revision }],
        transform: group.transform,
      })),
  ]
  for (const id of action.affectedPieceIds) {
    const piece = pieces.get(id)
    if (!piece) continue
    for (const edge of piece.edges) {
      const neighbor = edge.neighborPieceId
      if (!neighbor || moving.has(neighbor) || occupied.has(neighbor)) continue
      const candidate = candidates.find((item) => item.ids.includes(neighbor))
      const other = pieces.get(neighbor)
      if (
        !candidate ||
        !other ||
        candidate.ids.some((member) => moving.has(member) || occupied.has(member)) ||
        candidate.transform.rotation !== transform.rotation
      )
        continue
      const tolerance = Math.floor(Math.min(sampledScale(piece), sampledScale(other)) / 20)
      if (
        Math.hypot(
          transform.translation.x - candidate.transform.translation.x,
          transform.translation.y - candidate.transform.translation.y,
        ) > tolerance
      )
        continue
      const revisions = new Map(
        [...action.baseRevisions, ...candidate.revisions].map((revision) => [
          revision.groupId,
          revision,
        ]),
      )
      return {
        ...action,
        affectedPieceIds: [...action.affectedPieceIds, ...candidate.ids],
        baseRevisions: [...revisions.values()] as Action['baseRevisions'],
      }
    }
  }
  return action
}
