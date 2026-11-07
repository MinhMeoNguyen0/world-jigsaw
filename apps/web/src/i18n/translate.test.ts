import { describe, expect, it } from 'vitest'
import en from './locales/en.json'
import { createTranslator, t } from './translate.ts'

describe('createTranslator', () => {
  const translate = createTranslator({
    greeting: 'Hello, {name}!',
    moves: '{count} of {total} moves left',
    plain: 'No placeholders here',
  })

  it('returns the template when no params are given', () => {
    expect(translate('plain')).toBe('No placeholders here')
  })

  it('substitutes every placeholder', () => {
    expect(translate('moves', { count: 3, total: 5 })).toBe('3 of 5 moves left')
  })

  it('leaves a placeholder visible when its param is missing', () => {
    expect(translate('greeting', {})).toBe('Hello, {name}!')
  })
})

describe('English catalog', () => {
  it('names the product', () => {
    expect(t('app.name')).toBe('World Jigsaw')
  })

  it('has no empty messages', () => {
    for (const [key, message] of Object.entries(en)) {
      expect(message.trim(), key).not.toBe('')
    }
  })
})
