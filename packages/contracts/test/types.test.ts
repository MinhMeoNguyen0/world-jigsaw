import { applyPreview, validate, type Preview, type Subscribe } from '@world-jigsaw/contracts'

const subscribe: Subscribe = {
  schemaVersion: 1,
  type: 'subscribe',
  puzzleId: '00000000-0000-4000-8000-000000000100',
  afterBoardVersion: null,
}
validate('Subscribe', subscribe)
const preview = null as unknown as Preview
applyPreview(null, preview)
// @ts-expect-error v1 cannot silently accept a newer protocol version
const incompatible: Subscribe = { ...subscribe, schemaVersion: 2 }
void incompatible
