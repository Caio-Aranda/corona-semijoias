import eslint from '@eslint/js';
import { defineConfig } from 'eslint/config';
import prettier from 'eslint-config-prettier';
import tseslint from 'typescript-eslint';

const mensagemVendas =
  'O Corona Vendas não pode usar código da dona (ADR-0003). Use só o que é compartilhado em packages/ ou o cliente do vendedor.';

const mensagemPacotes =
  'Os pacotes compartilhados não podem usar código de aplicativos nem o cliente da dona (ADR-0003). Quem importa pacotes é o aplicativo, nunca o contrário.';

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
  // Barreira de importação, primeira camada (ADR-0003).
  {
    files: ['apps/seller-app/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@corona/api-client-dona',
                '@corona/api-client-dona/*',
                '@corona/owner-app',
                '@corona/owner-app/*',
                '**/owner-app',
                '**/owner-app/**',
              ],
              message: mensagemVendas,
            },
          ],
        },
      ],
    },
  },
  {
    files: ['packages/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '@corona/owner-app',
                '@corona/owner-app/*',
                '@corona/seller-app',
                '@corona/seller-app/*',
                '@corona/api-client-dona',
                '@corona/api-client-dona/*',
                '**/apps/**',
              ],
              message: mensagemPacotes,
            },
          ],
        },
      ],
    },
  },
  // Por último: desliga regras de estilo que brigariam com o Prettier.
  prettier,
);
