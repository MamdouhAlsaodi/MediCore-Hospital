import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const reviewServer = {
  host: process.env.REVIEW_BIND_HOST || '127.0.0.1',
  port: Number(process.env.REVIEW_FRONTEND_PORT || 5502),
  strictPort: true,
  proxy: {
    '/api': process.env.REVIEW_API_PROXY_TARGET || 'http://127.0.0.1:5501'
  }
};

export default defineConfig({
  plugins: [react()],
  server: reviewServer,
  preview: reviewServer
});
