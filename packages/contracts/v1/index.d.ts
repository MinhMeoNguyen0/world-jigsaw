export * from './types.js'
export interface ValidationResult {
  valid: boolean
  errors: unknown[]
}
export function validate(type: string, value: unknown): ValidationResult
export function validateBoard(definition: unknown, snapshot: unknown): ValidationResult
export interface BoardEventHistory {
  byVersion: Map<number, string>
  eventIds: Set<string>
}
export function applyBoardChanged(
  definition: unknown,
  snapshot: import('./types.js').Snapshot,
  event: unknown,
  seenEvents?: BoardEventHistory,
):
  | { status: 'resync' | 'duplicate' }
  | {
      status: 'applied'
      snapshot: import('./types.js').Snapshot
      seenEvents: BoardEventHistory
    }
export interface PreviewState {
  puzzleId: string
  turnId: string
  generation: number
  sequence: number
  closed: boolean
  actions: Record<string, import('./types.js').Action>
}
export function applyPreview(state: PreviewState | null, event: unknown): PreviewState | null
export function isInsideSurface(
  point: import('./types.js').Point,
  surface: import('./types.js').Rect,
): boolean
export function progressPieceCount(snapshot: import('./types.js').Snapshot): number
