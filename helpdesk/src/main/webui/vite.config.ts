import { defineConfig } from 'vite';

export default defineConfig({
  server: {
    port: 3001,
    proxy: {
      '/tickets': 'http://localhost:8095',
      '/scenario': 'http://localhost:8095',
    },
  },
});
