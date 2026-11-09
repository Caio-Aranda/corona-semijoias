import { defineConfig } from '@hey-api/openapi-ts';

// Código gerado de openapi.json (grupo dona do backend). Não edite src/gerado/ à mão:
// rode "npm run api:clientes" na raiz.
export default defineConfig({
  input: './openapi.json',
  output: { path: './src/gerado' },
  plugins: ['@hey-api/client-fetch', '@hey-api/typescript', '@hey-api/sdk'],
});
