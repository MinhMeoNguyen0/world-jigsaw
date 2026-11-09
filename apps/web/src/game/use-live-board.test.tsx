import { createElement, type PointerEvent } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import fixture from '../../../../packages/contracts/v1/fixtures/board-and-batch.json'
import type { CurrentGame } from '@world-jigsaw/contracts'
import { useLiveBoard } from './use-live-board'

const mock = vi.hoisted(() => ({
  state: {},
  sendCursor: vi.fn(),
  recover: vi.fn(),
}))
vi.mock('./live-game.ts', () => ({
  LiveGame: class {
    getState() {
      return mock.state
    }
    provenDraft() {
      return []
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
    begin() {
      return Promise.resolve(null)
    }
  },
}))

function setup() {
  const game: CurrentGame = {
    schemaVersion: 1,
    day: '2026-11-08',
    serverTime: '2026-11-08T12:00:00Z',
    endsAt: '2026-11-09T00:00:00Z',
    definition: fixture.definition as CurrentGame['definition'],
    snapshot: fixture.snapshot as CurrentGame['snapshot'],
    allowance: { limit: 5, used: 0, remaining: 5 },
    ownTurn: null,
  }
  mock.state = { connection: 'ready', game, previews: {}, cursors: {} }
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
  vi.clearAllMocks()
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
