import { defineConfig } from 'vite'

export default defineConfig({
  esbuild: {
    jsx: 'automatic',
    jsxImportSource: 'react'
  },
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: true,
    sourcemap: false
  }
})
