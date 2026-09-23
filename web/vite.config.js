import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // il frontend chiama /api.xsp/..., che sul server Domino vive sotto
      // /apps/time.nsf/api.xsp/...: la chiave '/api' da sola duplicherebbe
      // "api.xsp" nell'URL finale, serve una rewrite esplicita.
      '/api.xsp': {
        target: 'https://time.devangarde.it',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    assetsDir: '',
  },
});
