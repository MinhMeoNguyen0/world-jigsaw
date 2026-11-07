import { t } from '../i18n/translate.ts'

export function HomePage() {
  return (
    <main>
      <title>{t('app.name')}</title>
      <h1>{t('app.name')}</h1>
      <p>{t('home.tagline')}</p>
      <p>{t('home.status')}</p>
    </main>
  )
}
