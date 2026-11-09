import { describe, expect, it } from 'vitest'
import { canMoveGroup } from './move-availability.ts'

describe('move availability', () => {
  it('blocks interaction at zero committed moves and while availability is unknown', () => {
    expect(canMoveGroup(0, 0)).toBe(false)
    expect(canMoveGroup(null, 0)).toBe(false)
    expect(canMoveGroup(1, null)).toBe(false)
  })

  it('blocks all actions when the batch uses the remaining moves', () => {
    expect(canMoveGroup(1, 1)).toBe(false)
    expect(canMoveGroup(1, 2)).toBe(false)
    expect(canMoveGroup(1, 0)).toBe(true)
  })
})
