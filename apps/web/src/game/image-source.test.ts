import { describe, expect, it } from 'vitest'
import { imageSource } from './use-live-board'

describe('puzzle image sources', () => {
  const origin = 'http://127.0.0.1:5173'
  it('accepts local image files and known fixtures', () => {
    expect(imageSource('/images/imported.png', origin)).toBe('/images/imported.png')
    expect(imageSource(`${origin}/art/custom.webp`, origin)).toBe('/art/custom.webp')
    expect(imageSource('fixture://original-landscape', origin)).toBe('/original-landscape.svg')
  })
  it.each(['https://example.com/a.png', '//example.com/a.png', '/api/v1/a.png',
    '/images/../secret.png', '/images/%2e%2e/secret.png', '/images/a.png?key=1', 'data:image/png,x'])
    ('rejects unapproved source %s', (value) => {
      expect(imageSource(value, origin)).toBeNull()
    })
})
