import { createElement, type PointerEvent } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import multiplayerFixture from '../../../../packages/generator/fixtures/surround-60.json'
import type { Action, CurrentGame, Group } from '@world-jigsaw/contracts'
import { useLiveBoard } from './use-live-board'
import type { LiveGameState } from './live-game'
import * as moveAvailability from './move-availability'
import * as join from './join'

const mock = vi.hoisted(() => ({
  state: {},
  actions: [] as Action[] | null,
  recoveredId: null as string | null,
  sendCursor: vi.fn(),
  recover: vi.fn(),
  preview: vi.fn<(action: Action, resyncOnCollision?: boolean) => void>(),
  undo: vi.fn(),
  begin: vi.fn<() => Promise<CurrentGame['ownTurn']>>(),
  abandon: vi.fn(),
  effects: [] as (() => undefined | (() => void))[],
  listener: null as ((state: LiveGameState) => void) | null,
}))
vi.mock('react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react')>()),
  useEffect: (effect: () => undefined | (() => void)) => {
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
    recoveredPreviewActionId() {
      return mock.recoveredId
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
    disconnect = vi.fn()
    begin() {
      return mock.begin()
    }
  },
}))

function firstPreview(): Action {
  const call = mock.preview.mock.calls[0]
  if (!call) throw new Error('Expected a preview submission')
  return call[0]
}

function lastPreview(): Action {
  const call = mock.preview.mock.calls.at(-1)
  if (!call) throw new Error('Expected a preview submission')
  return call[0]
}

function requireGame(state: LiveGameState): CurrentGame {
  if (!state.game) throw new Error('Expected a current game')
  return state.game
}

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
  mock.state = {
    connection: 'ready',
    game,
    previews: {},
    peerRecoveryViews: {},
    cursors: {},
    ...stateOverride,
  }
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
  mock.recoveredId = null
  mock.preview.mockReset()
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
    boardVersion: fixture.snapshot.boardVersion,
  } as NonNullable<CurrentGame['ownTurn']>
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
          turn: turn,
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

  it('renders peer, own recovery, and current previews together as overlays', () => {
    const peer = { ...action, actionId: 'peer' }
    const current = { ...action, actionId: 'current' }
    const { board } = setup(
      { ownTurn: turn },
      {
        connection: 'resyncing',
        recoveryView: { turn, actions: [action] },
        peerRecoveryViews: {
          peer: { generation: 1, sequence: 0, actions: [peer], deadline: 1000 },
        },
        previews: {
          current: {
            puzzleId: turn.puzzleId,
            turnId: 'current',
            generation: 1,
            sequence: 0,
            closed: false,
            actions: { current },
          },
        },
      },
    )
    expect(board.previews).toEqual([current, action, peer])
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
      expect(firstPreview()).toMatchObject({
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

  it.each([
    ['existing', 'held', 'unchanged'],
    ['existing', 'held', 'accepted'],
    ['opening', 'held', 'unchanged'],
    ['opening', 'held', 'accepted'],
    ['existing', 'released', 'unchanged'],
    ['existing', 'released', 'accepted'],
    ['opening', 'released', 'unchanged'],
    ['opening', 'released', 'accepted'],
  ] as const)(
    'reconciles a %s-turn %s preview when recovery is %s',
    async (kind, lifecycle, outcome) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = kind === 'existing' ? [action] : []
      const { board, group } = setup({ ownTurn: kind === 'existing' ? turn : null })
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
      mock.preview.mockImplementation(() => {
        mock.state = { ...(mock.state as LiveGameState), previewPending: true }
      })
      const ready = { ...old, game: { ...requireGame(old), ownTurn: turn } }
      if (kind === 'opening')
        mock.begin.mockImplementation(() => {
          emit(ready)
          return Promise.resolve(turn)
        })
      const target = kind === 'existing' ? joined : group
      board.down(pointer(10, 20), target)
      board.move(pointer(30, 50))
      await Promise.resolve()
      await Promise.resolve()
      expect(mock.preview).toHaveBeenCalledOnce()
      const initial = firstPreview()
      if (lifecycle === 'released') {
        board.move(pointer(40, 60))
        board.up()
        mock.actions = [initial]
        emit({ ...ready, previewPending: false })
        await Promise.resolve()
        expect(mock.preview).toHaveBeenCalledTimes(2)
      }
      const sent = lastPreview()
      mock.preview.mockClear()
      emit({ ...ready, connection: 'resyncing' })
      mock.actions =
        outcome === 'accepted'
          ? [sent]
          : lifecycle === 'released'
            ? [initial]
            : kind === 'existing'
              ? [action]
              : []
      emit({ ...ready, previewPending: false })
      await Promise.resolve()
      if (outcome === 'unchanged') {
        expect(mock.preview).toHaveBeenCalledOnce()
        expect(firstPreview()).toEqual(sent)
      } else expect(mock.preview).not.toHaveBeenCalled()
      if (lifecycle === 'released' && outcome === 'accepted')
        expect(board.pending.current).toBeNull()
      else expect(board.pending.current?.active).toBe(lifecycle === 'held')
      cleanup?.()
    },
  )

  it('finishes a recovered server-authored join without resubmitting it', async () => {
    vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
    const merged = { ...action, affectedPieceIds: source.flatMap((group) => group.pieceIds) }
    vi.spyOn(join, 'suggestJoin').mockReturnValue(merged)
    mock.actions = [action]
    const { board } = setup({ ownTurn: turn })
    vi.stubGlobal('sessionStorage', { getItem: () => null, removeItem: vi.fn(), setItem: vi.fn() })
    const cleanup = mock.effects[3]?.()
    const old = mock.state as LiveGameState
    const emit = (next: LiveGameState) => {
      mock.state = next
      mock.listener?.(next)
    }
    board.down(pointer(10, 20), joined)
    board.move(pointer(30, 50))
    await Promise.resolve()
    board.up(pointer(30, 50))
    const serverJoin = {
      ...lastPreview(),
      targetId: fixture.mergedSnapshot.groups[0].groupId,
      intendedTransform: joined.transform,
    }
    mock.preview.mockClear()
    emit({ ...old, connection: 'resyncing' })
    mock.actions = [serverJoin]
    mock.recoveredId = serverJoin.actionId
    emit(old)
    await Promise.resolve()
    expect(mock.preview).not.toHaveBeenCalled()
    expect(board.pending.current).toBeNull()
    cleanup?.()
  })

  it.each(['moved', 'revised', 'regrouped', 'unchanged'] as const)(
    'rebuilds an unaccepted final join when its target was %s during recovery',
    async (change) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const moving = fixture.snapshot.groups[0] as Group
      const target = {
        ...fixture.snapshot.groups[1],
        transform: {
          ...moving.transform,
          translation: {
            x: moving.transform.translation.x + 20,
            y: moving.transform.translation.y + 30,
          },
        },
      } as Group
      const snapshot = { ...fixture.snapshot, groups: [moving, target] } as CurrentGame['snapshot']
      const { board } = setup({ ownTurn: turn, snapshot })
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
      mock.preview.mockImplementation(() => {
        mock.state = { ...(mock.state as LiveGameState), previewPending: true }
      })
      board.down(pointer(10, 20), moving)
      board.move(pointer(30, 50))
      await Promise.resolve()
      const active = firstPreview()
      board.up(pointer(30, 50))
      mock.actions = [active]
      emit({ ...old, previewPending: false })
      await Promise.resolve()
      expect(lastPreview().affectedPieceIds).toEqual([...moving.pieceIds, ...target.pieceIds])
      mock.preview.mockClear()
      emit({ ...old, connection: 'resyncing' })
      const updated =
        change === 'moved'
          ? { ...target, transform: { ...target.transform, translation: { x: 9000, y: 9000 } } }
          : change === 'revised'
            ? { ...target, revision: target.revision + 1 }
            : change === 'regrouped'
              ? { ...target, groupId: '00000000-0000-4000-8000-000000000999' }
              : target
      emit({
        ...old,
        previewPending: false,
        game: {
          ...requireGame(old),
          snapshot: {
            ...snapshot,
            boardVersion: snapshot.boardVersion + 1,
            groups: [moving, updated],
          },
        },
      })
      await Promise.resolve()
      expect(mock.preview).toHaveBeenCalledOnce()
      expect(firstPreview().affectedPieceIds).toEqual(
        change === 'unchanged' ? [...moving.pieceIds, ...target.pieceIds] : moving.pieceIds,
      )
      expect(firstPreview().baseRevisions).toEqual(
        change === 'unchanged'
          ? [
              { groupId: moving.groupId, revision: moving.revision },
              { groupId: target.groupId, revision: target.revision },
            ]
          : [{ groupId: moving.groupId, revision: moving.revision }],
      )
      cleanup?.()
    },
  )

  it.each(['snapshot', 'own draft'] as const)(
    'uses only proven %s targets during recovery when the turn base is unavailable',
    async (source) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const moving = fixture.snapshot.groups[0] as Group
      const target = {
        ...fixture.snapshot.groups[1],
        transform: {
          ...moving.transform,
          translation: {
            x: moving.transform.translation.x + 20,
            y: moving.transform.translation.y + 30,
          },
        },
      } as Group
      const targetAction = {
        ...fixture.preview.action,
        actionId: 'target',
        targetId: target.pieceIds[0],
        affectedPieceIds: target.pieceIds,
        baseRevisions: [{ groupId: target.groupId, revision: target.revision }],
        intendedTransform: target.transform,
      } as Action
      mock.actions = source === 'own draft' ? [targetAction] : []
      const snapshot = {
        ...fixture.snapshot,
        boardVersion: fixture.snapshot.boardVersion + 1,
        groups: [moving, target],
      } as CurrentGame['snapshot']
      const { board } = setup({ ownTurn: turn, snapshot })
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      const old = mock.state as LiveGameState
      mock.preview.mockImplementation(() => {
        mock.state = { ...(mock.state as LiveGameState), previewPending: true }
      })
      board.down(pointer(10, 20), moving)
      board.move(pointer(30, 50))
      await Promise.resolve()
      const active = firstPreview()
      board.up(pointer(30, 50))
      mock.actions = source === 'own draft' ? [targetAction, active] : [active]
      mock.state = { ...old, connection: 'resyncing', previewPending: false }
      mock.listener?.(mock.state as LiveGameState)
      mock.state = { ...old, previewPending: false }
      mock.listener?.(mock.state as LiveGameState)
      await Promise.resolve()
      expect(lastPreview().affectedPieceIds).toEqual(
        source === 'own draft' ? [...moving.pieceIds, ...target.pieceIds] : moving.pieceIds,
      )
      cleanup?.()
    },
  )

  it.each([
    ['drag', 'revised'],
    ['drag', 'unchanged'],
    ['rotate', 'revised'],
    ['rotate', 'unchanged'],
  ] as const)(
    'checks the %s target when a held drag recovers with a %s neighbor',
    async (interaction, change) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const multiplayer = multiplayerFixture as unknown as CurrentGame
      const [first, second] = multiplayer.snapshot.groups
      if (!second) throw new Error('Expected neighboring groups')
      const moving = { ...first, finalPosition: false }
      const target = {
        ...second,
        finalPosition: false,
        transform:
          interaction === 'drag'
            ? { rotation: 0, translation: { x: 20, y: 30 } }
            : { rotation: 90000, translation: { x: 126687, y: 6697 } },
      }
      const snapshot = {
        ...multiplayer.snapshot,
        groups: [moving, target],
      } as CurrentGame['snapshot']
      const ownTurn = { ...turn, puzzleId: snapshot.puzzleId, boardVersion: snapshot.boardVersion }
      const { board } = setup({ definition: multiplayer.definition, ownTurn, snapshot })
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
      board.down(pointer(10, 20), moving)
      board.move(pointer(30, 50))
      await Promise.resolve()
      mock.actions = [firstPreview()]
      emit({ ...old, connection: 'resyncing' })
      const updated = change === 'revised' ? { ...target, revision: target.revision + 1 } : target
      emit({
        ...old,
        game: {
          ...requireGame(old),
          snapshot: {
            ...snapshot,
            boardVersion: snapshot.boardVersion + 1,
            groups: [moving, updated],
          },
        },
      })
      await Promise.resolve()
      if (interaction === 'rotate') board.rotate(moving)
      mock.preview.mockClear()
      board.up(pointer(30, 50))
      expect(lastPreview().affectedPieceIds).toEqual(
        change === 'unchanged' ? [...moving.pieceIds, ...target.pieceIds] : moving.pieceIds,
      )
      expect(lastPreview().baseRevisions).toEqual(
        change === 'unchanged'
          ? [
              { groupId: moving.groupId, revision: moving.revision },
              { groupId: target.groupId, revision: target.revision },
            ]
          : [{ groupId: moving.groupId, revision: moving.revision }],
      )
      cleanup?.()
    },
  )

  it.each(['drag', 'rotate'] as const)(
    'joins an unchanged neighbor after another player confirms an unrelated move: %s',
    async (interaction) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      const multiplayer = multiplayerFixture as unknown as CurrentGame
      const [first, second] = multiplayer.snapshot.groups
      const other = multiplayer.snapshot.groups.at(-1)
      if (!second || !other) throw new Error('Expected separate groups for both players')
      const moving = { ...first, finalPosition: false }
      const target = {
        ...second,
        finalPosition: false,
        transform:
          interaction === 'drag'
            ? { rotation: 0, translation: { x: 20, y: 30 } }
            : { rotation: 90000, translation: { x: 126667, y: 6667 } },
      }
      const peer = { ...other, finalPosition: false }
      const snapshot = {
        ...multiplayer.snapshot,
        groups: [moving, target, peer],
      } as CurrentGame['snapshot']
      const ownTurn = { ...turn, puzzleId: snapshot.puzzleId, boardVersion: snapshot.boardVersion }
      const { board } = setup({ definition: multiplayer.definition, ownTurn, snapshot })
      vi.stubGlobal('sessionStorage', {
        getItem: () => null,
        removeItem: vi.fn(),
        setItem: vi.fn(),
      })
      const cleanup = mock.effects[3]?.()
      const old = mock.state as LiveGameState
      mock.state = { ...old, connection: 'resyncing' }
      mock.listener?.(mock.state as LiveGameState)
      const peerMove = {
        ...peer,
        revision: peer.revision + 1,
        transform: { ...peer.transform, translation: { x: 500000, y: 500000 } },
      }
      mock.state = {
        ...old,
        game: {
          ...requireGame(old),
          snapshot: {
            ...snapshot,
            boardVersion: snapshot.boardVersion + 1,
            groups: [moving, target, peerMove],
          },
        },
      }
      mock.listener?.(mock.state as LiveGameState)
      await Promise.resolve()
      if (interaction === 'drag') {
        board.down(pointer(10, 20), moving)
        board.move(pointer(30, 50))
        board.up(pointer(30, 50))
      } else board.rotate(moving)
      await Promise.resolve()
      expect(lastPreview().affectedPieceIds).toEqual([...moving.pieceIds, ...target.pieceIds])
      expect(lastPreview().baseRevisions).toEqual([
        { groupId: moving.groupId, revision: moving.revision },
        { groupId: target.groupId, revision: target.revision },
      ])
      cleanup?.()
    },
  )

  it.each([
    'different turn',
    'canceled turn',
    'uncertain draft',
    'failed recovery',
    'missing game',
    'different game',
  ] as const)('discards an existing drag after %s', async (outcome) => {
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
        : outcome === 'missing game'
          ? { ...old, connection: 'resyncing', game: null }
          : outcome === 'different game'
            ? {
                ...old,
                connection: 'resyncing',
                game: {
                  ...requireGame(old),
                  snapshot: {
                    ...requireGame(old).snapshot,
                    puzzleId: '00000000-0000-4000-8000-000000000099',
                  },
                },
              }
            : {
                ...old,
                connection: 'ready',
                game: {
                  ...requireGame(old),
                  ownTurn:
                    outcome === 'canceled turn'
                      ? null
                      : outcome === 'different turn'
                        ? { ...turn, generation: turn.generation + 1 }
                        : turn,
                },
              },
    )
    await Promise.resolve()
    expect(board.pending.current).toBeNull()
    expect(mock.abandon).toHaveBeenCalledOnce()
    expect(mock.preview).not.toHaveBeenCalled()
    cleanup?.()
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
      const view = { turn: turn, actions: [action] }
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
          game: { ...requireGame(old), ownTurn: null },
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
      expect(firstPreview().affectedPieceIds).toEqual(joined.pieceIds)
      expect(firstPreview().intendedTransform.translation).toEqual({ x: 20, y: 30 })
      expect(firstPreview().baseRevisions).toEqual(
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
      emit({ ...old, connection: 'ready', game: { ...requireGame(old), ownTurn: turn } })
      emit({ ...old, connection: 'resyncing' })
      resolveBegin(turn)
      await Promise.resolve()
      expect(mock.preview).not.toHaveBeenCalled()
      emit({ ...old, connection: 'ready', game: { ...requireGame(old), ownTurn: turn } })
      await Promise.resolve()
      expect(mock.preview).toHaveBeenCalledOnce()
      if (kind === 'drag')
        expect(firstPreview().intendedTransform.translation).toEqual({
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
    emit({ ...old, connection: 'ready', game: { ...requireGame(old), ownTurn: turn } })
    await Promise.resolve()
    expect(board.pending.current?.active).toBe(true)
    expect(firstPreview().intendedTransform.translation).toEqual({
      x: group.transform.translation.x + 90,
      y: group.transform.translation.y + 80,
    })
    cleanup?.()
  })

  it.each([
    ['drag', 'open'],
    ['drag', 'canceled'],
    ['rotation', 'open'],
    ['rotation', 'canceled'],
  ] as const)(
    'reconciles the first %s after an older no-turn handoff when the turn is %s',
    async (kind, outcome) => {
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
      emit({ ...old, connection: 'ready' })
      expect(board.pending.current).not.toBeNull()
      expect(mock.preview).not.toHaveBeenCalled()
      emit({ ...old, connection: 'resyncing' })
      resolveBegin(turn)
      await Promise.resolve()
      expect(board.pending.current).not.toBeNull()
      expect(mock.preview).not.toHaveBeenCalled()
      emit({
        ...old,
        connection: 'ready',
        game: { ...requireGame(old), ownTurn: outcome === 'open' ? turn : null },
      })
      await Promise.resolve()
      if (outcome === 'open') {
        expect(mock.preview).toHaveBeenCalledOnce()
        if (kind === 'drag')
          expect(firstPreview().intendedTransform.translation).toEqual({
            x: group.transform.translation.x + 90,
            y: group.transform.translation.y + 90,
          })
      } else {
        expect(mock.preview).not.toHaveBeenCalled()
        expect(board.pending.current).toBeNull()
        board.down(pointer(10, 20), group)
        expect(board.pending.current).not.toBeNull()
      }
      cleanup?.()
    },
  )

  it.each(['drag', 'rotation'] as const)(
    'discards an opening %s when a later handoff has no turn',
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
      resolveBegin(turn)
      await Promise.resolve()
      emit({ ...old, connection: 'ready' })
      await Promise.resolve()
      expect(board.pending.current).toBeNull()
      expect(mock.preview).not.toHaveBeenCalled()
      board.down(pointer(10, 20), group)
      expect(board.pending.current).not.toBeNull()
      cleanup?.()
    },
  )

  it.each(['opening', 'existing'] as const)(
    'uses release coordinates after a recovered %s drag handoff',
    async (kind) => {
      vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
      mock.actions = kind === 'existing' ? [action] : []
      const { board, group } = setup({ ownTurn: kind === 'existing' ? turn : null })
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
      const ready = { ...old, game: { ...requireGame(old), ownTurn: turn } }
      if (kind === 'opening') mock.begin.mockResolvedValue(turn)
      const target = kind === 'existing' ? joined : group
      board.down(pointer(10, 20), target)
      board.move(pointer(30, 50))
      emit({ ...old, connection: 'resyncing' })
      board.move(pointer(100, 110))
      await Promise.resolve()
      emit(ready)
      await Promise.resolve()
      mock.preview.mockClear()
      board.up(pointer(120, 140))
      expect(lastPreview().intendedTransform.translation).toEqual({
        x: target.transform.translation.x + 110,
        y: target.transform.translation.y + 120,
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
      emit({ ...old, connection: 'ready', game: { ...requireGame(old), ownTurn: turn } })
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
        emit({ ...old, game: { ...requireGame(old), ownTurn: turn } })
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
      expect(firstPreview().intendedTransform.translation).toEqual({
        x: group.transform.translation.x + 90,
        y: group.transform.translation.y + 90,
      })
      cleanup?.()
    },
  )

  it.each([
    ['opening', 'held', 'moved'],
    ['opening', 'move', 'joined'],
    ['opening', 'release', 'moved'],
    ['existing', 'held', 'joined'],
    ['existing', 'move', 'moved'],
    ['existing', 'release', 'joined'],
  ] as const)('discards a %s-turn drag %s when the group was %s', async (kind, timing, change) => {
    vi.spyOn(moveAvailability, 'canMoveGroup').mockReturnValue(true)
    const { board, group } = setup({ ownTurn: kind === 'existing' ? turn : null })
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
    board.down(pointer(10, 20), group)
    emit({ ...old, connection: 'resyncing' })
    if (timing === 'move') board.move(pointer(100, 110))
    if (timing === 'release') board.up(pointer(100, 110))
    const game = requireGame(old)
    const groups = [...game.snapshot.groups] as CurrentGame['snapshot']['groups']
    groups[0] =
      change === 'moved'
        ? { ...group, revision: group.revision + 1 }
        : { ...group, pieceIds: [...group.pieceIds, groups[1].pieceIds[0]] }
    emit({ ...old, game: { ...game, snapshot: { ...game.snapshot, groups } } })
    await Promise.resolve()
    if (timing === 'held') board.move(pointer(100, 110))
    await Promise.resolve()
    expect(board.pending.current).toBeNull()
    expect(mock.begin).not.toHaveBeenCalled()
    expect(mock.preview).not.toHaveBeenCalled()
    cleanup?.()
  })

  it('discards an opening drag if its group changed while begin was in flight', async () => {
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
    const old = mock.state as LiveGameState
    const emit = (next: LiveGameState) => {
      mock.state = next
      mock.listener?.(next)
    }
    board.down(pointer(10, 20), group)
    board.move(pointer(30, 50))
    emit({ ...old, connection: 'resyncing' })
    resolveBegin(turn)
    await Promise.resolve()
    const game = requireGame(old)
    const groups = [...game.snapshot.groups] as CurrentGame['snapshot']['groups']
    groups[0] = { ...group, revision: group.revision + 1 }
    emit({ ...old, game: { ...game, ownTurn: turn, snapshot: { ...game.snapshot, groups } } })
    await Promise.resolve()
    expect(board.pending.current).toBeNull()
    expect(mock.preview).not.toHaveBeenCalled()
    cleanup?.()
  })

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
      const submitted = firstPreview()
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
      expect(mock.abandon).not.toHaveBeenCalled()
      mock.actions = [submitted]
      mock.preview.mockClear()
      emit({ ...old, connection: 'ready', previewPending: false })
      expect(mock.abandon).not.toHaveBeenCalled()
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
