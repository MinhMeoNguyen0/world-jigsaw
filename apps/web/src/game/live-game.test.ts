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
  close() { this.onopen = null }
  open() { this.onopen?.call(this as unknown as WebSocket, new Event('open')) }
  emit(value: unknown) {
    this.onmessage?.call(this as unknown as WebSocket, new MessageEvent('message', { data: JSON.stringify(value) }))
  }
  drop() { this.onclose?.call(this as unknown as WebSocket, new Event('close') as CloseEvent) }
}

function harness() {
  const sockets: TestSocket[] = []
  let game = structuredClone(current)
  let commandReply: Promise<Response> | null = null
  const calls: string[] = []
  const fetcher = vi.fn((input: RequestInfo | URL) => {
    const path = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    calls.push(path)
    if (path.includes('/commands/') || path.endsWith('/confirm')) {
      if (!commandReply) throw new Error('Missing command response')
      return commandReply
    }
    const value = path === '/api/v1/session'
      ? { schemaVersion: 1, displayName: 'Player', expiresAt: '2026-11-09T00:00:00Z' }
      : path === '/api/v1/game/current' ? game : null
    if (!value) throw new Error(`Unexpected request ${path}`)
    return Promise.resolve(new Response(JSON.stringify(value)))
  })
  const deps: LiveGameDependencies = {
    fetch: fetcher,
    socket: () => { const socket = new TestSocket(); sockets.push(socket); return socket },
    origin: 'http://localhost:8080',
    id: () => '00000000-0000-4000-8000-000000000001',
  }
  const client = new LiveGame(deps)
  async function start() {
    await client.connect()
    const socket = sockets.at(-1)!
    socket.open()
    return socket
  }
  function subscribe(socket: TestSocket, snapshot = current.snapshot) {
    socket.emit({ schemaVersion: 1, type: 'subscribed', snapshot, watermark: snapshot.boardVersion, previews: [] })
  }
  return {
    client, sockets, calls, start, subscribe,
    setGame: (value: CurrentGame) => { game = value },
    setCommandReply: (value: Promise<Response>) => { commandReply = value },
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function confirmed() {
  return new Response(JSON.stringify(fixture.commandResult))
}

describe('live game adapter', () => {
  it.each([
    ['begin', null, turn],
    ['cancel', turn, null],
    ['undo', turn, { ...turn, generation: turn.generation + 1, turnRevision: turn.turnRevision + 1 }],
  ])('keeps the handoff unready when another tab can %s without a board change', async (_operation, before, after) => {
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
  })

  it('does not publish ready with an advanced board and stale allowance', async () => {
    const h = harness()
    const socket = await h.start()
    h.setGame({ ...current, snapshot: fixture.confirmedSnapshot as CurrentGame['snapshot'], allowance: { limit: 5, used: 1, remaining: 4 } })
    h.subscribe(socket, fixture.confirmedSnapshot as CurrentGame['snapshot'])
    expect(h.client.getState().connection).not.toBe('ready')
    await vi.waitFor(() => expect(h.sockets).toHaveLength(2))
    const next = h.sockets[1]!
    next.open()
    h.subscribe(next, fixture.confirmedSnapshot as CurrentGame['snapshot'])
    expect(h.client.getState().game?.allowance?.used).toBe(1)
    expect(h.client.getState().connection).not.toBe('ready')
  })

  it.each(['confirm', 'lookup'] as const)('ignores a delayed %s result after disconnect', async (operation) => {
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
  })

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
