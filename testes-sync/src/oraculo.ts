import type { Aparelho, LinhaLocal } from './aparelho';
import { consultar, texto } from './banco-do-servidor';

/** As 23 tabelas que o Corona Vendas copia (a mesma lista do `RegistroDeTabelas` do servidor, ADR-0013, item 4). */
export const TABELAS = [
  'cotacoes',
  'categorias',
  'semanas',
  'clientes',
  'produtos',
  'produto_codigos',
  'produtos_fotos',
  'remessas',
  'itens_remessa',
  'movimentos_remessa',
  'vendas',
  'itens_venda',
  'pagamentos_cliente',
  'conferencias',
  'itens_conferencia',
  // Fase 6 (tarefa 6.7).
  'devolucoes',
  'itens_devolucao',
  'autorizacoes_excecao',
  'itens_autorizacao',
  'trocas',
  'creditos_cliente',
  'usos_credito',
  'creditos_sem_saldo',
] as const;

/** Tabelas que são só de um vendedor: nada delas pode aparecer no computador de outro. */
export const TABELAS_PRIVADAS = [
  'remessas',
  'itens_remessa',
  'movimentos_remessa',
  'vendas',
  'itens_venda',
  'pagamentos_cliente',
  'conferencias',
  'itens_conferencia',
  'devolucoes',
  'itens_devolucao',
  'autorizacoes_excecao',
  'itens_autorizacao',
  'trocas',
  'creditos_cliente',
  'usos_credito',
  'creditos_sem_saldo',
] as const;

/** Versão e lápide de cada linha, por tabela: o que o oráculo compara. */
export type Medidas = Record<string, Map<string, { versao: number; excluida: boolean }>>;

function vazio(): Medidas {
  const m: Medidas = {};
  for (const t of TABELAS) m[t] = new Map();
  return m;
}

const sqlDoOraculo = (v: string) => `
  WITH maletas AS (SELECT id FROM corona.remessas WHERE vendedor_id = ${v} AND enviada_em IS NOT NULL),
       pecas AS (SELECT DISTINCT produto_id FROM corona.itens_remessa
                  WHERE remessa_id IN (SELECT id FROM maletas) AND deleted_at IS NULL),
       minhas_vendas AS (SELECT id FROM corona.vendas WHERE vendedor_id = ${v}),
       minhas_conferencias AS (SELECT id FROM corona.conferencias WHERE vendedor_id = ${v}),
       minhas_devolucoes AS (SELECT id FROM corona.devolucoes WHERE vendedor_id = ${v}),
       minhas_autorizacoes AS (SELECT id FROM corona.autorizacoes_excecao WHERE vendedor_id = ${v}),
       meus_creditos AS (SELECT id FROM corona.creditos_cliente WHERE vendedor_id = ${v})
  SELECT 'cotacoes' AS tabela, id::text, version, deleted_at IS NOT NULL AS excluida FROM corona.cotacoes
  UNION ALL SELECT 'categorias', id::text, version, deleted_at IS NOT NULL FROM corona.categorias
  UNION ALL SELECT 'semanas', id::text, version, deleted_at IS NOT NULL FROM corona.semanas
  UNION ALL SELECT 'clientes', id::text, version, deleted_at IS NOT NULL FROM corona.clientes
  UNION ALL SELECT 'remessas', id::text, version, deleted_at IS NOT NULL FROM corona.remessas
             WHERE id IN (SELECT id FROM maletas)
  UNION ALL SELECT 'itens_remessa', id::text, version, deleted_at IS NOT NULL FROM corona.itens_remessa
             WHERE remessa_id IN (SELECT id FROM maletas)
  UNION ALL SELECT 'movimentos_remessa', id::text, version, deleted_at IS NOT NULL FROM corona.movimentos_remessa
             WHERE remessa_id IN (SELECT id FROM maletas)
  UNION ALL SELECT 'produtos', id::text, version, deleted_at IS NOT NULL FROM corona.produtos
             WHERE id IN (SELECT produto_id FROM pecas)
  UNION ALL SELECT 'produto_codigos', id::text, version, deleted_at IS NOT NULL FROM corona.produto_codigos
             WHERE produto_id IN (SELECT produto_id FROM pecas)
  UNION ALL SELECT 'produtos_fotos', id::text, version, deleted_at IS NOT NULL FROM corona.produtos_fotos
             WHERE produto_id IN (SELECT produto_id FROM pecas)
  UNION ALL SELECT 'vendas', id::text, version, deleted_at IS NOT NULL FROM corona.vendas
             WHERE id IN (SELECT id FROM minhas_vendas)
  UNION ALL SELECT 'itens_venda', id::text, version, deleted_at IS NOT NULL FROM corona.itens_venda
             WHERE venda_id IN (SELECT id FROM minhas_vendas)
  UNION ALL SELECT 'pagamentos_cliente', id::text, version, deleted_at IS NOT NULL FROM corona.pagamentos_cliente
             WHERE venda_id IN (SELECT id FROM minhas_vendas)
  UNION ALL SELECT 'conferencias', id::text, version, deleted_at IS NOT NULL FROM corona.conferencias
             WHERE id IN (SELECT id FROM minhas_conferencias)
  UNION ALL SELECT 'itens_conferencia', id::text, version, deleted_at IS NOT NULL FROM corona.itens_conferencia
             WHERE conferencia_id IN (SELECT id FROM minhas_conferencias)
  UNION ALL SELECT 'devolucoes', id::text, version, deleted_at IS NOT NULL FROM corona.devolucoes
             WHERE id IN (SELECT id FROM minhas_devolucoes)
  UNION ALL SELECT 'itens_devolucao', id::text, version, deleted_at IS NOT NULL FROM corona.itens_devolucao
             WHERE devolucao_id IN (SELECT id FROM minhas_devolucoes)
  UNION ALL SELECT 'autorizacoes_excecao', id::text, version, deleted_at IS NOT NULL FROM corona.autorizacoes_excecao
             WHERE id IN (SELECT id FROM minhas_autorizacoes)
  UNION ALL SELECT 'itens_autorizacao', id::text, version, deleted_at IS NOT NULL FROM corona.itens_autorizacao
             WHERE autorizacao_id IN (SELECT id FROM minhas_autorizacoes)
  UNION ALL SELECT 'trocas', id::text, version, deleted_at IS NOT NULL FROM corona.trocas WHERE vendedor_id = ${v}
  UNION ALL SELECT 'creditos_cliente', id::text, version, deleted_at IS NOT NULL FROM corona.creditos_cliente
             WHERE id IN (SELECT id FROM meus_creditos)
  UNION ALL SELECT 'usos_credito', id::text, version, deleted_at IS NOT NULL FROM corona.usos_credito
             WHERE credito_id IN (SELECT id FROM meus_creditos)
  UNION ALL SELECT 'creditos_sem_saldo', id::text, version, deleted_at IS NOT NULL FROM corona.creditos_sem_saldo
             WHERE venda_id IN (SELECT id FROM minhas_vendas)`;

/**
 * O oráculo: o que é do vendedor V, calculado à parte, lendo as tabelas do servidor e aplicando as regras do ADR-0013
 * (item 4 e a linha de 11/11/2026) em SQL escrito aqui. Não usa o código do pull nem o filtro do servidor; é a mesma regra
 * do `CenarioDeSync.oraculo` do backend (tarefa 5.4).
 */
export async function oraculo(vendedorId: string): Promise<Medidas> {
  const linhas = await consultar<{
    tabela: string;
    id: string;
    version: number;
    excluida: boolean;
  }>(sqlDoOraculo(texto(vendedorId)));
  const m = vazio();
  for (const l of linhas) m[l.tabela]!.set(l.id, { versao: l.version, excluida: l.excluida });
  return m;
}

/** Ids das linhas privadas que NÃO são do vendedor V (de todos os outros vendedores). */
export async function idsDeOutrosVendedores(vendedorId: string): Promise<Set<string>> {
  const v = texto(vendedorId);
  const linhas = await consultar<{ id: string }>(`
    WITH maletas AS (SELECT id FROM corona.remessas WHERE vendedor_id <> ${v}),
         vendas AS (SELECT id FROM corona.vendas WHERE vendedor_id <> ${v}),
         conferencias AS (SELECT id FROM corona.conferencias WHERE vendedor_id <> ${v}),
         devolucoes AS (SELECT id FROM corona.devolucoes WHERE vendedor_id <> ${v}),
         autorizacoes AS (SELECT id FROM corona.autorizacoes_excecao WHERE vendedor_id <> ${v}),
         creditos AS (SELECT id FROM corona.creditos_cliente WHERE vendedor_id <> ${v})
    SELECT id::text FROM maletas
    UNION ALL SELECT id::text FROM corona.itens_remessa WHERE remessa_id IN (SELECT id FROM maletas)
    UNION ALL SELECT id::text FROM corona.movimentos_remessa WHERE remessa_id IN (SELECT id FROM maletas)
    UNION ALL SELECT id::text FROM vendas
    UNION ALL SELECT id::text FROM corona.itens_venda WHERE venda_id IN (SELECT id FROM vendas)
    UNION ALL SELECT id::text FROM corona.pagamentos_cliente WHERE venda_id IN (SELECT id FROM vendas)
    UNION ALL SELECT id::text FROM conferencias
    UNION ALL SELECT id::text FROM corona.itens_conferencia WHERE conferencia_id IN (SELECT id FROM conferencias)
    UNION ALL SELECT id::text FROM devolucoes
    UNION ALL SELECT id::text FROM corona.itens_devolucao WHERE devolucao_id IN (SELECT id FROM devolucoes)
    UNION ALL SELECT id::text FROM autorizacoes
    UNION ALL SELECT id::text FROM corona.itens_autorizacao WHERE autorizacao_id IN (SELECT id FROM autorizacoes)
    UNION ALL SELECT id::text FROM corona.trocas WHERE vendedor_id <> ${v}
    UNION ALL SELECT id::text FROM creditos
    UNION ALL SELECT id::text FROM corona.usos_credito WHERE credito_id IN (SELECT id FROM creditos)
    UNION ALL SELECT id::text FROM corona.creditos_sem_saldo WHERE venda_id IN (SELECT id FROM vendas)`);
  return new Set(linhas.map((l) => l.id));
}

/** A cópia local de um aparelho nas mesmas medidas, e as provisórias que sobraram. */
export function copiaLocal(a: Aparelho): { medidas: Medidas; provisorias: string[] } {
  const m = vazio();
  const provisorias: string[] = [];
  for (const t of TABELAS) {
    for (const l of a.ler<LinhaLocal>(`SELECT id, version, deleted_at, origem_local FROM ${t}`)) {
      if (l.origem_local !== 'SERVIDOR') provisorias.push(`${t}:${l.id}`);
      else m[t]!.set(l.id, { versao: l.version, excluida: l.deleted_at !== null });
    }
  }
  return { medidas: m, provisorias };
}

/** Diferenças por tabela entre o oráculo e a cópia (as 30 primeiras), em português. */
export function diferencas(esperado: Medidas, local: Medidas): string[] {
  const r: string[] = [];
  for (const t of TABELAS) {
    const e = esperado[t]!;
    const l = local[t]!;
    for (const [id, x] of e) {
      const y = l.get(id);
      if (!y)
        r.push(
          `${t}:${id} falta no computador (servidor: versão ${x.versao}${x.excluida ? ', excluída' : ''})`,
        );
      else if (y.versao !== x.versao || y.excluida !== x.excluida)
        r.push(
          `${t}:${id} servidor versão ${x.versao}${x.excluida ? ' excluída' : ''}, computador versão ${y.versao}${y.excluida ? ' excluída' : ''}`,
        );
    }
    for (const [id] of l) if (!e.has(id)) r.push(`${t}:${id} está no computador e não devia`);
  }
  return r.slice(0, 30);
}
