/** Valores do ambiente de teste (falsos; nada disto vale fora do banco descartável do e2e). */
export const PORTA_DA_API = 8099;
export const URL_DA_API = `http://localhost:${PORTA_DA_API}`;
export const DONA = {
  nome: 'Dona dos Testes',
  login: 'dona.e2e',
  senhaTemporaria: 'Temp12345abc',
  senhaNova: 'Nova12345abc',
};

export const VARIAVEIS_DA_API: Record<string, string> = {
  DB_HOST: 'localhost',
  DB_PORT: '5499',
  DB_NAME: 'corona_e2e',
  DB_MIGRACAO_USER: 'e2e_mig',
  DB_MIGRACAO_PASSWORD: 'e2e-mig-senha',
  DB_APP_USER: 'e2e_app',
  DB_APP_PASSWORD: 'e2e-app-senha',
  JWT_CHAVE_ATUAL_ID: 'e2e-1',
  JWT_CHAVE_ATUAL: 'ZTJlLWNoYXZlLWRlLXRlc3RlLTMyLWJ5dGVzLXNvLXBhcmEtZTJlISE=',
  SESSAO_SEGREDO_RENOVACAO: 'ZTJlLXNlZ3JlZG8tZGUtcmVub3ZhY2FvLWRpZmVyZW50ZS1kYXMtY2hhdmVz',
  ADMIN_NOME: DONA.nome,
  ADMIN_LOGIN: DONA.login,
  ADMIN_SENHA: DONA.senhaTemporaria,
  DEMO_VENDEDOR_SENHA: '',
  SERVER_PORT: String(PORTA_DA_API),
  // Sem buscar cotação na internet durante os testes.
  CORONA_COTACOES_BUSCA_AGENDADA: 'false',
  ARQUIVOS_PASTA: './dados/e2e-arquivos',
};

/**
 * O Corona Vendas é compilado apontando para esta porta, onde o próprio teste liga uma "rede" (`rede-do-vendas.ts`) que
 * repassa tudo para a API de teste e pode ser cortada para simular a falta de internet.
 */
export const PORTA_DA_REDE_DO_VENDAS = 8098;
export const URL_DA_REDE_DO_VENDAS = `http://localhost:${PORTA_DA_REDE_DO_VENDAS}`;

/** A representante dos testes do Corona Vendas (valores falsos, só do banco descartável). */
export const REPRESENTANTE = {
  senhaNova: 'Vendedora12345abc',
};
