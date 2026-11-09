import { useState } from 'react'
import type { ShapeChange, ShapeParameters } from '@world-jigsaw/contracts'
import { t } from '../i18n/translate'

export function ShapePanel({
  shape,
  disabled,
  apply,
}: {
  shape: ShapeParameters
  disabled: boolean
  apply: (
    values: Pick<ShapeChange, 'tabDepthPermille' | 'tabWidthPermille' | 'edgeWavePermille'>,
  ) => void
}) {
  const [depth, setDepth] = useState(shape.tabDepthPermille / 10)
  const [width, setWidth] = useState(shape.tabWidthPermille / 10)
  const [wave, setWave] = useState(shape.edgeWavePermille / 10)
  const changed =
    depth * 10 !== shape.tabDepthPermille ||
    width * 10 !== shape.tabWidthPermille ||
    wave * 10 !== shape.edgeWavePermille
  return (
    <div className="shape-settings">
      <p>{t('game.sharedShapeHint')}</p>
      <div className="shape-setting">
        <label htmlFor="shape-depth">{t('home.shapeDepth')}</label>
        <output htmlFor="shape-depth">{t('home.shapeValue', { value: depth })}</output>
        <input
          id="shape-depth"
          type="range"
          min="10"
          max="22"
          step="1"
          value={depth}
          disabled={disabled}
          onChange={(event) => {
            setDepth(Number(event.currentTarget.value))
          }}
        />
      </div>
      <div className="shape-setting">
        <label htmlFor="shape-width">{t('home.shapeWidth')}</label>
        <output htmlFor="shape-width">{t('home.shapeValue', { value: width })}</output>
        <input
          id="shape-width"
          type="range"
          min="22"
          max="36"
          step="1"
          value={width}
          disabled={disabled}
          onChange={(event) => {
            setWidth(Number(event.currentTarget.value))
          }}
        />
      </div>
      <div className="shape-setting">
        <label htmlFor="shape-wave">{t('home.shapeWave')}</label>
        <output htmlFor="shape-wave">{t('home.shapeValue', { value: wave })}</output>
        <input
          id="shape-wave"
          type="range"
          min="0"
          max="6"
          step="1"
          value={wave}
          disabled={disabled}
          onChange={(event) => {
            setWave(Number(event.currentTarget.value))
          }}
        />
      </div>
      <button
        type="button"
        className="shape-apply"
        disabled={disabled || !changed}
        onClick={() => {
          apply({
            tabDepthPermille: depth * 10,
            tabWidthPermille: width * 10,
            edgeWavePermille: wave * 10,
          })
        }}
      >
        {t('game.applyShape')}
      </button>
    </div>
  )
}
