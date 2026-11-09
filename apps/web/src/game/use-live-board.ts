/* eslint-disable @typescript-eslint/restrict-template-expressions */
import { useEffect, useRef, useState, type PointerEvent } from 'react'
import { LiveGame, type LiveGameState } from '../game/live-game.ts'
import { t } from '../i18n/translate.ts'
import { suggestJoin } from '../game/join.ts'
import { validate } from '@world-jigsaw/contracts'
import type {
  Action,
  Confirm,
  DraftSummary,
  Group,
  Piece,
  ShapeChange,
  Transform,
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
  finalPrepared: boolean
  interrupted: boolean
  restoring: boolean
  undoStarted: boolean
  original: Action | null
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
  if (value === 'fixture://hoi-an-generated') return '/hoi-an-generated.png'
  try {
    if (/%|\\/.test(value) || /(^|\/)\.{1,2}(\/|$)/.test(value)) return null
    const url = new URL(value, origin)
    if (url.origin !== origin || url.username || url.password || url.search || url.hash ||
      !/^\/(?!api(?:\/|$))(?:[a-z0-9_-]+\/)*[a-z0-9_.-]+\.(?:png|jpe?g|webp|gif|avif|svg)$/i.test(url.pathname))
      return null
    return url.pathname
  } catch {
    return null
  }
}

export function useLiveBoard() {
  const [client] = useState(() => new LiveGame())
  const [state, setState] = useState<LiveGameState>(() => client.getState())
  const [overlay, setOverlay] = useState(true),
    [reference, setReference] = useState(false)
  const [shapeOpen, setShapeOpen] = useState(false)
  const [selected, setSelected] = useState<Group | null>(null),
    [, setAccepted] = useState(false),
    [error, setError] = useState<string | null>(null)
  const [ghost, setGhost] = useState<{ ids: string[]; transform: Transform } | null>(null),
    [dragging, setDragging] = useState(false)
  const [camera, setCamera] = useState({ zoom: 1, x: 0, y: 0 })
  const [summary, setSummary] = useState<DraftSummary | null>(null)
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
        if (active) setSummary(result)
      })
      .catch(() => {
        if (active) setSummary(null)
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [turnId, generation, puzzleId, proofRevision, state.connection])
  const pendingMoves = !turnId
    ? 0
    : summary?.turnId.toLowerCase() === turnId.toLowerCase() &&
        summary.generation === generation &&
        summary.draftRevision === proofRevision
      ? summary.pendingMoves
      : null
  const previews = Object.values(state.previews).flatMap((turn) =>
    turn.closed ? [] : Object.values(turn.actions),
  )
  const previewOwners = new Map<string, string>()
  for (const action of previews)
    for (const id of action.affectedPieceIds) previewOwners.set(id.toLowerCase(), action.actionId)
  const ghostIds = new Set(ghost?.ids.map((id) => id.toLowerCase()) ?? [])
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
    img.onload = () => { setImageLoad({ source, loaded: true }) }
    img.onerror = () => { setImageLoad({ source, loaded: false }) }
    img.src = source
    return () => { img.onload = null; img.onerror = null }
  }, [source])
  const supportedImage = source && imageLoad?.source === source && imageLoad.loaded ? source : null
  const imageError = game && (!source || (imageLoad?.source === source && !imageLoad.loaded))
    ? t('game.imageUnavailable') : null
  const point = (x: number, y: number): Point | null => {
    const m = board.current?.getScreenCTM()
    if (!m) return null
    const p = new DOMPoint(x, y).matrixTransform(m.inverse())
    return { x: p.x, y: p.y }
  }
  const flush = () => {
    const p = pending.current,
      now = client.getState()
    if (!p || !p.moved || now.connection !== 'ready' || !now.game?.ownTurn || now.previewPending)
      return
    if (p.interrupted) {
      if (p.undoStarted && !p.restoring) {
        p.restoring = true
        void client.undo(p.action.actionId)
          .then(() => {
            pending.current = null
            setGhost(null)
            setDragging(false)
            setSelected(null)
          })
          .catch((e: unknown) => {
            p.restoring = false
            setError(String(e))
          })
        return
      }
      const draft = client.provenDraft()
      if (!draft || p.restoring) return
      const current = draft.find((action) => action.actionId === p.action.actionId)
      if (!current) {
        pending.current = null
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
          client.preview(p.original)
          p.restoring = false
        } catch (e) {
          p.restoring = false
          setError(String(e))
        }
      } else {
        pending.current = null
        setGhost(null)
        setDragging(false)
        setSelected(null)
      }
      return
    }
    if (!p.active && !p.finalPrepared) {
      p.action = suggestJoin(
        now.game,
        now.previews,
        p.action,
        p.desired,
        client.provenDraft() ?? [],
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
      ?.find((action) => action.actionId === p.action.actionId)
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
        pending.current = null
      }
    }
  }
  const begin = (p: Pending) => {
    if (p.acquiring) return
    p.acquiring = true
    void client
      .begin()
      .then(() => {
        flush()
      })
      .catch((e: unknown) => {
        setError(String(e))
        pending.current = null
        setGhost(null)
        setDragging(false)
      })
  }
  const makePending = (group: Group, start: Point): Pending => ({
    group,
    start,
    origin: structuredClone(group.transform),
    desired: structuredClone(group.transform),
    sent: '',
    sentFor: 'active',
    active: true,
    moved: false,
    acquiring: false,
    finalPrepared: false,
    interrupted: false,
    restoring: false,
    undoStarted: false,
    original:
      actions.find((action) => action.affectedPieceIds.some((id) => group.pieceIds.includes(id))) ?? null,
    action:
      actions.find((action) => action.affectedPieceIds.some((id) => group.pieceIds.includes(id))) ??
      client.newAction({
        kind: group.pieceIds.length > 1 ? 'group' : 'piece',
        targetId: group.pieceIds.length > 1 ? group.groupId : group.pieceIds[0],
        affectedPieceIds: group.pieceIds,
        baseRevisions: [{ groupId: group.groupId, revision: group.revision }],
        intendedTransform: group.transform,
      }),
  })
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
  const move = (event: PointerEvent<SVGSVGElement>) => {
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
    if (!d?.active) return
    const p = point(event.clientX, event.clientY)
    if (!p) return
    const dx = p.x - d.start.x,
      dy = p.y - d.start.y
    if (
      !d.moved &&
      d.startScreen &&
      Math.hypot(event.clientX - d.startScreen.x, event.clientY - d.startScreen.y) < 5
    )
      return
    d.moved = true
    setAccepted(false)
    d.desired = {
      ...d.origin,
      translation: {
        x: Math.round(d.origin.translation.x + dx),
        y: Math.round(d.origin.translation.y + dy),
      },
    }
    setGhost({ ids: d.action.affectedPieceIds, transform: d.desired })
    begin(d)
    flush()
  }
  const up = () => {
    if (pan.current) {
      pan.current = null
      return
    }
    const d = pending.current
    if (!d) return
    if (!d.moved) {
      pending.current = null
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
    if (!d) return
    setAccepted(false)
    setSelected(null)
    setError(t('probe.interrupted'))
    if (!d.moved) {
      pending.current = null
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
      state.pendingConfirm ||
      state.pendingShape ||
      state.unresolvedCommand ||
      recoveryFailed.current ||
      (state.game?.ownTurn && proven === null)
    )
      return
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
      const savedShape = sessionStorage.getItem('world-jigsaw-pending-shape')
      if (savedShape && !client.getState().pendingShape)
        client.restorePendingShape(JSON.parse(savedShape) as ShapeChange)
    } catch (e) {
      recoveryFailed.current = true
      queueMicrotask(() => {
        setError(String(e))
      })
    }
    const unsubscribe = client.subscribe((next) => {
      if (next.pendingConfirm)
        sessionStorage.setItem('world-jigsaw-pending-confirm', JSON.stringify(next.pendingConfirm))
      else if (!recoveryFailed.current) sessionStorage.removeItem('world-jigsaw-pending-confirm')
      if (next.pendingShape)
        sessionStorage.setItem('world-jigsaw-pending-shape', JSON.stringify(next.pendingShape))
      else if (!recoveryFailed.current) sessionStorage.removeItem('world-jigsaw-pending-shape')
      if (next.connection !== 'ready' || next.issue || next.pendingConfirm) setAccepted(false)
      if (next.connection === 'ready' && !next.issue && !next.previewPending && !pending.current) {
        setAccepted((client.provenDraft()?.length ?? 0) > 0)
      }
      if (next.connection === 'resyncing' && next.issue?.startsWith('Preview rejected:')) {
        const current = pending.current
        if (current && current.sentFor === 'active' && !current.interrupted) {
          if (!current.active) current.sent = ''
        } else {
          pending.current = null
          setGhost(null)
          setDragging(false)
          setSelected(null)
          setError(next.issue)
        }
      }
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
        pending.current = null
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
      void client.undo(actionId).catch((e: unknown) => {
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
          pending.current = null
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
  const changeShape = (
    values?: Pick<ShapeChange, 'tabDepthPermille' | 'tabWidthPermille' | 'edgeWavePermille'>,
  ) => {
    if (recoveryFailed.current) return
    try {
      if (state.pendingShape)
        sessionStorage.setItem('world-jigsaw-pending-shape', JSON.stringify(state.pendingShape))
    } catch (e) {
      setError(String(e))
      return
    }
    setError(null)
    void client.changeShape(values).catch((e: unknown) => {
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
    proven,
    selected,
    setSelected,
    error,
    setError,
    overlay,
    setOverlay,
    reference,
    setReference,
    shapeOpen,
    setShapeOpen,
    ghost,
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
    up,
    abort,
    rotate,
    cancel,
    undo,
    confirm,
    changeShape,
  }
}
