import eslint from '@eslint/js';
import { defineConfig } from 'eslint/config';
import prettier from 'eslint-config-prettier';
import tseslint from 'typescript-eslint';
import { configuracaoDaBarreira } from './scripts/barreira/configuracao-eslint.mjs';

export default defineConfig(
  {
    ignores: [
      '**/node_modules/',
      '**/dist/',
      '**/out/',
      '**/release/',
      '**/coverage/',
      '**/target/',
      'docs/',
      'packages/api-client-*/src/gerado/',
      // Relatórios do Playwright e logs que o e2e e as duas pontas deixam (ignorados pelo Git).
      'testes-e2e/resultados/',
      'testes-sync/resultados/',
      // Pastas de trabalho das sessões (worktrees com cópias do repositório inteiro).
      '.claude/',
    ],
  },
  eslint.configs.recommended,
  tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['**/*.{js,mjs,cjs}'],
    extends: [tseslint.configs.disableTypeChecked],
  },
  // Nos testes, `expect(objeto.metodo)` sobre métodos de mentira (vi.fn) é normal e não perde o `this`.
  {
    files: ['**/*.test.{ts,tsx}'],
    rules: { '@typescript-eslint/unbound-method': 'off' },
  },
  // Scripts de apoio rodam no Node.
  {
    files: ['scripts/**/*.mjs'],
    languageOptions: { globals: { process: 'readonly', console: 'readonly' } },
  },
  // Spike do SQLCipher (ADR-0005): código descartável que roda no Node e no Electron.
  {
    files: ['spikes/**/*.{js,mjs,cjs}'],
    languageOptions: {
      globals: {
        process: 'readonly',
        console: 'readonly',
        Buffer: 'readonly',
        performance: 'readonly',
        require: 'readonly',
        module: 'writable',
        __dirname: 'readonly',
      },
    },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
  // Barreira de importação, primeira camada (ADR-0003). Os nomes proibidos moram em scripts/marcadores-proibidos.json.
  ...configuracaoDaBarreira(),
  // Por último: desliga regras de estilo que brigariam com o Prettier.
  prettier,
);
