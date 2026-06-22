import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');

  return {
    plugins: [tailwindcss(), react()],
    server: {
      // client(:5173)와 동시에 띄울 수 있도록 admin은 5174 고정
      port: 5174,
      proxy: {
        '/api': {
          target: env.VITE_DEV_PROXY_TARGET ?? 'http://localhost:80',
          changeOrigin: true,
          ws: true,
        },
      },
    },
  };
});
