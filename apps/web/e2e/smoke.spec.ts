import { expect, test } from '@playwright/test'

test('the app shell loads and shows the product name', async ({ page }) => {
  const pageErrors: Error[] = []
  page.on('pageerror', (error) => pageErrors.push(error))

  await page.goto('/')

  await expect(page.getByRole('heading', { level: 1, name: 'World Jigsaw' })).toBeVisible()
  await expect(page).toHaveTitle('World Jigsaw')
  expect(pageErrors).toEqual([])
})
