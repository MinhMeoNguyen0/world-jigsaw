import { generate } from './generator.ts'

const usage =
  'Usage: node src/cli.ts --seed TEXT --columns N --rows N --width N --height N --image URI [--definition-version N] [--placed-pieces N]'
try {
  const args = process.argv.slice(2)
  if (args.length === 1 && args[0] === '--help') {
    console.log(usage)
    process.exit(0)
  }
  const allowed = new Set([
    'seed',
    'columns',
    'rows',
    'width',
    'height',
    'image',
    'definition-version',
    'placed-pieces',
  ])
  const values: Record<string, string> = {}
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i].startsWith('--') ? args[i].slice(2) : ''
    if (
      !allowed.has(key) ||
      Object.hasOwn(values, key) ||
      args[i + 1] === undefined ||
      args[i + 1].startsWith('--')
    )
      throw new Error(`invalid or repeated argument: ${args[i]}`)
    values[key] = args[i + 1]
  }
  const number = (key: string, fallback?: number) => {
    if (values[key] === undefined && fallback !== undefined) return fallback
    if (!/^(0|[1-9][0-9]*)$/.test(values[key] ?? ''))
      throw new Error(`${key} must be a nonnegative integer`)
    return Number(values[key])
  }
  const board = generate({
    seed: values.seed,
    columns: number('columns'),
    rows: number('rows'),
    width: number('width'),
    height: number('height'),
    image: values.image,
    definitionVersion: number('definition-version', 1),
    placedPieces: number('placed-pieces', 0),
  })
  process.stdout.write(`${JSON.stringify(board, null, 2)}\n`)
} catch (error) {
  console.error(`${error instanceof Error ? error.message : String(error)}\n${usage}`)
  process.exitCode = 1
}
