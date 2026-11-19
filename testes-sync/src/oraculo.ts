import type { Aparelho, LinhaLocal } from './aparelho';
import { consultar, texto } from './banco-do-servidor';

/** As 32 tabelas que o Corona Vendas copia (a mesma lista do `RegistroDeTabelas` do servidor, ADR-0013, item 4). */
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
  // Fase 7 (tarefas 7.3 a 7.6): contas a receber, acerto e o que o vendedor pagou à loja.
  'contas_receber',
  'baixas_receber',
  'recebimentos_vendedor',
  'acertos',
  'leituras_acerto',
  'itens_acerto',
  'acerto_eventos',
  'comissoes',
  'pendencias_acerto',
  // Consignado ao cliente (tarefas 9.1 e 9.2; ADR-0016): só do representante dono.
  'semanas_vendedor',
  'notas_consignado',
  'reposicoes_nota',
  'trocas_nota',
  'itens_nota_consignado',
  'remarcacoes_nota',
  'acertos_nota',
  'itens_acerto_nota',
  'recibos_consignado',
  'formas_recibo_consignado',
  'aplicacoes_recibo',
] as const;

/** Tabelas que são só de um vendedor: nada delas pode aparecer no computador de outro. */
export const TABELAS_PRIVADAS = [
  // Cada representante tem a SUA lista de clientes (ADR-0016, item 4): nenhum cliente de outro chega ao computador.
  'clientes',
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
  // Fase 7 (tarefas 7.3 a 7.6): contas a receber, acerto e o que o vendedor pagou à loja.
  'contas_receber',
  'baixas_receber',
  'recebimentos_vendedor',
  'acertos',
  'leituras_acerto',
  'itens_acerto',
  'acerto_eventos',
  'comissoes',
  'pendencias_acerto',
  'semanas_vendedor',
  'notas_consignado',
  'reposicoes_nota',
  'trocas_nota',
  'itens_nota_consignado',
  'remarcacoes_nota',
  'acertos_nota',
  'itens_acerto_nota',
  'recibos_consignado',
  'formas_recibo_consignado',
  'aplicacoes_recibo',
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
       meus_creditos AS (SELECT id FROM corona.creditos_cliente WHERE vendedor_id = ${v}),
       minhas_contas AS (SELECT c.id FROM corona.contas_receber c LEFT JOIN corona.vendas ve ON ve.id = c.venda_id
                          WHERE c.vendedor_id = ${v} OR ve.vendedor_id = ${v}),
       meus_acertos AS (SELECT id FROM corona.acertos WHERE vendedor_id = ${v}),
       minhas_notas AS (SELECT id FROM corona.notas_consignado WHERE vendedor_id = ${v}),
       meus_acertos_nota AS (SELECT id FROM corona.acertos_nota WHERE vendedor_id = ${v}),
       meus_recibos AS (SELECT id FROM corona.recibos_consignado WHERE vendedor_id = ${v})
  SELECT 'cotacoes' AS tabela, id::text, version, deleted_at IS NOT NULL AS excluida FROM corona.cotacoes
  UNION ALL SELECT 'categorias', id::text, version, deleted_at IS NOT NULL FROM corona.categorias
  UNION ALL SELECT 'semanas', id::text, version, deleted_at IS NOT NULL FROM corona.semanas
  UNION ALL SELECT 'clientes', id::text, version, deleted_at IS NOT NULL FROM corona.clientes
             WHERE vendedor_responsavel_id = ${v}
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
             WHERE venda_id IN (SELECT id FROM minhas_vendas)
  UNION ALL SELECT 'contas_receber', id::text, version, deleted_at IS NOT NULL FROM corona.contas_receber
             WHERE id IN (SELECT id FROM minhas_contas)
  UNION ALL SELECT 'baixas_receber', id::text, version, deleted_at IS NOT NULL FROM corona.baixas_receber
             WHERE conta_id IN (SELECT id FROM minhas_contas)
  UNION ALL SELECT 'recebimentos_vendedor', id::text, version, deleted_at IS NOT NULL FROM corona.recebimentos_vendedor
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'acertos', id::text, version, deleted_at IS NOT NULL FROM corona.acertos
             WHERE id IN (SELECT id FROM meus_acertos)
  UNION ALL SELECT 'leituras_acerto', id::text, version, deleted_at IS NOT NULL FROM corona.leituras_acerto
             WHERE acerto_id IN (SELECT id FROM meus_acertos)
  UNION ALL SELECT 'itens_acerto', id::text, version, deleted_at IS NOT NULL FROM corona.itens_acerto
             WHERE acerto_id IN (SELECT id FROM meus_acertos)
  UNION ALL SELECT 'acerto_eventos', id::text, version, deleted_at IS NOT NULL FROM corona.acerto_eventos
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'comissoes', id::text, version, deleted_at IS NOT NULL FROM corona.comissoes
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'pendencias_acerto', id::text, version, deleted_at IS NOT NULL FROM corona.pendencias_acerto
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'semanas_vendedor', id::text, version, deleted_at IS NOT NULL FROM corona.semanas_vendedor
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'notas_consignado', id::text, version, deleted_at IS NOT NULL FROM corona.notas_consignado
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'reposicoes_nota', id::text, version, deleted_at IS NOT NULL FROM corona.reposicoes_nota
             WHERE nota_id IN (SELECT id FROM minhas_notas)
  UNION ALL SELECT 'trocas_nota', id::text, version, deleted_at IS NOT NULL FROM corona.trocas_nota
             WHERE nota_id IN (SELECT id FROM minhas_notas)
  UNION ALL SELECT 'itens_nota_consignado', id::text, version, deleted_at IS NOT NULL FROM corona.itens_nota_consignado
             WHERE nota_id IN (SELECT id FROM minhas_notas)
  UNION ALL SELECT 'remarcacoes_nota', id::text, version, deleted_at IS NOT NULL FROM corona.remarcacoes_nota
             WHERE nota_id IN (SELECT id FROM minhas_notas)
  UNION ALL SELECT 'acertos_nota', id::text, version, deleted_at IS NOT NULL FROM corona.acertos_nota
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'itens_acerto_nota', id::text, version, deleted_at IS NOT NULL FROM corona.itens_acerto_nota
             WHERE acerto_id IN (SELECT id FROM meus_acertos_nota)
  UNION ALL SELECT 'recibos_consignado', id::text, version, deleted_at IS NOT NULL FROM corona.recibos_consignado
             WHERE vendedor_id = ${v}
  UNION ALL SELECT 'formas_recibo_consignado', id::text, version, deleted_at IS NOT NULL FROM corona.formas_recibo_consignado
             WHERE recibo_id IN (SELECT id FROM meus_recibos)
  UNION ALL SELECT 'aplicacoes_recibo', id::text, version, deleted_at IS NOT NULL FROM corona.aplicacoes_recibo
             WHERE recibo_id IN (SELECT id FROM meus_recibos)`;

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
         creditos AS (SELECT id FROM corona.creditos_cliente WHERE vendedor_id <> ${v}),
         contas AS (SELECT c.id FROM corona.contas_receber c LEFT JOIN corona.vendas ve ON ve.id = c.venda_id
                     WHERE c.vendedor_id <> ${v} OR ve.vendedor_id <> ${v}),
         acertos AS (SELECT id FROM corona.acertos WHERE vendedor_id <> ${v}),
         notas AS (SELECT id FROM corona.notas_consignado WHERE vendedor_id <> ${v}),
         acertos_nota AS (SELECT id FROM corona.acertos_nota WHERE vendedor_id <> ${v}),
         recibos AS (SELECT id FROM corona.recibos_consignado WHERE vendedor_id <> ${v})
    SELECT id::text FROM corona.clientes WHERE vendedor_responsavel_id IS DISTINCT FROM ${v}
    UNION ALL SELECT id::text FROM maletas
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
    UNION ALL SELECT id::text FROM corona.creditos_sem_saldo WHERE venda_id IN (SELECT id FROM vendas)
    UNION ALL SELECT id::text FROM contas
    UNION ALL SELECT id::text FROM corona.baixas_receber WHERE conta_id IN (SELECT id FROM contas)
    UNION ALL SELECT id::text FROM corona.recebimentos_vendedor WHERE vendedor_id <> ${v}
    UNION ALL SELECT id::text FROM acertos
    UNION ALL SELECT id::text FROM corona.leituras_acerto WHERE acerto_id IN (SELECT id FROM acertos)
    UNION ALL SELECT id::text FROM corona.itens_acerto WHERE acerto_id IN (SELECT id FROM acertos)
    UNION ALL SELECT id::text FROM corona.acerto_eventos WHERE vendedor_id <> ${v}
    UNION ALL SELECT id::text FROM corona.comissoes WHERE vendedor_id <> ${v}
    UNION ALL SELECT id::text FROM corona.pendencias_acerto WHERE vendedor_id <> ${v}
    UNION ALL SELECT id::text FROM corona.semanas_vendedor WHERE vendedor_id <> ${v}
    UNION ALL SELECT id::text FROM notas
    UNION ALL SELECT id::text FROM corona.reposicoes_nota WHERE nota_id IN (SELECT id FROM notas)
    UNION ALL SELECT id::text FROM corona.trocas_nota WHERE nota_id IN (SELECT id FROM notas)
    UNION ALL SELECT id::text FROM corona.itens_nota_consignado WHERE nota_id IN (SELECT id FROM notas)
    UNION ALL SELECT id::text FROM corona.remarcacoes_nota WHERE nota_id IN (SELECT id FROM notas)
    UNION ALL SELECT id::text FROM acertos_nota
    UNION ALL SELECT id::text FROM corona.itens_acerto_nota WHERE acerto_id IN (SELECT id FROM acertos_nota)
    UNION ALL SELECT id::text FROM recibos
    UNION ALL SELECT id::text FROM corona.formas_recibo_consignado WHERE recibo_id IN (SELECT id FROM recibos)
    UNION ALL SELECT id::text FROM corona.aplicacoes_recibo WHERE recibo_id IN (SELECT id FROM recibos)`);
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
