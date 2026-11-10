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
  // Barreira de importação, primeira camada (ADR-0003). Os nomes proibidos moram em scripts/marcadores-proibidos.json.
  ...configuracaoDaBarreira(),
  // Por último: desliga regras de estilo que brigariam com o Prettier.
  prettier,
);
