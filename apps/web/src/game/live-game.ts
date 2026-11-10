import { applyBoardChanged, applyPreview, validate, validateBoard } from '@world-jigsaw/contracts'
import type {
  Action,
  BoardChanged,
  BoardEventHistory,
  Cancel,
  CancelResult,
  CommandResult,
  Confirm,
  ConfirmV2,
  CurrentGame,
  Preview,
  PreviewCleared,
  LeaseRenewed,
  PreviewRejected,
  PreviewAcceptedV2,
  SubscribedV2,
  PreviewState,
  PuzzleChanged,
  Subscribed,
  Turn,
  UndoV2,
  UndoResult,
  ShapeChange,
  ShapeResult,
  CursorPresence,
  Point,
  Snapshot,
} from '@world-jigsaw/contracts'

/** Provisional adapter for the private same-origin v1 service. It does not render a game. */
export interface LiveGameState {
  connection: 'disconnected' | 'connecting' | 'ready' | 'resyncing'
  game: CurrentGame | null
  turnBase: Snapshot | null
  previews: Record<string, PreviewState>
  /** Tentative appearance only; never used for commands or draft proof. */
  recoveryView: { turn: Turn; actions: Action[] } | null
  peerRecoveryViews: Record<
    string,
    { generation: number; sequence: number; actions: Action[]; deadline: number }
  >
  previewPending: boolean
  previewRejection: PreviewRejected | null
  cursors: Record<string, CursorPresence>
  pendingConfirm: Confirm | null
  pendingShape: ShapeChange | null
  unresolvedCommand: { puzzleId: string; commandId: string } | null
  issue: string | null
}

type Socket = Pick<WebSocket, 'send' | 'close' | 'onopen' | 'onmessage' | 'onclose' | 'onerror'>
interface DraftProof {
  turn: Turn
  revision: number
  nextSequence: number
  actions: Action[]
}
interface Flight {
  sequence: number
  submissionId: string
  actionId: string
  draftRevision: number
  proof: DraftProof | null
  recover: boolean
  resyncOnCollision: boolean
}
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
  readonly code: string
  readonly path: string

  constructor(message: string, definitive: boolean, code: string, path: string) {
    super(message)
    this.definitive = definitive
    this.code = code
    this.path = path
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
    turnBase: null,
    previews: {},
    recoveryView: null,
    peerRecoveryViews: {},
    previewPending: false,
    previewRejection: null,
    cursors: {},
    pendingConfirm: null,
    pendingShape: null,
    unresolvedCommand: null,
    issue: null,
  }
  private listeners = new Set<(state: LiveGameState) => void>()
  private cursorTimers = new Map<string, ReturnType<typeof setTimeout>>()
  private recoveryTimer: ReturnType<typeof setTimeout> | null = null
  private peerRecoveryTimer: ReturnType<typeof setTimeout> | null = null
  private previewDeadlines = new Map<string, number>()
  private currentReadStarted = 0
  private socket: Socket | null = null
  private epoch = 0
  private history: BoardEventHistory = { byVersion: new Map(), eventIds: new Set() }
  private sequence = 0
  private uncertainDraft = false
  private inFlight: Flight | null = null
  private lostPreview: Flight | null = null
  private recoveredPreviewId: string | null = null
  private draftRevision: number | null = null
  private acceptedActions: Action[] = []
  private draftProof: DraftProof | null = null
  private beginRequest: { schemaVersion: 1; puzzleId: string; requestId: string } | null = null
  private cancelRequest: Cancel | null = null
  private undoRequest: UndoV2 | null = null
  private undoBaseProof: DraftProof | null = null
  private confirmBusy: { payload: Confirm; epoch: number } | null = null
  private shapeBusy = false
  private readonly deps: LiveGameDependencies

  constructor(deps: LiveGameDependencies = defaults()) {
    this.deps = deps
  }

  getState(): LiveGameState {
    return structuredClone(this.state)
  }

  /** Return only actions whose latest revision this client has proven. */
  provenDraft(): Action[] | null {
    if (
      this.state.connection !== 'ready' ||
      !this.state.game?.ownTurn ||
      this.uncertainDraft ||
      this.inFlight ||
      this.state.pendingConfirm ||
      this.state.pendingShape ||
      this.state.unresolvedCommand ||
      this.undoRequest ||
      this.cancelRequest ||
      this.draftRevision === null
    )
      return null
    return structuredClone(this.acceptedActions)
  }

  /** The server revision associated with the locally proven action set. */
  provenDraftRevision(): number | null {
    return this.provenDraft() === null ? null : this.draftRevision
  }

  recoveredPreviewActionId(): string | null {
    return this.recoveredPreviewId
  }

  pendingUndoActionId(): string | null {
    return this.undoRequest?.actionId ?? null
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

  private clearRecoveryView() {
    if (this.recoveryTimer !== null) clearTimeout(this.recoveryTimer)
    this.recoveryTimer = null
  }

  private retainRecoveryView(): LiveGameState['recoveryView'] {
    const game = this.state.game
    const turn = game?.ownTurn
    const preview = turn && this.state.previews[turn.turnId.toLowerCase()]
    if (
      !game ||
      !turn ||
      this.state.connection !== 'ready' ||
      !preview ||
      preview.closed ||
      preview.generation !== turn.generation ||
      !Object.values(preview.actions).some((action) => action.affectedPieceIds.length > 1)
    )
      return null
    const displayed = Object.values(preview.actions)
    // The server sampled serverTime after the current-game request began. Subtracting
    // the entire elapsed request time gives a conservative lease/rollover bound.
    const remaining =
      Math.min(Date.parse(turn.expiresAt), Date.parse(game.endsAt)) -
      Date.parse(game.serverTime) -
      (performance.now() - this.currentReadStarted)
    if (!Number.isFinite(remaining) || remaining <= 0) return null
    this.clearRecoveryView()
    this.recoveryTimer = setTimeout(() => {
      this.recoveryTimer = null
      this.publish({ recoveryView: null })
    }, remaining)
    return { turn, actions: displayed }
  }

  private clearPeerRecovery() {
    if (this.peerRecoveryTimer !== null) clearTimeout(this.peerRecoveryTimer)
    this.peerRecoveryTimer = null
  }

  private schedulePeerRecovery(views: LiveGameState['peerRecoveryViews']) {
    this.clearPeerRecovery()
    const deadline = Object.values(views).reduce(
      (min, view) => Math.min(min, view.deadline),
      Infinity,
    )
    if (!Number.isFinite(deadline)) return
    this.peerRecoveryTimer = setTimeout(
      () => {
        this.peerRecoveryTimer = null
        const now = performance.now()
        const remaining = Object.fromEntries(
          Object.entries(this.state.peerRecoveryViews).filter(([, view]) => view.deadline > now),
        )
        this.schedulePeerRecovery(remaining)
        this.publish({ peerRecoveryViews: remaining })
      },
      Math.max(0, deadline - performance.now()),
    )
  }

  private previewDeadline(preview: Preview, game: CurrentGame): number {
    return (
      this.currentReadStarted +
      Math.min(Date.parse(preview.expiresAt), Date.parse(game.endsAt)) -
      Date.parse(game.serverTime)
    )
  }

  private retainPeerRecovery(): LiveGameState['peerRecoveryViews'] {
    if (this.state.connection !== 'ready' || !this.state.game) return this.state.peerRecoveryViews
    const ownId = this.state.game.ownTurn?.turnId.toLowerCase()
    const views: LiveGameState['peerRecoveryViews'] = {}
    for (const [key, preview] of Object.entries(this.state.previews)) {
      const deadline = this.previewDeadlines.get(key)
      const actions = Object.values(preview.actions)
      if (
        key !== ownId &&
        !preview.closed &&
        deadline !== undefined &&
        Number.isFinite(deadline) &&
        deadline > performance.now() &&
        actions.some((action) => action.affectedPieceIds.length > 1)
      )
        views[key] = {
          generation: preview.generation,
          sequence: preview.sequence,
          actions,
          deadline,
        }
    }
    this.schedulePeerRecovery(views)
    return views
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
        error.error.code,
        path,
      )
    }
    return checked<T>(name, value)
  }

  /** Reconnect always obtains a fresh current game and a fresh atomic subscription. */
  async connect(): Promise<void> {
    const recoveryView = this.retainRecoveryView() ?? this.state.recoveryView
    const peerRecoveryViews = this.retainPeerRecovery()
    this.previewDeadlines.clear()
    const epoch = ++this.epoch
    this.stopSocket()
    if (this.inFlight) {
      this.uncertainDraft = true
      this.lostPreview = this.inFlight
    }
    this.inFlight = null
    this.publish({
      connection: 'connecting',
      turnBase: null,
      previews: {},
      recoveryView,
      peerRecoveryViews,
      cursors: {},
      previewPending: false,
      previewRejection: null,
    })
    if (epoch !== this.epoch) return
    try {
      await this.request('/api/v1/session', 'Session', {})
      if (epoch !== this.epoch) return
      const currentReadStarted = performance.now()
      const game = await this.request<CurrentGame>('/api/v1/game/current', 'CurrentGame')
      await verifyGame(game)
      if (epoch !== this.epoch) return
      this.currentReadStarted = currentReadStarted
      this.uncertainDraft = Boolean(game.ownTurn)
      if (
        this.beginRequest &&
        (game.ownTurn || !sameId(this.beginRequest.puzzleId, game.snapshot.puzzleId))
      )
        this.beginRequest = null
      if (this.cancelRequest && !sameId(this.cancelRequest.puzzleId, game.snapshot.puzzleId))
        this.cancelRequest = null
      if (this.undoRequest && !sameId(this.undoRequest.puzzleId, game.snapshot.puzzleId)) {
        this.undoRequest = null
        this.undoBaseProof = null
      }
      this.history = { byVersion: new Map(), eventIds: new Set() }
      const view = this.state.recoveryView
      const keepView =
        view &&
        this.state.game &&
        game.ownTurn &&
        sameId(game.ownTurn.turnId, view.turn.turnId) &&
        game.ownTurn.generation === view.turn.generation &&
        game.ownTurn.turnRevision === view.turn.turnRevision &&
        canonical(this.state.game.definition) === canonical(game.definition)
      if (!keepView) this.clearRecoveryView()
      const sameDefinition =
        this.state.game &&
        sameId(this.state.game.snapshot.puzzleId, game.snapshot.puzzleId) &&
        this.state.game.day === game.day &&
        canonical(this.state.game.definition) === canonical(game.definition)
      const endDeadline = currentReadStarted + Date.parse(game.endsAt) - Date.parse(game.serverTime)
      const peerViews = sameDefinition
        ? Object.fromEntries(
            Object.entries(this.state.peerRecoveryViews)
              .map(
                ([key, peer]) =>
                  [key, { ...peer, deadline: Math.min(peer.deadline, endDeadline) }] as const,
              )
              .filter(
                ([, peer]) => Number.isFinite(peer.deadline) && peer.deadline > performance.now(),
              ),
          )
        : {}
      this.schedulePeerRecovery(peerViews)
      this.publish({
        game,
        connection: 'resyncing',
        recoveryView: keepView ? view : null,
        peerRecoveryViews: peerViews,
      })
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
              type: 'subscribe.v2',
              puzzleId: game.snapshot.puzzleId,
              afterBoardVersion: null,
              includeTurnBase: true,
            }),
          )
      }
      socket.onmessage = (event) => {
        if (epoch === this.epoch) this.receive(event.data)
      }
      socket.onclose = () => {
        if (epoch !== this.epoch) return
        const recoveryView = this.retainRecoveryView() ?? this.state.recoveryView
        const peerRecoveryViews = this.retainPeerRecovery()
        this.previewDeadlines.clear()
        if (!recoveryView) this.clearRecoveryView()
        ++this.epoch
        if (this.inFlight) {
          this.uncertainDraft = true
          this.lostPreview = this.inFlight
        }
        this.inFlight = null
        this.stopSocket()
        this.publish({
          connection: 'disconnected',
          turnBase: null,
          cursors: {},
          previews: {},
          recoveryView,
          peerRecoveryViews,
          previewPending: false,
          issue: 'Connection lost; reconnect to resynchronize.',
        })
      }
      socket.onerror = () => {
        if (epoch === this.epoch)
          this.publish({ issue: 'Game stream error; reconnect if it closes.' })
      }
    } catch (error) {
      if (epoch === this.epoch) {
        this.clearRecoveryView()
        this.clearPeerRecovery()
        this.previewDeadlines.clear()
        this.publish({
          connection: 'disconnected',
          recoveryView: null,
          peerRecoveryViews: {},
          issue: String(error),
          ...(error instanceof GameRequestError &&
          error.path === '/api/v1/game/current' &&
          error.code === 'PUZZLE_UNAVAILABLE'
            ? { game: null, previews: {} }
            : {}),
        })
      }
      throw error
    }
  }

  disconnect() {
    this.clearRecoveryView()
    this.clearPeerRecovery()
    this.previewDeadlines.clear()
    ++this.epoch
    if (this.inFlight) {
      this.uncertainDraft = true
      this.lostPreview = this.inFlight
    }
    this.inFlight = null
    this.stopSocket()
    this.publish({
      connection: 'disconnected',
      turnBase: null,
      previews: {},
      recoveryView: null,
      peerRecoveryViews: {},
      cursors: {},
      previewPending: false,
    })
  }

  private stopSocket() {
    for (const timer of this.cursorTimers.values()) clearTimeout(timer)
    this.cursorTimers.clear()
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onclose = null
      socket.close()
    }
  }

  private resync(reason: string, game = this.state.game, discardView = false) {
    const epoch = this.epoch
    const recoveryView = discardView ? null : (this.retainRecoveryView() ?? this.state.recoveryView)
    const peerRecoveryViews = this.retainPeerRecovery()
    this.previewDeadlines.clear()
    if (!recoveryView) this.clearRecoveryView()
    this.publish({
      issue: reason,
      connection: 'resyncing',
      turnBase: null,
      game,
      previews: {},
      recoveryView,
      peerRecoveryViews,
      cursors: {},
    })
    if (epoch !== this.epoch) return
    void this.connect().catch(() => {
      // connect() has already published the error.
    })
  }

  private undoProof(payload: UndoV2, survivors?: Action[]): DraftProof | null {
    const proof = this.undoBaseProof ?? this.draftProof
    if (
      !proof ||
      !sameId(proof.turn.puzzleId, payload.puzzleId) ||
      !sameId(proof.turn.turnId, payload.turnId) ||
      proof.turn.generation !== payload.generation ||
      proof.turn.turnRevision !== payload.turnRevision ||
      proof.revision !== payload.draftRevision
    )
      return null
    const removed = proof.actions.find((action) => sameId(action.actionId, payload.actionId))
    if (!removed) return null
    const previous = proof.actions.filter((action) => !sameId(action.actionId, payload.actionId))
    if (
      !survivors &&
      previous.some((action) =>
        action.affectedPieceIds.some((id) =>
          removed.affectedPieceIds.some((piece) => sameId(id, piece)),
        ),
      )
    )
      return null
    if (
      survivors &&
      (survivors.length !== previous.length ||
        survivors.some((action) => {
          const old = previous.find((item) => sameId(item.actionId, action.actionId))
          if (!old) return true
          const joined = old.affectedPieceIds.some((id) =>
            removed.affectedPieceIds.some((piece) => sameId(id, piece)),
          )
          if (!joined) return canonical(old) !== canonical(action)
          return (
            action.affectedPieceIds.some(
              (id) => !old.affectedPieceIds.some((piece) => sameId(id, piece)),
            ) ||
            action.baseRevisions.some(
              (revision) =>
                !old.baseRevisions.some(
                  (base) =>
                    sameId(base.groupId, revision.groupId) && base.revision === revision.revision,
                ),
            )
          )
        }) ||
        new Set(survivors.map((action) => action.actionId.toLowerCase())).size !== survivors.length)
    )
      return null
    return {
      turn: {
        ...proof.turn,
        generation: payload.generation + 1,
        turnRevision: payload.turnRevision + 1,
      },
      revision: proof.revision + 1,
      nextSequence: 0,
      actions: structuredClone(survivors ?? previous),
    }
  }

  recoverInterruptedPreview() {
    if (this.inFlight) this.inFlight.recover = true
    if (this.lostPreview) {
      this.lostPreview.recover = true
      if (this.uncertainDraft && this.state.connection === 'ready')
        this.resync('Recovering interrupted preview.')
    }
  }

  abandonInterruptedPreview() {
    if (this.inFlight) this.inFlight.recover = false
    if (this.lostPreview) this.lostPreview.recover = false
  }

  private matchesInterruptedPreview(reply: SubscribedV2): boolean {
    const flight = this.lostPreview
    const proof = flight?.proof
    if (
      !flight?.recover ||
      !proof ||
      !reply.ownTurn ||
      !sameId(proof.turn.puzzleId, reply.ownTurn.puzzleId) ||
      !sameId(proof.turn.turnId, reply.ownTurn.turnId) ||
      proof.turn.generation !== reply.ownTurn.generation ||
      proof.turn.turnRevision !== reply.ownTurn.turnRevision ||
      reply.draftRevision !== flight.draftRevision + 1 ||
      reply.nextSequence !== flight.sequence + 1
    )
      return false
    return Boolean(
      reply.lastSubmissionId &&
      sameId(reply.lastSubmissionId, flight.submissionId) &&
      reply.actions.some((action) => sameId(action.actionId, flight.actionId)) &&
      new Set(reply.actions.map((action) => action.actionId.toLowerCase())).size ===
        reply.actions.length,
    )
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
      if (
        (type === 'subscribed' || type === 'subscribed.v2') &&
        this.state.connection !== 'resyncing'
      )
        throw new Error('Unexpected subscription handoff')
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
        this.previewDeadlines.clear()
        for (const preview of reply.previews) {
          const key = preview.turnId.toLowerCase()
          const previous = previews[key] ?? null
          const next = applyPreview(previous, preview)
          if (next !== previous) this.previewDeadlines.set(key, this.previewDeadline(preview, game))
          if (next) previews[key] = next
        }
        this.history = { byVersion: new Map(), eventIds: new Set() }
        this.clearRecoveryView()
        this.clearPeerRecovery()
        this.publish({
          game: { ...game, snapshot: reply.snapshot },
          previews,
          recoveryView: null,
          peerRecoveryViews: {},
          connection: 'resyncing',
          issue: 'Service must provide identity state atomically with the subscription.',
        })
      } else if (type === 'subscribed.v2') {
        const reply = checked<SubscribedV2>('SubscribedV2', message)
        if (
          !validateBoard(game.definition, reply.snapshot).valid ||
          !sameId(reply.snapshot.puzzleId, game.snapshot.puzzleId) ||
          reply.watermark !== reply.snapshot.boardVersion ||
          reply.watermark < game.snapshot.boardVersion ||
          (reply.ownTurn && !sameId(reply.ownTurn.puzzleId, reply.snapshot.puzzleId)) ||
          (reply.ownTurn && reply.ownTurn.boardVersion > reply.snapshot.boardVersion) ||
          (!reply.ownTurn &&
            (reply.actions.length || reply.nextSequence || reply.draftRevision !== null)) ||
          (reply.ownTurn && reply.draftRevision === null) ||
          (reply.turnBase &&
            (reply.turnBase.boardVersion !== reply.ownTurn?.boardVersion ||
              !sameId(reply.turnBase.puzzleId, reply.ownTurn.puzzleId) ||
              !validateBoard(game.definition, reply.turnBase).valid))
        )
          throw new Error('Invalid subscription handoff')
        const previews: Record<string, PreviewState> = {}
        this.previewDeadlines.clear()
        for (const preview of reply.previews) {
          if (!sameId(preview.puzzleId, reply.snapshot.puzzleId))
            throw new Error('Wrong preview puzzle')
          const key = preview.turnId.toLowerCase()
          const previous = previews[key] ?? null
          const next = applyPreview(previous, preview)
          if (next !== previous) this.previewDeadlines.set(key, this.previewDeadline(preview, game))
          if (next) previews[key] = next
        }
        const proof = this.draftProof
        const undoneProof = this.undoRequest ? this.undoProof(this.undoRequest) : null
        const matches = (candidate: DraftProof | null) =>
          Boolean(
            candidate &&
            reply.ownTurn &&
            sameId(candidate.turn.puzzleId, reply.ownTurn.puzzleId) &&
            sameId(candidate.turn.turnId, reply.ownTurn.turnId) &&
            candidate.turn.generation === reply.ownTurn.generation &&
            candidate.turn.turnRevision === reply.ownTurn.turnRevision &&
            candidate.revision === reply.draftRevision &&
            candidate.nextSequence === reply.nextSequence &&
            canonical(candidate.actions) === canonical(reply.actions),
          )
        const recoveredPreview = this.matchesInterruptedPreview(reply)
        this.recoveredPreviewId =
          recoveredPreview && this.lostPreview ? this.lostPreview.actionId : null
        this.uncertainDraft =
          !matches(proof) &&
          !matches(undoneProof) &&
          !(this.lostPreview?.recover && matches(this.lostPreview.proof)) &&
          !recoveredPreview &&
          (reply.actions.length > 0 || Boolean(this.lostPreview?.recover))
        this.draftProof =
          reply.ownTurn && reply.draftRevision !== null && !this.uncertainDraft
            ? {
                turn: reply.ownTurn,
                revision: reply.draftRevision,
                nextSequence: reply.nextSequence,
                actions: structuredClone(reply.actions),
              }
            : null
        this.history = { byVersion: new Map(), eventIds: new Set() }
        this.sequence = reply.nextSequence
        this.draftRevision = reply.draftRevision
        this.acceptedActions = structuredClone(reply.actions)
        if (!this.uncertainDraft) this.lostPreview = null
        this.clearRecoveryView()
        this.clearPeerRecovery()
        this.publish({
          turnBase: reply.ownTurn
            ? (reply.turnBase ??
              (reply.snapshot.boardVersion === reply.ownTurn.boardVersion ? reply.snapshot : null))
            : null,
          game: {
            ...game,
            snapshot: reply.snapshot,
            allowance: reply.allowance,
            ownTurn: reply.ownTurn,
          },
          previews,
          recoveryView: null,
          peerRecoveryViews: {},
          connection: 'ready',
          previewPending: false,
          issue: this.uncertainDraft
            ? 'Recovered draft is uncertain; cancel it before editing.'
            : null,
        })
      } else if (type === 'cursor.presence') {
        const cursor = checked<CursorPresence>('CursorPresence', message)
        if (!sameId(cursor.puzzleId, game.snapshot.puzzleId)) return
        const previousTimer = this.cursorTimers.get(cursor.cursorId)
        if (previousTimer) clearTimeout(previousTimer)
        this.cursorTimers.delete(cursor.cursorId)
        if (cursor.position) {
          this.cursorTimers.set(
            cursor.cursorId,
            setTimeout(() => {
              this.cursorTimers.delete(cursor.cursorId)
              const current = Object.fromEntries(
                Object.entries(this.state.cursors).filter(([id]) => id !== cursor.cursorId),
              )
              this.publish({ cursors: current })
            }, 7000),
          )
        }
        this.publish({
          cursors: cursor.position
            ? { ...this.state.cursors, [cursor.cursorId]: cursor }
            : Object.fromEntries(
                Object.entries(this.state.cursors).filter(([id]) => id !== cursor.cursorId),
              ),
        })
      } else if (type === 'preview.accepted') {
        const receipt = checked<PreviewAcceptedV2>('PreviewAcceptedV2', message)
        const turn = game.ownTurn
        const pending = this.inFlight
        if (
          !pending ||
          !turn ||
          !sameId(receipt.puzzleId, turn.puzzleId) ||
          !sameId(receipt.turnId, turn.turnId) ||
          receipt.generation !== turn.generation ||
          receipt.sequence !== pending.sequence ||
          !sameId(receipt.actionId, pending.actionId)
        )
          return
        if (
          receipt.draftRevision !== pending.draftRevision + 1 ||
          !receipt.actions.some((action) => sameId(action.actionId, pending.actionId)) ||
          new Set(receipt.actions.map((action) => action.actionId.toLowerCase())).size !==
            receipt.actions.length
        ) {
          this.uncertainDraft = true
          this.resync('Draft changed concurrently; resynchronizing.')
          return
        }
        this.inFlight = null
        this.lostPreview = null
        this.acceptedActions = structuredClone(receipt.actions)
        this.draftRevision = receipt.draftRevision
        this.draftProof = {
          turn,
          revision: receipt.draftRevision,
          nextSequence: this.sequence,
          actions: structuredClone(receipt.actions),
        }
        this.publish({ previewPending: false, issue: null })
      } else if (type === 'preview' || type === 'previewCleared') {
        const event = checked<Preview | PreviewCleared>(
          type === 'preview' ? 'Preview' : 'PreviewCleared',
          message,
        )
        if (!sameId(event.puzzleId, game.snapshot.puzzleId))
          throw new Error('Preview for another puzzle')
        const view = this.state.recoveryView
        if (
          type === 'previewCleared' &&
          view &&
          sameId(event.turnId, view.turn.turnId) &&
          event.generation >= view.turn.generation
        ) {
          this.resync('Turn changed; refreshing turn and previews.', game, true)
          return
        }
        const key = event.turnId.toLowerCase()
        const peerView = this.state.peerRecoveryViews[key]
        if (
          peerView &&
          (event.generation < peerView.generation ||
            (type === 'preview' &&
              event.generation === peerView.generation &&
              (event as Preview).sequence <= peerView.sequence))
        )
          return
        const peerRecoveryViews = { ...this.state.peerRecoveryViews }
        if (peerView && event.generation >= peerView.generation) {
          Reflect.deleteProperty(peerRecoveryViews, key)
          this.schedulePeerRecovery(peerRecoveryViews)
        }
        const previous = this.state.previews[key] ?? null
        const next = applyPreview(previous, event)
        if (
          next !== previous &&
          game.ownTurn &&
          sameId(event.turnId, game.ownTurn.turnId) &&
          event.generation >= game.ownTurn.generation &&
          type === 'previewCleared'
        ) {
          this.uncertainDraft = true
          this.resync(
            'Turn changed without a correlated receipt; resynchronizing before cancellation.',
            game,
            true,
          )
          return
        }
        if (next !== previous) {
          if (type === 'preview')
            this.previewDeadlines.set(key, this.previewDeadline(event as Preview, game))
          else this.previewDeadlines.delete(key)
        }
        if (next)
          this.publish({ previews: { ...this.state.previews, [key]: next }, peerRecoveryViews })
      } else if (type === 'preview.rejected') {
        const rejection = checked<PreviewRejected>('PreviewRejected', message)
        const view = this.state.recoveryView
        if (
          view &&
          sameId(rejection.puzzleId, view.turn.puzzleId) &&
          sameId(rejection.turnId, view.turn.turnId) &&
          rejection.generation === view.turn.generation
        ) {
          this.resync('Preview rejected; refreshing turn and previews.', game, true)
          return
        }
        if (
          this.inFlight?.sequence === rejection.sequence &&
          sameId(rejection.puzzleId, game.snapshot.puzzleId) &&
          rejection.generation === game.ownTurn?.generation &&
          sameId(rejection.turnId, game.ownTurn.turnId) &&
          sameId(this.inFlight.actionId, rejection.actionId)
        ) {
          const flight = this.inFlight
          this.inFlight = null
          if (
            rejection.error.code === 'INVALID_ACTION' &&
            [
              'Forbidden piece overlap',
              'Group outside workspace',
              'Cannot join selected groups',
              'Not adjacent or outside snap tolerance',
            ].includes(rejection.error.message) &&
            !flight.recover &&
            !flight.resyncOnCollision
          ) {
            this.publish({ previewPending: false, previewRejection: rejection })
          } else {
            this.uncertainDraft = true
            this.resync(
              `Preview rejected: ${rejection.error.code}: ${rejection.error.message}; resynchronizing.`,
              game,
              true,
            )
          }
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
          this.resync('Board changed; refreshing allowance and turn.', {
            ...game,
            snapshot: result.snapshot,
            allowance: null,
          })
        }
      } else if (type === 'leaseRenewed') {
        const renewed = checked<LeaseRenewed>('LeaseRenewed', message)
        if (
          game.ownTurn &&
          sameId(renewed.puzzleId, game.ownTurn.puzzleId) &&
          sameId(renewed.turnId, game.ownTurn.turnId) &&
          renewed.generation === game.ownTurn.generation
        )
          this.publish({
            game: { ...game, ownTurn: { ...game.ownTurn, expiresAt: renewed.expiresAt } },
          })
      } else if (type === 'resyncRequired' || type === 'puzzleChanged') {
        if (type === 'puzzleChanged') {
          const event = checked<PuzzleChanged>('PuzzleChanged', message)
          if (
            !event.available ||
            !event.puzzleId ||
            !sameId(event.puzzleId, game.snapshot.puzzleId) ||
            event.day !== game.day
          ) {
            this.clearRecoveryView()
            this.clearPeerRecovery()
            this.previewDeadlines.clear()
            this.publish({ game: null, previews: {}, recoveryView: null, peerRecoveryViews: {} })
            if (epoch !== this.epoch) return
          }
        } else checked('ResyncRequired', message)
        this.resync('Game changed; resynchronizing.')
      } else if (type === 'error') {
        const error = checked<{ error: { code: string; message: string } }>('SocketError', message)
        if (error.error.code === 'PUZZLE_UNAVAILABLE') {
          this.clearRecoveryView()
          this.clearPeerRecovery()
          this.previewDeadlines.clear()
        }
        this.publish({
          issue: `${error.error.code}: ${error.error.message}`,
          ...(error.error.code === 'PUZZLE_UNAVAILABLE'
            ? { game: null, previews: {}, recoveryView: null, peerRecoveryViews: {} }
            : {}),
        })
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

  /** Cursor positions are ephemeral, optional hints in board coordinates. */
  sendCursor(position: Point | null) {
    if (this.state.connection !== 'ready' || !this.socket || !this.state.game) return
    try {
      this.socket.send(
        JSON.stringify({
          schemaVersion: 1,
          type: 'cursor.update',
          puzzleId: this.state.game.snapshot.puzzleId,
          position,
        }),
      )
    } catch {
      // Presence is best effort and does not affect a game command.
    }
  }

  private turn(): Turn {
    const turn = this.readyGame().ownTurn
    if (!turn) throw new Error('No active turn')
    return turn
  }

  async begin(): Promise<Turn> {
    const game = this.readyGame()
    if (this.state.pendingConfirm || this.state.pendingShape || this.state.unresolvedCommand)
      throw new Error('Resolve the pending confirmation first')
    if (game.ownTurn) return game.ownTurn
    this.beginRequest ??= {
      schemaVersion: 1,
      puzzleId: game.snapshot.puzzleId,
      requestId: this.deps.id(),
    }
    const epoch = this.epoch
    const beginRequest = this.beginRequest
    let turn: Turn
    try {
      turn = await this.request<Turn>('/api/v1/game/turns', 'Turn', beginRequest)
    } catch (error) {
      if (epoch === this.epoch && error instanceof GameRequestError && error.definitive)
        this.beginRequest = null
      throw error
    }
    if (!sameId(turn.puzzleId, game.snapshot.puzzleId)) throw new Error('Turn puzzle mismatch')
    if (this.beginRequest === beginRequest) this.beginRequest = null
    if (epoch === this.epoch) {
      this.sequence = 0
      this.draftRevision = 0
      this.acceptedActions = []
      this.draftProof = { turn, revision: 0, nextSequence: 0, actions: [] }
      this.uncertainDraft = false
    }
    if (
      this.state.connection !== 'disconnected' &&
      this.state.game &&
      sameId(this.state.game.snapshot.puzzleId, turn.puzzleId)
    )
      this.resync('Turn opened; synchronizing the latest board.')
    return turn
  }

  newAction(action: Omit<Action, 'actionId'>): Action {
    return { ...action, actionId: this.deps.id() }
  }

  /** Keep the action ID returned by newAction when adjusting the same move. */
  preview(action: Action, resyncOnCollision = false) {
    const turn = this.turn()
    if (
      this.state.pendingConfirm ||
      this.state.pendingShape ||
      this.state.unresolvedCommand ||
      this.uncertainDraft ||
      this.inFlight ||
      this.undoRequest ||
      this.cancelRequest
    )
      throw new Error('Draft is not ready for a preview; resolve or cancel it first')
    checked<Action>('Action', action)
    const sequence = this.sequence++
    const socket = this.socket
    if (!socket) throw new Error('Game stream is not connected')
    if (this.draftRevision === null) throw new Error('Missing draft revision')
    const epoch = this.epoch
    this.lostPreview = null
    this.recoveredPreviewId = null
    const submissionId = this.deps.id()
    this.inFlight = {
      sequence,
      submissionId,
      actionId: action.actionId,
      draftRevision: this.draftRevision,
      proof: this.draftProof,
      recover: false,
      resyncOnCollision,
    }
    this.publish({ previewPending: true, previewRejection: null, issue: null })
    if (epoch !== this.epoch || socket !== this.socket)
      throw new Error('Connection changed before preview')
    try {
      socket.send(
        JSON.stringify({
          schemaVersion: 1,
          type: 'preview.submit',
          puzzleId: turn.puzzleId,
          turnId: turn.turnId,
          generation: turn.generation,
          sequence,
          submissionId,
          action,
        }),
      )
    } catch (error) {
      this.uncertainDraft = true
      this.resync('Preview outcome unknown; resynchronizing.')
      throw error
    }
  }

  /** Renew the active turn while the integrating UI is open. */
  heartbeat() {
    const turn = this.turn()
    if (!this.socket) throw new Error('Game stream is not connected')
    this.socket.send(
      JSON.stringify({
        schemaVersion: 1,
        type: 'heartbeat',
        puzzleId: turn.puzzleId,
        turnId: turn.turnId,
        generation: turn.generation,
      }),
    )
  }

  async undo(actionId: string): Promise<UndoResult> {
    if (
      this.state.pendingConfirm ||
      this.state.pendingShape ||
      this.state.unresolvedCommand ||
      (this.uncertainDraft && !this.undoRequest) ||
      this.inFlight ||
      this.cancelRequest
    )
      throw new Error('Draft is not ready for undo')
    if (!this.undoRequest) {
      const turn = this.turn()
      if (this.draftRevision === null) throw new Error('Missing draft revision')
      this.undoRequest = {
        schemaVersion: 1,
        puzzleId: turn.puzzleId,
        turnId: turn.turnId,
        requestId: this.deps.id(),
        turnRevision: turn.turnRevision,
        generation: turn.generation,
        draftRevision: this.draftRevision,
        actionId,
      }
      this.undoBaseProof = this.draftProof
    }
    const payload = this.undoRequest
    if (!sameId(payload.actionId, actionId)) throw new Error('Retry the pending undo first')
    const epoch = this.epoch
    let result: UndoResult
    try {
      result = await this.request<UndoResult>(
        `/api/v1/game/turns/${payload.turnId}/undo`,
        'UndoResult',
        payload,
      )
    } catch (error) {
      if (this.undoRequest === payload) {
        if (error instanceof GameRequestError && error.definitive) {
          this.undoRequest = null
          this.undoBaseProof = null
          if (this.state.connection === 'ready') {
            this.uncertainDraft = true
            this.resync('Undo rejected; refreshing the draft.')
          }
        } else if (epoch === this.epoch) this.publish({ issue: String(error) })
      }
      throw error
    }
    if (
      !sameId(result.requestId, payload.requestId) ||
      !sameId(result.turnId, payload.turnId) ||
      !sameId(result.puzzleId, payload.puzzleId) ||
      !sameId(result.actionId, actionId) ||
      result.generation !== payload.generation + 1 ||
      result.turnRevision !== payload.turnRevision + 1
    )
      throw new Error('Undo response mismatch')
    if (this.undoRequest !== payload) {
      this.requireEpoch(epoch)
      return result
    }
    const undoneProof = this.undoProof(payload, result.actions)
    if (!undoneProof) throw new Error('Undo survivor mismatch')
    this.draftProof = undoneProof
    this.undoRequest = null
    this.undoBaseProof = null
    const activeTurn = this.state.game?.ownTurn
    if (
      epoch === this.epoch ||
      (this.state.connection === 'ready' && this.uncertainDraft) ||
      (this.state.connection === 'ready' &&
        activeTurn &&
        sameId(activeTurn.puzzleId, payload.puzzleId) &&
        sameId(activeTurn.turnId, payload.turnId) &&
        activeTurn.generation === payload.generation &&
        activeTurn.turnRevision === payload.turnRevision)
    )
      this.resync('Turn changed; refreshing turn and previews.')
    return result
  }

  async cancel(): Promise<CancelResult> {
    if (
      this.state.pendingConfirm ||
      this.state.pendingShape ||
      this.state.unresolvedCommand ||
      this.undoRequest
    )
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
      if (this.cancelRequest === payload && error instanceof GameRequestError && error.definitive) {
        this.cancelRequest = null
        if (epoch === this.epoch) this.resync('Cancel rejected; refreshing turn before retry.')
      }
      throw error
    }
    if (
      !sameId(result.requestId, payload.requestId) ||
      !sameId(result.turnId, payload.turnId) ||
      !sameId(result.puzzleId, payload.puzzleId)
    )
      throw new Error('Cancel response mismatch')
    if (epoch !== this.epoch) {
      if (
        this.cancelRequest === payload &&
        (result.outcome === 'canceled' || result.outcome === 'already_canceled')
      ) {
        this.cancelRequest = null
        this.clearRecoveryView()
        this.publish({ recoveryView: null })
        return result
      }
      this.requireEpoch(epoch)
    }
    this.cancelRequest = null
    if (result.outcome === 'canceled' || result.outcome === 'already_canceled') {
      this.inFlight = null
      this.uncertainDraft = false
      this.resync('Canceled; synchronizing the latest board.', this.state.game, true)
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

  /** Retry an unknown outcome with the same request ID and payload. */
  async changeShape(
    values?: Pick<ShapeChange, 'tabDepthPermille' | 'tabWidthPermille' | 'edgeWavePermille'>,
  ): Promise<ShapeResult> {
    if (!this.state.pendingShape) {
      const game = this.readyGame()
      if (
        !values ||
        game.ownTurn ||
        this.state.pendingConfirm ||
        this.state.unresolvedCommand ||
        this.inFlight ||
        this.cancelRequest ||
        this.undoRequest
      )
        throw new Error('Finish or cancel the current draft before changing shape')
      const payload: ShapeChange = {
        schemaVersion: 1,
        puzzleId: game.snapshot.puzzleId,
        requestId: this.deps.id(),
        definitionVersion: game.definition.definitionVersion,
        ...values,
      }
      checked<ShapeChange>('ShapeChange', payload)
      this.publish({ pendingShape: payload, issue: null })
      // Listeners may synchronously replace the pending request or disconnect.
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
      if (this.state.pendingShape !== payload || this.state.connection !== 'ready')
        throw new Error('Connection changed before shape change')
    }
    const payload = this.state.pendingShape
    if (this.shapeBusy) throw new Error('Shape change already in progress')
    this.shapeBusy = true
    try {
      const result = await this.request<ShapeResult>('/api/v1/game/shape', 'ShapeResult', payload)
      if (
        !sameId(result.puzzleId, payload.puzzleId) ||
        !sameId(result.requestId, payload.requestId)
      )
        throw new Error('Shape response mismatch')
      if (this.state.pendingShape === payload) {
        this.publish({ pendingShape: null, issue: null })
        if (result.outcome === 'updated' && this.state.connection !== 'disconnected')
          this.resync('Shape updated; synchronizing the shared board.')
      }
      return result
    } catch (error) {
      if (this.state.pendingShape === payload) {
        if (error instanceof GameRequestError && error.definitive) {
          this.publish({ pendingShape: null, issue: String(error) })
          if (error.code === 'STALE_REVISION' && this.state.connection === 'ready')
            this.resync('Puzzle shape changed; refreshing the board.')
        } else this.publish({ issue: `Shape outcome unknown: ${String(error)}` })
      }
      throw error
    } finally {
      this.shapeBusy = false
    }
  }

  restorePendingShape(payload: ShapeChange) {
    checked<ShapeChange>('ShapeChange', payload)
    if (this.state.pendingShape) throw new Error('A shape change is already pending')
    this.publish({
      pendingShape: structuredClone(payload),
      issue: 'Resolve the restored shape change before editing.',
    })
  }

  async confirm(): Promise<CommandResult> {
    if (!this.state.pendingConfirm) {
      this.turn()
      if (
        this.state.unresolvedCommand ||
        this.state.pendingShape ||
        this.uncertainDraft ||
        this.inFlight ||
        this.undoRequest ||
        this.cancelRequest
      )
        throw new Error('Draft is not ready to confirm')
      if (!this.acceptedActions.length) throw new Error('No accepted actions to confirm')
      const turn = this.turn()
      if (this.draftRevision === null) throw new Error('Missing draft revision')
      const payload: ConfirmV2 = {
        schemaVersion: 1,
        puzzleId: turn.puzzleId,
        turnId: turn.turnId,
        commandId: this.deps.id(),
        turnRevision: turn.turnRevision,
        generation: turn.generation,
        draftRevision: this.draftRevision,
        actions: structuredClone(this.acceptedActions) as [Action, ...Action[]],
      }
      this.publish({ pendingConfirm: payload })
      // Listeners run synchronously and may disconnect or replace the pending command.
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
      if (this.state.pendingConfirm !== payload || this.state.connection !== 'ready')
        throw new Error('Connection changed before confirmation')
    }
    const payload = this.state.pendingConfirm
    const epoch = this.epoch
    if (this.confirmBusy?.payload === payload && this.confirmBusy.epoch === epoch)
      throw new Error('Confirmation already in progress')
    const unresolvedCommand = this.state.unresolvedCommand
    const busy = { payload, epoch }
    this.confirmBusy = busy
    try {
      const result = await this.request<CommandResult>(
        `/api/v1/game/turns/${payload.turnId}/confirm`,
        'CommandResult',
        payload,
      )
      if (epoch !== this.epoch && this.state.connection === 'disconnected') this.requireEpoch(epoch)
      return this.acceptResult(result, payload, epoch, unresolvedCommand)
    } catch (error) {
      if (
        this.state.pendingConfirm === payload &&
        this.state.unresolvedCommand === unresolvedCommand
      )
        if (error instanceof GameRequestError && error.definitive) {
          this.uncertainDraft = true
          this.publish({ pendingConfirm: null, issue: String(error) })
          if (
            // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
            this.state.pendingConfirm === null &&
            this.state.unresolvedCommand === unresolvedCommand &&
            this.state.connection === 'ready'
          )
            this.resync('Confirmation rejected; refreshing the draft.')
        } else if (epoch === this.epoch)
          this.publish({ issue: `Confirmation outcome unknown: ${String(error)}` })
      throw error
    } finally {
      if (this.confirmBusy === busy) this.confirmBusy = null
    }
  }

  async resolveConfirm(): Promise<CommandResult> {
    const payload = this.state.pendingConfirm
    if (!payload) throw new Error('No pending confirmation')
    const epoch = this.epoch
    const unresolvedCommand = this.state.unresolvedCommand
    const result = await this.request<CommandResult>(
      `/api/v1/game/commands/${payload.commandId}?puzzleId=${encodeURIComponent(payload.puzzleId)}`,
      'CommandResult',
    )
    this.requireEpoch(epoch)
    return this.acceptResult(result, payload, epoch, unresolvedCommand)
  }

  /** Look up a command reported by Cancel when another tab is confirming the turn. */
  async resolveCancelCommand(): Promise<CommandResult> {
    const command = this.state.unresolvedCommand
    if (!command) throw new Error('No command reported by cancel')
    const epoch = this.epoch
    const pendingConfirm = this.state.pendingConfirm
    const result = await this.request<CommandResult>(
      `/api/v1/game/commands/${command.commandId}?puzzleId=${encodeURIComponent(command.puzzleId)}`,
      'CommandResult',
    )
    this.requireEpoch(epoch)
    if (!sameId(result.commandId, command.commandId) || !sameId(result.puzzleId, command.puzzleId))
      throw new Error('Command response mismatch')
    if (this.state.unresolvedCommand !== command || this.state.pendingConfirm !== pendingConfirm)
      return result
    if (result.outcome === 'confirmed' || result.outcome === 'rejected') {
      this.publish({ unresolvedCommand: null })
      if (
        epoch === this.epoch &&
        // publish invokes listeners synchronously; they may replace the command.
        // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
        this.state.unresolvedCommand === null &&
        this.state.pendingConfirm === pendingConfirm &&
        this.state.connection !== 'disconnected'
      )
        this.resync(
          result.outcome === 'rejected'
            ? `${result.error?.code ?? 'REJECTED'}: ${result.error?.message ?? 'Confirmation rejected'}`
            : 'Confirmation confirmed; synchronizing the latest board.',
        )
    } else this.publish({ issue: `Confirmation ${result.outcome}; resolve it before editing.` })
    return result
  }

  /** Restore an unresolved payload retained by the integrating application across a reload. */
  restorePendingConfirm(payload: Confirm) {
    if (!validate('ConfirmV2', payload).valid) checked<Confirm>('Confirm', payload)
    if (this.state.pendingConfirm) throw new Error('A confirmation is already pending')
    this.publish({
      pendingConfirm: structuredClone(payload),
      issue: 'Resolve the restored confirmation before editing.',
    })
  }

  private acceptResult(
    result: CommandResult,
    payload: Confirm,
    epoch: number,
    unresolvedCommand: LiveGameState['unresolvedCommand'],
  ): CommandResult {
    if (!sameId(result.commandId, payload.commandId) || !sameId(result.puzzleId, payload.puzzleId))
      throw new Error('Confirmation response mismatch')
    if (this.state.pendingConfirm !== payload || this.state.unresolvedCommand !== unresolvedCommand)
      return result
    if (result.outcome === 'confirmed' || result.outcome === 'rejected') {
      this.publish({
        pendingConfirm: null,
        issue:
          result.outcome === 'rejected'
            ? `${result.error?.code ?? 'REJECTED'}: ${result.error?.message ?? 'Confirmation rejected'}`
            : null,
      })
      if (
        epoch === this.epoch &&
        // publish invokes listeners synchronously; they may replace the command.
        // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
        this.state.pendingConfirm === null &&
        this.state.unresolvedCommand === unresolvedCommand &&
        this.state.connection !== 'disconnected'
      )
        this.resync(
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
