import {
  COLUNAS_DO_PULL,
  MARCADORES_PROIBIDOS,
  colunasGravaveis,
  type TabelaSincronizavel,
} from '../../apps/seller-app/src/main/sync/colunas';
import type { Aparelho } from './aparelho';
import { consultar, texto } from './banco-do-servidor';
import type { Dona } from './dona';
import type { Lancamento, Livro } from './livro';
import { copiaLocal, diferencas, idsDeOutrosVendedores, oraculo, TABELAS } from './oraculo';

export interface AfirmacaoFinal {
  semente: number;
  dona: Dona;
  livro: Livro;
  /** Os computadores que continuam com dados (os apagados ficam de fora: a pasta deles é conferida no cenário). */
  vivos: Aparelho[];
  /** Todos os aparelhos que rodaram (para a varredura de marcadores e o relatório). */
  todos: Aparelho[];
  /** Pula a conciliação (só quando o cenário prova outra coisa e a conciliação é afirmada à parte). */
  semConciliacao?: boolean;
}

type Destino =
  | { onde: 'aceito'; resultado: string }
  | { onde: 'problema'; resultado: string; codigo: string | null; resolvido: boolean }
  | { onde: 'quarentena'; situacao: string }
  | { onde: 'sumiu' };

const CONTROLE = ['id', 'version', 'created_at', 'updated_at', 'deleted_at', 'origem_local'];

/** Relatório de falha: a semente, o que cada proxy fez e o registro de cada app (o final). */
export function relatorio(semente: number, aparelhos: readonly Aparelho[]): string {
  const partes = [`Semente: ${semente} (repita com CORONA_SYNC_SEMENTE=${semente}).`];
  for (const a of aparelhos) {
    const h = a.proxy.historico();
    partes.push(
      `--- ${a.nome}: ${h.length} pedidos pela rede; os últimos:`,
      ...h
        .slice(-25)
        .map(
          (p) =>
            `  #${p.numero} ${p.metodo} ${p.caminho.split('?')[0]} ${p.falha ?? 'ok'} servidor=${p.servidorRespondeu ? p.statusDoServidor : '-'}`,
        ),
      `--- ${a.nome}: registro do app (o final):`,
      ...a.registro.slice(-15).map((l) => `  ${l}`),
    );
  }
  return partes.join('\n');
}

async function destinos(vendedores: string[]): Promise<{
  porId: Map<string, Destino>;
  existentes: Set<string>;
  vendasPorVendedor: Map<string, string[]>;
}> {
  const lista = vendedores.map(texto).join(', ');
  const [log, quarentena, existentes, vendas] = await Promise.all([
    consultar<{ id: string; resultado: string; codigo: string | null; resolucao: string | null }>(
      `SELECT id::text, resultado, codigo, resolucao FROM corona.sync_log WHERE vendedor_id IN (${lista})`,
    ),
    consultar<{ id: string; situacao: string }>(
      `SELECT id::text, situacao FROM corona.envios_em_quarentena WHERE vendedor_id IN (${lista})`,
    ),
    consultar<{ id: string }>(`
      SELECT id::text FROM corona.vendas WHERE vendedor_id IN (${lista})
      UNION ALL SELECT estorno_id::text FROM corona.vendas WHERE vendedor_id IN (${lista}) AND estorno_id IS NOT NULL
      UNION ALL SELECT id::text FROM corona.conferencias WHERE vendedor_id IN (${lista})
      UNION ALL SELECT id::text FROM corona.clientes`),
    consultar<{ vendedor_id: string; id: string }>(
      `SELECT vendedor_id::text, id::text FROM corona.vendas WHERE vendedor_id IN (${lista})`,
    ),
  ]);
  const porId = new Map<string, Destino>();
  for (const l of log) {
    porId.set(
      l.id,
      l.resultado === 'ACEITO' || l.resultado === 'JA_RECEBIDO'
        ? { onde: 'aceito', resultado: l.resultado }
        : {
            onde: 'problema',
            resultado: l.resultado,
            codigo: l.codigo,
            resolvido: l.resolucao !== null,
          },
    );
  }
  // A quarentena vale por cima: o que veio de um computador bloqueado só entra pela aprovação da dona.
  for (const q of quarentena) porId.set(q.id, { onde: 'quarentena', situacao: q.situacao });
  const vendasPorVendedor = new Map<string, string[]>();
  for (const v of vendas)
    vendasPorVendedor.set(v.vendedor_id, [...(vendasPorVendedor.get(v.vendedor_id) ?? []), v.id]);
  return { porId, existentes: new Set(existentes.map((e) => e.id)), vendasPorVendedor };
}

/** O que tem de existir no servidor quando a operação entrou (o cliente alterado não cria nada). */
function criaAlgo(l: Lancamento): boolean {
  return l.tipo !== 'CLIENTE_ALTERADO';
}

/**
 * A afirmação final, a mesma para todo cenário e para o estresse (plano, item 3). Rede boa, todo computador vivo envia e
 * recebe até zerar, e então:
 * 1. a cópia local de cada um é igual ao oráculo (toda linha, versão e lápide) e não sobra provisória;
 * 2. nada privado de outro vendedor está na cópia;
 * 3. nenhum marcador proibido passou pela rede, e as colunas locais são só as da lista do pull;
 * 4. cada operação do livro tem destino no servidor (aceita uma vez, recusada ou em conflito nos "envios com problema",
 *    ou em quarentena), nenhuma aguardando, e o número de vendas de cada vendedor no servidor é o do livro;
 * 5. a conciliação "estoque + maletas" fecha.
 */
export async function afirmarNoFim(o: AfirmacaoFinal): Promise<void> {
  const problemas: string[] = [];
  for (const a of o.vivos) {
    a.comInternet();
    await a.sincronizar(120_000);
  }

  // 1 e 2: a cópia de cada computador vivo.
  for (const a of o.vivos) {
    const esperado = await oraculo(a.vendedorId);
    const { medidas, provisorias } = copiaLocal(a);
    for (const d of diferencas(esperado, medidas)) problemas.push(`${a.nome}: ${d}`);
    for (const p of provisorias) problemas.push(`${a.nome}: provisória sobrando: ${p}`);
    const deOutros = await idsDeOutrosVendedores(a.vendedorId);
    for (const t of TABELAS) {
      for (const id of medidas[t]!.keys())
        if (deOutros.has(id)) problemas.push(`${a.nome}: ${t}:${id} é de outro vendedor`);
    }
    // 3b: as colunas do banco local.
    for (const t of Object.keys(COLUNAS_DO_PULL) as TabelaSincronizavel[]) {
      const aceitas = new Set([...colunasGravaveis(t), ...CONTROLE]);
      for (const c of a.ler<{ name: string }>(`SELECT name FROM pragma_table_info('${t}')`)) {
        if (!aceitas.has(c.name))
          problemas.push(`${a.nome}: coluna ${t}.${c.name} fora da lista do pull`);
        if (MARCADORES_PROIBIDOS.some((m) => c.name.toLowerCase().includes(m)))
          problemas.push(`${a.nome}: coluna proibida ${t}.${c.name}`);
      }
    }
    // Nenhuma operação aguardando.
    for (const op of a.outbox())
      if (op.situacao === 'AGUARDANDO')
        problemas.push(`${a.nome}: ${op.tipo} ${op.operacao_id} ainda aguardando`);
  }

  // 3a: tudo que passou pela rede de todos os computadores.
  for (const a of o.todos) for (const m of a.marcadoresVistos) problemas.push(`${a.nome}: ${m}`);

  // 4: o livro.
  const vendedores = [...new Set(o.livro.todos().map((l) => l.vendedorId))];
  if (vendedores.length > 0) {
    const { porId, existentes, vendasPorVendedor } = await destinos(vendedores);
    const comProblema = new Set((await o.dona.enviosComProblema()).map((e) => e.id));
    const vendasDoLivro = new Map<string, Set<string>>();
    for (const l of o.livro.todos()) {
      const d = porId.get(l.operacaoId) ?? { onde: 'sumiu' };
      const existe = existentes.has(l.operacaoId);
      const rotulo = `${l.aparelho}: ${l.tipo} ${l.operacaoId}`;
      if (d.onde === 'sumiu' && l.perdaAceita) continue;
      if (d.onde === 'sumiu')
        problemas.push(`${rotulo} sumiu: não está no servidor, nem recusada, nem em quarentena`);
      else if (d.onde === 'aceito' && criaAlgo(l) && !existe)
        problemas.push(`${rotulo} foi aceita (${d.resultado}) e não existe no servidor`);
      else if (d.onde === 'problema') {
        if (!d.resolvido && !comProblema.has(l.operacaoId))
          problemas.push(`${rotulo} ${d.resultado} e não aparece nos envios com problema da dona`);
        if (
          (l.tipo === 'VENDA' || l.tipo === 'ESTORNO_VENDA') &&
          existe &&
          d.resultado !== 'CONFLITO'
        )
          problemas.push(`${rotulo} foi ${d.resultado} e mesmo assim existe no servidor`);
      } else if (d.onde === 'quarentena') {
        if (d.situacao === 'APROVADO' && criaAlgo(l) && !existe)
          problemas.push(`${rotulo} aprovada na quarentena e não existe no servidor`);
        if (d.situacao !== 'APROVADO' && l.tipo === 'VENDA' && existe)
          problemas.push(`${rotulo} está em quarentena (${d.situacao}) e já existe no servidor`);
      }
      if (l.tipo === 'VENDA' && existe) {
        const s = vendasDoLivro.get(l.vendedorId) ?? new Set<string>();
        s.add(l.operacaoId);
        vendasDoLivro.set(l.vendedorId, s);
      }
    }
    for (const v of vendedores) {
      const noServidor = vendasPorVendedor.get(v) ?? [];
      const doLivro = vendasDoLivro.get(v) ?? new Set();
      for (const id of noServidor)
        if (!doLivro.has(id))
          problemas.push(`venda ${id} do vendedor ${v} está no servidor e não no livro`);
    }
    // O livro de cada computador vivo bate com o servidor.
    for (const a of o.vivos) {
      for (const op of a.outbox()) {
        const d = porId.get(op.operacao_id);
        if (op.situacao === 'ENVIADO' && d?.onde !== 'aceito' && d?.onde !== 'quarentena')
          problemas.push(
            `${a.nome}: ${op.tipo} ${op.operacao_id} ENVIADO no computador e ${d?.onde ?? 'sumiu'} no servidor`,
          );
        if ((op.situacao === 'RECUSADO' || op.situacao === 'CONFLITO') && d?.onde !== 'problema')
          problemas.push(
            `${a.nome}: ${op.tipo} ${op.operacao_id} ${op.situacao} no computador e ${d?.onde ?? 'sumiu'} no servidor`,
          );
      }
    }
  }

  // 5: a conciliação.
  if (!o.semConciliacao) {
    const c = await o.dona.conciliacao();
    if (c.divergencias.length > 0)
      problemas.push(
        `conciliação: ${c.divergencias.length} divergências: ${JSON.stringify(c.divergencias).slice(0, 800)}`,
      );
    if (c.diferencasComMaletas.length > 0)
      problemas.push(
        `conciliação: estoque + maletas não fecha em ${c.diferencasComMaletas.length} peças: ${JSON.stringify(c.diferencasComMaletas).slice(0, 800)}`,
      );
  }

  if (problemas.length > 0) {
    throw new Error(
      `A afirmação final falhou (${problemas.length}):\n${problemas.slice(0, 60).join('\n')}\n\n${relatorio(o.semente, o.todos)}`,
    );
  }
}
