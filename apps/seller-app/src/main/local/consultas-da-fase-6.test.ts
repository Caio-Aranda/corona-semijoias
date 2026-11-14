import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { EnvioNaoAceito, Maleta, RelatorioAReceber, Venda } from '../../shared/dados';
import type { PedidoDeChamada, PecaQueVolta, TrocaParaEnviar } from '../../shared/operacoes';
import { OperacoesLocais, saldoDaPeca } from '../sync/operacoes-locais';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from '../sync/testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from '../sync/testes/dados-da-maleta';
import { doServidor, mudarMaleta, plantarPeca, venderPago } from '../sync/testes/dados-da-fase-6';
import { ConsultasLocais } from './consultas-locais';

/**
 * As consultas da devolução, troca e crédito no computador e os números locais corrigidos (tarefa 6.8; plano, testes
 * 13 e 15): "vendida" com troca e devolução, "a receber" descontando o devolvido, a conciliação da maleta depois de
 * cada caminho, e nada de custo nem de outro vendedor. SQLCipher de verdade. Escrito antes do código.
 */
let b: BancoDeTeste;
let m: MaletaDeTeste;
let ops: OperacoesLocais;
let consultas: ConsultasLocais;
let agora: Date;
let produtoC: string;
const T0 = new Date('2026-11-12T13:00:00.000Z');
const OUTRO_VENDEDOR = '00000000-0000-7000-8000-0000000000e2';
const EU = '00000000-0000-7000-8000-0000000000e1';

beforeEach(() => {
  b = criarBancoDeTeste();
  m = receberMaleta(b.db);
  agora = T0;
  ops = new OperacoesLocais({ banco: () => b.db, agora: () => agora });
  consultas = new ConsultasLocais({ banco: () => b.db, operacoes: ops, agora: () => agora });
  mudarMaleta(b.db, m.remessaId, 'CONFERIDA', { vendedor_id: EU });
  doServidor(b.db, 'produtos', m.produtoA, { nome: 'Anel', foto_principal_hash: 'a'.repeat(64) });
  doServidor(b.db, 'produto_codigos', idDeTeste(9), {
    produto_id: m.produtoA,
    codigo: 'ANEL-01',
    tipo: 'LOJA',
    principal: 1,
    ativo: 1,
  });
  produtoC = idDeTeste(2);
  plantarPeca(b.db, m.remessaId, produtoC, 5, '50.00');
});
afterEach(() => b.fechar());

function pedir<T>(pedido: PedidoDeChamada): T {
  const r = consultas.chamar(pedido);
  if (!r.ok) throw new Error(JSON.stringify(r.falha));
  return r.dados as T;
}

const vender = (
  itens: { produtoId: string; quantidade: number }[],
  pagamentos?: Parameters<typeof venderPago>[5],
) => venderPago(b.db, ops, m.remessaId, m.clienteId, itens, pagamentos);

const pecas = (...p: [string, number, ('BOA' | 'DEFEITO')?][]): PecaQueVolta[] =>
  p.map(([produtoId, quantidade, estado]) => ({ produtoId, quantidade, estado: estado ?? 'BOA' }));

const devolver = (vendaId: string, itens: PecaQueVolta[]) => {
  const id = idDeTeste(8);
  ops.devolver({ id, vendaId, motivo: 'ARREPENDIMENTO', formaReembolso: 'PIX', itens });
  return id;
};

const trocar = (vendaId: string, voltam: PecaQueVolta[], levam: string): TrocaParaEnviar => {
  const t: TrocaParaEnviar = {
    id: idDeTeste(8),
    creditoId: idDeTeste(8),
    pagamentoDoCreditoId: idDeTeste(8),
    devolucao: { id: idDeTeste(8), vendaId, motivo: 'TAMANHO', itens: voltam },
    venda: {
      id: idDeTeste(5),
      remessaId: m.remessaId,
      itens: [{ produtoId: levam, quantidade: 1 }],
      pagamentos: [],
    },
  };
  ops.trocar(t);
  return t;
};

/** A conciliação local da maleta: enviada + movimentos = saldo, e enviada − vendida − devolvida à dona = saldo. */
function conciliar(): void {
  const maleta = pedir<Maleta>({ operacao: 'maletas.ver', id: m.remessaId });
  for (const l of maleta.itens) {
    expect(l.enviada - l.vendida - l.devolvida, l.produto.id).toBe(l.saldo);
    expect(saldoDaPeca(b.db, m.remessaId, l.produto.id), l.produto.id).toBe(l.saldo);
  }
}

const linhaDa = (produtoId: string) =>
  pedir<Maleta>({ operacao: 'maletas.ver', id: m.remessaId }).itens.find(
    (l) => l.produto.id === produtoId,
  );

describe('números locais com devolução e troca (teste 13)', () => {
  it('"vendida" conta a troca e desconta a peça que voltou; a conciliação fecha depois de cada caminho', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    conciliar();
    expect(linhaDa(m.produtoA)?.vendida).toBe(2);
    const d = devolver(v1.vendaId, pecas([m.produtoA, 1]));
    expect(linhaDa(m.produtoA)?.vendida).toBe(1);
    conciliar();
    const t = trocar(v1.vendaId, pecas([m.produtoA, 1]), produtoC);
    expect(linhaDa(m.produtoA)?.vendida).toBe(0);
    expect(linhaDa(produtoC)?.vendida).toBe(1);
    conciliar();
    ops.cancelarTroca({ id: idDeTeste(8), trocaId: t.id, motivo: 'Desistiu' });
    expect(linhaDa(produtoC)?.vendida).toBe(0);
    conciliar();
    ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d, motivo: 'Errei' });
    expect(linhaDa(m.produtoA)?.vendida).toBe(2);
    conciliar();
    ops.estornar({ id: idDeTeste(8), vendaId: v1.vendaId, motivo: 'Errei' });
    expect(linhaDa(m.produtoA)?.vendida).toBe(0);
    conciliar();
  });

  it('"a receber" da venda é total − devolvido − pago; o desfazer do reembolso é um tipo próprio', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const d = devolver(v.vendaId, pecas([m.produtoA, 1]));
    let venda = pedir<Venda>({ operacao: 'vendas.ver', id: v.vendaId });
    expect(venda).toMatchObject({
      total: '66.66',
      devolvido: '33.33',
      pago: '33.33',
      aReceber: '0.00',
    });
    expect(venda.pagamentos.find((p) => p.tipo === 'REEMBOLSO')).toMatchObject({
      forma: 'PIX',
      valor: '-33.33',
      daDevolucao: true,
    });
    ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d, motivo: 'Errei' });
    venda = pedir<Venda>({ operacao: 'vendas.ver', id: v.vendaId });
    expect(venda).toMatchObject({ devolvido: '0.00', pago: '66.66', aReceber: '0.00' });
    expect(venda.pagamentos.map((p) => p.tipo)).toEqual([
      'PAGAMENTO',
      'REEMBOLSO',
      'ESTORNO_REEMBOLSO',
    ]);
  });

  it('o relatório "a receber" desconta o devolvido (a venda paga com devolução não aparece devendo)', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    devolver(v.vendaId, pecas([m.produtoA, 1]));
    vender([{ produtoId: produtoC, quantidade: 1 }], [{ valor: '10.00', forma: 'PIX' }]);
    const r = pedir<RelatorioAReceber>({ operacao: 'relatorios.aReceber' });
    expect(r.clientes).toHaveLength(1);
    expect(r.clientes[0]).toMatchObject({ vendido: '83.33', pago: '43.33', aReceber: '40.00' });
    expect(r.totais.total).toBe('40.00');
  });

  it('a venda nova de uma troca cancelada aqui aparece estornada; o crédito em revisão aparece na venda', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const t = trocar(v.vendaId, pecas([m.produtoA, 1]), produtoC);
    expect(pedir<Venda>({ operacao: 'vendas.ver', id: t.venda.id })).toMatchObject({
      status: 'CONCLUIDA',
      trocaId: t.id,
      aReceber: '16.67',
    });
    ops.cancelarTroca({ id: idDeTeste(8), trocaId: t.id, motivo: 'Desistiu' });
    expect(pedir<Venda>({ operacao: 'vendas.ver', id: t.venda.id })).toMatchObject({
      status: 'ESTORNADA',
      aReceber: '0.00',
    });
    const comCredito = vender([{ produtoId: produtoC, quantidade: 1 }]);
    doServidor(b.db, 'creditos_sem_saldo', idDeTeste(9), {
      venda_id: comCredito.vendaId,
      situacao: 'EM_REVISAO',
      valor_pedido: '10.00',
      valor_usado: '4.00',
    });
    expect(pedir<Venda>({ operacao: 'vendas.ver', id: comCredito.vendaId }).creditoSemSaldo).toBe(
      true,
    );
  });

  it('envios não aceitos têm a descrição de cada operação nova', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    const d = devolver(v.vendaId, pecas([m.produtoA, 1]));
    const t = trocar(v.vendaId, pecas([m.produtoA, 1]), produtoC);
    const c1 = idDeTeste(8);
    ops.cancelarDevolucao({ id: c1, devolucaoId: d, motivo: 'x' });
    const c2 = idDeTeste(8);
    ops.cancelarTroca({ id: c2, trocaId: t.id, motivo: 'x' });
    const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    agora = new Date('2026-11-25T15:00:00.000Z');
    const p = idDeTeste(8);
    ops.pedirAutorizacao({
      id: p,
      vendaId: v2.vendaId,
      motivo: 'ARREPENDIMENTO',
      itens: pecas([m.produtoA, 1]),
    });
    b.db
      .prepare("UPDATE outbox SET situacao = 'RECUSADO', ultimo_erro = 'x' WHERE tipo <> 'VENDA'")
      .run();
    const envios = pedir<EnvioNaoAceito[]>({ operacao: 'envios.naoAceitos' });
    expect(envios.map((e) => e.descricao)).toEqual([
      'Devolução de Ana',
      'Troca de Ana',
      'Cancelamento de devolução',
      'Cancelamento de troca',
      'Pedido de autorização: venda de Ana',
    ]);
    expect(envios.every((e) => !e.ehVenda)).toBe(true);
  });
});

describe('consultas da devolução e troca', () => {
  it('vendas com a peça: as minhas, da mais nova para a mais antiga, com o que ainda pode voltar e a garantia', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    agora = new Date('2026-11-13T13:00:00.000Z');
    const v2 = vender(
      [{ produtoId: m.produtoA, quantidade: 1 }],
      [{ valor: '10.00', forma: 'PIX' }],
    );
    devolver(v1.vendaId, pecas([m.produtoA, 1]));
    // Uma venda de outro vendedor no banco (defesa) não aparece.
    doServidor(b.db, 'vendas', idDeTeste(5), {
      vendedor_id: OUTRO_VENDEDOR,
      cliente_id: m.clienteId,
      remessa_id: m.remessaId,
      status: 'CONCLUIDA',
      total: '33.33',
      vendida_em: T0.toISOString(),
    });
    const lista = consultas.fase6.vendasComAPeca('  anel-01 ');
    expect(lista.map((l) => l.vendaId)).toEqual([v2.vendaId, v1.vendaId]);
    expect(lista[1]).toMatchObject({
      clienteNome: 'Ana',
      vendidas: 2,
      jaDevolvidas: 1,
      podemVoltar: 1,
      desistenciaAte: '2026-11-19',
      defeitoAte: '2026-12-12',
      bloqueio: null,
    });
    expect(lista[1]?.peca).toMatchObject({ nome: 'Anel', fotoHash: 'a'.repeat(64) });
    // A venda a prazo também se devolve (tarefa 6.6): nada a bloqueia.
    expect(lista[0]?.bloqueio).toBeNull();
    expect(consultas.fase6.vendasComAPeca('nao-existe')).toEqual([]);
  });

  it('prévia da devolução: valor, destino, prazo, pedido esperando e autorização aprovada', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    let p = consultas.fase6.previaDaDevolucao({
      vendaId: v.vendaId,
      itens: pecas([m.produtoA, 1]),
    });
    expect(p).toMatchObject({
      valor: '33.33',
      emDinheiro: '33.33',
      emCredito: '0.00',
      dentroDoPrazo: true,
      problema: null,
      podePedirAutorizacao: false,
      destinos: [{ produtoId: m.produtoA, estado: 'BOA', destino: 'MALETA' }],
    });
    expect(p.pecas).toEqual([
      expect.objectContaining({ vendidas: 2, jaDevolvidas: 0, podemVoltar: 2 }),
    ]);
    expect(
      consultas.fase6.previaDaDevolucao({ vendaId: v.vendaId, itens: pecas([m.produtoA, 3]) })
        .problema,
    ).toBe('Desta venda ainda podem voltar só 2 peça(s) de "Anel". Confira a quantidade.');
    agora = new Date('2026-11-21T15:00:00.000Z');
    p = consultas.fase6.previaDaDevolucao({ vendaId: v.vendaId, itens: [] });
    expect(p).toMatchObject({
      valor: '0.00',
      dentroDoPrazo: false,
      podePedirAutorizacao: true,
      problema: 'Passou do prazo da garantia. Peça autorização à dona.',
    });
    const pedido = idDeTeste(8);
    ops.pedirAutorizacao({
      id: pedido,
      vendaId: v.vendaId,
      motivo: 'ARREPENDIMENTO',
      itens: pecas([m.produtoA, 2]),
    });
    p = consultas.fase6.previaDaDevolucao({ vendaId: v.vendaId, itens: pecas([m.produtoA, 1]) });
    expect(p).toMatchObject({
      pedidoEsperando: true,
      podePedirAutorizacao: false,
      problema: 'Você já pediu autorização à dona para esta venda. Espere a resposta.',
    });
    // A resposta da dona chega pelo pull: aprovado, vale até 28/11.
    doServidor(b.db, 'autorizacoes_excecao', pedido, {
      venda_id: v.vendaId,
      motivo: 'ARREPENDIMENTO',
      situacao: 'APROVADA',
      valida_ate: '2026-11-28',
      pedida_em: agora.toISOString(),
      decidida_em: agora.toISOString(),
    });
    p = consultas.fase6.previaDaDevolucao({ vendaId: v.vendaId, itens: pecas([m.produtoA, 1]) });
    expect(p).toMatchObject({
      autorizacao: { id: pedido, validaAte: '2026-11-28' },
      problema: null,
      pedidoEsperando: false,
    });
  });

  it('prévia da troca: a diferença a pagar, de crédito ou nenhuma', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const base = {
      devolucao: { vendaId: v.vendaId, motivo: 'TAMANHO' as const, itens: pecas([m.produtoA, 1]) },
    };
    expect(
      consultas.fase6.previaDaTroca({ ...base, itens: [{ produtoId: produtoC, quantidade: 1 }] }),
    ).toEqual({
      remessaId: m.remessaId,
      valorQueVolta: '33.33',
      valorNovo: '50.00',
      abatido: '0.00',
      diferenca: '16.67',
      problema: null,
    });
    expect(
      consultas.fase6.previaDaTroca({ ...base, itens: [{ produtoId: m.produtoB, quantidade: 1 }] })
        .diferenca,
    ).toBe('-23.33');
  });

  it('minhas devoluções e trocas: aguardando envio, para revisão da dona, cancelada e o cancelar só no mesmo dia', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 3 }]);
    const d1 = devolver(v.vendaId, pecas([m.produtoA, 1]));
    const t = trocar(v.vendaId, pecas([m.produtoA, 1]), produtoC);
    let lista = consultas.fase6.listar({});
    expect(lista.map((l) => [l.tipo, l.situacao, l.podeCancelar])).toEqual([
      ['TROCA', 'AGUARDANDO_ENVIO', true],
      ['DEVOLUCAO', 'AGUARDANDO_ENVIO', true],
    ]);
    expect(lista[0]).toMatchObject({
      diferenca: '16.67',
      valor: '33.33',
      emCredito: '33.33',
      pecas: 1,
    });
    // A cópia do servidor chega: a devolução ficou para revisão da dona.
    doServidor(b.db, 'devolucoes', d1, {
      venda_id: v.vendaId,
      cliente_id: m.clienteId,
      situacao: 'EM_REVISAO',
      destino: 'REEMBOLSO',
      valor_devolvido: '33.33',
      valor_reembolsado: '33.33',
      valor_em_credito: '0.00',
      devolvida_em: T0.toISOString(),
    });
    lista = consultas.fase6.listar({});
    expect(lista.find((l) => l.id === d1)?.situacao).toBe('EM_REVISAO');
    ops.cancelarTroca({ id: idDeTeste(8), trocaId: t.id, motivo: 'x' });
    expect(consultas.fase6.listar({}).find((l) => l.id === t.id)).toMatchObject({
      situacao: 'CANCELADA',
      podeCancelar: false,
    });
    agora = new Date('2026-11-13T15:00:00.000Z');
    expect(consultas.fase6.listar({}).find((l) => l.id === d1)?.podeCancelar).toBe(false);
    expect(consultas.fase6.listar({ de: '2026-11-13' })).toEqual([]);
  });

  it('pedidos de autorização: a situação na tela e os avisos das respondidas', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const v2 = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    agora = new Date('2026-11-21T15:00:00.000Z');
    const p1 = idDeTeste(8);
    const p2 = idDeTeste(8);
    ops.pedirAutorizacao({
      id: p1,
      vendaId: v1.vendaId,
      motivo: 'ARREPENDIMENTO',
      itens: pecas([m.produtoA, 1]),
    });
    ops.pedirAutorizacao({
      id: p2,
      vendaId: v2.vendaId,
      motivo: 'ARREPENDIMENTO',
      itens: pecas([m.produtoA, 1]),
    });
    expect(consultas.fase6.autorizacoes().pedidos.map((p) => p.situacao)).toEqual([
      'AGUARDANDO_ENVIO',
      'AGUARDANDO_ENVIO',
    ]);
    b.db.prepare("UPDATE outbox SET situacao = 'ENVIADO'").run();
    doServidor(b.db, 'autorizacoes_excecao', p1, {
      venda_id: v1.vendaId,
      motivo: 'ARREPENDIMENTO',
      situacao: 'APROVADA',
      valida_ate: '2026-11-28',
      pedida_em: agora.toISOString(),
      decidida_em: agora.toISOString(),
    });
    doServidor(b.db, 'autorizacoes_excecao', p2, {
      venda_id: v2.vendaId,
      motivo: 'ARREPENDIMENTO',
      situacao: 'NEGADA',
      pedida_em: agora.toISOString(),
      decidida_em: agora.toISOString(),
    });
    let r = consultas.fase6.autorizacoes();
    expect(new Map(r.pedidos.map((p) => [p.id, [p.situacao, p.validaAte]]))).toEqual(
      new Map([
        [p1, ['APROVADA', '2026-11-28']],
        [p2, ['NEGADA', null]],
      ]),
    );
    expect(r.avisos.map((a) => [a.tipo, a.clienteNome, a.validaAte]).sort()).toEqual([
      ['APROVADA', 'Ana', '2026-11-28'],
      ['NEGADA', 'Ana', null],
    ]);
    // Usada aqui: a aprovação some dos avisos; vencida e negada antiga também.
    devolver(v1.vendaId, pecas([m.produtoA, 1]));
    r = consultas.fase6.autorizacoes();
    expect(r.pedidos.find((p) => p.id === p1)?.situacao).toBe('USADA');
    agora = new Date('2026-11-29T15:00:00.000Z');
    r = consultas.fase6.autorizacoes();
    expect(r.avisos).toEqual([]);
  });

  it('crédito do cliente: só os em aberto deste vendedor, com o saldo do computador (teste 15)', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 1 }]);
    const t = trocar(v.vendaId, pecas([m.produtoA, 1]), m.produtoB);
    doServidor(b.db, 'creditos_cliente', idDeTeste(9), {
      cliente_id: m.clienteId,
      vendedor_id: OUTRO_VENDEDOR,
      origem: 'TROCA',
      valor: '99.00',
      situacao: 'ABERTO',
    });
    expect(consultas.fase6.creditosDoCliente(m.clienteId)).toEqual({
      total: '23.33',
      creditos: [{ id: t.creditoId, saldo: '23.33' }],
    });
  });
});

describe('nada de custo nem de outro vendedor nas respostas novas (teste 15)', () => {
  it('nenhuma chave de custo, fornecedor ou estoque da loja; a devolução de outro vendedor não aparece', () => {
    const v = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    devolver(v.vendaId, pecas([m.produtoA, 1]));
    doServidor(b.db, 'devolucoes', idDeTeste(9), {
      venda_id: idDeTeste(5),
      vendedor_id: OUTRO_VENDEDOR,
      cliente_id: m.clienteId,
      situacao: 'ACEITA',
      destino: 'REEMBOLSO',
      valor_devolvido: '10.00',
      devolvida_em: T0.toISOString(),
    });
    const respostas: unknown[] = [
      consultas.fase6.vendasComAPeca('ANEL-01'),
      consultas.fase6.previaDaDevolucao({ vendaId: v.vendaId, itens: pecas([m.produtoA, 1]) }),
      consultas.fase6.previaDaTroca({
        devolucao: { vendaId: v.vendaId, motivo: 'TAMANHO', itens: pecas([m.produtoA, 1]) },
        itens: [{ produtoId: produtoC, quantidade: 1 }],
      }),
      consultas.fase6.listar({}),
      consultas.fase6.autorizacoes(),
      consultas.fase6.creditosDoCliente(m.clienteId),
    ];
    const chaves = new Set<string>();
    const juntar = (x: unknown): void => {
      if (Array.isArray(x)) x.forEach(juntar);
      else if (typeof x === 'object' && x !== null)
        for (const [k, y] of Object.entries(x)) {
          chaves.add(k);
          juntar(y);
        }
    };
    respostas.forEach(juntar);
    for (const k of chaves) {
      expect(k, k).not.toMatch(/custo|estoque|vendedor/i);
      expect(k === 'fornecedor' || k === 'fornecedorId', k).toBe(false);
    }
    expect(consultas.fase6.listar({})).toHaveLength(1);
  });
});

describe('Meus números com devolução, troca e crédito (tarefa 6.10)', () => {
  it('devoluções e trocas por motivo no período; a cancelada fica de fora; sem custo', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 3 }]);
    devolver(v1.vendaId, pecas([m.produtoA, 1]));
    const t = trocar(v1.vendaId, pecas([m.produtoA, 1]), produtoC);
    const d2 = idDeTeste(8);
    ops.devolver({
      id: d2,
      vendaId: v1.vendaId,
      motivo: 'DEFEITO',
      formaReembolso: 'PIX',
      itens: pecas([m.produtoA, 1, 'DEFEITO']),
    });
    ops.cancelarDevolucao({ id: idDeTeste(8), devolucaoId: d2, motivo: 'Errei' });

    const r = pedir<{
      linhas: { motivo: string; devolucoes: number; valor: string }[];
      totais: { devolucoes: number; trocas: number; valorDevolvido: string };
    }>({ operacao: 'relatorios.devolucoes' });
    expect(r.linhas.map((l) => l.motivo).sort()).toEqual(['ARREPENDIMENTO', 'TAMANHO']);
    expect(r.totais).toMatchObject({ devolucoes: 1, trocas: 1 });
    expect(r.linhas.reduce((t2, l) => t2 + l.devolucoes, 0)).toBe(2);
    expect(JSON.stringify(r)).not.toMatch(/custo/i);
    expect(t.id).toBeTruthy();

    // Período que não tem nada: vazio.
    const vazio = pedir<{ linhas: unknown[]; totais: { devolucoes: number } }>({
      operacao: 'relatorios.devolucoes',
      de: '2030-01-01',
      ate: '2030-01-31',
    });
    expect(vazio.linhas).toEqual([]);
    expect(vazio.totais.devolucoes).toBe(0);
  });

  it('crédito dos meus clientes: gerado, usado e em aberto; recebimentos não contam o crédito', () => {
    const v1 = vender([{ produtoId: m.produtoA, quantidade: 2 }]);
    trocar(v1.vendaId, pecas([m.produtoA, 1]), produtoC);
    const c = pedir<{
      clientes: { clienteId: string; gerado: string; usado: string; emAberto: string }[];
      totais: { gerado: string; usado: string; emAberto: string };
    }>({ operacao: 'relatorios.creditos' });
    expect(c.clientes).toHaveLength(1);
    const [cliente] = c.clientes;
    expect(cliente?.clienteId).toBe(m.clienteId);
    // gerado = usado + em aberto, com o que ele fez aqui ainda não enviado.
    const g = Number(cliente?.gerado);
    expect(g).toBeGreaterThan(0);
    expect((Number(cliente?.usado) + Number(cliente?.emAberto)).toFixed(2)).toBe(g.toFixed(2));
    expect(c.totais.gerado).toBe(cliente?.gerado);

    const recebimentos = pedir<{ linhas: { nome: string }[] }>({
      operacao: 'relatorios.recebimentos',
    });
    expect(recebimentos.linhas.map((l) => l.nome)).not.toContain('CREDITO');
  });
});
