import { defineConfig } from '@playwright/test';

// Testes de ponta a ponta do Corona Gestão (Electron de verdade contra uma API e um banco de teste isolados).
// Rodar: npm run e2e. Veja testes-e2e/README.md.
export default defineConfig({
  testDir: './testes',
  globalSetup: './preparar.ts',
  // Um app, uma API, um banco: os testes rodam um depois do outro, na ordem do arquivo.
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'resultados/relatorio' }]],
  outputDir: 'resultados/artefatos',
  use: { trace: 'retain-on-failure', screenshot: 'only-on-failure' },
});
