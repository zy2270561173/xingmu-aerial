import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const backend = env.VITE_DEV_BACKEND || 'http://127.0.0.1:18000';
  const webPort = Number(env.VITE_DEV_PORT || 15173);

  return {
    base: '/admin/',
    plugins: [vue()],
    server: {
      port: webPort,
      strictPort: true,
      proxy: {
        '/api': backend,
        '/static': backend,
        '/player': backend,
        '/palyer': backend,
        '/pair': backend,
        '/ws': {
          target: backend.replace(/^http/, 'ws'),
          ws: true,
        },
      },
    },
  };
});
