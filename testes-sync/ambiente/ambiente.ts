/**
 * Valores do ambiente de duas pontas (falsos; nada disto vale fora do banco descartável do `testes-sync`).
 * Porta e projeto próprios: os testes de duas pontas e o e2e podem rodar ao mesmo tempo no mesmo computador.
 */
export const PORTA_DO_BANCO = 5498;
export const PORTA_DA_API = 8098;
export const URL_DA_API = `http://127.0.0.1:${PORTA_DA_API}`;
export const PROJETO_DO_COMPOSE = 'corona-sync';

export const DONA = {
  nome: 'Dona das Duas Pontas',
  login: 'dona.sync',
  senhaTemporaria: 'Temp12345sync',
  senhaNova: 'Nova12345sync',
};

/** A versão que o Corona Vendas dos testes informa (a mínima da API de teste é a padrão, 1.0.0). */
export const VERSAO_DO_APP = '1.0.0';

export const VARIAVEIS_DA_API: Record<string, string> = {
  DB_HOST: '127.0.0.1',
  DB_PORT: String(PORTA_DO_BANCO),
  DB_NAME: 'corona_sync',
  DB_MIGRACAO_USER: 'sync_mig',
  DB_MIGRACAO_PASSWORD: 'sync-mig-senha',
  DB_APP_USER: 'sync_app',
  DB_APP_PASSWORD: 'sync-app-senha',
  JWT_CHAVE_ATUAL_ID: 'sync-1',
  JWT_CHAVE_ATUAL: 'c3luYy1jaGF2ZS1kZS10ZXN0ZS0zMi1ieXRlcy1zby1wYXJhLXN5bmMhIQ==',
  SESSAO_SEGREDO_RENOVACAO: 'c3luYy1zZWdyZWRvLWRlLXJlbm92YWNhby1kaWZlcmVudGUtZGFzLWNoYXZlcw==',
  ADMIN_NOME: DONA.nome,
  ADMIN_LOGIN: DONA.login,
  ADMIN_SENHA: DONA.senhaTemporaria,
  DEMO_VENDEDOR_SENHA: '',
  SERVER_PORT: String(PORTA_DA_API),
  APP_VERSAO_MINIMA_VENDAS: '1.0.0',
  APP_VERSAO_MINIMA_GESTAO: '1.0.0',
  // Sem buscar cotação na internet durante os testes.
  CORONA_COTACOES_BUSCA_AGENDADA: 'false',
  ARQUIVOS_PASTA: './dados/sync-arquivos',
};
