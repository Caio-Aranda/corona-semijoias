/** Conferência, no processo principal, dos dados das operações de produtos. */
import {
  EXTENSOES_DE_FOTO,
  FOTOS_POR_LOTE,
  MAXIMO_DE_NOMES_NA_PREVIA,
  TAMANHOS_DE_FOTO,
  TAMANHO_MAXIMO_DA_FOTO,
  TAMANHO_MAXIMO_DO_CODIGO,
  TIPOS_DE_CODIGO,
  extensaoDeFotoAceita,
  type CodigoParaGravar,
  type OperacaoDeProdutos,
  type TamanhoDeFoto,
} from './produtos';
import type { ArquivoParaEnviar } from './vendedores';

const ehTexto = (v: unknown): v is string => typeof v === 'string';
const ehObjeto = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DINHEIRO = /^\d{1,12}(\.\d{1,2})?$/;
const HASH = /^[0-9a-f]{64}$/i;
const idValido = (v: unknown): v is string => ehTexto(v) && UUID.test(v);
const versaoValida = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 1_000_000_000;

export interface ParametrosDeProduto {
  operacao: OperacaoDeProdutos;
  id?: string;
  fotoId?: string;
  hash?: string;
  tamanho?: TamanhoDeFoto;
  codigo?: string;
  version?: number;
  lista?: {
    busca?: string;
    categoriaId?: string;
    fornecedorId?: string;
    ativo?: boolean;
    pagina: number;
    tamanho: number;
  };
  corpo?: Record<string, unknown>;
  codigos?: CodigoParaGravar[];
  arquivo?: ArquivoParaEnviar;
  arquivos?: ArquivoParaEnviar[];
  nomes?: string[];
  /** Lote da importação (UUID gerado pela tela): liga o resultado ao relatório. */
  importacaoId?: string;
  importacao?: LoteDeImportacao;
}

export interface LinhaDeImportacao {
  referencia: string;
  resultado: 'CRIADO' | 'ATUALIZADO' | 'JA_EXISTIA' | 'RECUSADO';
  motivo?: string;
}

export interface LoteDeImportacao {
  tipo: 'PLANILHA' | 'FOTOS';
  arquivoNome?: string;
  linhas: LinhaDeImportacao[];
}

/** O servidor aceita até 500 linhas por envio. */
export const LINHAS_POR_ENVIO_DE_IMPORTACAO = 500;

function conferirLote(v: unknown): LoteDeImportacao | null {
  if (!ehObjeto(v)) return null;
  if (Object.keys(v).some((k) => !['tipo', 'arquivoNome', 'linhas'].includes(k))) return null;
  if (v.tipo !== 'PLANILHA' && v.tipo !== 'FOTOS') return null;
  if (v.arquivoNome !== undefined && (!ehTexto(v.arquivoNome) || v.arquivoNome.length > 255)) {
    return null;
  }
  if (
    !Array.isArray(v.linhas) ||
    v.linhas.length === 0 ||
    v.linhas.length > LINHAS_POR_ENVIO_DE_IMPORTACAO
  ) {
    return null;
  }
  const linhas: LinhaDeImportacao[] = [];
  for (const l of v.linhas) {
    if (
      !ehObjeto(l) ||
      Object.keys(l).some((k) => !['referencia', 'resultado', 'motivo'].includes(k))
    ) {
      return null;
    }
    if (!ehTexto(l.referencia) || l.referencia.trim() === '' || l.referencia.length > 500) {
      return null;
    }
    if (!['CRIADO', 'ATUALIZADO', 'JA_EXISTIA', 'RECUSADO'].includes(l.resultado as string)) {
      return null;
    }
    if (l.motivo !== undefined && (!ehTexto(l.motivo) || l.motivo.length > 500)) return null;
    linhas.push({
      referencia: l.referencia,
      resultado: l.resultado as LinhaDeImportacao['resultado'],
      ...(l.motivo ? { motivo: l.motivo } : {}),
    });
  }
  return { tipo: v.tipo, ...(v.arquivoNome ? { arquivoNome: v.arquivoNome } : {}), linhas };
}

function conferirCodigo(v: unknown): CodigoParaGravar | null {
  if (!ehObjeto(v)) return null;
  const permitidos = ['id', 'codigo', 'tipo', 'fornecedorId', 'principal', 'ativo'];
  if (Object.keys(v).some((k) => !permitidos.includes(k))) return null;
  if (!ehTexto(v.codigo) || v.codigo.trim() === '' || v.codigo.length > TAMANHO_MAXIMO_DO_CODIGO)
    return null;
  if (!(TIPOS_DE_CODIGO as readonly unknown[]).includes(v.tipo)) return null;
  const saida: CodigoParaGravar = {
    codigo: v.codigo,
    tipo: v.tipo as CodigoParaGravar['tipo'],
  };
  if (v.id !== undefined && v.id !== null) {
    if (!idValido(v.id)) return null;
    saida.id = v.id;
  }
  if (v.fornecedorId !== undefined && v.fornecedorId !== null) {
    if (!idValido(v.fornecedorId)) return null;
    saida.fornecedorId = v.fornecedorId;
  }
  for (const k of ['principal', 'ativo'] as const) {
    if (v[k] !== undefined && v[k] !== null) {
      if (typeof v[k] !== 'boolean') return null;
      saida[k] = v[k];
    }
  }
  return saida;
}

function conferirCodigos(v: unknown): CodigoParaGravar[] | null {
  if (!Array.isArray(v) || v.length > 30) return null;
  const lista: CodigoParaGravar[] = [];
  for (const item of v) {
    const c = conferirCodigo(item);
    if (!c) return null;
    lista.push(c);
  }
  return lista;
}

function conferirFoto(v: unknown): ArquivoParaEnviar | null {
  if (!ehObjeto(v) || !ehTexto(v.nome) || !(v.bytes instanceof Uint8Array)) return null;
  if (v.nome.length === 0 || v.nome.length > 255 || !extensaoDeFotoAceita(v.nome)) return null;
  if (v.bytes.byteLength === 0 || v.bytes.byteLength > TAMANHO_MAXIMO_DA_FOTO) return null;
  if (Object.keys(v).some((k) => k !== 'nome' && k !== 'bytes')) return null;
  return { nome: v.nome, bytes: v.bytes };
}

/** Os campos do produto (sem os códigos). Campo desconhecido ou de tipo errado recusa tudo. */
function conferirCorpoDoProduto(
  p: Record<string, unknown>,
  ao: 'criar' | 'editar',
): Record<string, unknown> | null {
  const extras = ao === 'criar' ? ['codigos', 'id'] : ['id', 'version'];
  const corpo: Record<string, unknown> = {};
  for (const [nome, v] of Object.entries(p)) {
    if (extras.includes(nome)) continue;
    if (v === undefined || v === null) continue;
    switch (nome) {
      case 'nome':
        if (!ehTexto(v) || v.length > 200) return null;
        break;
      case 'referencia':
        if (!ehTexto(v) || v.length > 100) return null;
        break;
      case 'categoriaId':
      case 'fornecedorId':
        if (!idValido(v)) return null;
        break;
      case 'precoVenda':
      case 'precoCusto':
        if (!ehTexto(v) || !DINHEIRO.test(v)) return null;
        break;
      case 'estoqueMinimo':
        if (typeof v !== 'number' || !Number.isInteger(v) || v < 0 || v > 1_000_000) return null;
        break;
      default:
        return null;
    }
    corpo[nome] = v;
  }
  if (ao === 'criar') {
    if (p.id !== undefined) {
      if (!idValido(p.id)) return null;
      corpo.id = p.id;
    }
    const codigos = conferirCodigos(p.codigos);
    if (!codigos) return null;
    corpo.codigos = codigos;
  } else {
    if (!versaoValida(p.version)) return null;
    corpo.version = p.version;
  }
  return corpo;
}

export function conferirProduto(
  operacao: OperacaoDeProdutos,
  p: Record<string, unknown>,
): ParametrosDeProduto | null {
  const soCom = (...nomes: string[]) => Object.keys(p).every((k) => nomes.includes(k));
  switch (operacao) {
    case 'produtos.listar': {
      if (!soCom('busca', 'categoriaId', 'fornecedorId', 'ativo', 'pagina', 'tamanho')) return null;
      const pagina = p.pagina ?? 0;
      const tamanho = p.tamanho ?? 20;
      if (typeof pagina !== 'number' || !Number.isInteger(pagina) || pagina < 0) return null;
      if (typeof tamanho !== 'number' || !Number.isInteger(tamanho)) return null;
      if (tamanho < 1 || tamanho > 100) return null;
      const lista: NonNullable<ParametrosDeProduto['lista']> = { pagina, tamanho };
      if (p.busca !== undefined && p.busca !== '') {
        if (!ehTexto(p.busca) || p.busca.length > 200) return null;
        lista.busca = p.busca;
      }
      for (const k of ['categoriaId', 'fornecedorId'] as const) {
        if (p[k] !== undefined && p[k] !== '') {
          if (!idValido(p[k])) return null;
          lista[k] = p[k];
        }
      }
      if (p.ativo !== undefined) {
        if (typeof p.ativo !== 'boolean') return null;
        lista.ativo = p.ativo;
      }
      return { operacao, lista };
    }
    case 'produtos.ver':
    case 'produtos.inativar':
    case 'produtos.reativar':
    case 'produtos.fotos.listar':
      return soCom('id') && idValido(p.id) ? { operacao, id: p.id } : null;
    case 'produtos.porCodigo': {
      if (!soCom('codigo') || !ehTexto(p.codigo)) return null;
      const codigo = p.codigo.trim();
      return codigo !== '' && codigo.length <= TAMANHO_MAXIMO_DO_CODIGO
        ? { operacao, codigo }
        : null;
    }
    case 'produtos.criar': {
      const corpo = conferirCorpoDoProduto(p, 'criar');
      return corpo ? { operacao, corpo } : null;
    }
    case 'produtos.editar': {
      if (!idValido(p.id)) return null;
      const corpo = conferirCorpoDoProduto(p, 'editar');
      return corpo ? { operacao, id: p.id, corpo } : null;
    }
    case 'produtos.estoqueMinimo': {
      if (!soCom('id', 'version', 'estoqueMinimo') || !idValido(p.id) || !versaoValida(p.version))
        return null;
      const m = p.estoqueMinimo;
      if (typeof m !== 'number' || !Number.isInteger(m) || m < 0 || m > 1_000_000) return null;
      return { operacao, id: p.id, corpo: { version: p.version, estoqueMinimo: m } };
    }
    case 'produtos.codigos.gravar': {
      if (!soCom('id', 'version', 'codigos') || !idValido(p.id) || !versaoValida(p.version))
        return null;
      const codigos = conferirCodigos(p.codigos);
      return codigos ? { operacao, id: p.id, version: p.version, codigos } : null;
    }
    case 'produtos.codigoProximo':
      return soCom() ? { operacao } : null;
    case 'produtos.fotos.enviar': {
      if (!soCom('id', 'arquivo') || !idValido(p.id)) return null;
      const arquivo = conferirFoto(p.arquivo);
      return arquivo ? { operacao, id: p.id, arquivo } : null;
    }
    case 'produtos.fotos.principal':
    case 'produtos.fotos.excluir':
      return soCom('id', 'fotoId') && idValido(p.id) && idValido(p.fotoId)
        ? { operacao, id: p.id, fotoId: p.fotoId }
        : null;
    case 'produtos.fotos.imagem':
      return soCom('hash', 'tamanho') &&
        ehTexto(p.hash) &&
        HASH.test(p.hash) &&
        (TAMANHOS_DE_FOTO as readonly unknown[]).includes(p.tamanho)
        ? { operacao, hash: p.hash, tamanho: p.tamanho as TamanhoDeFoto }
        : null;
    case 'produtos.importacao.previa': {
      if (!soCom('nomes') || !Array.isArray(p.nomes)) return null;
      if (p.nomes.length > MAXIMO_DE_NOMES_NA_PREVIA) return null;
      if (!p.nomes.every((n) => ehTexto(n) && n.length > 0 && n.length <= 255)) return null;
      return { operacao, nomes: p.nomes as string[] };
    }
    case 'importacoes.registrar': {
      if (!soCom('importacaoId', 'importacao') || !idValido(p.importacaoId)) return null;
      const lote = conferirLote(p.importacao);
      return lote ? { operacao, importacaoId: p.importacaoId, importacao: lote } : null;
    }
    case 'produtos.importacao.confirmar': {
      if (!soCom('arquivos', 'importacaoId') || !Array.isArray(p.arquivos)) return null;
      if (p.importacaoId !== undefined && !idValido(p.importacaoId)) return null;
      if (p.arquivos.length === 0 || p.arquivos.length > FOTOS_POR_LOTE) return null;
      const arquivos: ArquivoParaEnviar[] = [];
      for (const a of p.arquivos) {
        const ok = conferirFoto(a);
        if (!ok) return null;
        arquivos.push(ok);
      }
      return {
        operacao,
        arquivos,
        ...(p.importacaoId ? { importacaoId: p.importacaoId } : {}),
      };
    }
  }
}

export { EXTENSOES_DE_FOTO };
