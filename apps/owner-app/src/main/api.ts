import {
  alterarCodigos,
  confirmar as confirmarFotos,
  registrar as registrarImportacao,
  resultadoDasImportacoes,
  editar4 as editarProduto,
  excluir9 as excluirFoto,
  criar4 as criarProduto,
  enviar1 as enviarFoto,
  imagem as baixarImagem,
  inativar1 as inativarProduto,
  listar5 as listarProdutos,
  listar6 as listarFotos,
  porCodigo as produtoPorCodigo,
  previa as previaDasFotos,
  principal as fotoPrincipal,
  proximoCodigo,
  reativar2 as reativarProduto,
  ver4 as verProduto,
  baixar,
  converter,
  criar as criarVendedor,
  criarReferencia,
  editar as editarVendedor,
  editarReferencia,
  enviar as enviarDocumento,
  excluir8 as excluirDocumento,
  excluirReferencia,
  gravarConjuge,
  gravarContato,
  gravarEndereco,
  gravarMei,
  gravarObservacoes,
  gravarTrabalho,
  inativar as inativarVendedor,
  listar1 as listarDocumentos,
  listarReferencias,
  reativar as reativarVendedor,
  ver as verVendedor,
  verConjuge,
  verContato,
  verEndereco,
  verMei,
  verObservacoes,
  verTrabalho,
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
  alteracoesDeProdutos as relatorioDeAlteracoes,
  aniversariantes as relatorioDeAniversariantes,
  clientes as relatorioDeClientes,
  codigosEmConflito as relatorioDeCodigos,
  fornecedores as relatorioDeFornecedores,
  produtos as relatorioDeProdutos,
  semCodigoDeBarras as relatorioSemCodigoDeBarras,
  semFoto as relatorioSemFoto,
  vendedores as relatorioDeVendedores,
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
import type { ParametrosConferidos, ParametrosDeVendedor } from '../shared/validacao-parametros';
import type { ParametrosDeProduto } from '../shared/validacao-produtos';
import type { ParametrosDeRelatorio } from '../shared/validacao-relatorios';
import type { AbaDoVendedor, ArquivoBaixado } from '../shared/vendedores';
import { CacheDeFotos, LimiteDeBuscas } from './cache-de-fotos';
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
  /** Cache em disco das fotos de produto (a foto é imutável por hash). Sem ele, toda foto vem da rede. */
  cacheDeFotos?: CacheDeFotos;
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

type ChamadaDaAba = (o: Record<string, unknown>) => Promise<RetornoDoCliente<unknown>>;

/** As duas rotas (ver e gravar) de cada aba da ficha do vendedor. */
const ROTAS_DAS_ABAS = {
  endereco: { ver: verEndereco, gravar: gravarEndereco },
  contato: { ver: verContato, gravar: gravarContato },
  trabalho: { ver: verTrabalho, gravar: gravarTrabalho },
  conjuge: { ver: verConjuge, gravar: gravarConjuge },
  mei: { ver: verMei, gravar: gravarMei },
  observacoes: { ver: verObservacoes, gravar: gravarObservacoes },
} as unknown as Record<AbaDoVendedor, Record<'ver' | 'gravar', ChamadaDaAba>>;

const TIPOS_DE_ARQUIVO: Record<string, string> = {
  pdf: 'application/pdf',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
};

/** Nome do arquivo no cabeçalho Content-Disposition (aceita `filename*=UTF-8''...` e `filename="..."`). */
export function nomeDoArquivoNoCabecalho(cabecalho: string | null): string {
  if (cabecalho) {
    const estendido = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(cabecalho);
    if (estendido?.[1]) {
      try {
        return decodeURIComponent(estendido[1].trim());
      } catch {
        // cai para o nome simples
      }
    }
    const simples = /filename\s*=\s*"?([^";]+)"?/i.exec(cabecalho);
    if (simples?.[1]) return simples[1].trim();
  }
  return 'documento';
}

/** As rotas do vendedor: cadastro, abas, referências e documentos (só ADMIN; o servidor confere). */
async function executarVendedor(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeVendedor,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  switch (pedido.operacao) {
    case 'vendedores.listar': {
      const { pagina, tamanho, busca } = pedido.lista;
      return executar(() =>
        listar({
          ...base,
          query: {
            pagina,
            tamanho,
            ...(busca ? { busca } : {}),
            ...(pedido.situacao ? { situacao: pedido.situacao } : {}),
          },
        }),
      );
    }
    case 'vendedores.ver':
      return executar(() => verVendedor({ ...base, path: { id: pedido.id } }));
    case 'vendedores.criar':
      return executar(() => criarVendedor({ ...base, body: pedido.corpo as never }));
    case 'vendedores.editar':
      return executar(() =>
        editarVendedor({ ...base, path: { id: pedido.id }, body: pedido.corpo as never }),
      );
    case 'vendedores.inativar':
      return executar(() => inativarVendedor({ ...base, path: { id: pedido.id } }));
    case 'vendedores.reativar':
      return executar(() => reativarVendedor({ ...base, path: { id: pedido.id } }));
    case 'vendedores.aba.ver':
      return executar(() => ROTAS_DAS_ABAS[pedido.aba].ver({ ...base, path: { id: pedido.id } }));
    case 'vendedores.aba.gravar':
      return executar(() =>
        ROTAS_DAS_ABAS[pedido.aba].gravar({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
      );
    case 'vendedores.referencias.listar':
      return executar(() => listarReferencias({ ...base, path: { id: pedido.id } }));
    case 'vendedores.referencias.criar':
      return executar(() =>
        criarReferencia({ ...base, path: { id: pedido.id }, body: pedido.corpo as never }),
      );
    case 'vendedores.referencias.editar':
      return executar(() =>
        editarReferencia({
          ...base,
          path: { id: pedido.id, referenciaId: pedido.referenciaId },
          body: pedido.corpo as never,
        }),
      );
    case 'vendedores.referencias.excluir':
      return executar(() =>
        excluirReferencia({ ...base, path: { id: pedido.id, referenciaId: pedido.referenciaId } }),
      );
    case 'vendedores.documentos.listar':
      return executar(() => listarDocumentos({ ...base, path: { id: pedido.id } }));
    case 'vendedores.documentos.enviar': {
      const { nome, bytes } = pedido.arquivo;
      const extensao = nome.slice(nome.lastIndexOf('.') + 1).toLowerCase();
      const arquivo = new File([bytes as BlobPart], nome, {
        type: TIPOS_DE_ARQUIVO[extensao] ?? 'application/octet-stream',
      });
      return executar(() =>
        enviarDocumento({
          ...base,
          path: { id: pedido.id },
          ...(pedido.descricao ? { query: { descricao: pedido.descricao } } : {}),
          body: { arquivo },
        }),
      );
    }
    case 'vendedores.documentos.excluir':
      return executar(() =>
        excluirDocumento({ ...base, path: { id: pedido.id, documentoId: pedido.documentoId } }),
      );
    case 'vendedores.documentos.baixar': {
      try {
        const { data, error, response } = (await baixar({
          ...base,
          path: { id: pedido.id, documentoId: pedido.documentoId },
          parseAs: 'arrayBuffer',
        } as never)) as RetornoDoCliente<ArrayBuffer>;
        if (error !== undefined && error !== '') {
          return { ok: false, falha: comoFalha(error, response) };
        }
        if (response && !response.ok) return { ok: false, falha: comoFalha(undefined, response) };
        if (!response || !(data instanceof ArrayBuffer)) {
          return { ok: false, falha: { tipo: 'resposta-invalida' } };
        }
        const arquivo: ArquivoBaixado = {
          nome: nomeDoArquivoNoCabecalho(response.headers.get('content-disposition')),
          tipo: response.headers.get('content-type') ?? 'application/octet-stream',
          bytes: new Uint8Array(data),
        };
        return { ok: true, dados: arquivo };
      } catch (e) {
        if (ehAppDesatualizado(e)) return { ok: false, falha: comoFalha(e, undefined) };
        return { ok: false, falha: { tipo: 'sem-conexao' } };
      }
    }
  }
}

const TIPOS_DE_FOTO: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
};

function comoArquivoDeFoto(a: { nome: string; bytes: Uint8Array }): File {
  const extensao = a.nome.slice(a.nome.lastIndexOf('.') + 1).toLowerCase();
  return new File([a.bytes as BlobPart], a.nome, {
    type: TIPOS_DE_FOTO[extensao] ?? 'application/octet-stream',
  });
}

/** Relatórios de cadastro (só ADMIN; o servidor confere). O de produtos traz o custo, por isso só existe aqui. */
async function executarRelatorio(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeRelatorio,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  const q = pedido.consulta;
  switch (pedido.relatorio) {
    case 'vendedores':
      return executar(() => relatorioDeVendedores({ ...base, query: q }));
    case 'clientes':
      return executar(() => relatorioDeClientes({ ...base, query: q }));
    case 'fornecedores':
      return executar(() => relatorioDeFornecedores({ ...base, query: q }));
    case 'produtos':
      return executar(() => relatorioDeProdutos({ ...base, query: q }));
    case 'sem-codigo-de-barras':
      return executar(() => relatorioSemCodigoDeBarras({ ...base, query: q }));
    case 'codigos-em-conflito':
      return executar(() => relatorioDeCodigos({ ...base, query: q }));
    case 'aniversariantes':
      return executar(() => relatorioDeAniversariantes({ ...base, query: q }));
    case 'alteracoes-de-produtos':
      return executar(() => relatorioDeAlteracoes({ ...base, query: q }));
    case 'resultado-das-importacoes':
      return executar(() => resultadoDasImportacoes({ ...base, query: q }));
    case 'sem-foto': {
      // A rota que já existia (tarefa 1.6) usa "ativo" em vez de "situação" e não tem "todos".
      const { situacao, busca, categoriaId, fornecedorId, pagina, tamanho } = q;
      return executar(() =>
        relatorioSemFoto({
          ...base,
          query: {
            pagina,
            tamanho,
            ...(busca ? { busca } : {}),
            ...(categoriaId ? { categoriaId } : {}),
            ...(fornecedorId ? { fornecedorId } : {}),
            ...(situacao === 'INATIVOS' ? { ativo: false } : {}),
          },
        }),
      );
    }
  }
}

/** Produtos: lista, ficha, códigos, fotos e importação de fotos (só ADMIN; o servidor confere). */
async function executarProduto(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeProduto,
  fotos: { cache?: CacheDeFotos; limite: LimiteDeBuscas },
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  const id = pedido.id as string;
  switch (pedido.operacao) {
    case 'produtos.listar':
      return executar(() => listarProdutos({ ...base, query: pedido.lista as never }));
    case 'produtos.ver':
      return executar(() => verProduto({ ...base, path: { id } }));
    case 'produtos.porCodigo':
      return executar(() =>
        produtoPorCodigo({ ...base, query: { codigo: pedido.codigo as string } }),
      );
    case 'produtos.criar':
      return executar(() => criarProduto({ ...base, body: pedido.corpo as never }));
    case 'produtos.editar':
      return executar(() => editarProduto({ ...base, path: { id }, body: pedido.corpo as never }));
    case 'produtos.codigos.gravar':
      return executar(() =>
        alterarCodigos({
          ...base,
          path: { id },
          body: { version: pedido.version, codigos: pedido.codigos },
        }),
      );
    case 'produtos.codigoProximo':
      return executar(() => proximoCodigo({ ...base }));
    case 'produtos.inativar':
      return executar(() => inativarProduto({ ...base, path: { id } }));
    case 'produtos.reativar':
      return executar(() => reativarProduto({ ...base, path: { id } }));
    case 'produtos.fotos.listar':
      return executar(() => listarFotos({ ...base, path: { id } }));
    case 'produtos.fotos.enviar':
      return executar(() =>
        enviarFoto({
          ...base,
          path: { id },
          body: { arquivo: comoArquivoDeFoto(pedido.arquivo as never) },
        }),
      );
    case 'produtos.fotos.principal':
      return executar(() =>
        fotoPrincipal({ ...base, path: { id, fotoId: pedido.fotoId as string } }),
      );
    case 'produtos.fotos.excluir':
      return executar(() =>
        excluirFoto({ ...base, path: { id, fotoId: pedido.fotoId as string } }),
      );
    case 'produtos.importacao.previa':
      return executar(() => previaDasFotos({ ...base, body: { nomes: pedido.nomes } }));
    case 'produtos.importacao.confirmar':
      return executar(() =>
        confirmarFotos({
          ...base,
          ...(pedido.importacaoId ? { query: { importacaoId: pedido.importacaoId } } : {}),
          body: { arquivos: (pedido.arquivos ?? []).map(comoArquivoDeFoto) },
        }),
      );
    case 'importacoes.registrar':
      return executar(() =>
        registrarImportacao({
          ...base,
          path: { id: pedido.importacaoId ?? '' },
          body: { ...pedido.importacao },
        }),
      );
    case 'produtos.fotos.imagem': {
      const hash = pedido.hash as string;
      const tamanho = pedido.tamanho as string;
      // A foto é imutável por hash: se já está no disco, nunca se pergunta ao servidor de novo.
      const guardada = await fotos.cache?.ler(hash, tamanho);
      if (guardada) {
        return {
          ok: true,
          dados: { nome: `${hash}.jpg`, tipo: guardada.tipo, bytes: guardada.bytes },
        };
      }
      try {
        const { data, error, response } = (await fotos.limite.executar(() =>
          baixarImagem({
            ...base,
            path: { hash, tamanho },
            parseAs: 'arrayBuffer',
          } as never),
        )) as RetornoDoCliente<ArrayBuffer>;
        if (error !== undefined && error !== '') {
          return { ok: false, falha: comoFalha(error, response) };
        }
        if (response && !response.ok) return { ok: false, falha: comoFalha(undefined, response) };
        if (!response || !(data instanceof ArrayBuffer)) {
          return { ok: false, falha: { tipo: 'resposta-invalida' } };
        }
        const arquivo: ArquivoBaixado = {
          nome: `${hash}.jpg`,
          tipo: response.headers.get('content-type') ?? 'image/jpeg',
          bytes: new Uint8Array(data),
        };
        await fotos.cache?.gravar(hash, tamanho, arquivo.bytes);
        return { ok: true, dados: arquivo };
      } catch (e) {
        if (ehAppDesatualizado(e)) return { ok: false, falha: comoFalha(e, undefined) };
        return { ok: false, falha: { tipo: 'sem-conexao' } };
      }
    }
  }
}

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
  // No máximo 4 fotos sendo buscadas ao mesmo tempo: uma lista com 100 miniaturas não trava as outras chamadas.
  const fotos = {
    ...(opcoes.cacheDeFotos ? { cache: opcoes.cacheDeFotos } : {}),
    limite: new LimiteDeBuscas(4),
  };

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
      if (pedido.operacao === 'relatorios.cadastros') {
        return executarRelatorio(client, headers, pedido);
      }
      if (pedido.operacao.startsWith('produtos.') || pedido.operacao === 'importacoes.registrar') {
        return executarProduto(client, headers, pedido as ParametrosDeProduto, fotos);
      }
      if (pedido.operacao.startsWith('vendedores.') && pedido.operacao !== 'vendedores.escolha') {
        return executarVendedor(client, headers, pedido as ParametrosDeVendedor);
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
        default:
          return { ok: false, falha: { tipo: 'resposta-invalida' } };
      }
    },
  };
}
