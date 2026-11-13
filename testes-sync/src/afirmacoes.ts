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
            `  #${p.numero} ${p.em} ${p.metodo} ${p.caminho.split('?')[0]} ${p.falha ?? 'ok'} servidor=${p.servidorRespondeu ? p.statusDoServidor : '-'}`,
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
      UNION ALL SELECT id::text FROM corona.clientes
      UNION ALL SELECT id::text FROM corona.devolucoes WHERE vendedor_id IN (${lista})
      UNION ALL SELECT cancelamento_id::text FROM corona.devolucoes WHERE vendedor_id IN (${lista}) AND cancelamento_id IS NOT NULL
      UNION ALL SELECT id::text FROM corona.trocas WHERE vendedor_id IN (${lista})
      UNION ALL SELECT cancelamento_id::text FROM corona.trocas WHERE vendedor_id IN (${lista}) AND cancelamento_id IS NOT NULL
      UNION ALL SELECT id::text FROM corona.autorizacoes_excecao WHERE vendedor_id IN (${lista})`),
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

/**
 * As contas da Fase 6 conferidas direto no banco, sem passar por nenhum código da API: o devolvido de cada linha não passa
 * do vendido; nenhum crédito tem saldo negativo e o utilizado é a soma dos usos; o dinheiro que voltou ao cliente não passa
 * do que ele pagou e o recebido líquido fica entre zero e o total menos o devolvido.
 */
export async function conferirAFase6(): Promise<string[]> {
  const achados: string[] = [];
  const devolvidoDemais = await consultar<{ id: string; vendido: number; devolvido: number }>(`
    SELECT iv.id::text, iv.quantidade AS vendido, sum(i.quantidade)::int AS devolvido
      FROM corona.itens_devolucao i
      JOIN corona.devolucoes d ON d.id = i.devolucao_id AND d.situacao <> 'CANCELADA'
      JOIN corona.itens_venda iv ON iv.id = i.item_venda_id
     GROUP BY iv.id, iv.quantidade HAVING sum(i.quantidade) > iv.quantidade`);
  for (const l of devolvidoDemais)
    achados.push(`linha ${l.id} vendeu ${l.vendido} e devolveu ${l.devolvido}`);
  const creditos = await consultar<{ id: string; valor: string; usado: string; soma: string }>(`
    SELECT c.id::text, c.valor::text, c.valor_utilizado::text AS usado, coalesce(sum(u.valor), 0)::text AS soma
      FROM corona.creditos_cliente c LEFT JOIN corona.usos_credito u ON u.credito_id = c.id
     GROUP BY c.id, c.valor, c.valor_utilizado
    HAVING c.valor_utilizado <> coalesce(sum(u.valor), 0) OR coalesce(sum(u.valor), 0) < 0
        OR coalesce(sum(u.valor), 0) > c.valor`);
  for (const c of creditos)
    achados.push(
      `crédito ${c.id}: valor ${c.valor}, utilizado ${c.usado}, soma dos usos ${c.soma}`,
    );
  const vendas = await consultar<{
    id: string;
    liquido: string;
    total: string;
    devolvido: string;
  }>(`
    SELECT v.id::text, sum(p.valor)::text AS liquido, v.total::text,
           coalesce((SELECT sum(d.valor_devolvido) FROM corona.devolucoes d
                      WHERE d.venda_id = v.id AND d.situacao <> 'CANCELADA'), 0)::text AS devolvido
      FROM corona.vendas v JOIN corona.pagamentos_cliente p ON p.venda_id = v.id AND p.deleted_at IS NULL
     WHERE v.deleted_at IS NULL
     GROUP BY v.id, v.total
    HAVING sum(p.valor) < 0
        OR sum(p.valor) > v.total - coalesce((SELECT sum(d.valor_devolvido) FROM corona.devolucoes d
                      WHERE d.venda_id = v.id AND d.situacao <> 'CANCELADA'), 0)
           + 0.00`);
  for (const v of vendas)
    achados.push(
      `venda ${v.id}: recebido líquido ${v.liquido}, total ${v.total}, devolvido ${v.devolvido}`,
    );
  return achados;
}

/** Quantas voltas a afirmação final dá, no máximo, esperando o servidor parar de mudar. */
export const MAXIMO_DE_VOLTAS = 5;

/** Retrato do servidor nas tabelas que o Corona Vendas copia (toda mudança, inclusive a lápide, sobe a versão). */
interface Retrato {
  linhas: number;
  resumo: string;
}

async function retratoDoServidor(): Promise<Retrato> {
  const uniao = TABELAS.map(
    (t) => `SELECT '${t}' AS tabela, id::text AS id, version FROM corona.${t}`,
  ).join('\n  UNION ALL ');
  const [r] = await consultar<{ linhas: number; resumo: string }>(
    `SELECT count(*)::int AS linhas,
            md5(coalesce(string_agg(tabela || ':' || id || ':' || version, ',' ORDER BY tabela, id), '')) AS resumo
       FROM (${uniao}) linhas_do_pull`,
  );
  return { linhas: r?.linhas ?? 0, resumo: r?.resumo ?? '' };
}

/**
 * Rede boa para todos os computadores vivos de uma vez e voltas (cada um envia e recebe até zerar) até uma volta inteira
 * em que o servidor não mudou: aí todo computador recebeu depois do último envio de qualquer outro (issue #130). Uma
 * passada só deixava sem receber quem vinha antes na lista de alguém que ainda tinha o que enviar. Uma perda de verdade
 * no recebimento (cursor que passou por cima de uma linha) continua aparecendo: mais voltas só entregam o que está depois
 * do cursor de cada um.
 */
async function enviarEReceberAteOServidorParar(
  vivos: readonly Aparelho[],
): Promise<{ voltas: number; parou: boolean; retratos: Retrato[] }> {
  if (vivos.length === 0) return { voltas: 0, parou: true, retratos: [] };
  for (const a of vivos) a.comInternet();
  let antes = await retratoDoServidor();
  const retratos = [antes];
  for (let volta = 1; volta <= MAXIMO_DE_VOLTAS; volta++) {
    for (const a of vivos) await a.sincronizar(120_000);
    const depois = await retratoDoServidor();
    retratos.push(depois);
    if (depois.resumo === antes.resumo) return { voltas: volta, parou: true, retratos };
    antes = depois;
  }
  return { voltas: MAXIMO_DE_VOLTAS, parou: false, retratos };
}

/** Uma linha do oráculo que não chegou certa a um computador. */
interface Falta {
  aparelho: Aparelho;
  tabela: string;
  id: string;
}

/**
 * Para cada linha que faltou (as 10 primeiras): onde ela está no registro do servidor (`sync_xid`, `sync_seq`), o envio
 * que a criou (`sync_log`), e o cursor e o último recebimento de cada computador. Com isso dá para ver se o cursor passou
 * por cima da linha (perda no recebimento) ou se o computador só não recebeu de novo depois de ela entrar.
 */
async function detalharFaltas(
  faltas: readonly Falta[],
  vivos: readonly Aparelho[],
): Promise<string[]> {
  if (faltas.length === 0) return [];
  const linhas = ['--- Linhas que faltaram (registro do servidor e cursores):'];
  const vistas = new Set<string>();
  for (const f of faltas) {
    const chave = `${f.tabela}:${f.id}`;
    if (vistas.has(chave)) continue;
    vistas.add(chave);
    if (vistas.size > 10) break;
    const [noServidor] = await consultar<Record<string, unknown>>(
      `SELECT version, sync_xid::text AS sync_xid, sync_seq, created_at, updated_at, deleted_at
         FROM corona.${f.tabela} WHERE id = ${texto(f.id)}`,
    );
    const [envio] = await consultar<Record<string, unknown>>(
      `SELECT tipo, resultado, codigo, recebido_em, vendedor_id::text AS vendedor_id
         FROM corona.sync_log WHERE id = ${texto(f.id)}`,
    );
    const quem = faltas.filter((x) => `${x.tabela}:${x.id}` === chave).map((x) => x.aparelho.nome);
    linhas.push(
      `  ${chave} (falta em ${quem.join(', ')}): servidor ${JSON.stringify(noServidor ?? null)}; envio ${envio ? JSON.stringify(envio) : 'nenhum no sync_log (não veio de um envio com este id)'}`,
    );
  }
  for (const a of vivos) {
    const cursor = a.bancoAberto
      ? (a.ler<{ valor: string }>("SELECT valor FROM estado_sync WHERE chave = 'cursor'")[0]
          ?.valor ?? 'nenhum')
      : 'banco fechado';
    const ultimoPull = [...a.proxy.historico()]
      .reverse()
      .find((p) => p.caminho.startsWith('/api/vendedor/sync/pull') && p.statusDoServidor === 200);
    linhas.push(
      `  ${a.nome}: cursor ${cursor}; último recebimento que o servidor respondeu com 200 às ${ultimoPull?.em ?? 'nunca'}`,
    );
  }
  return linhas;
}

/** O que tem de existir no servidor quando a operação entrou (o cliente alterado não cria nada). */
function criaAlgo(l: Lancamento): boolean {
  return l.tipo !== 'CLIENTE_ALTERADO';
}

/**
 * A afirmação final, a mesma para todo cenário e para o estresse (plano, item 3). Rede boa para todos os computadores
 * vivos, e voltas (cada um envia e recebe até zerar) até uma volta inteira sem mudança no servidor (no máximo 5), e então:
 * 1. a cópia local de cada um é igual ao oráculo (toda linha, versão e lápide) e não sobra provisória;
 * 2. nada privado de outro vendedor está na cópia;
 * 3. nenhum marcador proibido passou pela rede, e as colunas locais são só as da lista do pull;
 * 4. cada operação do livro tem destino no servidor (aceita uma vez, recusada ou em conflito nos "envios com problema",
 *    ou em quarentena), nenhuma aguardando, e o número de vendas de cada vendedor no servidor é o do livro;
 * 5. a conciliação "estoque + maletas" fecha.
 */
export async function afirmarNoFim(o: AfirmacaoFinal): Promise<void> {
  const problemas: string[] = [];
  const { voltas, parou, retratos } = await enviarEReceberAteOServidorParar(o.vivos);
  const linhasPorVolta = retratos.map((r) => r.linhas).join(' → ');
  if (o.vivos.length > 0)
    console.log(
      `Afirmação final: ${voltas} ${voltas === 1 ? 'volta' : 'voltas'} até o servidor parar (linhas nas ${TABELAS.length} tabelas: ${linhasPorVolta}).`,
    );
  if (!parou)
    problemas.push(
      `o servidor não parou de mudar em ${MAXIMO_DE_VOLTAS} voltas (linhas nas ${TABELAS.length} tabelas: ${linhasPorVolta})`,
    );

  // 1 e 2: a cópia de cada computador vivo.
  const faltas: Falta[] = [];
  for (const a of o.vivos) {
    const esperado = await oraculo(a.vendedorId);
    const { medidas, provisorias } = copiaLocal(a);
    for (const d of diferencas(esperado, medidas)) problemas.push(`${a.nome}: ${d}`);
    for (const t of TABELAS) {
      for (const [id, x] of esperado[t]!) {
        const y = medidas[t]!.get(id);
        if (!y || y.versao !== x.versao || y.excluida !== x.excluida)
          faltas.push({ aparelho: a, tabela: t, id });
      }
    }
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
          l.tipo !== 'CLIENTE_NOVO' &&
          l.tipo !== 'CLIENTE_ALTERADO' &&
          l.tipo !== 'CONFERENCIA' &&
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
      // A venda nova de uma troca aceita é venda do livro; a de uma troca recusada não pode existir (tarefa 6.11).
      if (l.tipo === 'TROCA' && l.vendaNovaId) {
        const vendaNovaExiste = existentes.has(l.vendaNovaId);
        if (d.onde === 'aceito' && existe) {
          if (!vendaNovaExiste)
            problemas.push(`${rotulo} foi aceita e a venda nova dela não existe no servidor`);
          const s = vendasDoLivro.get(l.vendedorId) ?? new Set<string>();
          s.add(l.vendaNovaId);
          vendasDoLivro.set(l.vendedorId, s);
        } else if (d.onde === 'problema' && d.resultado !== 'CONFLITO' && vendaNovaExiste)
          problemas.push(`${rotulo} foi ${d.resultado} e a venda nova dela existe no servidor`);
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

  // 6: a conferência da Fase 6, em SQL próprio (à parte do código da API, como o oráculo) e a da tarefa 6.10.
  if (!o.semConciliacao) {
    for (const p of await conferirAFase6()) problemas.push(`conferência da Fase 6: ${p}`);
    const c610 = await o.dona.conferenciaDeDevolucaoETroca();
    for (const c of c610.conferencias)
      if (c.divergencias.length > 0)
        problemas.push(
          `conferência de devolução e troca (6.10) "${c.chave}": ${JSON.stringify(c.divergencias).slice(0, 600)}`,
        );
  }

  if (problemas.length > 0) {
    const detalhe = await detalharFaltas(faltas, o.vivos).catch((erro: unknown) => [
      `--- Não consegui detalhar as linhas que faltaram: ${erro instanceof Error ? erro.message : String(erro)}`,
    ]);
    throw new Error(
      [
        `A afirmação final falhou (${problemas.length}):`,
        ...problemas.slice(0, 60),
        '',
        `Afirmação final: ${voltas} voltas (linhas nas ${TABELAS.length} tabelas: ${linhasPorVolta}).`,
        ...detalhe,
        '',
        relatorio(o.semente, o.todos),
      ].join('\n'),
    );
  }
}
