import { ESLint } from 'eslint'
import { describe, expect, it } from 'vitest'

// Runs the project's ESLint config on a JSX snippet, checking only the literal-text rule.
// Type information is not needed for that rule, so the fixture need not exist on disk.
const eslint = new ESLint({
  overrideConfig: { languageOptions: { parserOptions: { projectService: false } } },
  ruleFilter: ({ ruleId }) => ruleId === 'no-restricted-syntax',
})

async function lintJsx(jsx: string) {
  const [result] = await eslint.lintText(`export const view = ${jsx}\n`, {
    filePath: 'src/fixture.tsx',
  })
  return result?.messages.map(({ ruleId }) => ruleId)
}

describe('literal text in JSX', () => {
  it.each([
    '<p>Play now</p>',
    "<p>{'Play now'}</p>",
    '<p>{`Play now`}</p>',
    "<>{'Play now'}</>",
    '<img alt="Map of Hoi An" />',
    "<img alt={'Map of Hoi An'} />",
    '<button aria-label={`Close`} />',
    '<option label="Easy" />',
    '<input aria-placeholder="Search" />',
    '<div aria-roledescription="puzzle board" />',
    '<div role="slider" aria-valuetext="Half done" />',
  ])('rejects %s', async (jsx) => {
    expect(await lintJsx(jsx)).toEqual(['no-restricted-syntax'])
  })

  it.each([
    "<p>{t('home.tagline')}</p>",
    "<p>{t('app.name')}{' '}{count}</p>",
    '<img alt="" />',
    "<img alt={t('app.name')} />",
    '<div className="board" />',
    '<div className={`board ${size}`} />',
  ])('allows %s', async (jsx) => {
    expect(await lintJsx(jsx)).toEqual([])
  })
})
