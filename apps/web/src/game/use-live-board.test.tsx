import { createElement, type PointerEvent } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import type { Action, CurrentGame, Group } from '@world-jigsaw/contracts'
import { useLiveBoard } from './use-live-board'
import type { LiveGameState } from './live-game'
import * as moveAvailability from './move-availability'

const mock = vi.hoisted(() => ({
  state: {},
  actions: [] as Action[],
  sendCursor: vi.fn(),
  recover: vi.fn(),
  preview: vi.fn(),
  effects: [] as (() => void | (() => void))[],
  listener: null as ((state: LiveGameState) => void) | null,
}))
vi.mock('react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react')>()),
  useEffect: (effect: () => void | (() => void)) => {
    mock.effects.push(effect)
  },
}))
vi.mock('./live-game.ts', () => ({
  LiveGame: class {
    getState() {
      return mock.state
    }
    provenDraft() {
      return mock.actions
    }
    provenDraftRevision() {
      return 0
    }
    pendingUndoActionId() {
      return null
    }
    newAction(action: object) {
      return { ...action, actionId: 'action' }
    }
    sendCursor = mock.sendCursor
    recoverInterruptedPreview = mock.recover
    preview = mock.preview
    subscribe(listener: (state: LiveGameState) => void) {
      mock.listener = listener
      listener(mock.state as LiveGameState)
      return () => {
        mock.listener = null
      }
    }
    connect() {
      return Promise.resolve()
    }
    disconnect() {}
    begin() {
      return Promise.resolve(null)
    }
  },
}))

function setup(override?: Partial<CurrentGame>, stateOverride?: Partial<LiveGameState>) {
  const game: CurrentGame = {
    schemaVersion: 1,
    day: '2026-11-08',
    serverTime: '2026-11-08T12:00:00Z',
    endsAt: '2026-11-09T00:00:00Z',
    definition: fixture.definition as CurrentGame['definition'],
    snapshot: fixture.snapshot as CurrentGame['snapshot'],
    allowance: { limit: 5, used: 0, remaining: 5 },
    ownTurn: null,
    ...override,
  }
  mock.state = { connection: 'ready', game, previews: {}, cursors: {}, ...stateOverride }
  vi.stubGlobal('window', {
    location: { origin: 'http://127.0.0.1:5173' },
    setTimeout,
    clearTimeout,
  })
  vi.stubGlobal(
    'DOMPoint',
    class {
      private x: number
      private y: number
      constructor(x: number, y: number) {
        this.x = x
        this.y = y
      }
      matrixTransform() {
        return { x: this.x, y: this.y }
      }
    },
  )
  let result: ReturnType<typeof useLiveBoard> | undefined
  function Capture() {
    result = useLiveBoard()
    return null
  }
  renderToStaticMarkup(createElement(Capture))
  if (!result) throw new Error('Hook did not render')
  const board = result
  const matrix = { inverse: () => ({}) }
  const getScreenCTM = vi.fn(() => matrix as unknown as DOMMatrix)
  const setPointerCapture = vi.fn()
  board.board.current = { getScreenCTM, setPointerCapture } as unknown as SVGSVGElement
  return { board, getScreenCTM, matrix, group: game.snapshot.groups[0] }
}

const pointer = (x: number, y: number) =>
  ({
    button: 0,
    pointerId: 1,
    clientX: x,
    clientY: y,
    preventDefault: vi.fn(),
  }) as unknown as PointerEvent<SVGSVGElement>

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  mock.actions = []
  mock.preview.mockClear()
  mock.sendCursor.mockClear()
  mock.recover.mockClear()
  mock.effects = []
  mock.listener = null
})

describe('three-piece group interaction', () => {
  const joined = fixture.mergedSnapshot.groups[0] as Group
  const source = fixture.snapshot.groups
  const turn = {
    puzzleId: fixture.snapshot.puzzleId,
    turnId: fixture.preview.turnId,
    generation: 1,
  } as CurrentGame['ownTurn']
  const action: Action = {
    ...fixture.preview.action,
    targetId: joined.groupId,
    affectedPieceIds: joined.pieceIds,
    baseRevisions: source.map(({ groupId, revision }) => ({ groupId, revision })),
    intendedTransform: joined.transform,
  } as Action

  it('renders the tentative joined appearance while refusing pointer edits', () => {
    const { board } = setup(
      { ownTurn: turn },
      {
        connection: 'disconnected',
        recoveryView: {
          snapshot: fixture.snapshot as CurrentGame['snapshot'],
          turn: turn!,
          actions: [action],
        },
      },
    )
    expect(board.previews).toEqual([action])
    expect(joined.pieceIds.every((id) => board.previewed.has(id.toLowerCase()))).toBe(true)
    expect(
      joined.pieceIds.every((id) => board.previewOwners.get(id.toLowerCase()) === action.actionId),
    ).toBe(true)
    board.down(pointer(10, 20), joined)
    expect(board.pending.current).toBeNull()
    expect(mock.preview).not.toHaveBeenCalled()
  })

  it.each(['expiry', 'failed recovery', 'handoff without turn'] as const)(
    'discards an interrupted drag before %s and permits a later drag',
    (outcome) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = [action]
      const { board } = setup({ ownTurn: turn })
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const subscribeEffect = mock.effects[3]
      if (!subscribeEffect) throw new Error('Missing subscription effect')
      const cleanup = subscribeEffect()
      board.down(pointer(10, 20), joined)
      board.move(pointer(30, 50))
      expect(board.pending.current).not.toBeNull()
      const old = mock.state as LiveGameState
      const view = { snapshot: old.game!.snapshot, turn: turn!, actions: [action] }
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      emit({ ...old, connection: 'disconnected', recoveryView: view })
      expect(board.pending.current).toBeNull()
      mock.preview.mockClear()
      board.move(pointer(50, 70))
      board.up()
      expect(mock.preview).not.toHaveBeenCalled()
      if (outcome === 'expiry') emit({ ...old, connection: 'disconnected', recoveryView: null })
      else if (outcome === 'failed recovery')
        emit({ ...old, connection: 'disconnected', recoveryView: null, game: null })
      else
        emit({
          ...old,
          connection: 'ready',
          recoveryView: null,
          game: { ...old.game!, ownTurn: null },
        })
      emit(old)
      board.down(pointer(10, 20), joined)
      expect(board.pending.current).not.toBeNull()
      cleanup?.()
    },
  )

  it.each(['confirmed', 'draft'] as const)(
    'keeps the complete %s component for a click and subsequent drag',
    (mode) => {
      // Availability is covered independently; this test isolates pointer membership and payloads.
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = mode === 'draft' ? [action] : []
      const snapshot = mode === 'confirmed' ? fixture.mergedSnapshot : fixture.snapshot
      const { board } = setup({ snapshot: snapshot as CurrentGame['snapshot'], ownTurn: turn })
      // HomePage passes the entire component to down regardless of which piece was hit.
      const group = { ...joined, finalPosition: false }
      board.down(pointer(10, 20), group)
      expect(board.pending.current?.action.affectedPieceIds).toEqual(joined.pieceIds)
      board.up()
      expect(board.pending.current).toBeNull()
      expect(mock.preview).not.toHaveBeenCalled()

      board.down(pointer(10, 20), group)
      board.move(pointer(30, 50))
      expect(board.pending.current?.action.affectedPieceIds).toEqual(joined.pieceIds)
      expect(mock.preview).toHaveBeenCalled()
      expect(mock.preview.mock.calls[0][0].affectedPieceIds).toEqual(joined.pieceIds)
      expect(mock.preview.mock.calls[0][0].intendedTransform.translation).toEqual({ x: 20, y: 30 })
      expect(mock.preview.mock.calls[0][0].baseRevisions).toEqual(
        mode === 'draft'
          ? action.baseRevisions
          : [{ groupId: joined.groupId, revision: joined.revision }],
      )
    },
  )
})

describe('board pointer movement', () => {
  it('uses one coordinate conversion for cursor and drag and retains cancel recovery', () => {
    const { board, getScreenCTM, group } = setup()
    board.down(pointer(10, 20), group)
    getScreenCTM.mockClear()
    board.move(pointer(30, 50))
    expect(getScreenCTM).toHaveBeenCalledTimes(1)
    expect(mock.sendCursor).toHaveBeenCalledWith({ x: 30, y: 50 })
    expect(board.pending.current?.desired.translation).toEqual({
      x: group.transform.translation.x + 20,
      y: group.transform.translation.y + 30,
    })
    board.abort()
    expect(board.pending.current?.interrupted).toBe(true)
    expect(mock.recover).toHaveBeenCalledOnce()
  })

  it('retries a missing cursor matrix for an active drag and keeps panning separate', () => {
    const { board, getScreenCTM, matrix, group } = setup()
    board.down(pointer(10, 20), group)
    getScreenCTM.mockReset()
    getScreenCTM
      .mockReturnValueOnce(null as unknown as DOMMatrix)
      .mockReturnValue(matrix as unknown as DOMMatrix)
    board.move(pointer(30, 50))
    expect(getScreenCTM).toHaveBeenCalledTimes(2)
    expect(mock.sendCursor).not.toHaveBeenCalled()
    expect(board.pending.current?.desired.translation.x).toBe(group.transform.translation.x + 20)
    board.pan.current = { x: 30, y: 50, originX: 0, originY: 0, scaleX: 1, scaleY: 1 }
    getScreenCTM.mockClear()
    board.move(pointer(40, 60))
    expect(getScreenCTM).toHaveBeenCalledTimes(1)
    expect(board.pending.current?.desired.translation.x).toBe(group.transform.translation.x + 20)
  })

  it('keeps a drag unmoved when the matrix is unavailable or movement stays below the threshold', () => {
    const { board, getScreenCTM, matrix, group } = setup()
    board.down(pointer(10, 20), group)
    getScreenCTM.mockReturnValue(null as unknown as DOMMatrix)
    board.move(pointer(30, 50))
    expect(board.pending.current?.moved).toBe(false)
    expect(mock.sendCursor).not.toHaveBeenCalled()
    getScreenCTM.mockReturnValue(matrix as unknown as DOMMatrix)
    board.move(pointer(12, 22))
    expect(board.pending.current?.moved).toBe(false)
    board.up()
    expect(board.pending.current).toBeNull()
  })
})
