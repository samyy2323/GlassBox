import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, /api is proxied to the Spring Boot API (through the web container on :8080) so the SameSite=Strict refresh cookie is same-site.
// A browser on :5173 sends "Origin: http://localhost:5173" with every POST, and the API (rightly) allows only the origin it is deployed at, so the proxy
// presents that origin instead. Without this, signing in from the dev server answers 403 Forbidden.
export default defineConfig({
  // The recorded demo is served from a sub-path (GitHub Pages), so the build can be given a base; everything else uses the root.
  base: process.env.VITE_BASE ?? '/',
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        headers: { Origin: 'http://localhost:8080' },
      },
    },
  },
})
