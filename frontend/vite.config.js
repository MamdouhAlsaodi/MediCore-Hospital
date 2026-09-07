import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: process.env.REVIEW_BIND_HOST || '127.0.0.1',
    proxy: {
      '/api': 'http://127.0.0.1:5502'
    }
  }
});
