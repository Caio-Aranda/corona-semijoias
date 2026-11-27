import {
  alterarGarantia,
  aprovarAutorizacao,
  cancelarDevolucao,
  cancelarTroca,
  devolverPelaDona,
  lerGarantia,
  listarAutorizacoes,
  listarCreditos,
  listarDevolucoes,
  listarTrocas,
  manterDevolucao,
  naoAceitarDevolucao,
  negarAutorizacao,
  transferirCredito,
  vendasComAPecaDaDona,
} from '@corona/api-client-dona';
import {
  aprovarVendaDeComputadorBloqueado,
  bloquearAparelho,
  descartarEnvio,
  descartarVendaDeComputadorBloqueado,
  liberarAparelho,
  listarAparelhosDoVendedor,
  listarEnviosComProblema,
  listarValoresDeClienteSubstituidos,
  listarVendasDeComputadorBloqueado,
  tentarEnvioDeNovo,
  alterarCodigos,
  confirmar as confirmarFotos,
  registrar as registrarImportacao,
  resultadoDasImportacoes,
  editar4 as editarProduto,
  mudarEstoqueMinimo,
  excluir9 as excluirFoto,
  criar4 as criarProduto,
  enviar1 as enviarFoto,
  imagem as baixarImagem,
  inativar2 as inativarProduto,
  listar5 as listarProdutos,
  listar6 as listarFotos,
  porCodigo as produtoPorCodigo,
  previa as previaDasFotos,
  proximoCodigo,
  reativar3 as reativarProduto,
  ver4 as verProduto,
  baixar,
  abaixoDoMinimo1 as acabandoNoEstoque,
  ajustar as ajustarEstoque,
  cancelar as cancelarContagem,
  comecar as comecarContagem,
  corrigir as corrigirContagem,
  desfazer as desfazerLancamento,
  desfazer1 as desfazerCompra,
  ficha as fichaDoEstoque,
  fechar as fecharContagem,
  itens as itensDaContagem,
  lancar as lancarNoEstoque,
  ler as lerNaContagem,
  listar10 as listarLancamentos,
  listar8 as listarContagens,
  ver9 as verContagem,
  listar11 as listarCompras,
  receber as receberCompra,
  ver10 as verCompra,
  converter,
  doDia as cotacoesDoDia,
  gravar as gravarCotacao,
  previa1 as previaDaCotacao,
  colocarItemNaRemessa,
  criarRemessa,
  enviarRemessa,
  excluirRascunhoDeRemessa,
  listarMaletasEmAbertoDoVendedor,
  cancelarRemessa,
  desfazerDevolucaoDaMaleta,
  devolverPecasDaMaleta,
  verConferenciaDaMaleta,
  verMaletaDaRemessa,
  listarRemessas,
  tirarItemDaRemessa,
  verRemessa,
  notaDaRemessa,
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
  listar13,
  listar3,
  listar4,
  listar7,
  listar9,
  listar12,
  alterarComissao,
  alterarConfiguracaoDoConsignado,
  lerConfiguracaoDoConsignado,
  criarCategoriaDoCaixa,
  estornarLancamentoDoCaixa,
  estornarPagamentoAFornecedor,
  estornarRecebimentoDoVendedorPelaDona,
  fecharSemana,
  inativarCategoriaDoCaixa,
  lancarNoCaixa,
  listarCategoriasDoCaixa,
  listarContasAPagar,
  listarConferencias,
  painelSemanal,
  listarContasAReceber,
  listarRecebimentosDoVendedor,
  pagarContaAPagar,
  reativarCategoriaDoCaixa,
  receberDoVendedor,
  renomearCategoriaDoCaixa,
  verConferencia,
  verContaAPagar,
  verExtratoDoCaixa,
  verSaldoDoCaixa,
  desistirDoAcerto,
  fecharAcerto,
  iniciarAcerto,
  lerComissao,
  listarAcertos,
  listarPendenciasDoAcerto,
  previaDoFechamento,
  previaDoFechamentoComDecisoes,
  registrarLeituraDoAcerto,
  relatorioDeMaletasEmAbertoEAtrasadas,
  resolverPendenciaDoAcerto,
  verAcerto,
  relatorioDeDevolucoes,
  relatorioDeTaxaDeDevolucaoPorVendedor,
  relatorioDePecasComDefeito,
  relatorioDeTrocas,
  relatorioDeCreditosDosClientes,
  relatorioDeDinheiroDevolvido,
  relatorioDePedidosDeAutorizacao,
  conferirDevolucoesETrocas,
  conferirAgora,
  conferirODinheiroDaFase7,
  relatorioDeMercadoriaPorVendedor,
  relatorioDeAcertos,
  relatorioDeComissoes,
  relatorioDeLucroPorProduto,
  relatorioDeLucroPorVendedor,
  resultadoDoPeriodo,
  detalhamentoDoResultado,
  relatorioDeContasAPagar,
  relatorioDeContasAReceber,
  relatorioDeEsperadoEContado,
  relatorioDeFluxoDeCaixa,
  relatorioDeInadimplencia,
  relatorioDePendenciasDoAcerto,
  relatorioDeRecebimentosPorForma,
  relatorioDeCurvaAbc,
  relatorioDeAuditoria,
  relatorioDeComputadores,
  relatorioDeAcessos,
  relatorioDeRankingDePecas,
  relatorioDeRankingDeVendedores,
  relatorioDeTicketMedio,
  relatorioDeVendas,
  relatorioDeVendasCanceladas,
  relatorioDeVendasPorCategoria,
  relatorioDeVendasPorCliente,
  relatorioDeVendasPorPeriodo,
  relatorioDeVendasPorPraca,
  relatorioDeVendasPorProduto,
  relatorioDeVendasPorVendedor,
  relatorioDeRemessasPorVendedorESemana,
  relatorioDeReposicoes,
  relatorioDeSaldoPorMaleta,
  abaixoDoMinimo as relatorioDeEstoqueMinimo,
  compras as relatorioDeCompras,
  conciliacao as relatorioDeConferencia,
  historicoDeCusto as relatorioDeCustos,
  inventarios as relatorioDeContagens,
  itensDaCompra as relatorioDePecasDaCompra,
  movimentacoes as relatorioDeMovimentos,
  parados as relatorioDeParados,
  porFornecedor as relatorioDeComprasPorFornecedor,
  posicaoAtual as relatorioDePosicao,
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
  listar14,
  listar2,
  inativar1,
  inativar3,
  inativar4,
  inativar5,
  inativar6,
  reativar1,
  reativar2,
  reativar4,
  reativar5,
  reativar6,
  reativar7,
  redefinirSenha,
  renovar,
  sair,
  trocarSenha,
  type RespostaDoLogin,
} from '@corona/api-client-dona';
import {
  baixarFotoDoCliente,
  concluirFechamentoDaMaleta,
  iniciarFechamentoDaMaleta,
  lerFechamentoDaMaleta,
  lerTelasDoGestao,
  listarAlertasDoConsignado,
  listarNotasDoConsignado,
  listarSemanasDoRepresentante,
  marcarAlertaDoConsignadoComoVisto,
  registrarLeiturasDoFechamentoDaMaleta,
  relatorioDoFechamentoDaMaleta,
  verNotaDoConsignado,
  verSemanaDoRepresentante,
  verSituacaoDoClienteDoConsignado,
} from '@corona/api-client-dona';
import type { ParametrosDeConsignado } from '../shared/consignado';
import type { NomeDoCadastro } from '../shared/cadastros';
import type { ParametrosConferidos, ParametrosDeVendedor } from '../shared/validacao-parametros';
import type { ParametrosDeCompra } from '../shared/validacao-compras';
import type { ParametrosDeAcerto } from '../shared/validacao-acertos';
import type { ParametrosDeFinanceiro } from '../shared/validacao-financeiro';
import type { ParametrosDeMaleta } from '../shared/validacao-maletas';
import type { ParametrosDeDevolucoes } from '../shared/devolucoes';
import type { ParametrosDeSincronizacao } from '../shared/sincronizacao';
import type { ParametrosDeEstoque } from '../shared/validacao-estoque';
import type { ParametrosDeProduto } from '../shared/validacao-produtos';
import type { ParametrosDeRelatorio } from '../shared/validacao-relatorios';
import type { AbaDoVendedor, ArquivoBaixado } from '../shared/vendedores';
import { CacheDeFotos, LimiteDeBuscas } from './cache-de-fotos';
import type { ApiDeAutenticacao, Credenciais, Falha, Resultado } from './tipos';
import { idiomaDoComputador } from './idioma';

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
  /** Quando a tela precisa saber se o servidor criou (201) ou já tinha registrado (200). */
  distinguirCriado = false,
): Promise<Resultado<T | undefined>> {
  try {
    const { data, error, response } = await chamada();
    if (error !== undefined && error !== '')
      return { ok: false, falha: comoFalha(error, response) };
    if (response && !response.ok) return { ok: false, falha: comoFalha(undefined, response) };
    if (distinguirCriado && response)
      return { ok: true, dados: data, criado: response.status === 201 };
    return { ok: true, dados: data };
  } catch (e) {
    if (ehAppDesatualizado(e)) return { ok: false, falha: comoFalha(e, undefined) };
    return { ok: false, falha: { tipo: 'sem-conexao' } };
  }
}

type ChamadaGerada = (o: Record<string, unknown>) => Promise<RetornoDoCliente<unknown>>;

/** As quatro rotas de cada cadastro, no cliente gerado (os números vêm da ordem do OpenAPI). */
const ROTAS_DOS_CADASTROS = {
  fornecedores: {
    listar: listar9,
    criar: criar6,
    editar: editar6,
    excluir: excluir5,
    inativar: inativar4,
    reativar: reativar5,
  },
  categorias: {
    listar: listar13,
    criar: criar8,
    editar: editar8,
    excluir: excluir7,
    inativar: inativar6,
    reativar: reativar7,
  },
  pracas: {
    listar: listar7,
    criar: criar5,
    editar: editar5,
    excluir: excluir4,
    inativar: inativar3,
    reativar: reativar4,
  },
  semanas: { listar: listar3, criar: criar2, editar: editar2, excluir: excluir1 },
  restricoes: {
    listar: listar4,
    criar: criar3,
    editar: editar3,
    excluir: excluir2,
    inativar: inativar1,
    reativar: reativar2,
  },
  clientes: {
    listar: listar12,
    criar: criar7,
    editar: editar7,
    excluir: excluir6,
    inativar: inativar5,
    reativar: reativar6,
  },
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

/** Compras: receber, ver, listar e desfazer (só ADMIN; o servidor confere e recalcula tudo). */
async function executarCompra(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeCompra,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  const id = pedido.id as string;
  switch (pedido.operacao) {
    case 'compras.listar':
      return executar(() => listarCompras({ ...base, query: pedido.lista as never }));
    case 'compras.ver':
      return executar(() => verCompra({ ...base, path: { id } }));
    case 'compras.receber':
      return executar(() => receberCompra({ ...base, body: pedido.corpo as never }), true);
    case 'compras.desfazer':
      return executar(() =>
        desfazerCompra({
          ...base,
          path: { id },
          body: { id: pedido.pedidoId, motivo: pedido.motivo },
        }),
      );
  }
}

/** Computadores do vendedor, envios com problema e vendas de computador bloqueado (só ADMIN; o servidor confere). */
async function executarSincronizacao(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeSincronizacao,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  switch (pedido.operacao) {
    case 'sincronizacao.aparelhos':
      return executar(() =>
        listarAparelhosDoVendedor({ ...base, path: { vendedorId: pedido.vendedorId } }),
      );
    case 'sincronizacao.liberarAparelho':
      return executar(() => liberarAparelho({ ...base, path: { id: pedido.id } }));
    case 'sincronizacao.bloquearAparelho':
      return executar(() =>
        bloquearAparelho({
          ...base,
          path: { id: pedido.id },
          body: pedido.motivo ? { motivo: pedido.motivo } : {},
        }),
      );
    case 'sincronizacao.envios':
      return executar(() => listarEnviosComProblema(base));
    case 'sincronizacao.tentarDeNovo':
      return executar(() => tentarEnvioDeNovo({ ...base, path: { id: pedido.id } }));
    case 'sincronizacao.descartarEnvio':
      return executar(() =>
        descartarEnvio({ ...base, path: { id: pedido.id }, body: { motivo: pedido.motivo } }),
      );
    case 'sincronizacao.quarentena':
      return executar(() => listarVendasDeComputadorBloqueado(base));
    case 'sincronizacao.aprovarQuarentena':
      return executar(() =>
        aprovarVendaDeComputadorBloqueado({ ...base, path: { id: pedido.id } }),
      );
    case 'sincronizacao.descartarQuarentena':
      return executar(() =>
        descartarVendaDeComputadorBloqueado({
          ...base,
          path: { id: pedido.id },
          body: { motivo: pedido.motivo },
        }),
      );
    case 'sincronizacao.valoresSubstituidos':
      return executar(() => listarValoresDeClienteSubstituidos(base));
  }
}

/** Devoluções, trocas, créditos dos clientes e garantia (só ADMIN; o servidor recalcula valor, prazo e saldo). */
async function executarDevolucoes(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeDevolucoes,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  const filtros = (p: { situacao?: string; vendedorId?: string }) => ({
    ...(p.situacao ? { situacao: p.situacao } : {}),
    ...(p.vendedorId ? { vendedorId: p.vendedorId } : {}),
  });
  switch (pedido.operacao) {
    case 'devolucoes.autorizacoes':
      return executar(() => listarAutorizacoes({ ...base, query: filtros(pedido) }));
    case 'devolucoes.aprovarAutorizacao':
      return executar(() => aprovarAutorizacao({ ...base, path: { id: pedido.id } }));
    case 'devolucoes.negarAutorizacao':
      return executar(() =>
        negarAutorizacao({ ...base, path: { id: pedido.id }, body: { motivo: pedido.motivo } }),
      );
    case 'devolucoes.listar':
      return executar(() => listarDevolucoes({ ...base, query: filtros(pedido) }));
    case 'devolucoes.manter':
      return executar(() =>
        manterDevolucao({
          ...base,
          path: { id: pedido.id },
          body: pedido.observacao ? { observacao: pedido.observacao } : {},
        }),
      );
    case 'devolucoes.naoAceitar':
      return executar(() =>
        naoAceitarDevolucao({
          ...base,
          path: { id: pedido.id },
          body: { observacao: pedido.motivo },
        }),
      );
    case 'devolucoes.cancelar':
      return executar(() =>
        cancelarDevolucao({
          ...base,
          path: { id: pedido.id },
          body: { id: pedido.cancelamentoId, motivo: pedido.motivo },
        }),
      );
    case 'devolucoes.vendasComAPeca':
      return executar(() => vendasComAPecaDaDona({ ...base, query: { codigo: pedido.codigo } }));
    case 'devolucoes.registrar':
      return executar(() => devolverPelaDona({ ...base, body: pedido.corpo }), true);
    case 'devolucoes.conferencia':
      return executar(() => conferirDevolucoesETrocas({ ...base }));
    case 'trocas.listar':
      return executar(() => listarTrocas({ ...base, query: filtros(pedido) }));
    case 'trocas.cancelar':
      return executar(() =>
        cancelarTroca({
          ...base,
          path: { id: pedido.id },
          body: { id: pedido.cancelamentoId, motivo: pedido.motivo },
        }),
      );
    case 'creditos.listar':
      return executar(() =>
        listarCreditos({
          ...base,
          query: {
            ...filtros(pedido),
            ...(pedido.clienteId ? { clienteId: pedido.clienteId } : {}),
          },
        }),
      );
    case 'creditos.transferir':
      return executar(
        () =>
          transferirCredito({
            ...base,
            path: { id: pedido.id },
            body: { id: pedido.novoId, vendedorId: pedido.vendedorId },
          }),
        true,
      );
    case 'garantia.ler':
      return executar(() => lerGarantia(base));
    case 'garantia.alterar': {
      const { limiteReembolsoSemanal, valorAprovacaoReembolso } = pedido;
      return executar(() =>
        alterarGarantia({
          ...base,
          // O dinheiro vai em texto ("500.00"), nunca como número fracionário; vazio = sem limite.
          body: {
            prazoArrependimentoDias: pedido.prazoArrependimentoDias,
            prazoDefeitoDias: pedido.prazoDefeitoDias,
            percentualAlertaDevolucao: pedido.percentualAlertaDevolucao,
            ...(limiteReembolsoSemanal === null ? {} : { limiteReembolsoSemanal }),
            ...(valorAprovacaoReembolso === null ? {} : { valorAprovacaoReembolso }),
          },
        }),
      );
    }
  }
}

/** Maletas: montar o rascunho, enviar e consultar (só ADMIN; o servidor recalcula preço, totais e limite). */
async function executarMaleta(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeMaleta,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  switch (pedido.operacao) {
    case 'maletas.criar':
      return executar(() => criarRemessa({ ...base, body: pedido.corpo }), true);
    case 'maletas.ver':
      return executar(() => verRemessa({ ...base, path: { id: pedido.id } }));
    case 'maletas.listar':
      return executar(() => listarRemessas({ ...base, query: pedido.lista }));
    case 'maletas.colocarPeca':
      return executar(() =>
        colocarItemNaRemessa({
          ...base,
          path: { id: pedido.id, produtoId: pedido.produtoId },
          body: { quantidade: pedido.quantidade, version: pedido.version },
        }),
      );
    case 'maletas.tirarPeca':
      return executar(() =>
        tirarItemDaRemessa({
          ...base,
          path: { id: pedido.id, produtoId: pedido.produtoId },
          query: { version: pedido.version },
        }),
      );
    case 'maletas.excluir':
      return executar(() =>
        excluirRascunhoDeRemessa({
          ...base,
          path: { id: pedido.id },
          query: { version: pedido.version },
        }),
      );
    case 'maletas.enviar':
      return executar(() => enviarRemessa({ ...base, path: { id: pedido.id } }), true);
    case 'maletas.emAberto':
      return executar(() =>
        listarMaletasEmAbertoDoVendedor({ ...base, query: { vendedorId: pedido.vendedorId } }),
      );
    case 'maletas.nota':
      return executar(() => notaDaRemessa({ ...base, path: { id: pedido.id } }));
    case 'maletas.saldo':
      return executar(() => verMaletaDaRemessa({ ...base, path: { id: pedido.id } }));
    case 'maletas.conferencia':
      return executar(() => verConferenciaDaMaleta({ ...base, path: { id: pedido.id } }));
    case 'maletas.devolver':
      return executar(
        () =>
          devolverPecasDaMaleta({
            ...base,
            path: { id: pedido.id },
            body: { itens: pedido.itens },
          }),
        true,
      );
    case 'maletas.desfazerDevolucao':
      return executar(
        () =>
          desfazerDevolucaoDaMaleta({
            ...base,
            path: { id: pedido.id, devolucaoId: pedido.devolucaoId },
            body: { id: pedido.estornoId, motivo: pedido.motivo },
          }),
        true,
      );
    case 'maletas.cancelar':
      return executar(() =>
        cancelarRemessa({ ...base, path: { id: pedido.id }, body: { motivo: pedido.motivo } }),
      );
  }
}

/**
 * Consignado ao cliente (§23): o que a tela mostra ou esconde, o Fechamento da maleta e as leituras das notas, semanas,
 * clientes e alertas. Só ADMIN; nada daqui cria nota nem acerto (isso nasce no aplicativo do representante).
 */
async function executarConsignado(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeConsignado,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  switch (pedido.operacao) {
    case 'telas.ler':
      return executar(() => lerTelasDoGestao(base));
    case 'fechamento.iniciar':
      return executar(() => iniciarFechamentoDaMaleta({ ...base, path: { id: pedido.id } }), true);
    case 'fechamento.ler':
      return executar(() => lerFechamentoDaMaleta({ ...base, path: { id: pedido.id } }));
    case 'fechamento.leituras':
      return executar(
        () =>
          registrarLeiturasDoFechamentoDaMaleta({
            ...base,
            path: { id: pedido.id },
            body: { leituras: pedido.leituras },
          }),
        true,
      );
    case 'fechamento.concluir':
      return executar(() => concluirFechamentoDaMaleta({ ...base, path: { id: pedido.id } }), true);
    case 'consignado.semanas':
      return executar(() =>
        listarSemanasDoRepresentante({ ...base, path: { vendedorId: pedido.id } }),
      );
    case 'consignado.semana':
      return executar(() => verSemanaDoRepresentante({ ...base, path: { semanaId: pedido.id } }));
    case 'consignado.notas':
      return executar(() => listarNotasDoConsignado({ ...base, query: pedido.filtros }));
    case 'consignado.nota':
      return executar(() => verNotaDoConsignado({ ...base, path: { id: pedido.id } }));
    case 'consignado.cliente':
      return executar(() => verSituacaoDoClienteDoConsignado({ ...base, path: { id: pedido.id } }));
    case 'consignado.alertas':
      return executar(() =>
        listarAlertasDoConsignado({ ...base, query: { soNaoVistos: pedido.soNaoVistos } }),
      );
    case 'consignado.alertaVisto':
      return executar(
        () => marcarAlertaDoConsignadoComoVisto({ ...base, path: { id: pedido.id } }),
        true,
      );
    case 'consignado.configuracao':
      return executar(() => lerConfiguracaoDoConsignado(base));
    case 'consignado.configuracaoAlterar':
      return executar(() => alterarConfiguracaoDoConsignado({ ...base, body: pedido.corpo }));
    case 'consignado.fotoDoCliente': {
      // Foto de documento é dado pessoal: vem só pela rota da dona e não fica guardada em disco.
      try {
        const { data, error, response } = (await baixarFotoDoCliente({
          ...base,
          path: { id: pedido.id, tipo: pedido.tipo },
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
          nome: `${pedido.tipo}.jpg`,
          tipo: response.headers.get('content-type') ?? 'image/jpeg',
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

/** Acertos, pendências e a comissão (só ADMIN). O servidor calcula tudo; aqui só se repassa o que a tela decidiu. */
async function executarAcerto(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeAcerto,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  switch (pedido.operacao) {
    case 'acertos.listar':
      return executar(() => listarAcertos({ ...base, query: pedido.lista }));
    case 'acertos.iniciar':
      return executar(() => iniciarAcerto({ ...base, body: pedido.corpo }), true);
    case 'acertos.ver':
      return executar(() => verAcerto({ ...base, path: { id: pedido.id } }));
    case 'acertos.ler':
      return executar(
        () => registrarLeituraDoAcerto({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
        true,
      );
    case 'acertos.desistir':
      return executar(
        () => desistirDoAcerto({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
        true,
      );
    case 'acertos.previa':
      return executar(() => previaDoFechamento({ ...base, path: { id: pedido.id } }));
    case 'acertos.previaComDecisoes':
      return executar(() =>
        previaDoFechamentoComDecisoes({ ...base, path: { id: pedido.id }, body: pedido.pedido }),
      );
    case 'acertos.fechar':
      return executar(
        () => fecharAcerto({ ...base, path: { id: pedido.id }, body: pedido.pedido }),
        true,
      );
    case 'pendencias.listar':
      return executar(() => listarPendenciasDoAcerto({ ...base, query: pedido.lista }));
    case 'pendencias.resolver':
      return executar(
        () => resolverPendenciaDoAcerto({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
        true,
      );
    case 'comissao.ler':
      return executar(() => lerComissao(base));
    case 'comissao.alterar':
      return executar(() => alterarComissao({ ...base, body: pedido.corpo }));
  }
}

/** Caixa, contas a pagar e a receber, recebimento do vendedor e fechar semana (só ADMIN). O servidor calcula tudo. */
async function executarFinanceiro(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeFinanceiro,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  switch (pedido.operacao) {
    case 'caixa.extrato':
      return executar(() => verExtratoDoCaixa({ ...base, query: pedido.consulta }));
    case 'caixa.saldo':
      return executar(() => verSaldoDoCaixa(base));
    case 'caixa.lancar':
      return executar(() => lancarNoCaixa({ ...base, body: pedido.corpo }), true);
    case 'caixa.estornar':
      return executar(
        () => estornarLancamentoDoCaixa({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
        true,
      );
    case 'caixa.categorias':
      return executar(() => listarCategoriasDoCaixa(base));
    case 'caixa.criarCategoria':
      return executar(() => criarCategoriaDoCaixa({ ...base, body: pedido.corpo }), true);
    case 'caixa.renomearCategoria':
      return executar(() =>
        renomearCategoriaDoCaixa({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
      );
    case 'caixa.inativarCategoria':
      return executar(() => inativarCategoriaDoCaixa({ ...base, path: { id: pedido.id } }));
    case 'caixa.reativarCategoria':
      return executar(() => reativarCategoriaDoCaixa({ ...base, path: { id: pedido.id } }));
    case 'contasAPagar.listar':
      return executar(() => listarContasAPagar({ ...base, query: pedido.lista }));
    case 'contasAPagar.ver':
      return executar(() => verContaAPagar({ ...base, path: { id: pedido.id } }));
    case 'contasAPagar.pagar':
      return executar(
        () => pagarContaAPagar({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
        true,
      );
    case 'contasAPagar.estornar':
      return executar(
        () =>
          estornarPagamentoAFornecedor({
            ...base,
            path: { baixaId: pedido.id },
            body: pedido.corpo,
          }),
        true,
      );
    case 'contasAReceber.listar':
      return executar(() => listarContasAReceber({ ...base, query: pedido.lista }));
    case 'recebimentos.listar':
      return executar(() => listarRecebimentosDoVendedor({ ...base, query: pedido.lista }));
    case 'recebimentos.receber':
      return executar(() => receberDoVendedor({ ...base, body: pedido.corpo }), true);
    case 'recebimentos.estornar':
      return executar(
        () =>
          estornarRecebimentoDoVendedorPelaDona({
            ...base,
            path: { recebimentoId: pedido.id },
            body: pedido.corpo,
          }),
        true,
      );
    case 'semanas.fechar':
      return executar(
        () => fecharSemana({ ...base, path: { id: pedido.id }, body: pedido.corpo }),
        true,
      );
  }
}

/** Estoque e contagem (só ADMIN; o servidor recalcula saldo e ajustes, a tela só manda o que a pessoa fez). */
async function executarEstoque(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeEstoque,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  const id = pedido.id as string;
  const path = { id };
  const query = pedido.consulta as never;
  const body = pedido.corpo as never;
  switch (pedido.operacao) {
    case 'estoque.ficha':
      return executar(() => fichaDoEstoque({ ...base, path, query }));
    case 'estoque.lancar':
      return executar(() => lancarNoEstoque({ ...base, body }));
    case 'estoque.ajustar':
      return executar(() => ajustarEstoque({ ...base, body }));
    case 'estoque.desfazer':
      return executar(() => desfazerLancamento({ ...base, path, body }));
    case 'estoque.lancamentos':
      return executar(() => listarLancamentos({ ...base, query }));
    case 'estoque.acabando':
      return executar(() => acabandoNoEstoque({ ...base, query }));
    case 'contagens.listar':
      return executar(() => listarContagens({ ...base, query }));
    case 'contagens.comecar':
      return executar(() => comecarContagem({ ...base, body }));
    case 'contagens.ver':
      return executar(() => verContagem({ ...base, path }));
    case 'contagens.itens':
      return executar(() => itensDaContagem({ ...base, path, query }));
    case 'contagens.ler':
      return executar(() => lerNaContagem({ ...base, path, body }), true);
    case 'contagens.corrigir':
      return executar(() => corrigirContagem({ ...base, path, body }), true);
    case 'contagens.fechar':
      return executar(() => fecharContagem({ ...base, path, body }));
    case 'contagens.cancelar':
      return executar(() => cancelarContagem({ ...base, path, body }));
  }
}

function avisoDoCustoDoCadastro(n: number): string {
  return idiomaDoComputador() === 'pt'
    ? `${n} ${n === 1 ? 'peça está com o custo do cadastro' : 'peças estão com o custo do cadastro'}: esse custo muda se o cadastro mudar.`
    : `${n} ${n === 1 ? 'pieza tiene el costo del registro' : 'piezas tienen el costo del registro'}: ese costo cambia si el registro cambia.`;
}

/**
 * O resultado do período chega como uma lista de linhas; a tela de relatório o mostra como qualquer outro (linhas e arquivos).
 * Quando há peças vendidas com o custo do cadastro, uma linha de aviso diz quantas (é estimativa).
 */
function comoRelatorioDoResultado(dados: unknown): unknown {
  const r = dados as
    { pecasComCustoDoCadastro?: number; linhas?: Record<string, unknown>[] } | undefined;
  if (!r?.linhas) return dados;
  const itens: Record<string, unknown>[] = r.linhas.map((l, i) => ({
    ...l,
    id: `${String(l.chave)}-${i}`,
  }));
  const n = r.pecasComCustoDoCadastro ?? 0;
  if (n > 0) {
    itens.push({
      id: 'aviso',
      chave: 'AVISO',
      operacao: 'AVISO',
      titulo: avisoDoCustoDoCadastro(n),
    });
  }
  return {
    itens,
    total: itens.length,
    pagina: 0,
    tamanho: Math.max(itens.length, 1),
    totais: [],
    recorte: null,
  };
}

/** Relatórios de cadastro (só ADMIN; o servidor confere). O de produtos traz o custo, por isso só existe aqui. */
async function executarRelatorio(
  client: ReturnType<typeof criarClienteDaApi>,
  headers: { Authorization: string },
  pedido: ParametrosDeRelatorio,
): Promise<Resultado<unknown>> {
  const base = { client, headers };
  const { compraId, ...q } = pedido.consulta;
  switch (pedido.relatorio) {
    case 'estoque-posicao':
      return executar(() => relatorioDePosicao({ ...base, query: q }));
    case 'estoque-movimentos':
      return executar(() => relatorioDeMovimentos({ ...base, query: q }));
    case 'estoque-minimo':
      return executar(() => relatorioDeEstoqueMinimo({ ...base, query: q }));
    case 'estoque-parados':
      return executar(() => relatorioDeParados({ ...base, query: q }));
    case 'estoque-contagens':
      return executar(() => relatorioDeContagens({ ...base, query: q }));
    case 'estoque-conferencia':
      return executar(() => relatorioDeConferencia({ ...base, query: q }));
    case 'compras-por-compra':
      return executar(() => relatorioDeCompras({ ...base, query: q }));
    case 'compras-por-fornecedor':
      return executar(() => relatorioDeComprasPorFornecedor({ ...base, query: q }));
    case 'compras-custos':
      return executar(() => relatorioDeCustos({ ...base, query: q }));
    case 'compras-pecas': {
      const { recorte, pagina, tamanho } = q;
      return executar(() =>
        relatorioDePecasDaCompra({
          ...base,
          path: { id: compraId ?? '' },
          query: { pagina, tamanho, ...(recorte ? { recorte } : {}) },
        }),
      );
    }
    case 'maleta-remessas':
      return executar(() => relatorioDeRemessasPorVendedorESemana({ ...base, query: q }));
    case 'maleta-reposicoes':
      return executar(() => relatorioDeReposicoes({ ...base, query: q }));
    case 'maleta-saldo':
      return executar(() => relatorioDeSaldoPorMaleta({ ...base, query: q }));
    case 'maleta-mercadoria':
      return executar(() => relatorioDeMercadoriaPorVendedor({ ...base, query: q }));
    case 'maleta-em-aberto':
      return executar(() => relatorioDeMaletasEmAbertoEAtrasadas({ ...base, query: q }));
    case 'maleta-fechamento':
      return executar(() =>
        relatorioDoFechamentoDaMaleta({
          ...base,
          query: { remessaId: q.remessaId ?? '', pagina: q.pagina, tamanho: q.tamanho },
        }),
      );
    case 'devolucao-por-motivo':
      return executar(() => relatorioDeDevolucoes({ ...base, query: q }));
    case 'devolucao-por-vendedor':
      return executar(() => relatorioDeTaxaDeDevolucaoPorVendedor({ ...base, query: q }));
    case 'devolucao-defeitos':
      return executar(() => relatorioDePecasComDefeito({ ...base, query: q }));
    case 'devolucao-trocas':
      return executar(() => relatorioDeTrocas({ ...base, query: q }));
    case 'devolucao-creditos':
      return executar(() => relatorioDeCreditosDosClientes({ ...base, query: q }));
    case 'devolucao-reembolsos':
      return executar(() => relatorioDeDinheiroDevolvido({ ...base, query: q }));
    case 'devolucao-autorizacoes':
      return executar(() => relatorioDePedidosDeAutorizacao({ ...base, query: q }));
    case 'financeiro-acertos':
      return executar(() => relatorioDeAcertos({ ...base, query: q }));
    case 'financeiro-pendencias':
      return executar(() => relatorioDePendenciasDoAcerto({ ...base, query: q }));
    case 'financeiro-esperado-contado':
      return executar(() => relatorioDeEsperadoEContado({ ...base, query: q }));
    case 'financeiro-comissoes':
      return executar(() => relatorioDeComissoes({ ...base, query: q }));
    case 'financeiro-resultado': {
      const r = await executar(() =>
        resultadoDoPeriodo({ ...base, query: { de: q.de, ate: q.ate } }),
      );
      return r.ok ? { ...r, dados: comoRelatorioDoResultado(r.dados) } : r;
    }
    case 'financeiro-resultado-registros':
      return executar(() =>
        detalhamentoDoResultado({
          ...base,
          query: {
            de: q.de,
            ate: q.ate,
            linha: q.linha,
            pagina: q.pagina,
            tamanho: q.tamanho,
            ...(q.categoriaId ? { categoriaId: q.categoriaId } : {}),
          },
        }),
      );
    case 'financeiro-lucro-produto':
      return executar(() => relatorioDeLucroPorProduto({ ...base, query: q }));
    case 'financeiro-lucro-vendedor':
      return executar(() => relatorioDeLucroPorVendedor({ ...base, query: q }));
    case 'financeiro-a-receber':
      return executar(() => relatorioDeContasAReceber({ ...base, query: q }));
    case 'financeiro-a-pagar':
      return executar(() => relatorioDeContasAPagar({ ...base, query: q }));
    case 'financeiro-inadimplencia':
      return executar(() => relatorioDeInadimplencia({ ...base, query: q }));
    case 'financeiro-caixa':
      return executar(() => relatorioDeFluxoDeCaixa({ ...base, query: q }));
    case 'financeiro-recebimentos':
      return executar(() => relatorioDeRecebimentosPorForma({ ...base, query: q }));
    case 'vendas-lista':
      return executar(() => relatorioDeVendas({ ...base, query: q }));
    case 'vendas-canceladas':
      return executar(() => relatorioDeVendasCanceladas({ ...base, query: q }));
    case 'vendas-por-vendedor':
      return executar(() => relatorioDeVendasPorVendedor({ ...base, query: q }));
    case 'vendas-por-praca':
      return executar(() => relatorioDeVendasPorPraca({ ...base, query: q }));
    case 'vendas-por-cliente':
      return executar(() => relatorioDeVendasPorCliente({ ...base, query: q }));
    case 'vendas-por-produto':
      return executar(() => relatorioDeVendasPorProduto({ ...base, query: q }));
    case 'vendas-por-categoria':
      return executar(() => relatorioDeVendasPorCategoria({ ...base, query: q }));
    case 'vendas-por-periodo':
      return executar(() => relatorioDeVendasPorPeriodo({ ...base, query: q }));
    case 'vendas-ranking-vendedores':
      return executar(() => relatorioDeRankingDeVendedores({ ...base, query: q }));
    case 'vendas-ranking-pecas':
      return executar(() => relatorioDeRankingDePecas({ ...base, query: q }));
    case 'vendas-ticket-medio':
      return executar(() => relatorioDeTicketMedio({ ...base, query: q }));
    case 'vendas-curva-abc':
      return executar(() => relatorioDeCurvaAbc({ ...base, query: q }));
    case 'controle-auditoria':
      return executar(() => relatorioDeAuditoria({ ...base, query: q }));
    case 'controle-computadores':
      return executar(() => relatorioDeComputadores({ ...base, query: q }));
    case 'controle-acessos':
      return executar(() => relatorioDeAcessos({ ...base, query: q }));
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
    case 'produtos.estoqueMinimo':
      return executar(() =>
        mudarEstoqueMinimo({ ...base, path: { id }, body: pedido.corpo as never }),
      );
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
      if (pedido.operacao.startsWith('estoque.') || pedido.operacao.startsWith('contagens.')) {
        return executarEstoque(client, headers, pedido as ParametrosDeEstoque);
      }
      if (pedido.operacao.startsWith('maletas.')) {
        return executarMaleta(client, headers, pedido as ParametrosDeMaleta);
      }
      if (
        pedido.operacao.startsWith('acertos.') ||
        pedido.operacao.startsWith('pendencias.') ||
        pedido.operacao.startsWith('comissao.')
      ) {
        return executarAcerto(client, headers, pedido as ParametrosDeAcerto);
      }
      if (
        pedido.operacao.startsWith('caixa.') ||
        pedido.operacao.startsWith('contasAPagar.') ||
        pedido.operacao.startsWith('contasAReceber.') ||
        pedido.operacao.startsWith('recebimentos.') ||
        pedido.operacao === 'semanas.fechar'
      ) {
        return executarFinanceiro(client, headers, pedido as ParametrosDeFinanceiro);
      }
      if (pedido.operacao.startsWith('sincronizacao.')) {
        return executarSincronizacao(client, headers, pedido as ParametrosDeSincronizacao);
      }
      if (
        pedido.operacao.startsWith('devolucoes.') ||
        pedido.operacao.startsWith('trocas.') ||
        pedido.operacao.startsWith('creditos.') ||
        pedido.operacao.startsWith('garantia.')
      ) {
        return executarDevolucoes(client, headers, pedido as ParametrosDeDevolucoes);
      }
      if (
        pedido.operacao === 'telas.ler' ||
        pedido.operacao.startsWith('fechamento.') ||
        pedido.operacao.startsWith('consignado.')
      ) {
        return executarConsignado(client, headers, pedido as ParametrosDeConsignado);
      }
      if (pedido.operacao.startsWith('compras.')) {
        return executarCompra(client, headers, pedido as ParametrosDeCompra);
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
        case 'cotacao.dia':
          return executar(() =>
            cotacoesDoDia({ client, headers, query: pedido.data ? { data: pedido.data } : {} }),
          );
        case 'cotacao.previa':
          return executar(() =>
            previaDaCotacao({
              client,
              headers,
              body: {
                ...(pedido.usd ? { usd: pedido.usd } : {}),
                ...(pedido.guaranisPorReal ? { guaranisPorReal: pedido.guaranisPorReal } : {}),
              },
            }),
          );
        case 'cotacao.gravar':
          return executar(() =>
            gravarCotacao({
              client,
              headers,
              body: {
                ...(pedido.id ? { id: pedido.id } : {}),
                ...(pedido.usd ? { usd: pedido.usd } : {}),
                ...(pedido.guaranisPorReal ? { guaranisPorReal: pedido.guaranisPorReal } : {}),
              },
            }),
          );
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
          return executar(() => listar14({ client, headers }));
        case 'conciliacoes.conferir':
          return executar(() => conferirODinheiroDaFase7({ client, headers }));
        case 'conferencias.listar':
          return executar(() => listarConferencias({ client, headers }));
        case 'conferencias.ver':
          return executar(() => verConferencia({ client, headers, path: { id: pedido.id } }));
        case 'conferencias.conferirAgora':
          return executar(() => conferirAgora({ client, headers, body: { id: pedido.id } }), true);
        case 'painel.semanal':
          return executar(() =>
            painelSemanal({
              client,
              headers,
              query: {
                ...(pedido.semana ? { semana: pedido.semana } : {}),
                ...(pedido.vendedor ? { vendedor: pedido.vendedor } : {}),
                ...(pedido.praca ? { praca: pedido.praca } : {}),
                ...(pedido.situacao ? { situacao: pedido.situacao } : {}),
              },
            }),
          );
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
