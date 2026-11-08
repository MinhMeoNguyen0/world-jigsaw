import { describe, expect, it, vi } from 'vitest'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import type { Confirm, CurrentGame, Turn } from '@world-jigsaw/contracts'
import { LiveGame, type LiveGameDependencies } from './live-game'

const puzzleId = fixture.snapshot.puzzleId
const turn: Turn = {
  schemaVersion: 1,
  puzzleId,
  turnId: fixture.confirm.turnId,
  turnRevision: fixture.confirm.turnRevision,
  generation: fixture.confirm.generation,
  expiresAt: '2026-11-09T00:00:00Z',
  boardVersion: fixture.snapshot.boardVersion,
}
const current: CurrentGame = {
  schemaVersion: 1,
  day: '2026-11-08',
  serverTime: '2026-11-08T12:00:00Z',
  endsAt: '2026-11-09T00:00:00Z',
  definition: fixture.definition as CurrentGame['definition'],
  snapshot: fixture.snapshot as CurrentGame['snapshot'],
  allowance: { limit: 5, used: 0, remaining: 5 },
  ownTurn: null,
}

class TestSocket {
  onopen: WebSocket['onopen'] = null
  onmessage: WebSocket['onmessage'] = null
  onclose: WebSocket['onclose'] = null
  onerror: WebSocket['onerror'] = null
  sent: unknown[] = []
  send(data: string | ArrayBufferLike | Blob | ArrayBufferView) {
    this.sent.push(JSON.parse(data as string))
  }
  close() {
    this.onopen = null
  }
  open() {
    this.onopen?.call(this as unknown as WebSocket, new Event('open'))
  }
  emit(value: unknown) {
    this.onmessage?.call(
      this as unknown as WebSocket,
      new MessageEvent('message', { data: JSON.stringify(value) }),
    )
  }
  drop() {
    this.onclose?.call(this as unknown as WebSocket, new Event('close') as CloseEvent)
  }
}

function socketAt(sockets: TestSocket[], index: number) {
  const socket = sockets[index]
  if (!socket) throw new Error('Game socket was not created')
  return socket
}

function harness() {
  const sockets: TestSocket[] = []
  let game = structuredClone(current)
  let commandReply: Promise<Response> | null = null
  let undoReply: Promise<Response> | null = null
  let currentUnavailable = false
  const calls: string[] = []
  const fetcher = vi.fn((input: RequestInfo | URL) => {
    const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    calls.push(path)
    if (path.endsWith('/undo')) {
      if (!undoReply) throw new Error('Missing undo response')
      return undoReply
    }
    if (path.includes('/commands/') || path.endsWith('/confirm')) {
      if (!commandReply) throw new Error('Missing command response')
      return commandReply
    }
    if (path === '/api/v1/game/current' && currentUnavailable)
      return Promise.resolve(
        new Response(
          JSON.stringify({
            schemaVersion: 1,
            error: {
              code: 'PUZZLE_UNAVAILABLE',
              message: 'No current puzzle',
              requestId: '00000000-0000-4000-8000-000000000099',
              retryable: true,
            },
          }),
          { status: 503 },
        ),
      )
    const value =
      path === '/api/v1/session'
        ? { schemaVersion: 1, displayName: 'Player', expiresAt: '2026-11-09T00:00:00Z' }
        : path === '/api/v1/game/current'
          ? game
          : null
    if (!value) throw new Error(`Unexpected request ${path}`)
    return Promise.resolve(new Response(JSON.stringify(value)))
  })
  const deps: LiveGameDependencies = {
    fetch: fetcher,
    socket: () => {
      const socket = new TestSocket()
      sockets.push(socket)
      return socket
    },
    origin: 'http://localhost:8080',
    id: () => '00000000-0000-4000-8000-000000000001',
  }
  const client = new LiveGame(deps)
  async function start() {
    await client.connect()
    const socket = sockets.at(-1)
    if (!socket) throw new Error('Game socket was not created')
    socket.open()
    return socket
  }
  function subscribe(socket: TestSocket, snapshot = current.snapshot) {
    socket.emit({
      schemaVersion: 1,
      type: 'subscribed',
      snapshot,
      watermark: snapshot.boardVersion,
      previews: [],
    })
  }
  return {
    client,
    sockets,
    calls,
    start,
    subscribe,
    setGame: (value: CurrentGame) => {
      game = value
    },
    setCurrentUnavailable: (value: boolean) => {
      currentUnavailable = value
    },
    setCommandReply: (value: Promise<Response>) => {
      commandReply = value
    },
    setUndoReply: (value: Promise<Response>) => {
      undoReply = value
    },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function confirmed() {
  return new Response(JSON.stringify(fixture.commandResult))
}

describe('live game adapter', () => {
  it.each([
    ['begin', null, turn],
    ['cancel', turn, null],
    [
      'undo',
      turn,
      { ...turn, generation: turn.generation + 1, turnRevision: turn.turnRevision + 1 },
    ],
  ])(
    'keeps the handoff unready when another tab can %s without a board change',
    async (_operation, before, after) => {
      const h = harness()
      h.setGame({ ...current, ownTurn: before })
      const socket = await h.start()
      h.setGame({ ...current, ownTurn: after })
      h.subscribe(socket)
      expect(h.client.getState().connection).toBe('resyncing')
      expect(h.client.getState().issue).toContain('identity state')
      await expect(h.client.begin()).rejects.toThrow('not synchronized')
      await expect(h.client.confirm()).rejects.toThrow('not synchronized')
      expect(socket.sent).toHaveLength(1)
    },
  )

  it('does not start a session when a listener disconnects while connecting', async () => {
    const h = harness()
    h.client.subscribe((state) => {
      if (state.connection === 'connecting') h.client.disconnect()
    })
    await h.client.connect()
    expect(h.client.getState().connection).toBe('disconnected')
    expect(h.calls).toEqual([])
    expect(h.sockets).toHaveLength(0)
  })

  it('does not reconnect after a listener disconnects on a committed board event', async () => {
    const h = harness()
    const socket = await h.start()
    h.subscribe(socket)
    h.client.subscribe((state) => {
      if (
        state.game?.snapshot.boardVersion === fixture.confirmedSnapshot.boardVersion &&
        state.connection !== 'disconnected'
      )
        h.client.disconnect()
    })
    const calls = [...h.calls]
    socket.emit(fixture.boardChanged)
    expect(h.client.getState().connection).toBe('disconnected')
    expect(h.client.getState().game?.snapshot).toEqual(fixture.confirmedSnapshot)
    expect(h.calls).toEqual(calls)
    expect(h.sockets).toHaveLength(1)
  })

  it('does not replace a connection started by a listener on a board event', async () => {
    const h = harness()
    const socket = await h.start()
    h.subscribe(socket)
    let replaced = false
    h.client.subscribe((state) => {
      if (
        !replaced &&
        state.game?.snapshot.boardVersion === fixture.confirmedSnapshot.boardVersion
      ) {
        replaced = true
        void h.client.connect()
      }
    })
    socket.emit(fixture.boardChanged)
    await vi.waitFor(() => {
      expect(h.sockets).toHaveLength(2)
    })
    expect(h.calls.filter((path) => path === '/api/v1/session')).toHaveLength(2)
  })

  it('refreshes the board normally when no listener changes the connection', async () => {
    const h = harness()
    const socket = await h.start()
    h.subscribe(socket)
    socket.emit(fixture.boardChanged)
    await vi.waitFor(() => {
      expect(h.sockets).toHaveLength(2)
    })
    expect(h.calls.filter((path) => path === '/api/v1/session')).toHaveLength(2)
  })

  it('does not publish ready with an advanced board and stale allowance', async () => {
    const h = harness()
    const socket = await h.start()
    h.setGame({
      ...current,
      snapshot: fixture.confirmedSnapshot as CurrentGame['snapshot'],
      allowance: { limit: 5, used: 1, remaining: 4 },
    })
    h.subscribe(socket, fixture.confirmedSnapshot as CurrentGame['snapshot'])
    expect(h.client.getState().connection).not.toBe('ready')
    await vi.waitFor(() => {
      expect(h.sockets).toHaveLength(2)
    })
    const next = h.sockets[1]
    if (!next) throw new Error('Game socket was not created')
    next.open()
    h.subscribe(next, fixture.confirmedSnapshot as CurrentGame['snapshot'])
    expect(h.client.getState().game?.allowance?.used).toBe(1)
    expect(h.client.getState().connection).not.toBe('ready')
  })

  it.each([
    ['unavailable', null, false, '2026-11-09'],
    ['different puzzle', '00000000-0000-4000-8000-000000000101', true, '2026-11-09'],
  ] as const)(
    'clears the old game immediately on a %s rollover',
    async (_name, nextId, available, day) => {
      const h = harness()
      const socket = await h.start()
      h.subscribe(socket)
      h.client.restorePendingConfirm(fixture.confirm as Confirm)
      h.setCurrentUnavailable(true)
      socket.emit({ schemaVersion: 1, type: 'puzzleChanged', puzzleId: nextId, available, day })
      expect(h.client.getState().game).toBeNull()
      expect(h.client.getState().previews).toEqual({})
      expect(h.client.getState().pendingConfirm).toEqual(fixture.confirm)
      await vi.waitFor(() => {
        expect(h.client.getState().connection).toBe('disconnected')
      })
      expect(h.client.getState().game).toBeNull()
    },
  )

  it('clears the old game when reconnect reports no current puzzle', async () => {
    const h = harness()
    await h.start()
    h.setCurrentUnavailable(true)
    await expect(h.client.connect()).rejects.toThrow('PUZZLE_UNAVAILABLE')
    expect(h.client.getState().game).toBeNull()
    expect(h.client.getState().connection).toBe('disconnected')
  })

  it('keeps the current board when a same-puzzle event triggers a refresh', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit({
      schemaVersion: 1,
      type: 'puzzleChanged',
      puzzleId,
      available: true,
      day: current.day,
    })
    expect(h.client.getState().game?.snapshot).toEqual(current.snapshot)
    await vi.waitFor(() => {
      expect(h.sockets).toHaveLength(2)
    })
  })

  it('clears the board on a stream puzzle-unavailable error', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit({
      schemaVersion: 1,
      type: 'error',
      error: {
        code: 'PUZZLE_UNAVAILABLE',
        message: 'No current puzzle',
        requestId: '00000000-0000-4000-8000-000000000099',
        retryable: true,
      },
    })
    expect(h.client.getState().game).toBeNull()
  })

  it.each(['new command', 'new connection'] as const)(
    'keeps the new confirmation busy after an old request settles: %s',
    async (scenario) => {
      const h = harness()
      const commandA = fixture.confirm as Confirm
      const commandB = { ...commandA, commandId: '00000000-0000-4000-8000-000000000501' }
      h.client.restorePendingConfirm(commandA)
      const first = deferred<Response>()
      h.setCommandReply(first.promise)
      const old = h.client.confirm()
      await expect(h.client.confirm()).rejects.toThrow('already in progress')
      if (scenario === 'new command') {
        h.setCommandReply(Promise.resolve(confirmed()))
        await h.client.resolveConfirm()
        h.client.restorePendingConfirm(commandB)
      } else h.client.disconnect()
      const second = deferred<Response>()
      h.setCommandReply(second.promise)
      const active = h.client.confirm()
      first.resolve(confirmed())
      if (scenario === 'new connection') await expect(old).rejects.toThrow('Connection changed')
      else await old
      await expect(h.client.confirm()).rejects.toThrow('already in progress')
      second.resolve(
        new Response(
          JSON.stringify({
            ...fixture.commandResult,
            commandId: scenario === 'new command' ? commandB.commandId : commandA.commandId,
          }),
        ),
      )
      await active
      expect(h.client.getState().pendingConfirm).toBeNull()
    },
  )

  it.each(['confirm', 'lookup'] as const)(
    'ignores a delayed %s result after disconnect',
    async (operation) => {
      const h = harness()
      await h.start()
      h.client.restorePendingConfirm(fixture.confirm as Confirm)
      const response = deferred<Response>()
      h.setCommandReply(response.promise)
      const pending = operation === 'confirm' ? h.client.confirm() : h.client.resolveConfirm()
      h.client.disconnect()
      const state = h.client.getState()
      response.resolve(confirmed())
      await expect(pending).rejects.toThrow('Connection changed')
      expect(h.client.getState()).toEqual(state)
      expect(h.sockets).toHaveLength(1)
    },
  )

  it('does not let a delayed failed confirmation overwrite a newer connection', async () => {
    const h = harness()
    await h.start()
    h.client.restorePendingConfirm(fixture.confirm as Confirm)
    const response = deferred<Response>()
    h.setCommandReply(response.promise)
    const pending = h.client.confirm()
    const next = await h.start()
    h.subscribe(next)
    const state = h.client.getState()
    response.reject(new TypeError('lost response'))
    await expect(pending).rejects.toThrow('lost response')
    expect(h.client.getState()).toEqual(state)
  })

  it('fences a pending lookup when the socket closes', async () => {
    const h = harness()
    const socket = await h.start()
    h.client.restorePendingConfirm(fixture.confirm as Confirm)
    const response = deferred<Response>()
    h.setCommandReply(response.promise)
    const pending = h.client.resolveConfirm()
    socket.drop()
    const state = h.client.getState()
    response.resolve(confirmed())
    await expect(pending).rejects.toThrow('Connection changed')
    expect(h.client.getState()).toEqual(state)
  })

  it.each([
    ['lookup', 'confirmed'],
    ['lookup', 'unknown'],
    ['confirm', 'confirmed'],
    ['confirm', 'unknown'],
    ['confirm', 'failure'],
  ] as const)(
    'preserves a newer retained command after a stale %s %s response',
    async (operation, outcome) => {
      const h = harness()
      const commandA = fixture.confirm as Confirm
      const commandB = { ...commandA, commandId: '00000000-0000-4000-8000-000000000501' }
      h.client.restorePendingConfirm(commandA)
      const delayed = deferred<Response>()
      h.setCommandReply(delayed.promise)
      const stale = operation === 'confirm' ? h.client.confirm() : h.client.resolveConfirm()
      h.setCommandReply(Promise.resolve(confirmed()))
      await h.client.resolveConfirm()
      h.client.restorePendingConfirm(commandB)
      const state = h.client.getState()
      if (outcome === 'failure') {
        delayed.reject(new TypeError('lost response'))
        await expect(stale).rejects.toThrow('lost response')
      } else {
        delayed.resolve(
          outcome === 'confirmed'
            ? confirmed()
            : new Response(
                JSON.stringify({
                  schemaVersion: 1,
                  puzzleId,
                  commandId: commandA.commandId,
                  outcome: 'unknown',
                  boardVersion: null,
                  allowance: null,
                  error: null,
                }),
              ),
        )
        await stale
      }
      expect(h.client.getState()).toEqual(state)
      expect(h.client.getState().pendingConfirm).toEqual(commandB)
    },
  )

  it('preserves a command restored by a listener during confirmation resolution', async () => {
    const h = harness()
    const socket = await h.start()
    h.subscribe(socket)
    const commandA = fixture.confirm as Confirm
    const commandB = { ...commandA, commandId: '00000000-0000-4000-8000-000000000501' }
    h.client.restorePendingConfirm(commandA)
    let restored = false
    h.client.subscribe((state) => {
      if (!restored && state.pendingConfirm === null) {
        restored = true
        h.client.restorePendingConfirm(commandB)
      }
    })
    h.setCommandReply(Promise.resolve(confirmed()))
    await h.client.resolveConfirm()
    expect(h.client.getState().pendingConfirm).toEqual(commandB)
    expect(h.client.getState().issue).toContain('restored')
    expect(h.calls.filter((path) => path === '/api/v1/session')).toHaveLength(1)
  })

  it('resolves a retained confirmation without reconnecting a disposed adapter', async () => {
    const h = harness()
    h.client.restorePendingConfirm(fixture.confirm as Confirm)
    h.setCommandReply(Promise.resolve(confirmed()))
    await h.client.resolveConfirm()
    expect(h.client.getState().pendingConfirm).toBeNull()
    expect(h.client.getState().connection).toBe('disconnected')
    expect(h.sockets).toHaveLength(0)
  })
})

describe('versioned subscription and preview receipts', () => {
  const action = fixture.confirm.actions[0] as Confirm['actions'][number]
  const handoff = (actions: Confirm['actions'][number][] = [], draftRevision = 0) => ({
    schemaVersion: 1,
    type: 'subscribed.v2',
    snapshot: current.snapshot,
    watermark: current.snapshot.boardVersion,
    previews: [],
    allowance: current.allowance,
    ownTurn: turn,
    actions,
    nextSequence: draftRevision,
    draftRevision,
  })
  const accepted = (sequence: number, draftRevision: number) => ({
    schemaVersion: 1,
    type: 'preview.accepted',
    puzzleId,
    turnId: turn.turnId,
    generation: turn.generation,
    sequence,
    actionId: action.actionId,
    draftRevision,
    actions: [action],
  })

  it('confirms only the latest correlated receipt and retains its exact action and revision', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff())
    expect(h.client.getState().connection).toBe('ready')
    h.client.preview(action)
    socket.emit(accepted(1, 1))
    await expect(h.client.confirm()).rejects.toThrow('not ready')
    socket.emit(accepted(0, 1))
    h.client.preview(action)
    socket.emit(accepted(0, 1))
    await expect(h.client.confirm()).rejects.toThrow('not ready')
    socket.emit(accepted(1, 2))
    const pending = deferred<Response>()
    h.setCommandReply(pending.promise)
    const result = h.client.confirm()
    expect(h.client.getState().pendingConfirm).toMatchObject({
      draftRevision: 2,
      actions: [action],
      turnRevision: turn.turnRevision,
    })
    pending.resolve(
      new Response(
        JSON.stringify({
          ...fixture.commandResult,
          commandId: h.client.getState().pendingConfirm?.commandId,
        }),
      ),
    )
    await result
  })

  it('requires cancellation for a recovered draft and does not trust duplicate handoffs', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff([action], 1))
    expect(() => {
      h.client.preview(action)
    }).toThrow('not ready')
    await expect(h.client.confirm()).rejects.toThrow('not ready')
    socket.emit(handoff())
    expect(h.client.getState().connection).not.toBe('ready')
  })

  it('resynchronizes on a receipt that reveals another tab changed the draft', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff())
    h.client.preview(action)
    socket.emit(accepted(0, 2))
    expect(h.client.getState().connection).not.toBe('ready')
    await expect(h.client.confirm()).rejects.toThrow()
  })

  it('never treats a lost receipt or an old socket as acceptance', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff())
    h.client.preview(action)
    socket.drop()
    await h.client.connect()
    const replacement = h.sockets.at(-1)
    if (!replacement) throw new Error('Missing replacement socket')
    replacement.open()
    replacement.emit(handoff([action], 1))
    socket.emit(accepted(0, 1))
    await expect(h.client.confirm()).rejects.toThrow('not ready')
    expect(() => {
      h.client.preview(action)
    }).toThrow('not ready')
  })

  it('preserves an acknowledged draft after another player changes the board', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff())
    h.client.preview(action)
    socket.emit(accepted(0, 1))
    const changedGroup = fixture.boardChanged.groups[0]
    const unchangedGroup = current.snapshot.groups[1]
    if (!changedGroup || !unchangedGroup) throw new Error('Missing fixture group')
    const snapshot = {
      ...current.snapshot,
      boardVersion: fixture.boardChanged.boardVersion,
      groups: [changedGroup, unchangedGroup],
    }
    h.setGame({ ...current, snapshot, ownTurn: turn })
    socket.emit({ ...fixture.boardChanged, groups: [changedGroup] })
    await vi.waitFor(() => expect(h.sockets).toHaveLength(2))
    const next = socketAt(h.sockets, 1)
    next.open()
    next.emit({ ...handoff([action], 1), snapshot, watermark: snapshot.boardVersion })
    expect(h.client.getState().issue).toBeNull()
    const response = deferred<Response>()
    h.setCommandReply(response.promise)
    const pending = h.client.confirm()
    expect(h.client.getState().pendingConfirm).toMatchObject({
      draftRevision: 1,
      actions: [action],
    })
    response.resolve(
      new Response(
        JSON.stringify({
          ...fixture.commandResult,
          commandId: h.client.getState().pendingConfirm?.commandId,
        }),
      ),
    )
    await pending
  })

  it('preserves acknowledged work after a rejected preview', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff())
    h.client.preview(action)
    socket.emit(accepted(0, 1))
    h.client.preview(action)
    socket.emit({
      schemaVersion: 1,
      type: 'preview.rejected',
      puzzleId,
      turnId: turn.turnId,
      generation: turn.generation,
      sequence: 1,
      actionId: action.actionId,
      error: {
        code: 'INVALID_ACTION',
        message: 'Invalid move',
        requestId: action.actionId,
        retryable: false,
      },
    })
    await vi.waitFor(() => expect(h.sockets).toHaveLength(2))
    const next = socketAt(h.sockets, 1)
    next.open()
    next.emit(handoff([action], 1))
    h.client.preview(action)
    expect(next.sent).toHaveLength(2)
  })

  it('preserves acknowledged work after a rejected confirmation', async () => {
    const h = harness()
    const socket = await h.start()
    socket.emit(handoff())
    h.client.preview(action)
    socket.emit(accepted(0, 1))
    h.setCommandReply(
      Promise.resolve(
        new Response(
          JSON.stringify({
            schemaVersion: 1,
            error: {
              code: 'INVALID_ACTION',
              message: 'Invalid move',
              requestId: action.actionId,
              retryable: false,
            },
          }),
          { status: 409 },
        ),
      ),
    )
    await expect(h.client.confirm()).rejects.toThrow('INVALID_ACTION')
    await vi.waitFor(() => expect(h.sockets).toHaveLength(2))
    const next = socketAt(h.sockets, 1)
    next.open()
    next.emit(handoff([action], 1))
    h.client.preview(action)
    expect(next.sent).toHaveLength(2)
  })

  it.each(['handoff first', 'response first'] as const)(
    'keeps surviving actions after undo when %s',
    async (order) => {
      const h = harness()
      const second = fixture.confirm.actions[1] as Confirm['actions'][number]
      const socket = await h.start()
      socket.emit(handoff())
      h.client.preview(action)
      socket.emit(accepted(0, 1))
      h.client.preview(second)
      socket.emit({ ...accepted(1, 2), actionId: second.actionId, actions: [action, second] })
      const response = deferred<Response>()
      h.setUndoReply(response.promise)
      const pending = h.client.undo(action.actionId)
      const newTurn = {
        ...turn,
        generation: turn.generation + 1,
        turnRevision: turn.turnRevision + 1,
      }
      const result = new Response(
        JSON.stringify({
          schemaVersion: 1,
          puzzleId,
          turnId: turn.turnId,
          requestId: '00000000-0000-4000-8000-000000000001',
          turnRevision: newTurn.turnRevision,
          generation: newTurn.generation,
          actionId: action.actionId,
        }),
      )
      h.setGame({ ...current, ownTurn: newTurn })
      if (order === 'handoff first') {
        socket.emit({ schemaVersion: 1, type: 'resyncRequired', puzzleId, reason: 'gap' })
        await vi.waitFor(() => expect(h.sockets).toHaveLength(2))
        socketAt(h.sockets, 1).open()
        socketAt(h.sockets, 1).emit({ ...handoff([second], 3), ownTurn: newTurn, nextSequence: 0 })
        response.resolve(result)
        await pending
      } else {
        response.resolve(result)
        await pending
        await vi.waitFor(() => expect(h.sockets).toHaveLength(2))
        socketAt(h.sockets, 1).open()
        socketAt(h.sockets, 1).emit({ ...handoff([second], 3), ownTurn: newTurn, nextSequence: 0 })
      }
      expect(h.client.getState().issue).toBeNull()
      h.client.preview(second)
      expect(socketAt(h.sockets, 1).sent).toHaveLength(2)
    },
  )

  it.each(['revision', 'actions', 'generation', 'turn revision', 'sequence'] as const)(
    'blocks a recovered draft when its %s conflicts with acknowledged work',
    async (conflict) => {
      const h = harness()
      const socket = await h.start()
      socket.emit(handoff())
      h.client.preview(action)
      socket.emit(accepted(0, 1))
      await h.client.connect()
      const next = socketAt(h.sockets, 1)
      next.open()
      next.emit({
        ...handoff(
          [conflict === 'actions' ? { ...action, kind: 'sort' } : action],
          conflict === 'revision' ? 2 : 1,
        ),
        ownTurn: {
          ...turn,
          generation: turn.generation + (conflict === 'generation' ? 1 : 0),
          turnRevision: turn.turnRevision + (conflict === 'turn revision' ? 1 : 0),
        },
        nextSequence: conflict === 'sequence' ? 2 : 1,
      })
      expect(h.client.getState().connection).toBe('ready')
      expect(() => h.client.preview(action)).toThrow('not ready')
      await expect(h.client.confirm()).rejects.toThrow('not ready')
    },
  )
})
