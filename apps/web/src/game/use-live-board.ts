/* eslint-disable @typescript-eslint/restrict-template-expressions */
import { useCallback, useEffect, useRef, useState, type PointerEvent } from 'react'
import { LiveGame, type LiveGameState } from '../game/live-game.ts'
import { t } from '../i18n/translate.ts'
import { suggestJoin } from '../game/join.ts'
import { canMoveGroup } from './move-availability.ts'
import { validate } from '@world-jigsaw/contracts'
import type {
  Action,
  Confirm,
  DraftSummary,
  Group,
  Piece,
  Transform,
  Turn,
} from '@world-jigsaw/contracts'

interface Point {
  x: number
  y: number
}
interface Pending {
  group: Group
  action: Action
  start: Point
  startScreen?: Point
  origin: Transform
  desired: Transform
  sent: string
  sentFor: 'active' | 'final' | 'restore'
  active: boolean
  moved: boolean
  acquiring: boolean
  openingTurn: boolean
  openedTurn: Turn | null
  waitingForTurn: boolean
  initialTurn: Turn | null
  puzzleId: string
  finalPrepared: boolean
  interrupted: boolean
  restoring: boolean
  undoStarted: boolean
  original: Action | null
}
const sameTurn = (a: Turn, b: Turn) =>
  a.turnId.toLowerCase() === b.turnId.toLowerCase() &&
  a.puzzleId.toLowerCase() === b.puzzleId.toLowerCase() &&
  a.generation === b.generation &&
  a.turnRevision === b.turnRevision
const sameId = (a: string, b: string) => a.toLowerCase() === b.toLowerCase()
const sameMembers = (a: readonly string[], b: readonly string[]) =>
  a.length === b.length && a.every((id) => b.some((other) => sameId(id, other)))

function pendingStillValid(p: Pending, game: NonNullable<LiveGameState['game']>, draft: Action[]) {
  const accepted = draft.find((action) => sameId(action.actionId, p.action.actionId))
  if (accepted) {
    const members = p.original?.affectedPieceIds ?? p.group.pieceIds
    const revisions = p.original?.baseRevisions ?? [
      { groupId: p.group.groupId, revision: p.group.revision },
    ]
    return (
      members.every((id) => accepted.affectedPieceIds.some((member) => sameId(id, member))) &&
      revisions.every((base) =>
        accepted.baseRevisions.some(
          (revision) =>
            sameId(base.groupId, revision.groupId) && base.revision === revision.revision,
        ),
      )
    )
  }
  if (p.original) return false
  const group = game.snapshot.groups.find((item) => sameId(item.groupId, p.group.groupId))
  return Boolean(
    group &&
    group.revision === p.group.revision &&
    group.finalPosition === p.group.finalPosition &&
    sameMembers(group.pieceIds, p.group.pieceIds) &&
    group.transform.rotation === p.group.transform.rotation &&
    group.transform.translation.x === p.group.transform.translation.x &&
    group.transform.translation.y === p.group.transform.translation.y &&
    !draft.some((action) =>
      action.affectedPieceIds.some((id) => p.group.pieceIds.some((member) => sameId(id, member))),
    ),
  )
}

export function outline(piece: Piece): string {
  const base = piece.solvedPosition,
    first = piece.edges[0]
  let path = `M ${base.x + first.start.x} ${base.y + first.start.y}`
  for (const edge of piece.edges)
    for (const segment of edge.segments) {
      if (segment.kind === 'line') path += ` L ${base.x + segment.to.x} ${base.y + segment.to.y}`
      else
        path += ` C ${base.x + segment.control1.x} ${base.y + segment.control1.y} ${base.x + segment.control2.x} ${base.y + segment.control2.y} ${base.x + segment.to.x} ${base.y + segment.to.y}`
    }
  return `${path} Z`
}
export const matrix = (transform: Transform) =>
  `translate(${transform.translation.x} ${transform.translation.y}) rotate(${transform.rotation / 1000})`
const transformed = (p: Point, tr: Transform): Point => {
  const a = ((tr.rotation / 1000) * Math.PI) / 180
  return {
    x: Math.round(p.x * Math.cos(a) - p.y * Math.sin(a) + tr.translation.x),
    y: Math.round(p.x * Math.sin(a) + p.y * Math.cos(a) + tr.translation.y),
  }
}
function turnAt(tr: Transform, center: Point): Transform {
  return {
    rotation: (tr.rotation + 90000) % 360000,
    translation: {
      x: center.x + center.y - tr.translation.y,
      y: center.y - center.x + tr.translation.x,
    },
  }
}

export function imageSource(value: string, origin: string): string | null {
  if (value === 'fixture://original-landscape') return '/original-landscape.svg'
  if (value === 'fixture://hoi-an-generated') return '/sao-beach.jpg'
  try {
    if (/%|\\/.test(value) || /(^|\/)\.{1,2}(\/|$)/.test(value)) return null
    const url = new URL(value, origin)
    if (
      url.origin !== origin ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      !/^\/(?!api(?:\/|$))(?:[a-z0-9_-]+\/)*[a-z0-9_.-]+\.(?:png|jpe?g|webp|gif|avif|svg)$/i.test(
        url.pathname,
      )
    )
      return null
    return url.pathname
  } catch {
    return null
  }
}

export function useLiveBoard() {
  const [client] = useState(() => new LiveGame())
  const [state, setState] = useState<LiveGameState>(() => client.getState())
  const stateRef = useRef(state)
  const lastCursor = useRef(-Infinity)
  const cursorPosition = useRef<Point | null>(null)
  const cursorTimer = useRef<number | null>(null)
  const [overlay, setOverlay] = useState(true),
    [reference, setReference] = useState(false)
  const [selected, setSelected] = useState<Group | null>(null),
    [, setAccepted] = useState(false),
    [error, setError] = useState<string | null>(null)
  const [ghost, setGhost] = useState<{ ids: string[]; transform: Transform } | null>(null),
    [dragging, setDragging] = useState(false)
  const [camera, setCamera] = useState({ zoom: 1, x: 0, y: 0 })
  const [summary, setSummary] = useState<DraftSummary | null>(null)
  const [summaryFailure, setSummaryFailure] = useState<string | null>(null)
  const [summaryAttempt, setSummaryAttempt] = useState(0)
  const [imageLoad, setImageLoad] = useState<{ source: string; loaded: boolean } | null>(null)
  const board = useRef<SVGSVGElement>(null),
    pending = useRef<Pending | null>(null)
  const recoveryFailed = useRef(false)
  const pan = useRef<{
    x: number
    y: number
    originX: number
    originY: number
    scaleX: number
    scaleY: number
  } | null>(null)
  const game = state.game
  const proven = client.provenDraft()
  const actions = proven ?? []
  const undoPending = client.pendingUndoActionId() !== null
  const proofRevision = client.provenDraftRevision()
  const turnId = game?.ownTurn?.turnId
  const generation = game?.ownTurn?.generation
  const puzzleId = game?.snapshot.puzzleId
  const summaryKey = JSON.stringify([turnId, generation, puzzleId, proofRevision])
  useEffect(() => {
    if (
      !turnId ||
      generation === undefined ||
      !puzzleId ||
      proofRevision === null ||
      state.connection !== 'ready'
    )
      return
    const controller = new AbortController()
    let active = true
    void fetch(`/api/v1/game/turns/${encodeURIComponent(turnId)}/summary`, {
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
    })
      .then(async (response) => {
        const value: unknown = await response.json()
        if (!response.ok || !validate('DraftSummary', value).valid)
          throw new Error('Invalid draft summary')
        const result = value as DraftSummary
        if (
          result.turnId.toLowerCase() !== turnId.toLowerCase() ||
          result.puzzleId.toLowerCase() !== puzzleId.toLowerCase() ||
          result.generation !== generation ||
          result.draftRevision !== proofRevision
        )
          throw new Error('Stale draft summary')
        const draft = client.provenDraft()
        const chargedIds = new Set(result.chargedActionIds.map((id) => id.toLowerCase()))
        const actionIds = new Set(draft?.map((action) => action.actionId.toLowerCase()))
        const costIds = new Set(result.movementCosts.map((cost) => cost.actionId.toLowerCase()))
        if (
          client.provenDraftRevision() !== proofRevision ||
          !draft ||
          chargedIds.size !== result.pendingMoves ||
          result.chargedActionIds.length !== chargedIds.size ||
          [...chargedIds].some((id) => !actionIds.has(id)) ||
          costIds.size !== actionIds.size ||
          costIds.size !== result.movementCosts.length ||
          [...costIds].some((id) => !actionIds.has(id))
        )
          throw new Error('Invalid draft charges')
        if (active) {
          setSummary(result)
          setSummaryFailure(null)
        }
      })
      .catch(() => {
        if (active) {
          setSummary(null)
          setSummaryFailure(summaryKey)
        }
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [
    client,
    turnId,
    generation,
    puzzleId,
    proofRevision,
    state.connection,
    summaryKey,
    summaryAttempt,
  ])
  const currentSummary =
    turnId &&
    summary?.turnId.toLowerCase() === turnId.toLowerCase() &&
    summary.puzzleId.toLowerCase() === puzzleId?.toLowerCase() &&
    summary.generation === generation &&
    summary.draftRevision === proofRevision
      ? summary
      : null
  const pendingMoves = !turnId ? 0 : (currentSummary?.pendingMoves ?? null)
  const remaining =
    game?.allowance && pendingMoves !== null ? game.allowance.remaining - pendingMoves : null
  const canMove = (group: Group) =>
    canMoveGroup(
      game?.allowance?.remaining ?? null,
      pendingMoves,
      group,
      actions,
      !turnId ? [] : (currentSummary?.movementCosts ?? null),
    )
  const summaryUnavailable = state.connection === 'ready' && summaryFailure === summaryKey
  const previews = [
    ...Object.values(state.previews).flatMap((turn) =>
      turn.closed || turn.turnId.toLowerCase() === state.recoveryView?.turn.turnId.toLowerCase()
        ? []
        : Object.values(turn.actions),
    ),
    ...(state.recoveryView?.actions ?? []),
    ...Object.values(state.peerRecoveryViews).flatMap((view) => view.actions),
  ]
  const previewOwners = new Map<string, string>()
  for (const action of previews)
    for (const id of action.affectedPieceIds) previewOwners.set(id.toLowerCase(), action.actionId)
  const visibleGhost = state.connection === 'ready' && !state.recoveryView ? ghost : null
  const ghostIds = new Set(visibleGhost?.ids.map((id) => id.toLowerCase()) ?? [])
  const previewed = new Set([...previewOwners.keys(), ...ghostIds])
  const pieceMap = new Map(
    (game?.definition.pieces ?? []).map((piece) => [piece.pieceId.toLowerCase(), piece]),
  )
  const paths = (ids: string[]) =>
    ids
      .map((id) => pieceMap.get(id.toLowerCase()))
      .filter((piece): piece is Piece => !!piece)
      .map(outline)
      .join(' ')
  const connected =
    game?.snapshot.groups.reduce(
      (count, group) => count + (group.pieceIds.length > 1 ? group.pieceIds.length : 0),
      0,
    ) ?? 0
  const workspace = game?.definition.workspace,
    play = game?.definition.playSurface,
    image = game?.definition.imageBounds
  const width = workspace ? workspace.max.x - workspace.min.x : 1
  const height = workspace ? workspace.max.y - workspace.min.y : 1
  const visibleWidth = width / camera.zoom,
    visibleHeight = height / camera.zoom
  const centerX = workspace ? (workspace.min.x + workspace.max.x) / 2 + camera.x : 0.5
  const centerY = workspace ? (workspace.min.y + workspace.max.y) / 2 + camera.y : 0.5
  const bounds = `${centerX - visibleWidth / 2} ${centerY - visibleHeight / 2} ${visibleWidth} ${visibleHeight}`
  const source = game ? imageSource(game.definition.image, window.location.origin) : null
  useEffect(() => {
    if (!source) return
    const img = new Image()
    img.onload = () => {
      setImageLoad({ source, loaded: true })
    }
    img.onerror = () => {
      setImageLoad({ source, loaded: false })
    }
    img.src = source
    return () => {
      img.onload = null
      img.onerror = null
    }
  }, [source])
  const supportedImage = source && imageLoad?.source === source && imageLoad.loaded ? source : null
  const imageError =
    game && (!source || (imageLoad?.source === source && !imageLoad.loaded))
      ? t('game.imageUnavailable')
      : null
  const point = (x: number, y: number): Point | null => {
    const m = board.current?.getScreenCTM()
    if (!m) return null
    const p = new DOMPoint(x, y).matrixTransform(m.inverse())
    return { x: p.x, y: p.y }
  }
  const discardPending = useCallback(() => {
    if (pending.current) client.abandonInterruptedPreview()
    pending.current = null
  }, [client])
  const flush = () => {
    const p = pending.current,
      now = client.getState()
    if (!p || !p.moved || now.connection !== 'ready' || now.previewPending) return
    if (!p.acquiring) {
      begin(p)
      return
    }
    if (!now.game?.ownTurn) return
    const recovering = p.waitingForTurn
    if (p.waitingForTurn && !p.openingTurn) {
      if (!p.initialTurn || !sameTurn(now.game.ownTurn, p.initialTurn) || !client.provenDraft())
        return
      p.waitingForTurn = false
      if (!p.interrupted) setGhost({ ids: p.action.affectedPieceIds, transform: p.desired })
    }
    if (p.openingTurn) {
      if (!p.openedTurn) return
      const turn = now.game.ownTurn
      if (!sameTurn(turn, p.openedTurn) || client.provenDraft() === null) {
        discardPending()
        setGhost(null)
        setDragging(false)
        setSelected(null)
        return
      }
      p.waitingForTurn = false
      if (!p.interrupted) setGhost({ ids: p.action.affectedPieceIds, transform: p.desired })
    }
    if (recovering && !p.interrupted && p.finalPrepared) {
      const accepted = client
        .provenDraft()
        ?.find((action) => sameId(action.actionId, p.action.actionId))
      const finalAccepted =
        accepted &&
        sameMembers(accepted.affectedPieceIds, p.action.affectedPieceIds) &&
        accepted.baseRevisions.length === p.action.baseRevisions.length &&
        p.action.baseRevisions.every((base) =>
          accepted.baseRevisions.some(
            (revision) =>
              sameId(base.groupId, revision.groupId) && base.revision === revision.revision,
          ),
        ) &&
        accepted.intendedTransform.rotation === p.action.intendedTransform.rotation &&
        accepted.intendedTransform.translation.x === p.action.intendedTransform.translation.x &&
        accepted.intendedTransform.translation.y === p.action.intendedTransform.translation.y
      if (!finalAccepted && !sameId(client.recoveredPreviewActionId() ?? '', p.action.actionId)) {
        p.action = accepted ?? {
          ...p.action,
          affectedPieceIds: p.group.pieceIds,
          baseRevisions: [{ groupId: p.group.groupId, revision: p.group.revision }],
        }
        p.finalPrepared = false
        p.sent = ''
      }
    }
    if (
      recovering &&
      !p.interrupted &&
      p.sent &&
      !(p.sentFor === 'final' && client.recoveredPreviewActionId() === p.action.actionId)
    ) {
      const accepted = client
        .provenDraft()
        ?.find((action) => action.actionId.toLowerCase() === p.action.actionId.toLowerCase())
      const members = (action: Action) =>
        action.affectedPieceIds
          .map((id) => id.toLowerCase())
          .sort()
          .join(',')
      if (
        accepted?.intendedTransform.rotation !== p.action.intendedTransform.rotation ||
        accepted.intendedTransform.translation.x !== p.action.intendedTransform.translation.x ||
        accepted.intendedTransform.translation.y !== p.action.intendedTransform.translation.y ||
        members(accepted) !== members(p.action)
      )
        p.sent = ''
    }
    if (p.interrupted) {
      if (p.undoStarted && !p.restoring) {
        p.restoring = true
        void client
          .undo(p.action.actionId)
          .then(() => {
            if (pending.current !== p) return
            discardPending()
            setGhost(null)
            setDragging(false)
            setSelected(null)
          })
          .catch((e: unknown) => {
            if (pending.current !== p) return
            p.restoring = false
            if (client.pendingUndoActionId() === null) {
              discardPending()
              setGhost(null)
              setDragging(false)
              setSelected(null)
            }
            setError(String(e))
          })
        return
      }
      const draft = client.provenDraft()
      if (!draft || p.restoring) return
      const current = draft.find(
        (action) => action.actionId.toLowerCase() === p.action.actionId.toLowerCase(),
      )
      if (!current) {
        discardPending()
        setGhost(null)
        setDragging(false)
      } else if (!p.original) {
        p.undoStarted = true
        flush()
      } else if (
        JSON.stringify(current.intendedTransform) !== JSON.stringify(p.original.intendedTransform)
      ) {
        p.restoring = true
        try {
          p.sentFor = 'restore'
          client.preview(p.original, true)
          p.restoring = false
        } catch (e) {
          p.restoring = false
          setError(String(e))
        }
      } else {
        discardPending()
        setGhost(null)
        setDragging(false)
        setSelected(null)
      }
      return
    }
    if (!p.active && !p.finalPrepared) {
      const base = now.turnBase
      const candidates = now.game.snapshot.groups.filter((group) => {
        const original = base?.groups.find((item) => sameId(item.groupId, group.groupId))
        return (
          original &&
          original.revision === group.revision &&
          original.finalPosition === group.finalPosition &&
          sameMembers(original.pieceIds, group.pieceIds) &&
          original.transform.rotation === group.transform.rotation &&
          original.transform.translation.x === group.transform.translation.x &&
          original.transform.translation.y === group.transform.translation.y
        )
      })
      p.action = suggestJoin(
        now.game,
        now.previews,
        p.action,
        p.desired,
        client.provenDraft() ?? [],
        candidates,
      )
      p.finalPrepared = true
    }
    const value = JSON.stringify({ transform: p.desired, members: p.action.affectedPieceIds })
    if (value !== p.sent) {
      p.sent = value
      p.action = { ...p.action, intendedTransform: structuredClone(p.desired) }
      p.sentFor = p.active ? 'active' : 'final'
      setAccepted(false)
      try {
        client.preview(p.action)
      } catch (e) {
        setError(String(e))
      }
      return
    }
    const acceptedByServer = client
      .provenDraft()
      ?.find((action) => action.actionId.toLowerCase() === p.action.actionId.toLowerCase())
    if (!now.issue && acceptedByServer) {
      if (!p.active) {
        setAccepted(true)
        setSelected({
          groupId: acceptedByServer.targetId,
          revision: 0,
          pieceIds: acceptedByServer.affectedPieceIds,
          transform: acceptedByServer.intendedTransform,
          finalPosition: false,
        })
        setGhost(null)
        setDragging(false)
        discardPending()
      }
    }
  }
  const begin = (p: Pending) => {
    if (p.acquiring || client.getState().connection !== 'ready') return
    p.acquiring = true
    void client
      .begin()
      .then((turn) => {
        if (pending.current === p) {
          if (p.openingTurn) p.openedTurn = turn
          if (
            p.openingTurn &&
            client.getState().connection === 'ready' &&
            !client.getState().game?.ownTurn
          ) {
            discardPending()
            setGhost(null)
            setDragging(false)
            setSelected(null)
            return
          }
          flush()
        }
      })
      .catch((e: unknown) => {
        if (pending.current !== p) return
        setError(String(e))
        discardPending()
        setGhost(null)
        setDragging(false)
      })
  }
  const makePending = (group: Group, start: Point): Pending => {
    const currentGame = client.getState().game
    if (!currentGame) throw new Error('Game is not synchronized')
    return {
      group,
      start,
      origin: structuredClone(group.transform),
      desired: structuredClone(group.transform),
      sent: '',
      sentFor: 'active',
      active: true,
      moved: false,
      acquiring: false,
      openingTurn: !currentGame.ownTurn,
      openedTurn: null,
      waitingForTurn: false,
      initialTurn: currentGame.ownTurn,
      puzzleId: currentGame.snapshot.puzzleId,
      finalPrepared: false,
      interrupted: false,
      restoring: false,
      undoStarted: false,
      original:
        actions.find((action) =>
          action.affectedPieceIds.some((id) => group.pieceIds.includes(id)),
        ) ?? null,
      action:
        actions.find((action) =>
          action.affectedPieceIds.some((id) => group.pieceIds.includes(id)),
        ) ??
        client.newAction({
          kind: group.pieceIds.length > 1 ? 'group' : 'piece',
          targetId: group.pieceIds.length > 1 ? group.groupId : group.pieceIds[0],
          affectedPieceIds: group.pieceIds,
          baseRevisions: [{ groupId: group.groupId, revision: group.revision }],
          intendedTransform: group.transform,
        }),
    }
  }
  const down = (event: PointerEvent<SVGElement>, group: Group) => {
    if (
      event.button !== 0 ||
      state.connection !== 'ready' ||
      pending.current ||
      state.pendingConfirm ||
      state.pendingShape ||
      state.unresolvedCommand ||
      recoveryFailed.current ||
      (state.game?.ownTurn && proven === null)
    )
      return
    if (!canMove(group)) {
      if (remaining !== null && remaining > 0) setSelected(group)
      return
    }
    const p = point(event.clientX, event.clientY)
    if (!p) return
    event.preventDefault()
    board.current?.setPointerCapture(event.pointerId)
    pending.current = {
      ...makePending(group, p),
      startScreen: { x: event.clientX, y: event.clientY },
    }
    setSelected(group)
    setError(null)
    setDragging(true)
  }
  const updateDragPosition = (d: Pending, p: Point, x: number, y: number) => {
    const dx = p.x - d.start.x,
      dy = p.y - d.start.y
    if (!d.moved && d.startScreen && Math.hypot(x - d.startScreen.x, y - d.startScreen.y) < 5)
      return false
    d.moved = true
    d.desired = {
      ...d.origin,
      translation: {
        x: Math.round(d.origin.translation.x + dx),
        y: Math.round(d.origin.translation.y + dy),
      },
    }
    if (client.getState().connection === 'ready' && !d.waitingForTurn)
      setGhost({ ids: d.action.affectedPieceIds, transform: d.desired })
    return true
  }
  const move = (event: PointerEvent<SVGSVGElement>) => {
    const cursorPoint = point(event.clientX, event.clientY)
    if (cursorPoint) {
      cursorPosition.current = { x: Math.round(cursorPoint.x), y: Math.round(cursorPoint.y) }
      const now = performance.now()
      const delay = 50 - (now - lastCursor.current)
      if (delay <= 0) {
        if (cursorTimer.current !== null) window.clearTimeout(cursorTimer.current)
        cursorTimer.current = null
        lastCursor.current = now
        client.sendCursor(cursorPosition.current)
      } else {
        cursorTimer.current ??= window.setTimeout(() => {
          cursorTimer.current = null
          if (cursorPosition.current) {
            lastCursor.current = performance.now()
            client.sendCursor(cursorPosition.current)
          }
        }, delay)
      }
    }
    if (pan.current) {
      const start = pan.current
      setCamera((current) => {
        const maxX = (width - width / current.zoom) / 2
        const maxY = (height - height / current.zoom) / 2
        return {
          ...current,
          x: Math.max(
            -maxX,
            Math.min(maxX, start.originX - (event.clientX - start.x) * start.scaleX),
          ),
          y: Math.max(
            -maxY,
            Math.min(maxY, start.originY - (event.clientY - start.y) * start.scaleY),
          ),
        }
      })
      return
    }
    const d = pending.current
    const connection = client.getState().connection
    if (!d?.active || connection === 'disconnected') return
    const p = cursorPoint ?? point(event.clientX, event.clientY)
    if (!p) return
    if (!updateDragPosition(d, p, event.clientX, event.clientY)) return
    setAccepted(false)
    begin(d)
    flush()
  }
  const up = (event?: PointerEvent<SVGSVGElement>) => {
    if (pan.current) {
      pan.current = null
      return
    }
    const d = pending.current
    if (!d) return
    if (event && d.active) {
      const p = point(event.clientX, event.clientY)
      if (p) updateDragPosition(d, p, event.clientX, event.clientY)
    }
    if (!d.moved) {
      discardPending()
      setDragging(false)
      return
    }
    d.active = false
    d.sent = ''
    flush()
  }
  const abort = () => {
    if (pan.current) {
      pan.current = null
      return
    }
    const d = pending.current
    if (!d?.active) return
    setAccepted(false)
    setSelected(null)
    setError(t('probe.interrupted'))
    if (!d.moved || !d.acquiring) {
      discardPending()
      setGhost(null)
      setDragging(false)
      return
    }
    d.active = false
    d.interrupted = true
    client.recoverInterruptedPreview()
    flush()
  }
  const rotate = (group: Group) => {
    if (
      state.connection !== 'ready' ||
      client.getState().connection !== 'ready' ||
      state.pendingConfirm ||
      state.pendingShape ||
      state.unresolvedCommand ||
      recoveryFailed.current ||
      (state.game?.ownTurn && proven === null)
    )
      return
    if (!canMove(group)) return
    const piece = pieceMap.get(group.pieceIds[0].toLowerCase())
    if (!piece) return
    const current = pending.current
    if (current) {
      if (!current.active || current.group.groupId !== group.groupId) return
      const center = transformed(
        {
          x: piece.solvedPosition.x + piece.referencePoint.x,
          y: piece.solvedPosition.y + piece.referencePoint.y,
        },
        current.desired,
      )
      const dx = current.desired.translation.x - current.origin.translation.x,
        dy = current.desired.translation.y - current.origin.translation.y
      current.desired = turnAt(current.desired, center)
      current.origin = {
        rotation: current.desired.rotation,
        translation: {
          x: current.desired.translation.x - dx,
          y: current.desired.translation.y - dy,
        },
      }
      current.moved = true
      setAccepted(false)
      setGhost({ ids: current.action.affectedPieceIds, transform: current.desired })
      begin(current)
      flush()
      return
    }
    const center = transformed(
      {
        x: piece.solvedPosition.x + piece.referencePoint.x,
        y: piece.solvedPosition.y + piece.referencePoint.y,
      },
      group.transform,
    )
    const d = makePending(group, center)
    d.active = false
    d.moved = true
    d.desired = turnAt(group.transform, center)
    pending.current = d
    setSelected(group)
    setError(null)
    setDragging(true)
    setGhost({ ids: d.action.affectedPieceIds, transform: d.desired })
    begin(d)
    flush()
  }
  const flushRef = useRef(flush)
  useEffect(() => {
    flushRef.current = flush
  })
  useEffect(() => {
    try {
      const saved = sessionStorage.getItem('world-jigsaw-pending-confirm')
      if (saved && !client.getState().pendingConfirm)
        client.restorePendingConfirm(JSON.parse(saved) as Confirm)
      sessionStorage.removeItem('world-jigsaw-pending-shape')
    } catch (e) {
      recoveryFailed.current = true
      queueMicrotask(() => {
        setError(String(e))
      })
    }
    const unsubscribe = client.subscribe((next) => {
      const current = pending.current
      const previousTurn = current?.initialTurn
      const nextTurn = next.game?.ownTurn
      if (current && next.connection !== 'ready') {
        setGhost(null)
        setSelected(null)
        current.waitingForTurn = true
        if (next.connection !== 'disconnected') {
          client.recoverInterruptedPreview()
        }
        if (
          next.game?.snapshot.puzzleId.toLowerCase() !== current.puzzleId.toLowerCase() ||
          (current.interrupted &&
            next.connection === 'disconnected' &&
            stateRef.current.connection !== 'ready' &&
            stateRef.current.connection !== 'disconnected') ||
          (!current.interrupted && next.connection === 'disconnected')
        ) {
          discardPending()
          setDragging(false)
        }
      } else if (
        current &&
        (next.game?.snapshot.puzzleId.toLowerCase() !== current.puzzleId.toLowerCase() ||
          (!nextTurn && (!current.openingTurn || current.openedTurn)))
      ) {
        discardPending()
        setGhost(null)
        setDragging(false)
        setSelected(null)
      } else if (current && previousTurn && nextTurn && !sameTurn(previousTurn, nextTurn)) {
        discardPending()
        setGhost(null)
        setDragging(false)
        setSelected(null)
      } else if (
        current &&
        current.waitingForTurn &&
        !current.interrupted &&
        next.game &&
        (!nextTurn || client.provenDraft() !== null) &&
        !pendingStillValid(current, next.game, nextTurn ? (client.provenDraft() ?? []) : [])
      ) {
        discardPending()
        setGhost(null)
        setDragging(false)
        setSelected(null)
      } else if (
        current &&
        current.waitingForTurn &&
        !current.openingTurn &&
        nextTurn &&
        client.provenDraft() === null &&
        (!current.interrupted || client.pendingUndoActionId() === null)
      ) {
        discardPending()
        setGhost(null)
        setDragging(false)
        setSelected(null)
      }
      if (
        pending.current?.openingTurn &&
        pending.current.openedTurn &&
        next.connection === 'ready' &&
        nextTurn
      )
        pending.current.initialTurn = nextTurn
      if (next.connection === 'ready' && stateRef.current.connection !== 'ready')
        client.sendCursor(cursorPosition.current)
      if (next.pendingConfirm)
        sessionStorage.setItem('world-jigsaw-pending-confirm', JSON.stringify(next.pendingConfirm))
      else if (!recoveryFailed.current) sessionStorage.removeItem('world-jigsaw-pending-confirm')
      if (next.connection !== 'ready' || next.issue || next.pendingConfirm) setAccepted(false)
      if (next.connection === 'ready' && !next.issue && !next.previewPending && !pending.current) {
        setAccepted((client.provenDraft()?.length ?? 0) > 0)
      }
      if (next.connection === 'resyncing' && next.issue?.startsWith('Preview rejected:')) {
        const current = pending.current
        if (current?.sentFor === 'active' && !current.interrupted) {
          if (!current.active) current.sent = ''
        } else {
          discardPending()
          setGhost(null)
          setDragging(false)
          setSelected(null)
          setError(next.issue)
        }
      }
      if (next.previewRejection && !stateRef.current.previewRejection) {
        const current = pending.current
        if (current && current.sentFor !== 'active') {
          discardPending()
          setGhost(null)
          setDragging(false)
          setSelected(null)
          setError(t('game.invalidPlacement'))
        }
      }
      if (stateRef.current.pendingConfirm && !next.pendingConfirm && !next.issue)
        setError((current) => (current === t('game.invalidPlacement') ? null : current))
      stateRef.current = next
      setState(next)
      queueMicrotask(() => {
        flushRef.current()
      })
    })
    void client.connect().catch(() => {
      /* adapter publishes errors */
    })
    return () => {
      unsubscribe()
      client.disconnect()
    }
  }, [client, discardPending])
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (cursorPosition.current) client.sendCursor(cursorPosition.current)
    }, 2000)
    return () => {
      window.clearInterval(timer)
      if (cursorTimer.current !== null) window.clearTimeout(cursorTimer.current)
      cursorTimer.current = null
      cursorPosition.current = null
    }
  }, [client])
  useEffect(() => {
    if (!state.game?.ownTurn || state.connection !== 'ready') return
    const timer = window.setInterval(() => {
      try {
        client.heartbeat()
      } catch {
        /* state changes will resynchronize */
      }
    }, 5000)
    return () => {
      window.clearInterval(timer)
    }
  }, [client, state.connection, state.game?.ownTurn])
  const cancel = () => {
    void client
      .cancel()
      .then(() => {
        setError(null)
        discardPending()
        setAccepted(false)
        setGhost(null)
        setDragging(false)
        setSelected(null)
      })
      .catch((e: unknown) => {
        setError(String(e))
      })
  }
  const undo = () => {
    const actionId = client.pendingUndoActionId() ?? client.provenDraft()?.at(-1)?.actionId
    if (actionId) {
      setSelected(null)
      void client
        .undo(actionId)
        .then(() => {
          setError((current) => (current === t('game.invalidPlacement') ? null : current))
        })
        .catch((e: unknown) => {
          setError(String(e))
        })
    }
  }
  const confirm = () => {
    if (recoveryFailed.current) return
    // A retry must also persist the exact payload before it is sent.
    try {
      if (state.pendingConfirm)
        sessionStorage.setItem('world-jigsaw-pending-confirm', JSON.stringify(state.pendingConfirm))
    } catch (e) {
      setError(String(e))
      return
    }
    void client
      .confirm()
      .then((result) => {
        if (result.outcome === 'confirmed' || result.outcome === 'rejected') {
          discardPending()
          setAccepted(false)
          setGhost(null)
          setDragging(false)
          setSelected(null)
        }
      })
      .catch((e: unknown) => {
        setError(String(e))
      })
  }
  return {
    state,
    client,
    game,
    actions,
    undoPending,
    pendingMoves,
    remaining,
    canMove,
    summaryUnavailable,
    retrySummary: () => {
      setSummaryFailure(null)
      setSummaryAttempt((attempt) => attempt + 1)
    },
    proven,
    selected,
    setSelected,
    error,
    setError,
    overlay,
    setOverlay,
    reference,
    setReference,
    ghost: visibleGhost,
    dragging,
    board,
    pending,
    pan,
    camera,
    setCamera,
    width,
    height,
    bounds,
    previews,
    previewOwners,
    ghostIds,
    previewed,
    pieceMap,
    paths,
    connected,
    workspace,
    play,
    image,
    supportedImage,
    imageError,
    point,
    down,
    move,
    leave: () => {
      if (cursorTimer.current !== null) window.clearTimeout(cursorTimer.current)
      cursorTimer.current = null
      cursorPosition.current = null
      lastCursor.current = -Infinity
      client.sendCursor(null)
    },
    up,
    abort,
    rotate,
    cancel,
    undo,
    confirm,
  }
}
