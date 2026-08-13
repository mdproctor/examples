import { defineConfig } from 'vite';
import { resolve } from 'path';

const PAGES = resolve(__dirname, '../../../../../pages/packages');
const BLOCKS = resolve(__dirname, '../../../../../blocks-ui');

export default defineConfig({
  resolve: {
    alias: [
      // blocks-ui components
      { find: '@casehubio/blocks-ui-kpi-metric-row', replacement: resolve(BLOCKS, 'components/kpi-metric-row/src') },
      { find: '@casehubio/blocks-ui-blocks-timeline', replacement: resolve(BLOCKS, 'components/blocks-timeline/src') },
      { find: '@casehubio/blocks-ui-core', replacement: resolve(BLOCKS, 'packages/blocks-ui-core/src') },

      // pages packages — subpath imports must come before bare specifiers
      { find: /^@casehubio\/pages-primitives\/(.*)/, replacement: resolve(PAGES, 'pages-primitives/src/$1') },
      { find: '@casehubio/pages-primitives', replacement: resolve(PAGES, 'pages-primitives/src') },
      { find: /^@casehubio\/pages-component\/(.*)/, replacement: resolve(PAGES, 'pages-component/src/$1') },
      { find: '@casehubio/pages-component', replacement: resolve(PAGES, 'pages-component/src') },
      { find: /^@casehubio\/pages-data\/(.*)/, replacement: resolve(PAGES, 'pages-data/src/$1') },
      { find: '@casehubio/pages-data', replacement: resolve(PAGES, 'pages-data/src') },
      { find: '@casehubio/pages-table', replacement: resolve(PAGES, 'pages-table/src') },
      { find: /^@casehubio\/pages-ui-tokens\/(.*)/, replacement: resolve(PAGES, 'pages-ui-tokens/src/$1') },
      { find: '@casehubio/pages-ui-tokens', replacement: resolve(PAGES, 'pages-ui-tokens/src') },
      { find: '@casehubio/pages-ui-components', replacement: resolve(PAGES, 'pages-ui-components/src') },

      // single lit instance
      { find: 'lit', replacement: resolve(__dirname, 'node_modules/lit') },
      { find: '@lit/reactive-element', replacement: resolve(__dirname, 'node_modules/@lit/reactive-element') },
    ],
  },
  esbuild: {
    target: 'es2022',
    tsconfigRaw: JSON.stringify({
      compilerOptions: {
        experimentalDecorators: true,
        useDefineForClassFields: false,
      },
    }),
  },
  server: {
    port: 3001,
    proxy: {
      '/tickets': 'http://localhost:8090',
      '/scenario': 'http://localhost:8090',
      '/push': { target: 'http://localhost:8090', ws: true },
    },
    fs: { allow: ['../../../../..'] },
  },
});
