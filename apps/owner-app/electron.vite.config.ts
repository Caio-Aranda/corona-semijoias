import react from '@vitejs/plugin-react';
import { defineConfig } from 'electron-vite';
import type { Plugin } from 'vite';
import { POLITICA_DE_CONTEUDO } from './src/main/seguranca';

/** Troca o marcador %POLITICA_DE_CONTEUDO% do index.html pela política de conteúdo restrita. */
function politicaDeConteudo(): Plugin {
  let emDesenvolvimento = false;
  return {
    name: 'corona-politica-de-conteudo',
    configResolved(config) {
      emDesenvolvimento = config.command === 'serve';
    },
    transformIndexHtml(html) {
      // Só no desenvolvimento: o React Refresh precisa de um script embutido e de um canal para recarregar.
      const politica = emDesenvolvimento
        ? POLITICA_DE_CONTEUDO.replace("script-src 'self'", "script-src 'self' 'unsafe-inline'")
            .replace("style-src 'self'", "style-src 'self' 'unsafe-inline'")
            .replace("connect-src 'none'", "connect-src 'self' ws://localhost:* http://localhost:*")
        : POLITICA_DE_CONTEUDO;
      return html.replace('%POLITICA_DE_CONTEUDO%', politica);
    },
  };
}

// Processo principal e ponte (preload) saem em CommonJS: a janela é "sandbox" e a ponte não pode ser módulo.
const formatoCommonJs = {
  output: { format: 'cjs' as const, entryFileNames: '[name].cjs' },
};

export default defineConfig({
  main: {
    build: {
      // O cliente da API é código-fonte TypeScript do monorepo: entra no pacote em vez de ficar de fora.
      externalizeDeps: { exclude: ['@corona/api-client-dona'] },
      rollupOptions: formatoCommonJs,
      sourcemap: false,
    },
  },
  preload: {
    build: {
      externalizeDeps: false,
      rollupOptions: { ...formatoCommonJs, external: ['electron'] },
      sourcemap: false,
    },
  },
  renderer: {
    plugins: [react(), politicaDeConteudo()],
    build: { sourcemap: false },
  },
});
