import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Os testes do processo principal rodam sem janela; os da tela pedem "jsdom" no próprio arquivo.
    environment: 'node',
    globals: true,
    setupFiles: ['./src/testes/preparar.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
