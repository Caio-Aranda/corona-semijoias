/// <reference types="electron-vite/node" />

// As variáveis MAIN_VITE_* são embutidas no pacote na hora do build (import.meta.env), a partir de
// apps/owner-app/.env (desenvolvimento) ou .env.producao (instalador). O pacote não lê process.env
// para isso: sem o build, o endereço da API não existiria no computador da dona.
interface ImportMetaEnv {
  readonly MAIN_VITE_CORONA_API_URL?: string;
  readonly MAIN_VITE_CORONA_API_PINS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
