import { useEffect, useRef, useState, type ReactNode } from 'react'
import { LiveChat, type LiveChatState } from './live-chat'
import { t } from '../i18n/translate'

export function ChatPanel({ presence, ready }: { presence: ReactNode; ready: boolean }) {
  const [chat] = useState(
    () =>
      new LiveChat({
        fetch: window.fetch.bind(window),
        id: () => crypto.randomUUID(),
      }),
  )
  const [state, setState] = useState<LiveChatState>({ connection: 'disconnected', messages: [] })
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [notice, setNotice] = useState('')
  const [sending, setSending] = useState(false)
  const started = useRef(false)
  useEffect(() => {
    const unsubscribe = chat.subscribe(setState)
    return () => {
      unsubscribe()
      chat.disconnect()
      started.current = false
    }
  }, [chat])
  useEffect(() => {
    if (ready && !started.current) {
      started.current = true
      void chat.connect()
    }
  }, [chat, ready])
  const send = async () => {
    if (sending || !input.trim()) return
    setSending(true)
    const result = await chat.send(input)
    setSending(false)
    if (result === 'accepted') setInput('')
    setNotice(result === 'accepted' ? '' : t(`chat.${result}`))
  }
  return (
    <>
      {open && (
        <section className="chat-panel" aria-label={t('chat.title')}>
          <div className="chat-panel-head">
            <div>
              <strong>{t('chat.title')}</strong>
              <span>{t(`chat.${state.connection}`)}</span>
            </div>
            <div className="chat-panel-actions">
              {state.connection === 'disconnected' && (
                <button
                  type="button"
                  className="demo-move"
                  onClick={() => {
                    void chat.connect()
                  }}
                >
                  {t('chat.reconnect')}
                </button>
              )}
              <button
                type="button"
                className="chat-close"
                onClick={() => {
                  setOpen(false)
                }}
                aria-label={t('chat.close')}
              >
                {t('symbol.close')}
              </button>
            </div>
          </div>
          <div className="chat-messages" aria-live="polite">
            {state.messages.map((message, index) => (
              <div className="chat-entry" key={`${message.messageId}-${String(index)}`}>
                <span className="chat-avatar">{message.displayName.slice(0, 1)}</span>
                <div className="chat-copy">
                  <div className="chat-meta">
                    <strong>{message.displayName}</strong>
                    <time dateTime={message.sentAt}>
                      {new Date(message.sentAt).toLocaleTimeString([], {
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </time>
                  </div>
                  <p>{message.text}</p>
                </div>
              </div>
            ))}
          </div>
          {notice && (
            <p className="chat-pending-note" role="status">
              {notice}
            </p>
          )}
          <form
            className="chat-compose"
            onSubmit={(event) => {
              event.preventDefault()
              void send()
            }}
          >
            <input
              aria-label={t('chat.input')}
              placeholder={t('chat.placeholder')}
              value={input}
              onChange={(event) => {
                setInput(event.currentTarget.value)
              }}
            />
            <button
              type="submit"
              disabled={sending || state.connection !== 'ready' || !input.trim()}
            >
              {t('chat.send')}
            </button>
          </form>
        </section>
      )}
      <footer className="footer">
        {presence}
        <button
          type="button"
          className="chat-trigger"
          aria-expanded={open}
          onClick={() => {
            setOpen(!open)
          }}
        >
          <span aria-hidden="true">{t('symbol.chat')}</span>
          <span>{t('chat.title')}</span>
          <span className="chat-count">{state.messages.length}</span>
        </button>
        <span className="preview-label">{t('chat.private')}</span>
      </footer>
    </>
  )
}
