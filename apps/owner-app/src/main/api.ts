import {
  converter,
  criar1,
  criar2,
  criar3,
  criar5,
  criar6,
  criar7,
  criar8,
  excluir1,
  excluir2,
  excluir4,
  excluir5,
  excluir6,
  excluir7,
  editar2,
  editar3,
  editar5,
  editar6,
  editar7,
  editar8,
  listar10,
  listar3,
  listar4,
  listar7,
  listar8,
  listar9,
  criarClienteDaApi,
  desativar,
  editar1,
  ehAppDesatualizado,
  entrar,
  eu,
  hoje,
  listar,
  listar11,
  listar2,
  reativar1,
  redefinirSenha,
  renovar,
  sair,
  trocarSenha,
  type RespostaDoLogin,
} from '@corona/api-client-dona';
import type { NomeDoCadastro } from '../shared/cadastros';
import type { ParametrosConferidos } from '../shared/validacao-parametros';
import type { ApiDeAutenticacao, Credenciais, Falha, Resultado } from './tipos';

export interface DadosDoUsuario {
  nome?: string;
  perfil?: string;
}

export interface ApiDoGestao extends ApiDeAutenticacao {
  eu(tokenDeAcesso: string): Promise<Resultado<DadosDoUsuario>>;
  /** Uma consulta ou ação da dona, já conferida. Devolve o que a API respondeu, sem mexer. */
  executar(tokenDeAcesso: string, pedido: ParametrosConferidos): Promise<Resultado<unknown>>;
}

interface OpcoesDaApi {
  baseUrl: string;
  versaoDoApp: string;
  /** Só para testes. */
  fetch?: typeof globalThis.fetch;
}

interface CorpoDeErro {
  detail?: unknown;
  codigo?: unknown;
  campos?: unknown;
}

function comoCampos(v: unknown): { campo: string; mensagem: string }[] | undefined {
  if (!Array.isArray(v)) return undefined;
  const lista: { campo: string; mensagem: string }[] = [];
  for (const item of v) {
    if (typeof item !== 'object' || item === null) continue;
    const { campo, mensagem } = item as { campo?: unknown; mensagem?: unknown };
    if (typeof campo === 'string' && typeof mensagem === 'string') lista.push({ campo, mensagem });
  }
  return lista.length > 0 ? lista : undefined;
}

const texto = (v: unknown): string | undefined => (typeof v === 'string' && v ? v : undefined);

function comoFalha(erro: unknown, resposta: Response | undefined): Falha {
  if (ehAppDesatualizado(erro)) return { tipo: 'app-desatualizado', mensagem: erro.message };
  if (!resposta) return { tipo: 'sem-conexao' };
  const corpo = (typeof erro === 'object' && erro !== null ? erro : {}) as CorpoDeErro;
  const falha: Falha = { tipo: 'recusada', status: resposta.status };
  const mensagem = texto(corpo.detail);
  const codigo = texto(corpo.codigo);
  if (mensagem) falha.mensagem = mensagem;
  if (codigo) falha.codigo = codigo;
  const campos = comoCampos(corpo.campos);
  if (campos) falha.campos = campos;
  return falha;
}

function comoCredenciais(r: RespostaDoLogin): Resultado<Credenciais> {
  if (!r.tokenDeAcesso || typeof r.validadeEmSegundos !== 'number') {
    return { ok: false, falha: { tipo: 'resposta-invalida' } };
  }
  const c: Credenciais = {
    tokenDeAcesso: r.tokenDeAcesso,
    validadeEmSegundos: r.validadeEmSegundos,
    trocaDeSenhaObrigatoria: r.trocaDeSenhaObrigatoria === true,
  };
  if (r.tokenDeRenovacao) c.tokenDeRenovacao = r.tokenDeRenovacao;
  return { ok: true, dados: c };
}

type RetornoDoCliente<T> = { data?: T; error?: unknown; response?: Response };

/** Executa uma chamada do cliente gerado e traduz o resultado, inclusive a falta de internet. */
async function executar<T>(
  chamada: () => Promise<RetornoDoCliente<T>>,
): Promise<Resultado<T | undefined>> {
  try {
    const { data, error, response } = await chamada();
    if (error !== undefined && error !== '')
      return { ok: false, falha: comoFalha(error, response) };
    if (response && !response.ok) return { ok: false, falha: comoFalha(undefined, response) };
    return { ok: true, dados: data };
  } catch (e) {
    if (ehAppDesatualizado(e)) return { ok: false, falha: comoFalha(e, undefined) };
    return { ok: false, falha: { tipo: 'sem-conexao' } };
  }
}

type ChamadaGerada = (o: Record<string, unknown>) => Promise<RetornoDoCliente<unknown>>;

/** As quatro rotas de cada cadastro, no cliente gerado (os números vêm da ordem do OpenAPI). */
const ROTAS_DOS_CADASTROS = {
  fornecedores: { listar: listar8, criar: criar6, editar: editar6, excluir: excluir5 },
  categorias: { listar: listar10, criar: criar8, editar: editar8, excluir: excluir7 },
  pracas: { listar: listar7, criar: criar5, editar: editar5, excluir: excluir4 },
  semanas: { listar: listar3, criar: criar2, editar: editar2, excluir: excluir1 },
  restricoes: { listar: listar4, criar: criar3, editar: editar3, excluir: excluir2 },
  clientes: { listar: listar9, criar: criar7, editar: editar7, excluir: excluir6 },
} as unknown as Record<NomeDoCadastro, Record<string, ChamadaGerada>>;

/**
 * Chamadas à API do Corona Gestão. O cliente gerado já manda X-App-Tipo e X-App-Versao em toda
 * chamada; aqui só se acrescenta o token de acesso, que nunca sai do processo principal.
 */
export function criarApiDoGestao(opcoes: OpcoesDaApi): ApiDoGestao {
  const client = criarClienteDaApi({
    baseUrl: opcoes.baseUrl,
    versaoDoApp: opcoes.versaoDoApp,
    ...(opcoes.fetch ? { fetch: opcoes.fetch } : {}),
  });
  const comToken = (token: string) => ({ Authorization: `Bearer ${token}` });

  const credenciais = async (
    chamada: () => Promise<RetornoDoCliente<RespostaDoLogin>>,
  ): Promise<Resultado<Credenciais>> => {
    const r = await executar(chamada);
    if (!r.ok) return r;
    return r.dados ? comoCredenciais(r.dados) : { ok: false, falha: { tipo: 'resposta-invalida' } };
  };

  return {
    entrar: (usuario, senha) =>
      credenciais(() => entrar({ client, body: { login: usuario, senha } })),
    renovar: (tokenDeRenovacao) =>
      credenciais(() => renovar({ client, body: { tokenDeRenovacao } })),
    trocarSenha: (token, senhaNova) =>
      credenciais(() => trocarSenha({ client, headers: comToken(token), body: { senhaNova } })),
    async sair(token) {
      const r = await executar(() => sair({ client, headers: comToken(token) }));
      return r.ok ? { ok: true, dados: null } : r;
    },
    async eu(token) {
      const r = await executar(() => eu({ client, headers: comToken(token) }));
      if (!r.ok) return r;
      const d: DadosDoUsuario = {};
      if (r.dados?.nome) d.nome = r.dados.nome;
      if (r.dados?.perfil) d.perfil = r.dados.perfil;
      return { ok: true, dados: d };
    },
    async executar(token, pedido) {
      const headers = comToken(token);
      if ('cadastro' in pedido) {
        const rota = ROTAS_DOS_CADASTROS[pedido.cadastro]?.[pedido.acao];
        if (!rota) return { ok: false, falha: { tipo: 'resposta-invalida' } };
        const { id, corpo, lista } = pedido;
        return executar(() =>
          rota({
            client,
            headers,
            ...(lista ? { query: lista } : {}),
            ...(id ? { path: { id } } : {}),
            ...(corpo ? { body: corpo } : {}),
          }),
        );
      }
      switch (pedido.operacao) {
        case 'eu':
          return executar(() => eu({ client, headers }));
        case 'usuarios.listar':
          return executar(() => listar2({ client, headers, query: pedido.lista }));
        case 'usuarios.criar':
          return executar(() => criar1({ client, headers, body: pedido.novo }));
        case 'usuarios.editar': {
          const { id, ...corpo } = pedido.editado;
          return executar(() => editar1({ client, headers, path: { id }, body: corpo }));
        }
        case 'usuarios.desativar':
          return executar(() => desativar({ client, headers, path: { id: pedido.id } }));
        case 'usuarios.reativar':
          return executar(() => reativar1({ client, headers, path: { id: pedido.id } }));
        case 'usuarios.redefinirSenha':
          return executar(() => redefinirSenha({ client, headers, path: { id: pedido.id } }));
        case 'cotacao.hoje':
          return executar(() => hoje({ client, headers }));
        case 'cotacao.converter':
          return executar(() =>
            converter({
              client,
              headers,
              body: {
                valorEmReais: pedido.valorEmReais,
                ...(pedido.data ? { data: pedido.data } : {}),
              },
            }),
          );
        case 'avisos.listar':
          return executar(() => listar11({ client, headers }));
        case 'vendedores.escolha':
          return executar(() =>
            listar({ client, headers, query: { situacao: 'ATIVOS', pagina: 0, tamanho: 100 } }),
          );
      }
    },
  };
}
