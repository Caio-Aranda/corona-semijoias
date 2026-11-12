import { defineConfig } from 'vitest/config';

/**
 * Testes de duas pontas (tarefa 5.12): o motor do Corona Vendas contra a API de verdade. Uma API só para todos os
 * arquivos (`globalSetup`), um arquivo por vez. Roda com `npm run test:duas-pontas` (e o estresse com `npm run estresse`).
 */
export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['src/**/*.duas-pontas.test.ts'],
    globalSetup: ['./ambiente/preparar.ts'],
    fileParallelism: false,
    testTimeout: 180_000,
    hookTimeout: 120_000,
    // O estresse longo passa de 10 minutos.
    teardownTimeout: 60_000,
    reporters: ['default'],
  },
});
