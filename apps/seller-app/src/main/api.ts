import {
  acharPecaDaMinhaMaleta,
  baixarFotoDaMinhaMaleta,
  cadastrarMeuCliente,
  conferirMinhaMaleta,
  criarClienteDaApi,
  ehAppDesatualizado,
  entrar,
  estornarVendaDoVendedor,
  eu,
  listarMeusClientes,
  listarMinhasMaletas,
  listarVendasDoVendedor,
  relatorioDeMeusRecebimentos,
  relatorioDeMinhasVendas,
  relatorioDeValoresAReceber,
  renovar,
  sair,
  trocarSenha,
  venderNaMaleta,
  verConferenciaDaMinhaMaleta,
  verCotacaoDeHoje,
  verMeuCliente,
  verMinhaMaleta,
  verVendaDoVendedor,
  type RespostaDoLogin,
} from '@corona/api-client-vendedor';
import type { PedidoDeChamada } from '../shared/operacoes';
import type { ApiDeAutenticacao, ClienteExistente, Credenciais, Falha, Resultado } from './tipos';

export interface DadosDoUsuario {
  nome?: string;
  perfil?: string;
}

export interface ApiDoVendas extends ApiDeAutenticacao {
  eu(tokenDeAcesso: string): Promise<Resultado<DadosDoUsuario>>;
  /** Qualquer outra operação da tela (maleta, venda, clientes, cotação, foto, relatórios). Já validada na ponte. */
  chamar(tokenDeAcesso: string, pedido: PedidoDeChamada): Promise<Resultado<unknown>>;
}

interface OpcoesDaApi {
  baseUrl: string;
  versaoDoApp: string;
  /** Identificador deste aparelho (ADR-0004, item 4): vai no corpo do login e da renovação. */
  dispositivoId: string;
  /** Só para testes. */
  fetch?: typeof globalThis.fetch;
}

interface CorpoDeErro {
  detail?: unknown;
  codigo?: unknown;
  clienteExistente?: unknown;
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
  if (codigo === 'CLIENTE_CPF_JA_CADASTRADO') {
    const e = corpo.clienteExistente;
    if (typeof e === 'object' && e !== null) {
      const o = e as Record<string, unknown>;
      const id = texto(o.id);
      const nome = texto(o.nome);
      if (id && nome) {
        const existente: ClienteExistente = { id, nome };
        const telefone = texto(o.telefone);
        const cidade = texto(o.cidade);
        if (telefone) existente.telefone = telefone;
        if (cidade) existente.cidade = cidade;
        falha.clienteExistente = existente;
      }
    }
  }
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
  if (r.horaDoServidor) c.horaDoServidor = r.horaDoServidor;
  if (typeof r.tempoMaximoOfflineDias === 'number') {
    c.tempoMaximoOfflineDias = r.tempoMaximoOfflineDias;
  }
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

/**
 * Chamadas à API do Corona Vendas. O cliente gerado já manda X-App-Tipo e X-App-Versao em toda
 * chamada; aqui só se acrescenta o token de acesso, que nunca sai do processo principal.
 */
export function criarApiDoVendas(opcoes: OpcoesDaApi): ApiDoVendas {
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

  /** Foto como endereço de dados (a tela não fala com a rede nem leva o token). */
  const foto = async (
    token: string,
    hash: string,
    tamanho: string,
  ): Promise<Resultado<unknown>> => {
    const r = await executar<unknown>(() =>
      baixarFotoDaMinhaMaleta({
        client,
        headers: comToken(token),
        path: { hash, tamanho },
        parseAs: 'arrayBuffer',
      }),
    );
    if (!r.ok) return r;
    if (!(r.dados instanceof ArrayBuffer) || r.dados.byteLength === 0) {
      return { ok: false, falha: { tipo: 'resposta-invalida' } };
    }
    const base64 = Buffer.from(r.dados).toString('base64');
    return { ok: true, dados: { dataUrl: `data:image/jpeg;base64,${base64}` } };
  };

  const chamar = (token: string, pedido: PedidoDeChamada): Promise<Resultado<unknown>> => {
    const base = { client, headers: comToken(token) };
    const periodo = (p: { de?: string; ate?: string }) => ({
      ...(p.de ? { de: p.de } : {}),
      ...(p.ate ? { ate: p.ate } : {}),
    });
    switch (pedido.operacao) {
      case 'eu':
        return executar(() => eu(base));
      case 'maletas.listar':
        return executar(() => listarMinhasMaletas(base));
      case 'maletas.ver':
        return executar(() => verMinhaMaleta({ ...base, path: { id: pedido.id } }));
      case 'maletas.peca':
        return executar(() =>
          acharPecaDaMinhaMaleta({
            ...base,
            path: { id: pedido.id },
            query: { codigo: pedido.codigo },
          }),
        );
      case 'maletas.conferir':
        return executar(() =>
          conferirMinhaMaleta({
            ...base,
            path: { id: pedido.id },
            body: { id: pedido.conferenciaId, itens: pedido.itens },
          }),
        );
      case 'maletas.verConferencia':
        return executar(() => verConferenciaDaMinhaMaleta({ ...base, path: { id: pedido.id } }));
      case 'vendas.vender':
        return executar(() => venderNaMaleta({ ...base, body: pedido.venda }));
      case 'vendas.listar':
        return executar(() =>
          listarVendasDoVendedor({
            ...base,
            query: {
              ...periodo(pedido),
              ...(pedido.clienteId ? { clienteId: pedido.clienteId } : {}),
              ...(pedido.status ? { status: pedido.status } : {}),
            },
          }),
        );
      case 'vendas.ver':
        return executar(() => verVendaDoVendedor({ ...base, path: { id: pedido.id } }));
      case 'vendas.estornar':
        return executar(() =>
          estornarVendaDoVendedor({
            ...base,
            path: { id: pedido.id },
            body: { id: pedido.estornoId, motivo: pedido.motivo },
          }),
        );
      case 'clientes.listar':
        return executar(() =>
          listarMeusClientes({ ...base, query: pedido.busca ? { busca: pedido.busca } : {} }),
        );
      case 'clientes.ver':
        return executar(() => verMeuCliente({ ...base, path: { id: pedido.id } }));
      case 'clientes.cadastrar':
        return executar(() => cadastrarMeuCliente({ ...base, body: pedido.cliente }));
      case 'cotacao.hoje':
        return executar(() => verCotacaoDeHoje(base));
      case 'fotos.ver':
        return foto(token, pedido.hash, pedido.tamanho);
      case 'relatorios.vendas':
        return executar(() =>
          relatorioDeMinhasVendas({
            ...base,
            query: { ...periodo(pedido), ...(pedido.agrupar ? { agrupar: pedido.agrupar } : {}) },
          }),
        );
      case 'relatorios.recebimentos':
        return executar(() => relatorioDeMeusRecebimentos({ ...base, query: periodo(pedido) }));
      case 'relatorios.aReceber':
        return executar(() => relatorioDeValoresAReceber(base));
    }
  };

  return {
    chamar,
    entrar: (usuario, senha) =>
      credenciais(() =>
        entrar({ client, body: { login: usuario, senha, dispositivoId: opcoes.dispositivoId } }),
      ),
    renovar: (tokenDeRenovacao) =>
      credenciais(() =>
        renovar({ client, body: { tokenDeRenovacao, dispositivoId: opcoes.dispositivoId } }),
      ),
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
  };
}
