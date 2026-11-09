import { useEffect, useState } from 'react'
import { validate } from '@world-jigsaw/contracts'
import type { PresenceSnapshot } from '@world-jigsaw/contracts'
import { t } from '../i18n/translate'

/** Presence is a current snapshot of game sockets, not a chat transcript or authentication. */
export function PlayerStrip({ puzzleId, ready }: { puzzleId: string | undefined; ready: boolean }) {
  const [snapshot, setSnapshot] = useState<PresenceSnapshot | null>(null)
  useEffect(() => {
    if (!puzzleId || !ready) return
    const controller = new AbortController()
    let active = true
    const refresh = async () => {
      try {
        const response = await fetch(
          `/api/v1/game/presence?puzzleId=${encodeURIComponent(puzzleId)}`,
          { credentials: 'same-origin', cache: 'no-store', signal: controller.signal },
        )
        const value: unknown = await response.json()
        if (
          !response.ok ||
          !validate('PresenceSnapshot', value).valid ||
          (value as PresenceSnapshot).puzzleId.toLowerCase() !== puzzleId.toLowerCase()
        )
          throw new Error('Invalid presence')
        if (active) setSnapshot(value as PresenceSnapshot)
      } catch {
        if (active) setSnapshot(null)
      }
    }
    void refresh()
    const timer = window.setInterval(() => {
      void refresh()
    }, 5000)
    return () => {
      active = false
      controller.abort()
      window.clearInterval(timer)
    }
  }, [puzzleId, ready])
  const players =
    ready && puzzleId && snapshot?.puzzleId.toLowerCase() === puzzleId.toLowerCase()
      ? snapshot.players
      : null
  return (
    <div className="players" aria-label={t('presence.label')}>
      {players && (
        <div className="avatars">
          {players.slice(0, 8).map((player, index) => (
            <span
              className="person-avatar"
              key={`${player.displayName}-${String(index)}`}
              data-tone={index}
              title={player.displayName}
              aria-label={player.displayName}
            >
              {player.displayName.slice(-2)}
            </span>
          ))}
          {players.length > 8 && (
            <span className="avatar-more">{t('presence.more', { count: players.length - 8 })}</span>
          )}
        </div>
      )}
      <span className="players-full">
        {players ? t('presence.online', { count: players.length }) : t('presence.connecting')}
      </span>
      <span className="players-compact">
        {players ? t('presence.online', { count: players.length }) : t('presence.connecting')}
      </span>
      <span className="players-short">
        {players ? t('presence.online', { count: players.length }) : t('presence.connecting')}
      </span>
    </div>
  )
}
