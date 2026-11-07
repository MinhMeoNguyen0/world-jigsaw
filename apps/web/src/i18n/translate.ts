import en from './locales/en.json'

/** A flat message catalog: message key to message template. */
export type Catalog = Readonly<Record<string, string>>

/** Values substituted into `{name}` placeholders in a message template. */
export type MessageParams = Readonly<Record<string, string | number>>

/** Every message key the English catalog defines. Other locales must define the same keys. */
export type MessageKey = keyof typeof en

const placeholder = /\{(\w+)\}/g

/**
 * Builds a lookup function for one catalog. Placeholders such as `{count}` are replaced from
 * `params`; a placeholder without a matching param is left as written so the gap is visible.
 */
export function createTranslator<K extends string>(catalog: Readonly<Record<K, string>>) {
  return (key: K, params?: MessageParams): string => {
    const template = catalog[key]
    if (params === undefined) return template
    return template.replace(placeholder, (match, name: string) => {
      const value = params[name]
      return value === undefined ? match : String(value)
    })
  }
}

/** Looks up user-facing text. Components must render text through this, never as literals. */
export const t = createTranslator<MessageKey>(en)
