import {
  ACEITA_ID_NO_APP,
  CAMPOS_DO_ENDERECO,
  CAMPOS_DOS_CADASTROS,
  TEM_ENDERECO,
  type AcaoDeCadastro,
  TEM_ATIVO,
  type CampoDaApi,
  type NomeDoCadastro,
} from './cadastros';
import type { OperacaoDaApi } from './ponte';
import { conferirCompra, type ParametrosDeCompra } from './validacao-compras';
import { conferirAcerto, type ParametrosDeAcerto } from './validacao-acertos';
import { conferirFinanceiro, type ParametrosDeFinanceiro } from './validacao-financeiro';
import { conferirMaleta, type ParametrosDeMaleta } from './validacao-maletas';
import { conferirEstoque, type ParametrosDeEstoque } from './validacao-estoque';
import { conferirProduto, type ParametrosDeProduto } from './validacao-produtos';
import { conferirRelatorio, type ParametrosDeRelatorio } from './validacao-relatorios';
import { conferirDevolucoes, type ParametrosDeDevolucoes } from './devolucoes';
import { conferirConsignado, type ParametrosDeConsignado } from './consignado';
import { conferirSincronizacao, type ParametrosDeSincronizacao } from './sincronizacao';
import {
  ABAS_DO_VENDEDOR,
  CAMPOS_DA_REFERENCIA,
  CAMPOS_DAS_ABAS,
  CAMPOS_DO_VENDEDOR,
  SITUACOES_DA_LISTA,
  TAMANHO_MAXIMO_DO_DOCUMENTO,
  TAMANHO_MAXIMO_DO_NOME_DO_ARQUIVO,
  extensaoAceita,
  type AbaDoVendedor,
  type ArquivoParaEnviar,
  type OperacaoDeVendedores,
} from './vendedores';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DECIMAL = /^-?\d{1,14}(\.\d{1,2})?$/;
const DATA = /^\d{4}-\d{2}-\d{2}$/;
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const PERFIS = ['ADMIN', 'VENDEDOR'];
/** Tamanho máximo dos textos; o servidor confere de novo o que é regra de negócio. */
const MAX_TEXTO = 200;

export interface ParametrosDeUsuarioNovo {
  nome: string;
  login: string;
  perfil: string;
  vendedorId?: string;
}

export interface ParametrosDeUsuarioEditado {
  id: string;
  nome: string;
  perfil: string;
  vendedorId?: string;
  version: number;
}

export interface ParametrosDaLista {
  busca?: string;
  /** Só nos cadastros: ATIVOS (padrão do servidor), INATIVOS ou TODOS. */
  situacao?: string;
  perfil?: string;
  ativo?: boolean;
  pagina: number;
  tamanho: number;
}

export interface ParametrosDeCadastro {
  operacao: Exclude<OperacaoDaApi, 'eu'> & `${NomeDoCadastro}.${AcaoDeCadastro}`;
  cadastro: NomeDoCadastro;
  acao: AcaoDeCadastro;
  /** Só em `listar`. */
  lista?: ParametrosDaLista;
  /** Só em `editar`, `excluir`, `inativar` e `reativar`. */
  id?: string;
  /** Em `criar` e `editar`: os campos já conferidos. Em `inativar` e `reativar`: só a `version`. */
  corpo?: Record<string, unknown>;
}

export type ParametrosDeVendedor =
  | { operacao: 'vendedores.listar'; lista: ParametrosDaLista; situacao?: string }
  | { operacao: 'vendedores.ver' | 'vendedores.inativar' | 'vendedores.reativar'; id: string }
  | { operacao: 'vendedores.criar'; corpo: Record<string, unknown> }
  | { operacao: 'vendedores.editar'; id: string; corpo: Record<string, unknown> }
  | { operacao: 'vendedores.aba.ver'; id: string; aba: AbaDoVendedor }
  | {
      operacao: 'vendedores.aba.gravar';
      id: string;
      aba: AbaDoVendedor;
      corpo: Record<string, unknown>;
    }
  | { operacao: 'vendedores.referencias.listar' | 'vendedores.documentos.listar'; id: string }
  | { operacao: 'vendedores.referencias.criar'; id: string; corpo: Record<string, unknown> }
  | {
      operacao: 'vendedores.referencias.editar';
      id: string;
      referenciaId: string;
      corpo: Record<string, unknown>;
    }
  | { operacao: 'vendedores.referencias.excluir'; id: string; referenciaId: string }
  | {
      operacao: 'vendedores.documentos.enviar';
      id: string;
      descricao?: string;
      arquivo: ArquivoParaEnviar;
    }
  | {
      operacao: 'vendedores.documentos.baixar' | 'vendedores.documentos.excluir';
      id: string;
      documentoId: string;
    };

export type ParametrosConferidos =
  | ParametrosDeCadastro
  | ParametrosDeVendedor
  | ParametrosDeProduto
  | ParametrosDeCompra
  | ParametrosDeMaleta
  | ParametrosDeAcerto
  | ParametrosDeFinanceiro
  | ParametrosDeEstoque
  | ParametrosDeRelatorio
  | ParametrosDeSincronizacao
  | ParametrosDeDevolucoes
  | ParametrosDeConsignado
  | {
      operacao:
        | 'eu'
        | 'cotacao.hoje'
        | 'avisos.listar'
        | 'conciliacoes.conferir'
        | 'conferencias.listar'
        | 'vendedores.escolha';
    }
  | { operacao: 'conferencias.ver' | 'conferencias.conferirAgora'; id: string }
  | {
      operacao: 'painel.semanal';
      semana?: string;
      vendedor?: string;
      praca?: string;
      situacao?: string;
    }
  | { operacao: 'usuarios.listar'; lista: ParametrosDaLista }
  | { operacao: 'usuarios.criar'; novo: ParametrosDeUsuarioNovo }
  | { operacao: 'usuarios.editar'; editado: ParametrosDeUsuarioEditado }
  | { operacao: 'usuarios.desativar' | 'usuarios.reativar' | 'usuarios.redefinirSenha'; id: string }
  | { operacao: 'cotacao.converter'; valorEmReais: string; data?: string };

function textoOpcional(v: unknown): string | undefined | null {
  if (v === undefined || v === '') return undefined;
  return ehTexto(v) && v.length <= MAX_TEXTO ? v : null;
}

function vendedorOpcional(v: unknown): string | undefined | null {
  if (v === undefined || v === null || v === '') return undefined;
  return ehTexto(v) && UUID.test(v) ? v : null;
}

function valorDoCampo(campo: CampoDaApi, v: unknown): unknown {
  switch (campo.tipo) {
    case 'texto':
      return ehTexto(v) && v.length <= (campo.max ?? MAX_TEXTO) ? v : undefined;
    case 'inteiro':
      return typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 1_000_000_000
        ? v
        : undefined;
    case 'data':
      return ehTexto(v) && DATA.test(v) ? v : undefined;
    case 'booleano':
      return typeof v === 'boolean' ? v : undefined;
    case 'uuid':
      return ehTexto(v) && UUID.test(v) ? v : undefined;
    case 'situacao':
      return v === 'ABERTA' || v === 'FECHADA' ? v : undefined;
    case 'dinheiro':
      return ehTexto(v) && DINHEIRO.test(v) ? v : undefined;
    case 'sexo':
      return v === 'FEMININO' || v === 'MASCULINO' || v === 'OUTRO' ? v : undefined;
  }
}

/** Copia só os campos conhecidos; qualquer campo desconhecido ou de tipo errado recusa tudo (`null`). */
function conferirCampos(
  campos: readonly CampoDaApi[],
  bruto: Record<string, unknown>,
  extras: readonly string[],
): Record<string, unknown> | null {
  const saida: Record<string, unknown> = {};
  for (const [nome, v] of Object.entries(bruto)) {
    if (extras.includes(nome)) continue;
    const campo = campos.find((c) => c.nome === nome);
    if (!campo) return null;
    if (v === undefined || v === null) continue;
    const ok = valorDoCampo(campo, v);
    if (ok === undefined) return null;
    saida[nome] = ok;
  }
  return saida;
}

function conferirCorpoDeCadastro(
  cadastro: NomeDoCadastro,
  acao: 'criar' | 'editar',
  p: Record<string, unknown>,
): Record<string, unknown> | null {
  const corpo = conferirCampos(CAMPOS_DOS_CADASTROS[cadastro], p, [
    'endereco',
    'id',
    'version',
    'cadastro',
  ]);
  if (corpo === null) return null;
  if (p.endereco !== undefined && p.endereco !== null) {
    if (!TEM_ENDERECO[cadastro] || !ehObjeto(p.endereco)) return null;
    const endereco = conferirCampos(CAMPOS_DO_ENDERECO, p.endereco, []);
    if (endereco === null) return null;
    corpo.endereco = endereco;
  }
  if (acao === 'criar' && p.id !== undefined) {
    if (!ACEITA_ID_NO_APP[cadastro] || !ehTexto(p.id) || !UUID.test(p.id)) return null;
    corpo.id = p.id;
  }
  if (acao === 'editar') {
    const v = p.version;
    if (typeof v !== 'number' || !Number.isInteger(v) || v < 0) return null;
    corpo.version = v;
  }
  return corpo;
}

function conferirCadastro(
  operacao: ParametrosDeCadastro['operacao'],
  p: Record<string, unknown>,
): ParametrosConferidos | null {
  const [cadastro, acao] = operacao.split('.') as [NomeDoCadastro, AcaoDeCadastro];
  const base = { operacao, cadastro, acao };
  switch (acao) {
    case 'listar': {
      const busca = textoOpcional(p.busca);
      if (busca === null) return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 20;
      if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
      if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
      if (tamanho < 1 || tamanho > 100) return null;
      const lista: ParametrosDaLista = { pagina, tamanho };
      if (busca) lista.busca = busca;
      if (p.situacao !== undefined) {
        if (!TEM_ATIVO[cadastro]) return null;
        if (!(SITUACOES_DA_LISTA as readonly unknown[]).includes(p.situacao)) return null;
        lista.situacao = p.situacao as string;
      }
      return { ...base, lista };
    }
    case 'excluir':
      return ehTexto(p.id) && UUID.test(p.id) ? { ...base, id: p.id } : null;
    case 'inativar':
    case 'reativar': {
      if (!TEM_ATIVO[cadastro]) return null;
      if (Object.keys(p).some((k) => k !== 'id' && k !== 'version')) return null;
      if (!ehTexto(p.id) || !UUID.test(p.id)) return null;
      if (p.version === undefined) return { ...base, id: p.id };
      if (!versaoValida(p.version)) return null;
      return { ...base, id: p.id, corpo: { version: p.version } };
    }
    case 'criar': {
      const corpo = conferirCorpoDeCadastro(cadastro, 'criar', p);
      return corpo ? { ...base, corpo } : null;
    }
    case 'editar': {
      if (!ehTexto(p.id) || !UUID.test(p.id)) return null;
      const corpo = conferirCorpoDeCadastro(cadastro, 'editar', p);
      return corpo ? { ...base, id: p.id, corpo } : null;
    }
  }
}

const idValido = (v: unknown): v is string => ehTexto(v) && UUID.test(v);

function versaoValida(v: unknown): v is number {
  return typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 1_000_000_000;
}

/** Corpo de uma aba, da referência ou do vendedor: só campos conhecidos; `version` só quando `comVersao`. */
function corpoComVersao(
  campos: readonly CampoDaApi[],
  p: Record<string, unknown>,
  comVersao: boolean,
  ignorar: readonly string[],
): Record<string, unknown> | null {
  const corpo = conferirCampos(campos, p, ['version', ...ignorar]);
  if (corpo === null) return null;
  if (comVersao) {
    if (!versaoValida(p.version)) return null;
    corpo.version = p.version;
  } else if (p.version !== undefined) {
    return null;
  }
  return corpo;
}

function conferirArquivo(v: unknown): ArquivoParaEnviar | null {
  if (!ehObjeto(v) || !ehTexto(v.nome) || !(v.bytes instanceof Uint8Array)) return null;
  if (v.nome.length === 0 || v.nome.length > TAMANHO_MAXIMO_DO_NOME_DO_ARQUIVO) return null;
  if (!extensaoAceita(v.nome)) return null;
  if (v.bytes.byteLength === 0 || v.bytes.byteLength > TAMANHO_MAXIMO_DO_DOCUMENTO) return null;
  for (const k of Object.keys(v)) if (k !== 'nome' && k !== 'bytes') return null;
  return { nome: v.nome, bytes: v.bytes };
}

function conferirVendedor(
  operacao: OperacaoDeVendedores,
  p: Record<string, unknown>,
): ParametrosDeVendedor | null {
  const soCom = (...nomes: string[]) => Object.keys(p).every((k) => nomes.includes(k));
  switch (operacao) {
    case 'vendedores.listar': {
      if (!soCom('busca', 'situacao', 'pagina', 'tamanho')) return null;
      const busca = textoOpcional(p.busca);
      if (busca === null) return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 20;
      if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
      if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
      if (tamanho < 1 || tamanho > 100) return null;
      const lista: ParametrosDaLista = { pagina, tamanho };
      if (busca) lista.busca = busca;
      if (p.situacao === undefined) return { operacao, lista };
      if (!(SITUACOES_DA_LISTA as readonly unknown[]).includes(p.situacao)) return null;
      return { operacao, lista, situacao: p.situacao as string };
    }
    case 'vendedores.ver':
    case 'vendedores.inativar':
    case 'vendedores.reativar':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'vendedores.criar': {
      const corpo = corpoComVersao(CAMPOS_DO_VENDEDOR, p, false, []);
      return corpo ? { operacao, corpo } : null;
    }
    case 'vendedores.editar': {
      if (!idValido(p.id)) return null;
      const corpo = corpoComVersao(CAMPOS_DO_VENDEDOR, p, true, ['id']);
      return corpo ? { operacao, id: p.id, corpo } : null;
    }
    case 'vendedores.aba.ver': {
      if (!soCom('id', 'aba') || !idValido(p.id)) return null;
      return (ABAS_DO_VENDEDOR as readonly unknown[]).includes(p.aba)
        ? { operacao, id: p.id, aba: p.aba as AbaDoVendedor }
        : null;
    }
    case 'vendedores.aba.gravar': {
      if (!idValido(p.id)) return null;
      if (!(ABAS_DO_VENDEDOR as readonly unknown[]).includes(p.aba)) return null;
      const aba = p.aba as AbaDoVendedor;
      const corpo = corpoComVersao(CAMPOS_DAS_ABAS[aba], p, true, ['id', 'aba']);
      return corpo ? { operacao, id: p.id, aba, corpo } : null;
    }
    case 'vendedores.referencias.listar':
    case 'vendedores.documentos.listar':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'vendedores.referencias.criar': {
      if (!idValido(p.id)) return null;
      const corpo = corpoComVersao(CAMPOS_DA_REFERENCIA, p, false, ['id']);
      return corpo ? { operacao, id: p.id, corpo } : null;
    }
    case 'vendedores.referencias.editar': {
      if (!idValido(p.id) || !idValido(p.referenciaId)) return null;
      const corpo = corpoComVersao(CAMPOS_DA_REFERENCIA, p, true, ['id', 'referenciaId']);
      return corpo ? { operacao, id: p.id, referenciaId: p.referenciaId, corpo } : null;
    }
    case 'vendedores.referencias.excluir':
      return soCom('id', 'referenciaId') && idValido(p.id) && idValido(p.referenciaId)
        ? { operacao, id: p.id, referenciaId: p.referenciaId }
        : null;
    case 'vendedores.documentos.enviar': {
      if (!soCom('id', 'descricao', 'arquivo') || !idValido(p.id)) return null;
      const descricao = textoOpcional(p.descricao);
      const arquivo = conferirArquivo(p.arquivo);
      if (descricao === null || arquivo === null) return null;
      return descricao
        ? { operacao, id: p.id, descricao, arquivo }
        : { operacao, id: p.id, arquivo };
    }
    case 'vendedores.documentos.baixar':
    case 'vendedores.documentos.excluir':
      return soCom('id', 'documentoId') && idValido(p.id) && idValido(p.documentoId)
        ? { operacao, id: p.id, documentoId: p.documentoId }
        : null;
  }
}

/** Confere os dados de cada operação; o que não bate vira `null` e a chamada é recusada. */
export function conferirParametros(
  operacao: OperacaoDaApi,
  bruto: unknown,
): ParametrosConferidos | null {
  const p = bruto === undefined ? {} : bruto;
  if (!ehObjeto(p)) return null;
  switch (operacao) {
    case 'eu':
    case 'cotacao.hoje':
    case 'avisos.listar':
    case 'conciliacoes.conferir':
    case 'conferencias.listar':
    case 'vendedores.escolha':
      return { operacao };
    case 'conferencias.ver':
    case 'conferencias.conferirAgora':
      return ehTexto(p.id) && UUID.test(p.id) ? { operacao, id: p.id } : null;
    case 'painel.semanal': {
      const semana = vendedorOpcional(p.semana);
      const vendedor = vendedorOpcional(p.vendedor);
      const praca = vendedorOpcional(p.praca);
      const situacao = textoOpcional(p.situacao);
      if (semana === null || vendedor === null || praca === null || situacao === null) return null;
      if (situacao !== undefined && !['EM_ABERTO', 'EM_ACERTO', 'ACERTADO'].includes(situacao)) {
        return null;
      }
      return {
        operacao,
        ...(semana ? { semana } : {}),
        ...(vendedor ? { vendedor } : {}),
        ...(praca ? { praca } : {}),
        ...(situacao ? { situacao } : {}),
      };
    }
    case 'usuarios.listar': {
      const busca = textoOpcional(p.busca);
      const perfil = textoOpcional(p.perfil);
      if (busca === null || perfil === null) return null;
      if (perfil !== undefined && !PERFIS.includes(perfil)) return null;
      if (p.ativo !== undefined && typeof p.ativo !== 'boolean') return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 20;
      if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
      if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
      if (tamanho < 1 || tamanho > 100) return null;
      const lista: ParametrosDaLista = { pagina, tamanho };
      if (busca) lista.busca = busca;
      if (perfil) lista.perfil = perfil;
      if (typeof p.ativo === 'boolean') lista.ativo = p.ativo;
      return { operacao, lista };
    }
    case 'usuarios.criar': {
      if (!ehTexto(p.nome) || !ehTexto(p.login) || !ehTexto(p.perfil)) return null;
      if (p.nome.length > MAX_TEXTO || p.login.length > MAX_TEXTO) return null;
      if (!PERFIS.includes(p.perfil)) return null;
      const vendedorId = vendedorOpcional(p.vendedorId);
      if (vendedorId === null) return null;
      const novo: ParametrosDeUsuarioNovo = { nome: p.nome, login: p.login, perfil: p.perfil };
      if (vendedorId) novo.vendedorId = vendedorId;
      return { operacao, novo };
    }
    case 'usuarios.editar': {
      if (!ehTexto(p.id) || !UUID.test(p.id)) return null;
      if (!ehTexto(p.nome) || p.nome.length > MAX_TEXTO || !ehTexto(p.perfil)) return null;
      if (!PERFIS.includes(p.perfil)) return null;
      if (typeof p.version !== 'number' || !Number.isInteger(p.version) || p.version < 0)
        return null;
      const vendedorId = vendedorOpcional(p.vendedorId);
      if (vendedorId === null) return null;
      const editado: ParametrosDeUsuarioEditado = {
        id: p.id,
        nome: p.nome,
        perfil: p.perfil,
        version: p.version,
      };
      if (vendedorId) editado.vendedorId = vendedorId;
      return { operacao, editado };
    }
    case 'usuarios.desativar':
    case 'usuarios.reativar':
    case 'usuarios.redefinirSenha':
      return ehTexto(p.id) && UUID.test(p.id) ? { operacao, id: p.id } : null;
    case 'cotacao.converter': {
      if (!ehTexto(p.valorEmReais) || !DECIMAL.test(p.valorEmReais)) return null;
      if (p.data !== undefined && (!ehTexto(p.data) || !DATA.test(p.data))) return null;
      return p.data === undefined
        ? { operacao, valorEmReais: p.valorEmReais }
        : { operacao, valorEmReais: p.valorEmReais, data: p.data };
    }
    case 'vendedores.listar':
    case 'vendedores.ver':
    case 'vendedores.criar':
    case 'vendedores.editar':
    case 'vendedores.inativar':
    case 'vendedores.reativar':
    case 'vendedores.aba.ver':
    case 'vendedores.aba.gravar':
    case 'vendedores.referencias.listar':
    case 'vendedores.referencias.criar':
    case 'vendedores.referencias.editar':
    case 'vendedores.referencias.excluir':
    case 'vendedores.documentos.listar':
    case 'vendedores.documentos.enviar':
    case 'vendedores.documentos.baixar':
    case 'vendedores.documentos.excluir':
      return conferirVendedor(operacao, p);
    case 'importacoes.registrar':
    case 'produtos.listar':
    case 'produtos.ver':
    case 'produtos.porCodigo':
    case 'produtos.criar':
    case 'produtos.editar':
    case 'produtos.estoqueMinimo':
    case 'produtos.codigos.gravar':
    case 'produtos.codigoProximo':
    case 'produtos.inativar':
    case 'produtos.reativar':
    case 'produtos.fotos.listar':
    case 'produtos.fotos.enviar':
    case 'produtos.fotos.excluir':
    case 'produtos.fotos.imagem':
    case 'produtos.importacao.previa':
    case 'produtos.importacao.confirmar':
      return conferirProduto(operacao, p);
    case 'compras.listar':
    case 'compras.ver':
    case 'compras.receber':
    case 'compras.desfazer':
      return conferirCompra(operacao, p);
    case 'maletas.criar':
    case 'maletas.ver':
    case 'maletas.listar':
    case 'maletas.colocarPeca':
    case 'maletas.tirarPeca':
    case 'maletas.excluir':
    case 'maletas.enviar':
    case 'maletas.emAberto':
    case 'maletas.saldo':
    case 'maletas.conferencia':
    case 'maletas.devolver':
    case 'maletas.desfazerDevolucao':
    case 'maletas.cancelar':
    case 'maletas.nota':
      return conferirMaleta(operacao, p);
    case 'acertos.listar':
    case 'acertos.iniciar':
    case 'acertos.ver':
    case 'acertos.ler':
    case 'acertos.desistir':
    case 'acertos.previa':
    case 'acertos.previaComDecisoes':
    case 'acertos.fechar':
    case 'pendencias.listar':
    case 'pendencias.resolver':
    case 'comissao.ler':
    case 'comissao.alterar':
      return conferirAcerto(operacao, p);
    case 'caixa.extrato':
    case 'caixa.saldo':
    case 'caixa.lancar':
    case 'caixa.estornar':
    case 'caixa.categorias':
    case 'caixa.criarCategoria':
    case 'caixa.renomearCategoria':
    case 'caixa.inativarCategoria':
    case 'caixa.reativarCategoria':
    case 'contasAPagar.listar':
    case 'contasAPagar.ver':
    case 'contasAPagar.pagar':
    case 'contasAPagar.estornar':
    case 'contasAReceber.listar':
    case 'recebimentos.listar':
    case 'recebimentos.receber':
    case 'recebimentos.estornar':
    case 'semanas.fechar':
      return conferirFinanceiro(operacao, p);
    case 'estoque.ficha':
    case 'estoque.lancar':
    case 'estoque.ajustar':
    case 'estoque.desfazer':
    case 'estoque.lancamentos':
    case 'estoque.acabando':
    case 'contagens.listar':
    case 'contagens.comecar':
    case 'contagens.ver':
    case 'contagens.itens':
    case 'contagens.ler':
    case 'contagens.corrigir':
    case 'contagens.fechar':
    case 'contagens.cancelar':
      return conferirEstoque(operacao, p);
    case 'sincronizacao.aparelhos':
    case 'sincronizacao.liberarAparelho':
    case 'sincronizacao.bloquearAparelho':
    case 'sincronizacao.envios':
    case 'sincronizacao.tentarDeNovo':
    case 'sincronizacao.descartarEnvio':
    case 'sincronizacao.quarentena':
    case 'sincronizacao.aprovarQuarentena':
    case 'sincronizacao.descartarQuarentena':
    case 'sincronizacao.valoresSubstituidos':
      return conferirSincronizacao(operacao, p);
    case 'devolucoes.autorizacoes':
    case 'devolucoes.aprovarAutorizacao':
    case 'devolucoes.negarAutorizacao':
    case 'devolucoes.listar':
    case 'devolucoes.manter':
    case 'devolucoes.naoAceitar':
    case 'devolucoes.cancelar':
    case 'devolucoes.vendasComAPeca':
    case 'devolucoes.registrar':
    case 'devolucoes.conferencia':
    case 'trocas.listar':
    case 'trocas.cancelar':
    case 'creditos.listar':
    case 'creditos.transferir':
    case 'garantia.ler':
    case 'garantia.alterar':
      return conferirDevolucoes(operacao, p);
    case 'telas.ler':
    case 'fechamento.iniciar':
    case 'fechamento.ler':
    case 'fechamento.leituras':
    case 'fechamento.concluir':
    case 'consignado.semanas':
    case 'consignado.semana':
    case 'consignado.notas':
    case 'consignado.nota':
    case 'consignado.cliente':
    case 'consignado.fotoDoCliente':
    case 'consignado.alertas':
    case 'consignado.alertaVisto':
      return conferirConsignado(operacao, p);
    case 'relatorios.cadastros':
      return conferirRelatorio(p);
    default:
      return conferirCadastro(operacao, p);
  }
}
