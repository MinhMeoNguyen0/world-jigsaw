// @vitest-environment jsdom
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Action, CurrentGame } from '@world-jigsaw/contracts'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import type { LiveGameState } from '../game/live-game'
import { HomePage } from './HomePage'

const mock = vi.hoisted(() => ({
  state: {} as LiveGameState,
  actions: [] as Action[],
  pendingUndo: false,
  listener: null as ((state: LiveGameState) => void) | null,
  preview: vi.fn<(action: Action) => void>(),
  undo: vi.fn(),
  begin: vi.fn(),
}))

vi.mock('../chat/ChatPanel.tsx', () => ({ ChatPanel: () => null }))
vi.mock('../game/move-availability.ts', () => ({ canMoveGroup: () => true }))
vi.mock('../game/live-game.ts', () => ({
  LiveGame: class {
    getState() {
      return mock.state
    }
    provenDraft() {
      return mock.pendingUndo ? null : mock.actions
    }
    provenDraftRevision() {
      return mock.pendingUndo ? null : 0
    }
    recoveredPreviewActionId() {
      return null
    }
    pendingUndoActionId() {
      return mock.pendingUndo ? 'action' : null
    }
    newAction(action: object) {
      return { ...action, actionId: 'action' }
    }
    sendCursor() {}
    recoverInterruptedPreview() {}
    abandonInterruptedPreview() {}
    heartbeat() {}
    preview = mock.preview
    undo = mock.undo
    begin = mock.begin
    subscribe(listener: (state: LiveGameState) => void) {
      mock.listener = listener
      listener(mock.state)
      return () => {
        mock.listener = null
      }
    }
    connect() {
      return Promise.resolve()
    }
    disconnect() {}
  },
}))

const turn = {
  puzzleId: fixture.snapshot.puzzleId,
  turnId: fixture.preview.turnId,
  generation: 1,
  boardVersion: fixture.snapshot.boardVersion,
} as NonNullable<CurrentGame['ownTurn']>

let root: Root | undefined
let container: HTMLDivElement | undefined

afterEach(async () => {
  if (root) await act(async () => root?.unmount())
  container?.remove()
  root = undefined
  container = undefined
  mock.listener = null
  mock.actions = []
  mock.pendingUndo = false
  mock.preview.mockReset()
  mock.undo.mockReset()
  mock.begin.mockReset()
  vi.unstubAllGlobals()
})

async function interruptedDrag(kind: 'opening' | 'existing') {
  const game: CurrentGame = {
    schemaVersion: 1,
    day: '2026-11-08',
    serverTime: '2026-11-08T12:00:00Z',
    endsAt: '2026-11-09T00:00:00Z',
    definition: fixture.definition as CurrentGame['definition'],
    snapshot: fixture.snapshot as CurrentGame['snapshot'],
    allowance: { limit: 5, used: 0, remaining: 5 },
    ownTurn: kind === 'existing' ? turn : null,
  }
  mock.state = {
    connection: 'ready',
    game,
    turnBase: game.ownTurn ? game.snapshot : null,
    previews: {},
    peerRecoveryViews: {},
    cursors: {},
    previewPending: false,
  } as LiveGameState
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise(() => {})),
  )
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
  mock.begin.mockImplementation(() => {
    mock.state = { ...mock.state, game: { ...game, ownTurn: turn } }
    mock.listener?.(mock.state)
    return Promise.resolve(turn)
  })
  let rejectUndo!: (error: Error) => void
  mock.undo.mockImplementationOnce(() => {
    mock.pendingUndo = true
    return new Promise((_, reject) => {
      rejectUndo = reject
    })
  })
  container = document.createElement('div')
  document.body.append(container)
  root = createRoot(container)
  await act(async () => root?.render(createElement(HomePage)))
  const svg = container.querySelector('svg.board') as SVGSVGElement
  svg.getScreenCTM = () => ({ inverse: () => ({}) }) as DOMMatrix
  svg.setPointerCapture = vi.fn()
  const piece = container.querySelector('[data-group-id]')
  if (!piece) throw new Error('Missing puzzle piece')
  const pointer = (type: string, x: number, y: number) =>
    new MouseEvent(type, { bubbles: true, button: 0, clientX: x, clientY: y })
  await act(async () => {
    piece.dispatchEvent(pointer('pointerdown', 10, 20))
    svg.dispatchEvent(pointer('pointermove', 30, 50))
    await Promise.resolve()
  })
  const action = mock.preview.mock.calls[0]?.[0]
  if (!action) throw new Error('Expected a preview')
  mock.actions = [action]
  await act(async () => svg.dispatchEvent(pointer('pointercancel', 30, 50)))
  expect(mock.undo).toHaveBeenCalledOnce()
  const button = container.querySelector<HTMLButtonElement>('[aria-label="Undo last change"]')
  if (!button) throw new Error('Missing Undo button')
  expect(button.disabled).toBe(true)
  return { button, rejectUndo }
}

describe('interrupted drag undo', () => {
  it('displays a definitive undo rejection', async () => {
    const { rejectUndo } = await interruptedDrag('opening')
    await act(async () => {
      mock.pendingUndo = false
      rejectUndo(new Error('Undo rejected by server'))
      await Promise.resolve()
    })
    expect(container?.querySelector('[role="status"]')?.textContent).toContain(
      'Error: Undo rejected by server',
    )
  })

  it.each([
    ['opening', true],
    ['opening', false],
    ['existing', true],
    ['existing', false],
  ] as const)(
    'enables retry for an unresolved %s turn undo (published: %s)',
    async (kind, published) => {
      const { button, rejectUndo } = await interruptedDrag(kind)
      await act(async () => {
        if (published) {
          mock.listener?.({ ...mock.state, issue: 'Network failure' })
          await Promise.resolve()
        }
        rejectUndo(new Error(published ? 'Network failure' : 'Undo response mismatch'))
        await Promise.resolve()
      })
      expect(button.disabled).toBe(false)
      mock.undo.mockResolvedValueOnce({})
      await act(async () => button.click())
      expect(mock.undo).toHaveBeenCalledTimes(2)
      expect(mock.undo).toHaveBeenLastCalledWith('action')
    },
  )
})
