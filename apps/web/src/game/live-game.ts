import { applyBoardChanged, applyPreview, validate, validateBoard } from '@world-jigsaw/contracts'
import type {
  Action,
  BoardChanged,
  BoardEventHistory,
  Cancel,
  CancelResult,
  CommandResult,
  Confirm,
  CurrentGame,
  Preview,
  PreviewCleared,
  PreviewRejected,
  PreviewState,
  Subscribed,
  Turn,
  Undo,
  UndoResult,
} from '@world-jigsaw/contracts'

/** Provisional adapter for the private same-origin v1 service. It does not render a game. */
export interface LiveGameState {
  connection: 'disconnected' | 'connecting' | 'ready' | 'resyncing'
  game: CurrentGame | null
  previews: Record<string, PreviewState>
  previewPending: boolean
  pendingConfirm: Confirm | null
  unresolvedCommand: { puzzleId: string; commandId: string } | null
  issue: string | null
}

type Socket = Pick<WebSocket, 'send' | 'close' | 'onopen' | 'onmessage' | 'onclose' | 'onerror'>
export interface LiveGameDependencies {
  fetch: typeof fetch
  socket: (url: string) => Socket
  origin: string
  id: () => string
}

const defaults = (): LiveGameDependencies => ({
  fetch: window.fetch.bind(window),
  socket: (url) => new WebSocket(url),
  origin: window.location.origin,
  id: () => crypto.randomUUID(),
})

const sameId = (a: string, b: string) => a.toLowerCase() === b.toLowerCase()
class GameRequestError extends Error {
  readonly definitive: boolean
  constructor(message: string, definitive: boolean) {
    super(message)
    this.definitive = definitive
  }
}
// The schema name determines the corresponding return type at the call site.
// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
function checked<T>(name: string, value: unknown): T {
  if (!validate(name, value).valid) throw new Error(`Invalid ${name} from game service`)
  return value as T
}

// The definition contains only JSON values. Sorted keys and JSON's number/string encoding
// implement the canonical form used by the v1 checksum for these contract values.
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value !== null && typeof value === 'object') {
    const object = value as Record<string, unknown>
    return `{${Object.keys(object)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonical(object[key])}`)
      .join(',')}}`
  }
  return JSON.stringify(value)
}

async function verifyGame(game: CurrentGame) {
  if (!validateBoard(game.definition, game.snapshot).valid) throw new Error('Invalid board')
  const { checksum, ...definition } = game.definition
  const bytes = new TextEncoder().encode(canonical(definition))
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  const actual = `sha256:${Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')}`
  if (checksum !== actual) throw new Error('Definition checksum mismatch')
}

export class LiveGame {
  private state: LiveGameState = {
    connection: 'disconnected',
    game: null,
    previews: {},
    previewPending: false,
    pendingConfirm: null,
    unresolvedCommand: null,
    issue: null,
  }
  private listeners = new Set<(state: LiveGameState) => void>()
  private socket: Socket | null = null
  private epoch = 0
  private history: BoardEventHistory = { byVersion: new Map(), eventIds: new Set() }
  private sequence = 0
  private uncertainDraft = false
  private inFlight: { sequence: number; actionId: string } | null = null
  private beginRequest: { schemaVersion: 1; puzzleId: string; requestId: string } | null = null
  private cancelRequest: Cancel | null = null
  private undoRequest: Undo | null = null
  private confirmBusy = false
  private readonly deps: LiveGameDependencies

  constructor(deps: LiveGameDependencies = defaults()) {
    this.deps = deps
  }

  getState(): LiveGameState {
    return structuredClone(this.state)
  }

  subscribe(listener: (state: LiveGameState) => void): () => void {
    this.listeners.add(listener)
    listener(this.getState())
    return () => this.listeners.delete(listener)
  }

  private publish(patch: Partial<LiveGameState>) {
    this.state = { ...this.state, ...patch }
    for (const listener of this.listeners) listener(this.getState())
  }

  private async request<T>(path: string, name: string, body?: unknown): Promise<T> {
    const response = await this.deps.fetch(path, {
      method: body === undefined ? 'GET' : 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      ...(body === undefined
        ? {}
        : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
    })
    const value: unknown = await response.json()
    if (!response.ok) {
      const error = checked<{ error: { code: string; message: string; retryable: boolean } }>(
        'ErrorResponse',
        value,
      )
      throw new GameRequestError(
        `${error.error.code}: ${error.error.message}`,
        response.status < 500 && !error.error.retryable,
      )
    }
    return checked<T>(name, value)
  }

  /** Reconnect always obtains a fresh current game and a fresh atomic subscription. */
  async connect(): Promise<void> {
    const epoch = ++this.epoch
    this.stopSocket()
    if (this.inFlight) this.uncertainDraft = true
    this.inFlight = null
    this.publish({ connection: 'connecting', previews: {}, previewPending: false })
    if (epoch !== this.epoch) return
    try {
      await this.request('/api/v1/session', 'Session', {})
      if (epoch !== this.epoch) return
      const game = await this.request<CurrentGame>('/api/v1/game/current', 'CurrentGame')
      await verifyGame(game)
      if (epoch !== this.epoch) return
      this.uncertainDraft = Boolean(game.ownTurn)
      if (this.beginRequest && (game.ownTurn || !sameId(this.beginRequest.puzzleId, game.snapshot.puzzleId)))
        this.beginRequest = null
      if (this.cancelRequest && !sameId(this.cancelRequest.puzzleId, game.snapshot.puzzleId))
        this.cancelRequest = null
      if (this.undoRequest && !sameId(this.undoRequest.puzzleId, game.snapshot.puzzleId))
        this.undoRequest = null
      this.history = { byVersion: new Map(), eventIds: new Set() }
      this.publish({ game, connection: 'resyncing' })
      if (epoch !== this.epoch) return
      const url = new URL('/api/v1/game/stream', this.deps.origin)
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
      const socket = this.deps.socket(url.toString())
      this.socket = socket
      socket.onopen = () => {
        if (epoch === this.epoch)
          socket.send(
            JSON.stringify({
              schemaVersion: 1,
              type: 'subscribe',
              puzzleId: game.snapshot.puzzleId,
              afterBoardVersion: null,
            }),
          )
      }
      socket.onmessage = (event) => {
        if (epoch === this.epoch) this.receive(event.data)
      }
      socket.onclose = () => {
        if (epoch !== this.epoch) return
        ++this.epoch
        if (this.inFlight) this.uncertainDraft = true
        this.inFlight = null
        this.stopSocket()
        this.publish({
          connection: 'disconnected',
          previews: {},
          previewPending: false,
          issue: 'Connection lost; reconnect to resynchronize.',
        })
      }
      socket.onerror = () => {
        if (epoch === this.epoch)
          this.publish({ issue: 'Game stream error; reconnect if it closes.' })
      }
    } catch (error) {
      if (epoch === this.epoch) this.publish({ connection: 'disconnected', issue: String(error) })
      throw error
    }
  }

  disconnect() {
    ++this.epoch
    if (this.inFlight) this.uncertainDraft = true
    this.inFlight = null
    this.stopSocket()
    this.publish({ connection: 'disconnected', previews: {}, previewPending: false })
  }

  private stopSocket() {
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onclose = null
      socket.close()
    }
  }

  private resync(reason: string) {
    const epoch = this.epoch
    this.publish({ issue: reason, connection: 'resyncing', previews: {} })
    if (epoch !== this.epoch) return
    void this.connect().catch(() => {
      // connect() has already published the error.
    })
  }

  private receive(raw: unknown) {
    const epoch = this.epoch
    try {
      if (typeof raw !== 'string') throw new Error('Invalid stream frame')
      const message: unknown = JSON.parse(raw)
      if (!message || typeof message !== 'object' || !('type' in message))
        throw new Error('Invalid stream frame')
      const type = message.type
      const game = this.state.game
      if (!game) throw new Error('Missing current game')
      if (type === 'subscribed') {
        const reply = checked<Subscribed>('Subscribed', message)
        if (!validateBoard(game.definition, reply.snapshot).valid)
          throw new Error('Invalid subscribed board')
        if (reply.watermark !== reply.snapshot.boardVersion)
          throw new Error('Subscription watermark mismatch')
        if (reply.watermark < game.snapshot.boardVersion)
          throw new Error('Subscription regressed the board')
        if (reply.watermark > game.snapshot.boardVersion) {
          this.resync('Subscription advanced; refreshing allowance and turn.')
          return
        }
        const previews: Record<string, PreviewState> = {}
        for (const preview of reply.previews) {
          const key = preview.turnId.toLowerCase()
          const next = applyPreview(previews[key] ?? null, preview)
          if (next) previews[key] = next
        }
        this.history = { byVersion: new Map(), eventIds: new Set() }
        this.publish({
          game: { ...game, snapshot: reply.snapshot },
          previews,
          connection: 'resyncing',
          issue: 'Service must provide identity state atomically with the subscription.',
        })
      } else if (type === 'preview' || type === 'previewCleared') {
        const event = checked<Preview | PreviewCleared>(
          type === 'preview' ? 'Preview' : 'PreviewCleared',
          message,
        )
        if (!sameId(event.puzzleId, game.snapshot.puzzleId))
          throw new Error('Preview for another puzzle')
        const key = event.turnId.toLowerCase()
        const previous = this.state.previews[key] ?? null
        const next = applyPreview(previous, event)
        if (
          next !== previous &&
          game.ownTurn &&
          sameId(event.turnId, game.ownTurn.turnId) &&
          event.generation >= game.ownTurn.generation
        ) {
          this.uncertainDraft = true
          this.resync('Turn changed without a correlated receipt; resynchronizing before cancellation.')
          return
        }
        if (next) this.publish({ previews: { ...this.state.previews, [key]: next } })
      } else if (type === 'preview.rejected') {
        const rejection = checked<PreviewRejected>('PreviewRejected', message)
        if (
          this.inFlight?.sequence === rejection.sequence &&
          rejection.generation === game.ownTurn?.generation &&
          sameId(rejection.turnId, game.ownTurn.turnId) &&
          sameId(this.inFlight.actionId, rejection.actionId)
        ) {
          this.inFlight = null
          this.publish({ previewPending: false, issue: `${rejection.error.code}: ${rejection.error.message}` })
        } else this.publish({ issue: `${rejection.error.code}: ${rejection.error.message}` })
      } else if (type === 'boardChanged') {
        const result = applyBoardChanged(
          game.definition,
          game.snapshot,
          checked<BoardChanged>('BoardChanged', message),
          this.history,
        )
        if (result.status === 'resync') {
          this.resync('Board events require resynchronization.')
          return
        }
        if (result.status === 'applied') {
          this.history = result.seenEvents
          // An event does not carry this session's allowance or turn; refresh both.
          this.publish({ game: { ...game, snapshot: result.snapshot, allowance: null } })
          if (epoch === this.epoch) this.resync('Board changed; refreshing allowance and turn.')
        }
      } else if (type === 'leaseRenewed') {
        checked('LeaseRenewed', message)
      } else if (type === 'resyncRequired' || type === 'puzzleChanged') {
        checked(type === 'resyncRequired' ? 'ResyncRequired' : 'PuzzleChanged', message)
        this.resync('Game changed; resynchronizing.')
      } else if (type === 'error') {
        const error = checked<{ error: { code: string; message: string } }>('SocketError', message)
        this.publish({ issue: `${error.error.code}: ${error.error.message}` })
      } else throw new Error('Unsupported stream event')
    } catch (error) {
      if (epoch === this.epoch) this.resync(String(error))
    }
  }

  private requireEpoch(epoch: number) {
    if (epoch !== this.epoch) throw new Error('Connection changed while request was pending')
  }

  private readyGame(): CurrentGame {
    if (this.state.connection !== 'ready' || !this.state.game || !this.socket)
      throw new Error('Game is not synchronized')
    return this.state.game
  }

  private turn(): Turn {
    const turn = this.readyGame().ownTurn
    if (!turn) throw new Error('No active turn')
    return turn
  }

  async begin(): Promise<Turn> {
    const game = this.readyGame()
    if (this.state.pendingConfirm || this.state.unresolvedCommand)
      throw new Error('Resolve the pending confirmation first')
    if (game.ownTurn) return game.ownTurn
    this.beginRequest ??= {
      schemaVersion: 1,
      puzzleId: game.snapshot.puzzleId,
      requestId: this.deps.id(),
    }
    const epoch = this.epoch
    let turn: Turn
    try {
      turn = await this.request<Turn>('/api/v1/game/turns', 'Turn', this.beginRequest)
    } catch (error) {
      if (epoch === this.epoch && error instanceof GameRequestError && error.definitive)
        this.beginRequest = null
      throw error
    }
    this.requireEpoch(epoch)
    if (!sameId(turn.puzzleId, game.snapshot.puzzleId)) throw new Error('Turn puzzle mismatch')
    this.beginRequest = null
    this.sequence = 0
    this.uncertainDraft = false
    this.publish({ game: { ...this.readyGame(), ownTurn: turn }, issue: null })
    return turn
  }

  newAction(action: Omit<Action, 'actionId'>): Action {
    return { ...action, actionId: this.deps.id() }
  }

  /** Keep the action ID returned by newAction when adjusting the same move. */
  preview(action: Action) {
    const turn = this.turn()
    if (
      this.state.pendingConfirm ||
      this.state.unresolvedCommand ||
      this.uncertainDraft ||
      this.inFlight ||
      this.undoRequest ||
      this.cancelRequest
    )
      throw new Error('Draft is not ready for a preview; resolve or cancel it first')
    checked<Action>('Action', action)
    const sequence = this.sequence++
    if (!this.socket) throw new Error('Game stream is not connected')
    this.socket.send(
      JSON.stringify({
        schemaVersion: 1,
        type: 'preview.submit',
        puzzleId: turn.puzzleId,
        turnId: turn.turnId,
        generation: turn.generation,
        sequence,
        action,
      }),
    )
    this.inFlight = { sequence, actionId: action.actionId }
    this.publish({ previewPending: true, issue: null })
  }

  async undo(actionId: string): Promise<UndoResult> {
    if (
      this.state.pendingConfirm ||
      this.state.unresolvedCommand ||
      (this.uncertainDraft && !this.undoRequest) ||
      this.inFlight ||
      this.cancelRequest
    )
      throw new Error('Draft is not ready for undo')
    const turn = this.undoRequest ? null : this.turn()
    this.undoRequest ??= {
      schemaVersion: 1,
      puzzleId: turn!.puzzleId,
      turnId: turn!.turnId,
      requestId: this.deps.id(),
      turnRevision: turn!.turnRevision,
      generation: turn!.generation,
      actionId,
    }
    const payload = this.undoRequest
    if (!sameId(payload.actionId, actionId))
      throw new Error('Retry the pending undo first')
    const epoch = this.epoch
    let result: UndoResult
    try {
      result = await this.request<UndoResult>(
        `/api/v1/game/turns/${payload.turnId}/undo`,
        'UndoResult',
        payload,
      )
    } catch (error) {
      if (epoch === this.epoch) {
        if (error instanceof GameRequestError && error.definitive) this.undoRequest = null
        this.publish({ issue: String(error) })
      }
      throw error
    }
    this.requireEpoch(epoch)
    if (
      !sameId(result.requestId, payload.requestId) ||
      !sameId(result.turnId, payload.turnId) ||
      !sameId(result.puzzleId, payload.puzzleId) ||
      !sameId(result.actionId, actionId)
    )
      throw new Error('Undo response mismatch')
    this.undoRequest = null
    this.sequence = 0
    this.uncertainDraft = true
    this.resync('Turn changed; refreshing turn and previews.')
    return result
  }

  async cancel(): Promise<CancelResult> {
    if (this.state.pendingConfirm || this.state.unresolvedCommand || this.undoRequest)
      throw new Error('Resolve the pending operation first')
    if (!this.cancelRequest) {
      const turn = this.turn()
      this.cancelRequest = {
        schemaVersion: 1,
        puzzleId: turn.puzzleId,
        turnId: turn.turnId,
        requestId: this.deps.id(),
        turnRevision: turn.turnRevision,
        generation: turn.generation,
      }
    }
    const payload = this.cancelRequest
    const epoch = this.epoch
    let result: CancelResult
    try {
      result = await this.request<CancelResult>(
        `/api/v1/game/turns/${payload.turnId}/cancel`,
        'CancelResult',
        payload,
      )
    } catch (error) {
      if (epoch === this.epoch && error instanceof GameRequestError && error.definitive) {
        this.cancelRequest = null
        this.resync('Cancel rejected; refreshing turn before retry.')
      }
      throw error
    }
    this.requireEpoch(epoch)
    if (
      !sameId(result.requestId, payload.requestId) ||
      !sameId(result.turnId, payload.turnId) ||
      !sameId(result.puzzleId, payload.puzzleId)
    )
      throw new Error('Cancel response mismatch')
    this.cancelRequest = null
    if (result.outcome === 'canceled' || result.outcome === 'already_canceled') {
      this.inFlight = null
      this.uncertainDraft = false
      this.resync('Canceled; synchronizing the latest board.')
    } else {
      this.uncertainDraft = true
      this.publish({
        ...(result.commandId
          ? { unresolvedCommand: { puzzleId: result.puzzleId, commandId: result.commandId } }
          : {}),
        issue: `Cancel returned ${result.outcome}; resolve command ${result.commandId ?? '(missing)'}.`,
      })
    }
    return result
  }

  async confirm(): Promise<CommandResult> {
    if (this.confirmBusy) throw new Error('Confirmation already in progress')
    if (!this.state.pendingConfirm) {
      this.turn()
      if (
        this.state.unresolvedCommand ||
        this.uncertainDraft ||
        this.inFlight ||
        this.undoRequest ||
        this.cancelRequest
      )
        throw new Error('Draft is not ready to confirm')
      throw new Error('Confirmation requires a correlated preview receipt from the service')
    }
    const payload = this.state.pendingConfirm
    if (!payload) throw new Error('No confirmation payload')
    const epoch = this.epoch
    this.confirmBusy = true
    try {
      const result = await this.request<CommandResult>(
        `/api/v1/game/turns/${payload.turnId}/confirm`,
        'CommandResult',
        payload,
      )
      this.requireEpoch(epoch)
      return this.acceptResult(result, payload, epoch)
    } catch (error) {
      if (epoch === this.epoch)
        this.publish({ issue: `Confirmation outcome unknown: ${String(error)}` })
      throw error
    } finally {
      this.confirmBusy = false
    }
  }

  async resolveConfirm(): Promise<CommandResult> {
    const payload = this.state.pendingConfirm
    if (!payload) throw new Error('No pending confirmation')
    const epoch = this.epoch
    const result = await this.request<CommandResult>(
      `/api/v1/game/commands/${payload.commandId}?puzzleId=${encodeURIComponent(payload.puzzleId)}`,
      'CommandResult',
    )
    this.requireEpoch(epoch)
    return this.acceptResult(result, payload, epoch)
  }

  /** Look up a command reported by Cancel when another tab is confirming the turn. */
  async resolveCancelCommand(): Promise<CommandResult> {
    const command = this.state.unresolvedCommand
    if (!command) throw new Error('No command reported by cancel')
    const epoch = this.epoch
    const result = await this.request<CommandResult>(
      `/api/v1/game/commands/${command.commandId}?puzzleId=${encodeURIComponent(command.puzzleId)}`,
      'CommandResult',
    )
    this.requireEpoch(epoch)
    if (!sameId(result.commandId, command.commandId) || !sameId(result.puzzleId, command.puzzleId))
      throw new Error('Command response mismatch')
    if (result.outcome === 'confirmed' || result.outcome === 'rejected') {
      this.publish({ unresolvedCommand: null })
      if (epoch === this.epoch && this.state.connection !== 'disconnected') this.resync(
        result.outcome === 'rejected'
          ? `${result.error?.code ?? 'REJECTED'}: ${result.error?.message ?? 'Confirmation rejected'}`
          : 'Confirmation confirmed; synchronizing the latest board.',
      )
    } else this.publish({ issue: `Confirmation ${result.outcome}; resolve it before editing.` })
    return result
  }

  /** Restore an unresolved payload retained by the integrating application across a reload. */
  restorePendingConfirm(payload: Confirm) {
    checked<Confirm>('Confirm', payload)
    if (this.state.pendingConfirm) throw new Error('A confirmation is already pending')
    this.publish({
      pendingConfirm: structuredClone(payload),
      issue: 'Resolve the restored confirmation before editing.',
    })
  }

  private acceptResult(result: CommandResult, payload: Confirm, epoch: number): CommandResult {
    if (!sameId(result.commandId, payload.commandId) || !sameId(result.puzzleId, payload.puzzleId))
      throw new Error('Confirmation response mismatch')
    if (result.outcome === 'confirmed' || result.outcome === 'rejected') {
      this.publish({
        pendingConfirm: null,
        issue:
          result.outcome === 'rejected'
            ? `${result.error?.code ?? 'REJECTED'}: ${result.error?.message ?? 'Confirmation rejected'}`
            : null,
      })
      if (epoch === this.epoch && this.state.connection !== 'disconnected') this.resync(
        result.outcome === 'rejected'
          ? `${result.error?.code ?? 'REJECTED'}: ${result.error?.message ?? 'Confirmation rejected'}`
          : 'Confirmation confirmed; synchronizing the latest board.',
      )
    } else
      this.publish({
        issue: `Confirmation ${result.outcome}; retain the command ID and resolve it before editing.`,
      })
    return result
  }
}
