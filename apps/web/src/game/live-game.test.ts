import { describe, expect, it, vi } from 'vitest'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import type { Action, CancelResult, Confirm, CurrentGame, Turn } from '@world-jigsaw/contracts'
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
    this.onclose?.call(this as unknown as WebSocket, new CloseEvent('close'))
  }
}

function harness() {
  const sockets: TestSocket[] = []
  const calls: { path: string; body: unknown }[] = []
  let game = structuredClone(current)
  let confirmFailure = false
  let confirmResult: unknown = fixture.commandResult
  let cancelFailure = false
  const fetcher = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const body: { requestId: string } | undefined =
      typeof init?.body === 'string' ? (JSON.parse(init.body) as { requestId: string }) : undefined
    calls.push({ path, body })
    let result: unknown
    if (path === '/api/v1/session')
      result = { schemaVersion: 1, displayName: 'Player', expiresAt: '2026-11-09T00:00:00Z' }
    else if (path === '/api/v1/game/current') result = game
    else if (path === '/api/v1/game/turns') {
      game = { ...game, ownTurn: turn }
      result = turn
    } else if (path.endsWith('/confirm')) {
      if (confirmFailure) throw new TypeError('connection lost')
      result = confirmResult
    } else if (path.includes('/commands/')) result = confirmResult
    else if (path.endsWith('/undo'))
      return Promise.resolve(
        new Response(
          JSON.stringify({
            schemaVersion: 1,
            error: {
              code: 'INVALID_ACTION',
              message: 'Dependent action',
              requestId: '00000000-0000-4000-8000-000000000099',
              retryable: false,
            },
          }),
          { status: 400 },
        ),
      )
    else if (path.endsWith('/cancel')) {
      if (cancelFailure) throw new TypeError('connection lost')
      result = {
        schemaVersion: 1,
        puzzleId,
        turnId: turn.turnId,
        requestId: body?.requestId ?? '',
        outcome: 'canceled',
        boardVersion: 7,
        allowance: { limit: 5, used: 0, remaining: 5 },
        commandId: null,
      } satisfies CancelResult
      game = { ...game, ownTurn: null }
    } else throw new Error(`Unexpected request ${path}`)
    return Promise.resolve(
      new Response(JSON.stringify(result), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
  let id = 0
  const deps: LiveGameDependencies = {
    fetch: fetcher,
    socket: () => {
      const socket = new TestSocket()
      sockets.push(socket)
      return socket
    },
    origin: 'http://localhost:8080',
    id: () => `00000000-0000-4000-8000-${String(++id).padStart(12, '0')}`,
    setInterval: vi.fn(() => 1) as typeof setInterval,
    clearInterval: vi.fn() as typeof clearInterval,
  }
  const client = new LiveGame(deps)
  async function connect() {
    await client.connect()
    const socket = sockets.at(-1)
    if (!socket) throw new Error('Missing socket')
    socket.open()
    socket.emit({
      schemaVersion: 1,
      type: 'subscribed',
      snapshot: game.snapshot,
      watermark: game.snapshot.boardVersion,
      previews: [],
    })
    return socket
  }
  return {
    client,
    connect,
    sockets,
    calls,
    setGame: (value: CurrentGame) => {
      game = value
    },
    setConfirmFailure: (value: boolean) => {
      confirmFailure = value
    },
    setConfirmResult: (value: unknown) => {
      confirmResult = value
    },
    setCancelFailure: (value: boolean) => {
      cancelFailure = value
    },
  }
}

const preview = fixture.preview
const action = preview.action as Action
function frame(actionValue: Action, sequence: number, generation = turn.generation) {
  return { ...preview, turnId: turn.turnId, generation, sequence, action: actionValue }
}

describe('live game adapter', () => {
  it('reports a rejected preview and permits cancellation after rejected undo', async () => {
    const h = harness()
    const socket = await h.connect()
    await h.client.begin()
    h.client.preview(action)
    socket.emit({
      schemaVersion: 1,
      type: 'preview.rejected',
      puzzleId,
      turnId: turn.turnId,
      generation: turn.generation,
      sequence: 0,
      actionId: action.actionId,
      error: {
        code: 'PIECE_BUSY',
        message: 'Busy',
        requestId: '00000000-0000-4000-8000-000000000099',
        retryable: false,
      },
    })
    expect(h.client.getState().previewPending).toBe(false)
    expect(h.client.getState().issue).toContain('PIECE_BUSY')
    await expect(h.client.undo(action.actionId)).rejects.toThrow('Dependent action')
    const result = await h.client.cancel()
    expect(result.outcome).toBe('canceled')
  })

  it('keeps previews temporary, applies committed events, and fences canceled generations', async () => {
    const h = harness()
    const socket = await h.connect()
    expect(socket.sent[0]).toEqual({
      schemaVersion: 1,
      type: 'subscribe',
      puzzleId,
      afterBoardVersion: null,
    })
    socket.emit(frame(action, 1))
    expect(h.client.getState().game?.snapshot).toEqual(current.snapshot)
    expect(
      Object.values(h.client.getState().previews[turn.turnId.toLowerCase()]?.actions ?? {}),
    ).toEqual([action])
    socket.emit({
      ...fixture.previewCleared,
      turnId: turn.turnId,
      generation: turn.generation,
      reason: 'canceled',
    })
    socket.emit(frame(action, 2))
    expect(h.client.getState().previews[turn.turnId.toLowerCase()]?.actions).toEqual({})
    socket.emit(fixture.boardChanged)
    expect(h.client.getState().game?.snapshot).toEqual(fixture.confirmedSnapshot)
    socket.emit(fixture.boardChanged)
    expect(h.client.getState().game?.snapshot.boardVersion).toBe(8)
  })

  it('retains the exact command and server-authored action after a lost Confirm response', async () => {
    const h = harness()
    const socket = await h.connect()
    await h.client.begin()
    const pendingAction = h.client.newAction({ ...action })
    h.client.preview(pendingAction)
    expect(() => {
      h.client.preview(pendingAction)
    }).toThrow('not ready')
    socket.emit(frame(pendingAction, 100))
    h.setConfirmFailure(true)
    await expect(h.client.confirm()).rejects.toThrow('connection lost')
    const pending = h.client.getState().pendingConfirm
    if (!pending) throw new Error('Missing pending confirmation')
    expect(pending.actions).toEqual([pendingAction])
    expect(() => {
      h.client.preview(pendingAction)
    }).toThrow('not ready')
    h.setConfirmResult({ ...fixture.commandResult, commandId: pending.commandId })
    h.setConfirmFailure(false)
    await h.client.confirm()
    const payloads = h.calls
      .filter((call) => call.path.endsWith('/confirm'))
      .map((call) => call.body)
    expect(payloads).toEqual([pending, pending])
    expect(h.client.getState().pendingConfirm).toBeNull()
  })

  it('reconnects with fresh overlays and keeps submitted sequence independent of received sequence', async () => {
    const h = harness()
    const socket = await h.connect()
    await h.client.begin()
    h.client.preview(action)
    socket.emit(frame(action, 100))
    socket.drop()
    const next = await h.connect()
    h.client.preview({
      ...action,
      intendedTransform: { ...action.intendedTransform, rotation: 90000 },
    })
    expect(next.sent[1]).toMatchObject({
      type: 'preview.submit',
      sequence: 1,
      action: { actionId: action.actionId },
    })
    expect(h.client.getState().previews).toEqual({})
  })

  it('does not apply a board event with a version gap and requests a fresh subscription', async () => {
    const h = harness()
    const socket = await h.connect()
    socket.emit({ ...fixture.boardChanged, boardVersion: 9 })
    expect(h.client.getState().game?.snapshot).toEqual(current.snapshot)
    expect(h.client.getState().connection).toBe('connecting')
    await vi.waitFor(() => {
      expect(h.sockets).toHaveLength(2)
    })
    expect(h.sockets[1]?.sent).toEqual([])
  })

  it('retries a lost cancel with the same request ID even after the clear arrives', async () => {
    const h = harness()
    const socket = await h.connect()
    await h.client.begin()
    socket.emit(frame(action, 1))
    h.setCancelFailure(true)
    await expect(h.client.cancel()).rejects.toThrow('connection lost')
    socket.emit({
      ...fixture.previewCleared,
      turnId: turn.turnId,
      generation: turn.generation,
      reason: 'canceled',
    })
    h.setCancelFailure(false)
    await h.client.cancel()
    const payloads = h.calls
      .filter((call) => call.path.endsWith('/cancel'))
      .map((call) => call.body)
    expect(payloads[1]).toEqual(payloads[0])
  })

  it('retains unresolved restored commands for lookup without inventing success', async () => {
    const h = harness()
    const pending = fixture.confirm as Confirm
    h.client.restorePendingConfirm(pending)
    h.setConfirmResult({
      schemaVersion: 1,
      puzzleId,
      commandId: pending.commandId,
      outcome: 'unknown',
      boardVersion: null,
      allowance: null,
      error: null,
    })
    await h.client.resolveConfirm()
    expect(h.client.getState().pendingConfirm).toEqual(pending)
    expect(h.client.getState().issue).toContain('unknown')
  })
})
