import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/manrope'
import '@fontsource-variable/cormorant-garamond'
import '@fontsource-variable/cormorant-garamond/wght-italic.css'
import '@fontsource-variable/geist-mono'
import './index.css'
import App from './App.tsx'
import { applyInitialTheme } from './lib/theme'
import { installPointerLight } from './lib/pointerLight'
import { preloadCube } from './lib/webgl'

applyInitialTheme() // before the first paint, so there is no flash of the wrong theme
installPointerLight() // the light that follows the cursor over interactive glass
preloadCube() // the 3D cube of the splash screen starts downloading now, not when the splash asks for it

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
