import js from '@eslint/js'
import { defineConfig, globalIgnores } from 'eslint/config'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import globals from 'globals'
import tseslint from 'typescript-eslint'

const i18nMessage = 'Put user-facing text in src/i18n/locales/en.json and render it with t().'

// Attributes whose values are shown or read out to the user.
const textAttribute =
  'JSXAttribute[name.name=/^(alt|title|placeholder|label|aria-(label|description|placeholder|roledescription|valuetext))$/]'
// A `{...}` whose value renders as text: an element or fragment child, or a text attribute value.
const textExpression = `:matches(JSXElement, JSXFragment, ${textAttribute}) > JSXExpressionContainer`

export default defineConfig([
  globalIgnores(['dist', 'playwright-report', 'test-results']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
    ],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [reactHooks.configs.flat.recommended, reactRefresh.configs.vite],
    languageOptions: { globals: globals.browser },
    rules: {
      // Interface text lives in translation files: no literal text in JSX.
      'no-restricted-syntax': [
        'error',
        ...[
          'JSXText[value=/\\S/]',
          `${textAttribute} > Literal[value=/\\S/]`,
          `${textExpression} > Literal[value=/\\S/]`,
          `${textExpression} > TemplateLiteral`,
        ].map((selector) => ({ selector, message: i18nMessage })),
      ],
    },
  },
  {
    files: ['*.config.{js,ts}', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },
])
