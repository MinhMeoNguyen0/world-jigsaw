import { createBrowserRouter, RouterProvider } from 'react-router'
import { HomePage } from '../pages/HomePage.tsx'

const router = createBrowserRouter([{ path: '/', element: <HomePage /> }])

export function App() {
  return <RouterProvider router={router} />
}
