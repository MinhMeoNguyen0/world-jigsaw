import { describe, expect, it, vi } from 'vitest'
import { LiveChat, type LiveChatState } from './live-chat'

const messageId = '00000000-0000-4000-8000-000000000002'
const message = {
  schemaVersion: 1,
  messageId,
  displayName: 'Player',
  text: '👋 hello',
  sentAt: '2026-11-08T00:00:00Z',
}
const tick = () => new Promise((resolve) => setTimeout(resolve, 0))

function harness() {
  let stream!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      stream = controller
    },
  })
  const fetcher = vi.fn<typeof fetch>()
  fetcher.mockResolvedValueOnce(
    new Response(body, { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } }),
  )
  const chat = new LiveChat({ fetch: fetcher, id: () => messageId })
  let state!: LiveChatState
  chat.subscribe((next) => {
    state = next
  })
  return { chat, fetcher, stream, state: () => state }
}

describe('live chat', () => {
  it('parses fragmented UTF-8 events and drops history on disconnect', async () => {
    const h = harness()
    await h.chat.connect()
    expect(h.state().connection).toBe('ready')
    const bytes = new TextEncoder().encode(`: connected\n\ndata: ${JSON.stringify(message)}\n\n`)
    const split = bytes.indexOf(240) + 2
    h.stream.enqueue(bytes.slice(0, split))
    h.stream.enqueue(bytes.slice(split))
    await tick()
    expect(h.state().messages).toEqual([message])
    h.chat.disconnect()
    expect(h.state()).toEqual({ connection: 'disconnected', messages: [] })
    h.stream.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(message)}\n\n`))
    await tick()
    expect(h.state().messages).toEqual([])
  })

  it('reports acknowledgement, rejection, and unknown outcomes without retrying', async () => {
    const h = harness()
    await h.chat.connect()
    h.fetcher.mockResolvedValueOnce(
      new Response(JSON.stringify({ schemaVersion: 1, messageId }), { status: 202 }),
    )
    expect(await h.chat.send('hello')).toBe('accepted')
    expect(h.fetcher.mock.calls[1]?.[1]).toMatchObject({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
    })
    h.fetcher.mockResolvedValueOnce(new Response('', { status: 400 }))
    expect(await h.chat.send('bad')).toBe('invalid')
    h.fetcher.mockRejectedValueOnce(new Error('lost response'))
    expect(await h.chat.send('maybe delivered')).toBe('unknown')
    expect(h.fetcher).toHaveBeenCalledTimes(4)
    expect(await h.chat.send('x'.repeat(65536))).toBe('invalid')
    expect(h.fetcher).toHaveBeenCalledTimes(4)
    h.chat.disconnect()
  })

  it('ignores invalid events and clears messages when the stream closes', async () => {
    const h = harness()
    await h.chat.connect()
    h.stream.enqueue(new TextEncoder().encode(`data: {}\n\ndata: ${JSON.stringify(message)}\n\n`))
    await tick()
    expect(h.state().messages).toEqual([message])
    h.stream.close()
    await tick()
    expect(h.state()).toEqual({ connection: 'disconnected', messages: [] })
  })
})
