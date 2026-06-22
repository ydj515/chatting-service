import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  // ?? 는 빈 문자열을 폴백하지 못하므로 trim 후 || 로 공백/빈 값까지 폴백한다.
  const proxyTarget = env.VITE_DEV_PROXY_TARGET?.trim() || 'http://localhost:80';

  return {
    plugins: [tailwindcss(), react()],
    server: {
      // client(:5173)와 동시에 띄울 수 있도록 admin은 5174 고정
      port: 5174,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
          ws: true,
        },
      },
    },
  };
});
