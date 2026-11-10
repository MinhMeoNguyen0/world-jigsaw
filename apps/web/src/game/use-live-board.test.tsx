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
  actions: [] as Action[] | null,
  sendCursor: vi.fn(),
  recover: vi.fn(),
  preview: vi.fn(),
  undo: vi.fn(),
  begin: vi.fn(),
  abandon: vi.fn(),
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
    abandonInterruptedPreview = mock.abandon
    preview = mock.preview
    undo = mock.undo
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
      return mock.begin()
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
  mock.begin.mockImplementation(() => Promise.resolve(game.ownTurn))
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
  mock.undo.mockReset()
  mock.begin.mockReset()
  mock.abandon.mockReset()
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

  it.each(['held', 'released'] as const)(
    'resumes an existing joined drag %s during a peer resync',
    async (mode) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = [action]
      const { board } = setup({ ownTurn: turn })
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      board.down(pointer(10, 20), joined)
      board.move(pointer(30, 50))
      await Promise.resolve()
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      mock.preview.mockClear()
      emit({ ...old, connection: 'resyncing', previewPending: false })
      expect(mock.recover).toHaveBeenCalled()
      board.move(pointer(100, 110))
      if (mode === 'released') board.up(pointer(120, 140))
      expect(mock.preview).not.toHaveBeenCalled()
      emit({ ...old, connection: 'ready', previewPending: false })
      await Promise.resolve()
      expect(mock.preview).toHaveBeenCalledOnce()
      expect(mock.preview.mock.calls[0][0]).toMatchObject({
        affectedPieceIds: joined.pieceIds,
        intendedTransform: {
          ...joined.transform,
          translation: {
            x: joined.transform.translation.x + (mode === 'held' ? 90 : 110),
            y: joined.transform.translation.y + (mode === 'held' ? 90 : 120),
          },
        },
      })
      cleanup?.()
    },
  )

  it.each(['different turn', 'canceled turn', 'uncertain draft', 'failed recovery'] as const)(
    'discards an existing drag after %s',
    async (outcome) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = [action]
      const { board } = setup({ ownTurn: turn })
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      board.down(pointer(10, 20), joined)
      board.move(pointer(30, 50))
      await Promise.resolve()
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      emit({ ...old, connection: 'resyncing', previewPending: false })
      board.move(pointer(100, 110))
      board.up(pointer(120, 140))
      mock.preview.mockClear()
      if (outcome === 'uncertain draft') mock.actions = null
      emit(
        outcome === 'failed recovery'
          ? { ...old, connection: 'disconnected', issue: 'Recovery failed' }
          : {
              ...old,
              connection: 'ready',
              game: {
                ...old.game!,
                ownTurn:
                  outcome === 'canceled turn'
                    ? null
                    : outcome === 'different turn'
                      ? { ...turn!, generation: turn!.generation + 1 }
                      : turn,
              },
            },
      )
      await Promise.resolve()
      expect(board.pending.current).toBeNull()
      expect(mock.preview).not.toHaveBeenCalled()
      cleanup?.()
    },
  )

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
      const view = { turn: turn!, actions: [action] }
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

  it.each(['drag', 'rotation'] as const)(
    'submits the first %s after turn-opening resynchronization',
    async (kind) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const { board, group } = setup()
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      let resolveBegin!: (value: CurrentGame['ownTurn']) => void
      mock.begin.mockReturnValue(
        new Promise((resolve) => {
          resolveBegin = resolve
        }),
      )
      if (kind === 'drag') {
        board.down(pointer(10, 20), group)
        board.move(pointer(30, 50))
      } else board.rotate(group)
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      emit({ ...old, connection: 'resyncing' })
      expect(board.pending.current).not.toBeNull()
      if (kind === 'drag') {
        board.move(pointer(100, 100))
        expect(board.pending.current?.desired.translation).toEqual({
          x: group.transform.translation.x + 90,
          y: group.transform.translation.y + 80,
        })
        board.up(pointer(120, 130))
      }
      expect(mock.preview).not.toHaveBeenCalled()
      emit({ ...old, connection: 'ready', game: { ...old.game!, ownTurn: turn } })
      resolveBegin(turn)
      await Promise.resolve()
      expect(mock.preview).toHaveBeenCalledOnce()
      if (kind === 'drag')
        expect(mock.preview.mock.calls[0][0].intendedTransform.translation).toEqual({
          x: group.transform.translation.x + 110,
          y: group.transform.translation.y + 110,
        })
      cleanup?.()
    },
  )

  it('keeps a held first drag at its latest position through the handoff', async () => {
    vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
    const { board, group } = setup()
    vi.stubGlobal('sessionStorage', { getItem: () => null, removeItem: vi.fn(), setItem: vi.fn() })
    const cleanup = mock.effects[3]?.()
    let resolveBegin!: (value: CurrentGame['ownTurn']) => void
    mock.begin.mockReturnValue(
      new Promise((resolve) => {
        resolveBegin = resolve
      }),
    )
    board.down(pointer(10, 20), group)
    board.move(pointer(30, 50))
    const old = mock.state as LiveGameState
    const emit = (next: LiveGameState) => {
      mock.state = next
      mock.listener?.(next)
    }
    emit({ ...old, connection: 'resyncing' })
    board.move(pointer(100, 100))
    resolveBegin(turn)
    await Promise.resolve()
    expect(mock.preview).not.toHaveBeenCalled()
    emit({ ...old, connection: 'ready', game: { ...old.game!, ownTurn: turn } })
    await Promise.resolve()
    expect(board.pending.current?.active).toBe(true)
    expect(mock.preview.mock.calls[0][0].intendedTransform.translation).toEqual({
      x: group.transform.translation.x + 90,
      y: group.transform.translation.y + 80,
    })
    cleanup?.()
  })

  it.each([
    ['drag', 'before'],
    ['drag', 'after'],
    ['rotation', 'before'],
    ['rotation', 'after'],
  ] as const)(
    'retains the first %s when a no-turn handoff arrives %s begin resolves',
    async (kind, order) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const { board, group } = setup()
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      let resolveBegin!: (value: CurrentGame['ownTurn']) => void
      mock.begin.mockReturnValue(
        new Promise((resolve) => {
          resolveBegin = resolve
        }),
      )
      if (kind === 'drag') {
        board.down(pointer(10, 20), group)
        board.move(pointer(30, 50))
      } else board.rotate(group)
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      emit({ ...old, connection: 'resyncing' })
      if (kind === 'drag') board.up(pointer(100, 110))
      if (order === 'after') {
        resolveBegin(turn)
        await Promise.resolve()
      }
      emit({ ...old, connection: 'ready' })
      expect(board.pending.current).not.toBeNull()
      expect(mock.preview).not.toHaveBeenCalled()
      if (order === 'before') {
        resolveBegin(turn)
        await Promise.resolve()
      }
      expect(mock.preview).not.toHaveBeenCalled()
      emit({ ...old, connection: 'resyncing' })
      emit({ ...old, connection: 'ready', game: { ...old.game!, ownTurn: turn } })
      await Promise.resolve()
      expect(mock.preview).toHaveBeenCalledOnce()
      if (kind === 'drag')
        expect(mock.preview.mock.calls[0][0].intendedTransform.translation).toEqual({
          x: group.transform.translation.x + 90,
          y: group.transform.translation.y + 90,
        })
      cleanup?.()
    },
  )

  it('discards the acquiring drag when begin rejects after a no-turn handoff', async () => {
    vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
    const { board, group } = setup()
    vi.stubGlobal('sessionStorage', { getItem: () => null, removeItem: vi.fn(), setItem: vi.fn() })
    const cleanup = mock.effects[3]?.()
    let rejectBegin!: (reason: Error) => void
    mock.begin.mockReturnValue(
      new Promise((_, reject) => {
        rejectBegin = reject
      }),
    )
    board.down(pointer(10, 20), group)
    board.move(pointer(30, 50))
    const old = mock.state as LiveGameState
    mock.state = { ...old, connection: 'resyncing' }
    mock.listener?.(mock.state as LiveGameState)
    mock.state = old
    mock.listener?.(old)
    rejectBegin(new Error('begin rejected'))
    await Promise.resolve()
    await Promise.resolve()
    expect(board.pending.current).toBeNull()
    expect(mock.preview).not.toHaveBeenCalled()
    cleanup?.()
  })

  it.each(['before', 'after'] as const)(
    'clears a canceled opening turn when the begin response arrives %s cancellation',
    async (order) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const { board, group } = setup()
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      let resolveBegin!: (value: CurrentGame['ownTurn']) => void
      mock.begin.mockReturnValue(
        new Promise((resolve) => {
          resolveBegin = resolve
        }),
      )
      board.down(pointer(10, 20), group)
      board.move(pointer(30, 50))
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      emit({ ...old, connection: 'resyncing' })
      emit({ ...old, connection: 'ready', game: { ...old.game!, ownTurn: turn } })
      if (order === 'before') {
        resolveBegin(turn)
        await Promise.resolve()
      }
      emit({ ...old, connection: 'resyncing' })
      emit({ ...old, connection: 'ready' })
      if (order === 'after') {
        resolveBegin(turn)
        await Promise.resolve()
      }
      expect(board.pending.current).toBeNull()
      board.down(pointer(10, 20), group)
      expect(board.pending.current).not.toBeNull()
      cleanup?.()
    },
  )

  it.each(['move', 'release'] as const)(
    'starts begin after resync when the first movement is a %s',
    async (kind) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const { board, group } = setup()
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      mock.begin.mockImplementation(() => {
        emit({ ...old, game: { ...old.game!, ownTurn: turn } })
        return Promise.resolve(turn)
      })
      board.down(pointer(10, 20), group)
      emit({ ...old, connection: 'resyncing' })
      if (kind === 'move') board.move(pointer(100, 110))
      else board.up(pointer(100, 110))
      expect(mock.begin).not.toHaveBeenCalled()
      expect(mock.preview).not.toHaveBeenCalled()
      emit(old)
      await Promise.resolve()
      await Promise.resolve()
      expect(mock.begin).toHaveBeenCalledOnce()
      expect(mock.preview).toHaveBeenCalledOnce()
      expect(mock.preview.mock.calls[0][0].intendedTransform.translation).toEqual({
        x: group.transform.translation.x + 90,
        y: group.transform.translation.y + 90,
      })
      cleanup?.()
    },
  )

  it.each(['restore', 'undo'] as const)(
    'reconciles a canceled drag after the lost preview is recovered: %s',
    async (outcome) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = outcome === 'restore' ? [action] : []
      const { board } = setup({ ownTurn: turn })
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      mock.undo.mockResolvedValue({})
      board.down(pointer(10, 20), joined)
      board.move(pointer(30, 50))
      const submitted = mock.preview.mock.calls[0]?.[0] as Action
      expect(submitted).toBeDefined()
      mock.state = { ...(mock.state as LiveGameState), previewPending: true }
      board.abort()
      const old = mock.state as LiveGameState
      const emit = (next: LiveGameState) => {
        mock.state = next
        mock.listener?.(next)
      }
      emit({ ...old, connection: 'disconnected', previewPending: false })
      expect(board.pending.current?.interrupted).toBe(true)
      mock.actions = [submitted]
      mock.preview.mockClear()
      emit({ ...old, connection: 'ready', previewPending: false })
      await Promise.resolve()
      if (outcome === 'restore') expect(mock.preview).toHaveBeenCalledWith(action, true)
      else expect(mock.undo).toHaveBeenCalledWith(submitted.actionId)
      cleanup?.()
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
