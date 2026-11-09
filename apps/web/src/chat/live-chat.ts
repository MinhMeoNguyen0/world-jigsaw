import { validate } from '@world-jigsaw/contracts'
import type { ChatAccepted, ChatMessage } from '@world-jigsaw/contracts'

/** Private-local relay identity, independent of the game session. This is not authentication. */
export interface LiveChatDependencies {
  fetch: typeof fetch
  subject: string
  id: () => string
}

export interface LiveChatState {
  connection: 'disconnected' | 'connecting' | 'ready'
  messages: ChatMessage[]
}

export type ChatSendResult = 'accepted' | 'unavailable' | 'unknown' | 'invalid'

const MAX_BODY_BYTES = 64 * 1024
const MAX_EVENT_CHARS = 64 * 1024
const MAX_DISPLAYED_MESSAGES = 100

/** Live only: displayed messages are discarded on disconnect or reconnect. */
export class LiveChat {
  private state: LiveChatState = { connection: 'disconnected', messages: [] }
  private listeners = new Set<(state: LiveChatState) => void>()
  private controller: AbortController | null = null
  private generation = 0

  private readonly deps: LiveChatDependencies

  constructor(deps: LiveChatDependencies) {
    this.deps = deps
  }

  subscribe(listener: (state: LiveChatState) => void): () => void {
    this.listeners.add(listener)
    listener(this.state)
    return () => this.listeners.delete(listener)
  }

  private update(state: LiveChatState) {
    this.state = state
    for (const listener of this.listeners) listener(state)
  }

  async connect(): Promise<void> {
    this.disconnect()
    const generation = this.generation
    const controller = new AbortController()
    this.controller = controller
    this.update({ connection: 'connecting', messages: [] })
    try {
      const response = await this.deps.fetch('/api/v1/chat/stream', {
        headers: { 'X-Local-Subject': this.deps.subject, Accept: 'text/event-stream' },
        signal: controller.signal,
        cache: 'no-store',
      })
      if (generation !== this.generation) return
      if (
        !response.ok ||
        !response.body ||
        !response.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream')
      ) {
        this.disconnect()
        return
      }
      this.update({ connection: 'ready', messages: [] })
      void this.read(response.body, generation)
    } catch {
      if (generation === this.generation) this.disconnect()
    }
  }

  disconnect(): void {
    this.generation++
    this.controller?.abort()
    this.controller = null
    this.update({ connection: 'disconnected', messages: [] })
  }

  async send(text: string): Promise<ChatSendResult> {
    if (this.state.connection !== 'ready') return 'unavailable'
    const messageId = this.deps.id()
    const payload = { schemaVersion: 1, messageId, text }
    const body = JSON.stringify(payload)
    if (
      !validate('ChatSend', payload).valid ||
      new TextEncoder().encode(body).length > MAX_BODY_BYTES
    )
      return 'invalid'
    try {
      const response = await this.deps.fetch('/api/v1/chat/messages', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Local-Subject': this.deps.subject },
        body,
      })
      if (!response.ok) return response.status >= 500 ? 'unavailable' : 'invalid'
      if (response.status !== 202) return 'unknown'
      const result: unknown = await response.json()
      if (
        !validate('ChatAccepted', result).valid ||
        (result as ChatAccepted).messageId !== messageId
      )
        return 'unknown'
      return 'accepted'
    } catch {
      // The relay may have delivered the request before the response was lost. Do not retry it.
      return 'unknown'
    }
  }

  private async read(body: ReadableStream<Uint8Array>, generation: number): Promise<void> {
    const reader = body.getReader()
    const decoder = new TextDecoder('utf-8', { fatal: true })
    let buffer = ''
    let data: string[] = []
    let eventSize = 0
    try {
      while (generation === this.generation) {
        const chunk = await reader.read()
        if (generation !== this.generation) break
        if (chunk.done) break
        buffer += decoder.decode(chunk.value, { stream: true })
        let newline: number
        while ((newline = buffer.indexOf('\n')) >= 0) {
          let line = buffer.slice(0, newline)
          buffer = buffer.slice(newline + 1)
          if (line.endsWith('\r')) line = line.slice(0, -1)
          eventSize += line.length + 1
          if (eventSize > MAX_EVENT_CHARS) throw new Error('Chat event too large')
          if (line === '') {
            if (data.length) this.receive(data.join('\n'))
            data = []
            eventSize = 0
          } else if (line.startsWith('data:')) {
            const value = line.slice(5)
            data.push(value.startsWith(' ') ? value.slice(1) : value)
          } else if (line === 'data') {
            data.push('')
          }
        }
        if (eventSize + buffer.length > MAX_EVENT_CHARS) throw new Error('Chat event too large')
      }
    } catch {
      // A malformed or interrupted stream must be reconnected explicitly.
    } finally {
      reader.releaseLock()
      if (generation === this.generation) this.disconnect()
    }
  }

  private receive(data: string): void {
    try {
      const value: unknown = JSON.parse(data)
      if (!validate('ChatMessage', value).valid) return
      const messages = [...this.state.messages, value as ChatMessage].slice(-MAX_DISPLAYED_MESSAGES)
      this.update({ ...this.state, messages })
    } catch {
      // Ignore malformed events rather than treating them as messages.
    }
  }
}
