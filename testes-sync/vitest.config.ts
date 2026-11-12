import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['src/**/*.test.ts'],
    // Os de duas pontas precisam da API e do banco: rodam pelo `vitest.duas-pontas.config.ts`.
    exclude: ['src/**/*.duas-pontas.test.ts', 'node_modules/**'],
    testTimeout: 20_000,
  },
});
