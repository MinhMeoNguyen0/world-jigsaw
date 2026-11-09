/* SVG coordinates are numeric by design. */
import { useEffect, useRef, useState } from 'react'
import type { Rect } from '@world-jigsaw/contracts'
import { ChatPanel } from '../chat/ChatPanel.tsx'
import { PlayerStrip } from '../game/PlayerStrip.tsx'
import { ShapePanel } from '../game/ShapePanel.tsx'
import { matrix, useLiveBoard } from '../game/use-live-board.ts'
import { t } from '../i18n/translate.ts'
import './HomePage.css'

// The reviewed page's movable pill stays within the visible viewport.
const clampPill = (position: { x: number; y: number }) => ({
  x: Math.max(0, Math.min(window.innerWidth - 250, position.x)),
  y: Math.max(0, Math.min(window.innerHeight - 60, position.y)),
})

// Like the reviewed GroupArtwork, clip the whole image once for a connected group.
function Artwork({
  id,
  ids,
  path,
  image,
  bounds,
}: {
  id: string
  ids: string[]
  path: string
  image: string | null
  bounds: Rect | undefined
}) {
  const clipId = `joined-${id}`
  return (
    <>
      {ids.length > 0 && image && bounds && (
        <>
          <defs>
            <clipPath id={clipId}>
              <path d={path} />
            </clipPath>
          </defs>
          <image
            href={image}
            x={bounds.min.x}
            y={bounds.min.y}
            width={bounds.max.x - bounds.min.x}
            height={bounds.max.y - bounds.min.y}
            preserveAspectRatio="xMidYMid slice"
            clipPath={`url(#${clipId})`}
            pointerEvents="none"
          />
        </>
      )}
      <path
        d={path}
        fill={image && bounds ? 'transparent' : '#999'}
        stroke={ids.length > 1 ? 'none' : '#d0c8b9'}
        strokeWidth="1200"
        strokeLinejoin="round"
      />
    </>
  )
}

export function HomePage() {
  const {
    state,
    client,
    game,
    actions,
    pendingMoves,
    proven,
    selected,
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
    pan: panRef,
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
    down,
    move,
    up,
    abort,
    rotate,
    cancel,
    undo,
    confirm,
    changeShape,
  } = useLiveBoard()
  const [time, setTime] = useState('—')
  const [pillPosition, setPillPosition] = useState(() => ({
    x: Math.max(0, window.innerWidth / 2 - 125),
    y: Math.max(55, window.innerHeight - (window.innerHeight <= 500 ? 44 : 53) - 70),
  }))
  const pillDrag = useRef<{ x: number; y: number; left: number; top: number } | null>(null)
  const pillMoved = useRef(false)
  const zoomFocus = useRef<string | null>(null)
  const serverTime = game?.serverTime,
    endsAt = game?.endsAt
  useEffect(() => {
    if (!serverTime || !endsAt) return
    const offset = Date.parse(serverTime) - Date.now()
    const update = () => {
      const seconds = Math.max(0, Math.floor((Date.parse(endsAt) - Date.now() - offset) / 1000))
      setTime(
        [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
          .map((n) => String(n).padStart(2, '0'))
          .join(':'),
      )
    }
    const timer = window.setInterval(update, 1000)
    return () => {
      window.clearInterval(timer)
    }
  }, [serverTime, endsAt])
  useEffect(() => {
    const resize = () => {
      setPillPosition((position) =>
        pillMoved.current
          ? clampPill(position)
          : clampPill({
              x: window.innerWidth / 2 - 125,
              y: window.innerHeight - (window.innerHeight <= 500 ? 44 : 53) - 70,
            }),
      )
      setCamera({ zoom: 1, x: 0, y: 0 })
      zoomFocus.current = null
    }
    window.addEventListener('resize', resize)
    return () => {
      window.removeEventListener('resize', resize)
    }
  }, [setCamera])
  const constrainCamera = (zoom: number, x: number, y: number) => {
    const maxX = Math.max(0, (width - width / zoom) / 2),
      maxY = Math.max(0, (height - height / zoom) / 2)
    return { zoom, x: Math.max(-maxX, Math.min(maxX, x)), y: Math.max(-maxY, Math.min(maxY, y)) }
  }
  const zoomCanvas = (factor: number) => {
    let focusId = selected?.pieceIds[0] ?? zoomFocus.current
    if (!focusId && factor > 1 && camera.zoom === 1 && game && workspace) {
      let nearest = Number.POSITIVE_INFINITY
      for (const group of game.snapshot.groups) {
        const piece = pieceMap.get(group.pieceIds[0].toLowerCase())
        if (!piece) continue
        const x = piece.solvedPosition.x + piece.referencePoint.x
        const y = piece.solvedPosition.y + piece.referencePoint.y
        const angle = (group.transform.rotation * Math.PI) / 180000
        const dx =
          Math.cos(angle) * x -
          Math.sin(angle) * y +
          group.transform.translation.x -
          (workspace.min.x + workspace.max.x) / 2
        const dy =
          Math.sin(angle) * x +
          Math.cos(angle) * y +
          group.transform.translation.y -
          (workspace.min.y + workspace.max.y) / 2
        const distance = Math.hypot(dx, dy)
        if (distance < nearest) {
          nearest = distance
          focusId = piece.pieceId
        }
      }
    }
    const piece = focusId ? pieceMap.get(focusId.toLowerCase()) : null
    const group = focusId ? game?.snapshot.groups.find((g) => g.pieceIds.includes(focusId)) : null
    const transform = selected?.transform ?? group?.transform
    if (factor > 1 && focusId) zoomFocus.current = focusId
    setCamera((current) => {
      const zoom = Math.max(1, Math.min(6, current.zoom * factor))
      if (factor > 1 && piece && transform && workspace) {
        const x = piece.solvedPosition.x + piece.referencePoint.x,
          y = piece.solvedPosition.y + piece.referencePoint.y
        const angle = (transform.rotation * Math.PI) / 180000
        return constrainCamera(
          zoom,
          Math.cos(angle) * x -
            Math.sin(angle) * y +
            transform.translation.x -
            (workspace.min.x + workspace.max.x) / 2,
          Math.sin(angle) * x +
            Math.cos(angle) * y +
            transform.translation.y -
            (workspace.min.y + workspace.max.y) / 2,
        )
      }
      return constrainCamera(zoom, current.x, current.y)
    })
  }
  const committedRemaining = game?.allowance?.remaining ?? null
  const remaining =
    committedRemaining === null || pendingMoves === null ? null : committedRemaining - pendingMoves
  return (
    <main
      className="app"
      data-connection={state.connection}
      data-preview-pending={String(state.previewPending)}
      data-accepted={String(actions.length > 0 && !state.previewPending)}
      onContextMenu={(event) => {
        if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement)
          return
        event.preventDefault()
        if (pending.current?.active) rotate(pending.current.group)
      }}
    >
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">{t('symbol.brand')}</span>
          <div>
            <h1>{t('app.name')}</h1>
            <span>
              {t(
                game?.definition.image === 'fixture://hoi-an-generated'
                  ? 'game.generatedCaption'
                  : 'live.local',
              )}
            </span>
          </div>
        </div>
        <div className="top-right">
          <span className="piece-tally" data-testid="live-tally">
            {t('live.groups', { count: game?.snapshot.groups.length ?? 0 })}
          </span>
          <span
            className="progress-count"
            aria-label={t('game.progressLabel', {
              count: connected,
              total: game?.definition.pieces.length ?? 0,
            })}
          >
            <span aria-hidden="true">
              {t('live.progress', { count: connected, total: game?.definition.pieces.length ?? 0 })}
            </span>
          </span>
          <span className="timer">{time}</span>
          <button
            className="overlay-toggle"
            type="button"
            aria-pressed={overlay}
            onClick={() => {
              setOverlay(!overlay)
            }}
          >
            <span aria-hidden="true">{t('symbol.overlay')}</span>
            {t(overlay ? 'home.pictureOn' : 'home.pictureOff')}
          </button>
          <button
            className="header-action"
            type="button"
            aria-label={t(reference ? 'home.hideReference' : 'home.showReference')}
            aria-pressed={reference}
            onClick={() => {
              setReference(!reference)
            }}
          >
            {t('home.view')}
          </button>
          <button
            className="header-action shape-button"
            type="button"
            aria-label={t('home.shapeSettings')}
            aria-expanded={shapeOpen}
            onClick={() => {
              setShapeOpen(!shapeOpen)
            }}
          >
            {t('home.shapeShort')}
          </button>
        </div>
      </header>
      {shapeOpen && game && (
        <ShapePanel
          key={`${game.snapshot.puzzleId}:${String(game.definition.definitionVersion)}`}
          shape={game.definition.shape}
          disabled={
            state.connection !== 'ready' ||
            !!game.ownTurn ||
            !!state.pendingConfirm ||
            !!state.pendingShape ||
            !!state.unresolvedCommand
          }
          apply={changeShape}
        />
      )}
      <section className="workspace">
        <div className="board-wrap">
          <svg
            ref={board}
            className={camera.zoom > 1 ? 'board is-zoomed' : 'board'}
            viewBox={bounds}
            role="img"
            aria-label={t('live.board')}
            aria-describedby="canvas-help"
            onPointerDown={(event) => {
              if (event.button !== 0 || event.target !== event.currentTarget) return
              if (event.pointerType === 'touch') {
                let nearest: { group: NonNullable<typeof selected>; distance: number } | null = null
                for (const element of event.currentTarget.querySelectorAll<SVGGElement>(
                  '[data-group-id], [data-preview-action]',
                )) {
                  const rect = element.querySelector(':scope > path')?.getBoundingClientRect()
                  if (!rect) continue
                  if (!rect.width || !rect.height) continue
                  const distance = Math.hypot(
                    Math.max(rect.left - event.clientX, 0, event.clientX - rect.right),
                    Math.max(rect.top - event.clientY, 0, event.clientY - rect.bottom),
                  )
                  if (distance > 22 || (nearest && distance >= nearest.distance)) continue
                  const action = actions.find(
                    (item) => item.actionId === element.dataset.previewAction,
                  )
                  const group = action
                    ? {
                        groupId: action.targetId,
                        revision: 0,
                        pieceIds: action.affectedPieceIds,
                        transform: action.intendedTransform,
                        finalPosition: false,
                      }
                    : game?.snapshot.groups.find(
                        (item) =>
                          item.groupId === element.dataset.groupId && !!element.dataset.pieceIds,
                      )
                  if (group) nearest = { group, distance }
                }
                if (nearest) {
                  down(event, nearest.group)
                  return
                }
              }
              if (camera.zoom <= 1) return
              const m = board.current?.getScreenCTM()
              if (!m?.a || !m.d) return
              event.currentTarget.setPointerCapture(event.pointerId)
              panRef.current = {
                x: event.clientX,
                y: event.clientY,
                originX: camera.x,
                originY: camera.y,
                scaleX: 1 / m.a,
                scaleY: 1 / m.d,
              }
            }}
            onPointerMove={move}
            onPointerUp={up}
            onPointerCancel={abort}
            onContextMenu={(event) => {
              event.preventDefault()
              if (pending.current?.active) rotate(pending.current.group)
            }}
          >
            {play && (
              <rect
                x={play.min.x}
                y={play.min.y}
                width={play.max.x - play.min.x}
                height={play.max.y - play.min.y}
                rx="16000"
                fill="#d0c8b9"
                stroke="#b8ae9d"
                strokeWidth="3000"
                pointerEvents="none"
              />
            )}
            {image && supportedImage && (
              <image
                data-picture-overlay="true"
                href={supportedImage}
                x={image.min.x}
                y={image.min.y}
                width={image.max.x - image.min.x}
                height={image.max.y - image.min.y}
                preserveAspectRatio="xMidYMid slice"
                opacity={overlay ? '.12' : '0'}
                pointerEvents="none"
              />
            )}
            {game?.snapshot.groups.map((group) => {
              const ids = group.pieceIds.filter((id) => !previewed.has(id.toLowerCase()))
              return (
                <g
                  key={group.groupId}
                  className="board-piece"
                  data-group-id={group.groupId}
                  data-piece-ids={ids.join(',')}
                  transform={matrix(group.transform)}
                  onPointerDown={(event) => {
                    down(event, group)
                  }}
                  onContextMenu={(event) => {
                    event.preventDefault()
                    event.stopPropagation()
                    rotate(group)
                  }}
                >
                  <Artwork
                    id={`board-${group.groupId}`}
                    ids={ids}
                    path={paths(ids)}
                    image={supportedImage}
                    bounds={image}
                  />
                </g>
              )
            })}
            {previews.map((action) => {
              const ids = action.affectedPieceIds.filter(
                (id) =>
                  !ghostIds.has(id.toLowerCase()) &&
                  previewOwners.get(id.toLowerCase()) === action.actionId,
              )
              const own = actions.find((item) => item.actionId === action.actionId)
              const group = own
                ? {
                    groupId: own.targetId,
                    revision: 0,
                    pieceIds: own.affectedPieceIds,
                    transform: action.intendedTransform,
                    finalPosition: false,
                  }
                : null
              return ids.length ? (
                <g
                  key={action.actionId}
                  className="board-piece"
                  data-preview-action={action.actionId}
                  data-preview-piece-ids={ids.join(',')}
                  transform={matrix(action.intendedTransform)}
                  onPointerDown={(event) => {
                    if (group) down(event, group)
                  }}
                  onContextMenu={(event) => {
                    event.preventDefault()
                    event.stopPropagation()
                    if (group) rotate(group)
                    else if (pending.current?.active) rotate(pending.current.group)
                  }}
                >
                  <Artwork
                    id={`preview-${action.actionId}`}
                    ids={ids}
                    path={paths(ids)}
                    image={supportedImage}
                    bounds={image}
                  />
                </g>
              ) : null
            })}
            {ghost && (
              <g
                className="selection-ghost"
                data-local-ghost="true"
                transform={matrix(ghost.transform)}
                onContextMenu={(event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  if (pending.current?.active) rotate(pending.current.group)
                }}
              >
                <Artwork
                  id="ghost"
                  ids={ghost.ids}
                  path={paths(ghost.ids)}
                  image={supportedImage}
                  bounds={image}
                />
              </g>
            )}
          </svg>
          {reference && supportedImage && (
            <div className="reference-card">
              <img
                src={supportedImage}
                alt={t(
                  game?.definition.image === 'fixture://hoi-an-generated'
                    ? 'game.generatedAlt'
                    : 'live.fixtureAlt',
                )}
              />
              <span>
                {t(
                  game?.definition.image === 'fixture://hoi-an-generated'
                    ? 'game.generatedCaption'
                    : 'live.fixture',
                )}
              </span>
            </div>
          )}
        </div>
        <div className="feedback" aria-live="polite" role="status">
          {error ??
            state.issue ??
            t(state.connection === 'ready' ? 'probe.ready' : 'live.connecting')}
        </div>
      </section>
      <p id="canvas-help" className="visually-hidden">
        {t('home.canvasHelp')}
      </p>
      {state.connection === 'disconnected' && (
        <button
          className="reconnect"
          type="button"
          onClick={() => {
            setError(null)
            void client.connect().catch(() => {
              /* adapter publishes connection errors */
            })
          }}
        >
          {t('probe.reconnect')}
        </button>
      )}
      <div className="canvas-controls" aria-label={t('home.canvasControls')}>
        <button
          type="button"
          onClick={() => {
            if (selected) rotate(selected)
          }}
          disabled={
            !selected || state.connection !== 'ready' || (!!game?.ownTurn && proven === null)
          }
          aria-label={t('home.rotate')}
          title={t('home.rotate')}
        >
          {t('symbol.rotate')}
        </button>
        <button
          type="button"
          onClick={() => {
            zoomCanvas(1.6)
          }}
          disabled={camera.zoom >= 6}
          aria-label={t('home.zoomIn')}
          title={t('home.zoomIn')}
        >
          {t('symbol.zoomIn')}
        </button>
        <button
          type="button"
          onClick={() => {
            zoomCanvas(1 / 1.6)
          }}
          disabled={camera.zoom <= 1}
          aria-label={t('home.zoomOut')}
          title={t('home.zoomOut')}
        >
          {t('symbol.zoomOut')}
        </button>
        <button
          type="button"
          onClick={() => {
            zoomFocus.current = null
            setCamera({ zoom: 1, x: 0, y: 0 })
          }}
          disabled={camera.zoom === 1}
          aria-label={t('home.fitCanvas')}
          title={t('home.fitCanvas')}
        >
          {t('symbol.fitCanvas')}
        </button>
      </div>
      <div className="move-pill" style={{ left: pillPosition.x, top: pillPosition.y }}>
        <button
          className="pill-handle"
          aria-label={t('home.movePill')}
          onPointerDown={(event) => {
            event.stopPropagation()
            event.currentTarget.setPointerCapture(event.pointerId)
            pillDrag.current = {
              x: event.clientX,
              y: event.clientY,
              left: pillPosition.x,
              top: pillPosition.y,
            }
          }}
          onPointerMove={(event) => {
            if (!pillDrag.current) return
            event.stopPropagation()
            pillMoved.current = true
            const origin = pillDrag.current
            setPillPosition(
              clampPill({
                x: origin.left + event.clientX - origin.x,
                y: origin.top + event.clientY - origin.y,
              }),
            )
          }}
          onPointerUp={(event) => {
            event.stopPropagation()
            pillDrag.current = null
          }}
          onPointerCancel={() => {
            pillDrag.current = null
          }}
        >
          {t('symbol.grip')}
        </button>
        <div className="pill-count">
          <strong>{t('home.remaining', { count: remaining ?? '—' })}</strong>
          <div className="dots">
            {Array.from({ length: 5 }, (_, i) => (
              <i key={i} className={remaining !== null && i < remaining ? 'available' : ''} />
            ))}
          </div>
          <span>{t('game.pending', { count: pendingMoves ?? '—' })}</span>
          {game?.ownTurn && (
            <span>{t('game.savedAllowance', { count: committedRemaining ?? '—' })}</span>
          )}
        </div>
        <button
          className="pill-action"
          disabled={!game?.ownTurn || state.connection !== 'ready'}
          onClick={cancel}
          aria-label={t('game.cancel')}
        >
          {t('symbol.close')}
        </button>
        <button
          className="pill-action"
          disabled={!actions.length || dragging || state.previewPending}
          onClick={undo}
          aria-label={t('home.undo')}
        >
          {t('symbol.undo')}
        </button>
        <button
          className="pill-confirm"
          disabled={
            dragging ||
            !actions.length ||
            state.previewPending ||
            state.connection !== 'ready' ||
            !!state.issue ||
            !!state.pendingConfirm ||
            !!state.unresolvedCommand ||
            !game?.ownTurn
          }
          onClick={confirm}
          aria-label={t('home.confirm')}
        >
          {t('symbol.confirm')}
        </button>
      </div>
      {state.pendingShape && (
        <div className="recovery-controls">
          <button
            type="button"
            onClick={() => {
              changeShape()
            }}
          >
            {t('game.retryShape')}
          </button>
        </div>
      )}
      {state.pendingConfirm && (
        <div className="recovery-controls">
          <button type="button" onClick={confirm}>
            {t('game.confirmRetry')}
          </button>
          <button
            type="button"
            onClick={() => {
              void client.resolveConfirm().catch((e: unknown) => {
                setError(String(e))
              })
            }}
          >
            {t('game.resolve')}
          </button>
        </div>
      )}
      {state.unresolvedCommand && (
        <div className="recovery-controls">
          <button
            type="button"
            onClick={() => {
              void client.resolveCancelCommand().catch((e: unknown) => {
                setError(String(e))
              })
            }}
          >
            {t('game.resolve')}
          </button>
        </div>
      )}
      <ChatPanel
        ready={state.connection === 'ready'}
        presence={
          <PlayerStrip puzzleId={game?.snapshot.puzzleId} ready={state.connection === 'ready'} />
        }
      />
    </main>
  )
}
