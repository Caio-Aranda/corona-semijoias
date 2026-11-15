import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type {
  FichaDoAcerto,
  RecebiveisDoVendedor,
  ResumoDoAcerto,
  Venda,
} from '../../shared/dados';
import type { PedidoDeChamada } from '../../shared/operacoes';
import { OperacoesLocais } from '../sync/operacoes-locais';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from '../sync/testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from '../sync/testes/dados-da-maleta';
import { doServidor, mudarMaleta } from '../sync/testes/dados-da-fase-6';
import { ConsultasLocais } from './consultas-locais';

/**
 * As consultas do acerto fechado, da comissão e dos recebíveis no computador (tarefa 7.11): lidas das cópias do servidor
 * (parcelas, contas do nível 2, acertos e fechamento) e do que foi feito aqui e ainda não chegou à loja. O computador não
 * calcula parcela nem comissão: só mostra o que o servidor mandou. Nada de custo nem de outro vendedor. SQLCipher de
 * verdade. Escrito antes do código.
 */
let b: BancoDeTeste;
let m: MaletaDeTeste;
let ops: OperacoesLocais;
let consultas: ConsultasLocais;
let agora: Date;
const T0 = new Date('2026-11-12T13:00:00.000Z'); // 12/11 10:00 em Brasília
const EU = '00000000-0000-7000-8000-0000000000e1';
const OUTRO = '00000000-0000-7000-8000-0000000000e2';
const PARCELAS = { quantidade: 3, primeiroVencimento: '2026-11-20' };

beforeEach(() => {
  b = criarBancoDeTeste();
  m = receberMaleta(b.db);
  agora = T0;
  ops = new OperacoesLocais({ banco: () => b.db, agora: () => agora });
  consultas = new ConsultasLocais({ banco: () => b.db, operacoes: ops, agora: () => agora });
  mudarMaleta(b.db, m.remessaId, 'CONFERIDA', { vendedor_id: EU });
});
afterEach(() => b.fechar());

function pedir<T>(pedido: PedidoDeChamada): T {
  const r = consultas.chamar(pedido);
  if (!r.ok) throw new Error(JSON.stringify(r.falha));
  return r.dados as T;
}
function recusado(pedido: PedidoDeChamada): string {
  const r = consultas.chamar(pedido);
  if (r.ok) throw new Error('devia recusar');
  return r.falha.tipo === 'recusada' ? (r.falha.mensagem ?? '') : r.falha.tipo;
}

/** Venda de 1 peça A (R$ 33,33) a prazo, paga em parte. */
function vendaAPrazo(pago = '10.00', parcelamento: typeof PARCELAS | null = PARCELAS): string {
  const id = idDeTeste(5);
  ops.vender({
    id,
    remessaId: m.remessaId,
    clienteId: m.clienteId,
    itens: [{ produtoId: m.produtoA, quantidade: 1 }],
    pagamentos: pago ? [{ id: idDeTeste(6), valor: pago, forma: 'DINHEIRO' }] : [],
    ...(parcelamento ? { parcelamento } : {}),
  });
  return id;
}

/** As parcelas que o servidor gerou (3 de 7,78, 7,78 e 7,77 sobre 23,33). */
function parcelasDoServidor(vendaId: string, pagoNaPrimeira = '0.00'): void {
  const valores = ['7.78', '7.78', '7.77'];
  const vencimentos = ['2026-11-10', '2026-12-10', '2027-01-10'];
  valores.forEach((valor, i) => {
    doServidor(b.db, 'contas_receber', idDeTeste(11), {
      devedor_tipo: 'CLIENTE',
      cliente_id: m.clienteId,
      origem_tipo: 'VENDA',
      origem_id: vendaId,
      venda_id: vendaId,
      parcela: i + 1,
      total_parcelas: 3,
      vencimento: vencimentos[i] ?? null,
      valor,
      valor_pago: i === 0 ? pagoNaPrimeira : '0.00',
      valor_cancelado: '0.00',
      status: i === 0 && pagoNaPrimeira !== '0.00' ? 'PARCIAL' : 'ABERTA',
    });
  });
}

describe('recebíveis: o que os clientes devem', () => {
  it('a venda a prazo que ainda não chegou à loja aparece sem parcelas e com o quanto falta', () => {
    const vendaId = vendaAPrazo('10.00');
    const r = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' });
    expect(r.dosClientes).toHaveLength(1);
    expect(r.dosClientes[0]).toMatchObject({
      vendaId,
      clienteNome: 'Ana',
      total: '33.33',
      pago: '10.00',
      aReceber: '23.33',
      aguardandoEnvio: true,
      parcelas: [],
      recebimentos: [],
    });
  });

  it('mostra as parcelas do servidor na ordem do vencimento, com a situação e a vencida', () => {
    const vendaId = vendaAPrazo('10.00');
    parcelasDoServidor(vendaId, '2.00');
    // A loja já recebeu a venda: o envio saiu da fila.
    b.db.prepare("UPDATE outbox SET situacao = 'ENVIADO'").run();
    const [v] = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes;
    expect(v?.aguardandoEnvio).toBe(false);
    expect(
      v?.parcelas.map((p) => [
        p.numero,
        p.total,
        p.vencimento,
        p.valor,
        p.valorPago,
        p.situacao,
        p.vencida,
      ]),
    ).toEqual([
      [1, 3, '2026-11-10', '7.78', '2.00', 'PARCIAL', true],
      [2, 3, '2026-12-10', '7.78', '0.00', 'ABERTA', false],
      [3, 3, '2027-01-10', '7.77', '0.00', 'ABERTA', false],
    ]);
  });

  it('a parcela vence no dia: ainda não está vencida; no dia seguinte (Brasília) está', () => {
    const vendaId = vendaAPrazo('10.00');
    parcelasDoServidor(vendaId);
    agora = new Date('2026-11-10T20:00:00.000Z');
    const antes = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes[0];
    expect(antes?.parcelas[0]?.vencida).toBe(false);
    agora = new Date('2026-11-11T12:00:00.000Z');
    const depois = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes[0];
    expect(depois?.parcelas[0]?.vencida).toBe(true);
  });

  it('venda paga, estornada ou à vista não aparece', () => {
    vendaAPrazo('33.33', null);
    const estornada = vendaAPrazo('', PARCELAS);
    ops.estornar({ id: idDeTeste(7), vendaId: estornada, motivo: 'Errei' });
    expect(pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes).toEqual([]);
  });

  it('receber diminui o que falta, aparece no histórico e pode ser estornado no mesmo dia', () => {
    const vendaId = vendaAPrazo('10.00');
    const recebimentoId = idDeTeste(9);
    const v = pedir<Venda>({
      operacao: 'recebimentos.receber',
      recebimento: { id: recebimentoId, vendaId, valor: '5.00', forma: 'PIX' },
    });
    expect(v.aReceber).toBe('18.33');
    const [linha] = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes;
    expect(linha?.aReceber).toBe('18.33');
    expect(linha?.recebimentos).toEqual([
      {
        id: recebimentoId,
        valor: '5.00',
        forma: 'PIX',
        em: '2026-11-12T13:00:00.000Z',
        estornado: false,
        podeEstornar: true,
        aguardandoEnvio: true,
      },
    ]);
    const depois = pedir<Venda>({
      operacao: 'recebimentos.estornar',
      id: recebimentoId,
      estornoId: idDeTeste(10),
      motivo: 'Valor errado',
    });
    expect(depois.aReceber).toBe('23.33');
    const [apos] = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes;
    expect(apos?.recebimentos[0]).toMatchObject({ estornado: true, podeEstornar: false });
    expect(apos?.aReceber).toBe('23.33');
  });

  it('a venda paga hoje por um recebimento continua na lista (para poder estornar), e some no dia seguinte', () => {
    const vendaId = vendaAPrazo('10.00');
    const id = idDeTeste(9);
    pedir({
      operacao: 'recebimentos.receber',
      recebimento: { id, vendaId, valor: '23.33', forma: 'DINHEIRO' },
    });
    const [v] = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes;
    expect(v?.aReceber).toBe('0.00');
    expect(v?.recebimentos[0]?.podeEstornar).toBe(true);
    agora = new Date('2026-11-13T13:00:00.000Z');
    expect(pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes).toEqual([]);
  });

  it('o recebimento de ontem não pode mais ser estornado pelo vendedor', () => {
    const vendaId = vendaAPrazo('10.00');
    const id = idDeTeste(9);
    pedir({
      operacao: 'recebimentos.receber',
      recebimento: { id, vendaId, valor: '5.00', forma: 'DINHEIRO' },
    });
    agora = new Date('2026-11-13T13:00:00.000Z');
    const [v] = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes;
    expect(v?.recebimentos[0]?.podeEstornar).toBe(false);
    expect(
      recusado({ operacao: 'recebimentos.estornar', id, estornoId: idDeTeste(10), motivo: 'x' }),
    ).toContain('mesmo dia');
  });

  it('o pagamento feito na hora da venda não vira recebimento do histórico', () => {
    vendaAPrazo('10.00');
    const [v] = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).dosClientes;
    expect(v?.recebimentos).toEqual([]);
  });
});

describe('recebíveis: o que o vendedor deve à loja', () => {
  const conta = (campos: Record<string, string | number | null>) =>
    doServidor(b.db, 'contas_receber', idDeTeste(12), {
      devedor_tipo: 'VENDEDOR',
      vendedor_id: EU,
      origem_tipo: 'ACERTO',
      origem_id: idDeTeste(13),
      total_parcelas: 1,
      parcela: 1,
      valor_pago: '0.00',
      valor_cancelado: '0.00',
      status: 'ABERTA',
      ...campos,
    });

  it('lista as contas do vendedor, a que vence primeiro antes, com a forma prevista do boleto', () => {
    conta({ valor: '80.00', vencimento: '2026-12-01', forma_prevista: 'BOLETO' });
    conta({ valor: '50.00', vencimento: '2026-11-05', valor_pago: '20.00', status: 'PARCIAL' });
    const r = pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' });
    expect(
      r.daLoja.map((c) => [c.valor, c.valorPago, c.situacao, c.vencida, c.formaPrevista]),
    ).toEqual([
      ['50.00', '20.00', 'PARCIAL', true, null],
      ['80.00', '0.00', 'ABERTA', false, 'BOLETO'],
    ]);
  });

  it('conta paga ou cancelada não aparece; conta de outro vendedor nunca aparece', () => {
    conta({ valor: '10.00', vencimento: '2026-12-01', status: 'PAGA', valor_pago: '10.00' });
    conta({ valor: '11.00', vencimento: '2026-12-01', status: 'CANCELADA' });
    conta({ valor: '12.00', vencimento: '2026-12-01', vendedor_id: OUTRO });
    expect(pedir<RecebiveisDoVendedor>({ operacao: 'recebiveis.ver' }).daLoja).toEqual([]);
  });

  it('nenhum campo de custo nem de caixa sai nas respostas', () => {
    conta({ valor: '80.00', vencimento: '2026-12-01' });
    vendaAPrazo();
    const texto = JSON.stringify(pedir({ operacao: 'recebiveis.ver' }));
    for (const proibido of ['custo', 'caixa', 'fornecedor', 'estoque']) {
      expect(texto.toLowerCase()).not.toContain(proibido);
    }
  });
});

describe('meu acerto', () => {
  const ACERTO = idDeTeste(14);
  const SEMANA = idDeTeste(15);
  const COTACAO = idDeTeste(15);

  function acertoFechado(extra: Record<string, string | number | null> = {}): void {
    doServidor(b.db, 'semanas', SEMANA, { numero: 46 });
    doServidor(b.db, 'cotacoes', COTACAO, {
      data_cotacao: '2026-11-10',
      usd_venda: '5.00',
      pyg_venda: '1500.0000',
    });
    doServidor(b.db, 'acertos', ACERTO, {
      vendedor_id: EU,
      semana_id: SEMANA,
      status: 'ACERTADO',
      pedido_pelo_vendedor: 1,
      iniciado_em: '2026-11-09T12:00:00.000Z',
      fechado_em: '2026-11-10T15:00:00.000Z',
      cotacao_id: COTACAO,
      meta_guarani: 700000,
      percentual_comissao: '40.00',
      vendido_para_meta_guarani: 720000,
      vendido_bruto: '500.00',
      descontos: '20.00',
      devolvido: '30.00',
      vendido_liquido: '450.00',
      comissao: '180.00',
      faltas_cobradas: '10.00',
      liquido: '280.00',
      recebido_no_acerto: '200.00',
      ficou_devendo: '80.00',
      ...extra,
    });
  }

  it('lista os acertos do vendedor, do mais novo para o mais antigo, sem o de outro vendedor', () => {
    acertoFechado();
    doServidor(b.db, 'acertos', idDeTeste(14), {
      vendedor_id: EU,
      semana_id: SEMANA,
      status: 'EM_ACERTO',
      pedido_pelo_vendedor: 0,
      iniciado_em: '2026-11-12T09:00:00.000Z',
    });
    doServidor(b.db, 'acertos', idDeTeste(14), {
      vendedor_id: OUTRO,
      semana_id: SEMANA,
      status: 'ACERTADO',
      iniciado_em: '2026-11-13T09:00:00.000Z',
    });
    const lista = pedir<ResumoDoAcerto[]>({ operacao: 'acertos.listar' });
    expect(lista.map((a) => [a.semanaNumero, a.situacao, a.pedidoPeloVendedor])).toEqual([
      [46, 'EM_ACERTO', false],
      [46, 'ACERTADO', true],
    ]);
  });

  it('a ficha do acerto fechado traz o que o servidor gravou e a cotação gravada, sem recalcular nada', () => {
    acertoFechado();
    const peca = idDeTeste(2);
    doServidor(b.db, 'produtos', peca, { nome: 'Brinco' });
    doServidor(b.db, 'itens_acerto', idDeTeste(15), {
      acerto_id: ACERTO,
      produto_id: peca,
      remessa_id: m.remessaId,
      preco_remessa: '25.00',
      esperado: 5,
      contado_boa: 3,
      contado_defeito: 1,
      falta: 1,
      defeito_aprovado: 1,
    });
    const venda = idDeTeste(5);
    doServidor(b.db, 'vendas', venda, {
      cliente_id: m.clienteId,
      remessa_id: m.remessaId,
      status: 'CONCLUIDA',
      total: '100.00',
      vendida_em: '2026-11-09T14:00:00.000Z',
    });
    doServidor(b.db, 'comissoes', idDeTeste(15), {
      acerto_id: ACERTO,
      venda_id: venda,
      tipo: 'COMISSAO',
      base: '100.00',
      percentual: '40.00',
      valor: '40.00',
    });
    doServidor(b.db, 'pendencias_acerto', idDeTeste(15), {
      acerto_id: ACERTO,
      tipo: 'FALTA',
      produto_id: peca,
      quantidade: 1,
      valor: '25.00',
      status: 'COBRADA',
    });
    doServidor(b.db, 'recebimentos_vendedor', idDeTeste(15), {
      vendedor_id: EU,
      momento: 'ACERTO',
      acerto_id: ACERTO,
      aplicado_em: 'ACERTO',
      forma: 'PIX',
      valor: '200.00',
      recebido_em: '2026-11-10T15:00:00.000Z',
    });
    doServidor(b.db, 'contas_receber', idDeTeste(12), {
      devedor_tipo: 'VENDEDOR',
      vendedor_id: EU,
      origem_tipo: 'ACERTO',
      origem_id: ACERTO,
      parcela: 1,
      total_parcelas: 1,
      vencimento: '2026-11-17',
      valor: '80.00',
      valor_pago: '0.00',
      valor_cancelado: '0.00',
      status: 'ABERTA',
    });

    const f = pedir<FichaDoAcerto>({ operacao: 'acertos.ver', id: ACERTO });
    expect(f).toMatchObject({ id: ACERTO, semanaNumero: 46, situacao: 'ACERTADO' });
    expect(f.fechamento).toMatchObject({
      cotacao: { data: '2026-11-10', dolar: '5.00', guarani: '1500.0000' },
      metaGuarani: 700000,
      percentualComissao: '40.00',
      vendidoParaMetaGuarani: 720000,
      vendidoBruto: '500.00',
      descontos: '20.00',
      devolvido: '30.00',
      vendidoLiquido: '450.00',
      comissao: '180.00',
      faltasCobradas: '10.00',
      liquido: '280.00',
      recebidoNoAcerto: '200.00',
      ficouDevendo: '80.00',
    });
    expect(f.fechamento?.itens).toMatchObject([
      {
        produto: { nome: 'Brinco' },
        esperado: 5,
        contadoBoa: 3,
        contadoDefeito: 1,
        falta: 1,
        defeitoAprovado: 1,
      },
    ]);
    expect(f.fechamento?.comissoes).toMatchObject([
      { clienteNome: 'Ana', tipo: 'COMISSAO', base: '100.00', percentual: '40.00', valor: '40.00' },
    ]);
    expect(f.fechamento?.pendencias).toMatchObject([
      {
        tipo: 'FALTA',
        quantidade: 1,
        valor: '25.00',
        status: 'COBRADA',
        produto: { nome: 'Brinco' },
      },
    ]);
    expect(f.fechamento?.recebimentos).toMatchObject([
      { forma: 'PIX', valor: '200.00', aplicadoEm: 'ACERTO', estornado: false },
    ]);
    expect(f.fechamento?.contas).toMatchObject([
      { valor: '80.00', vencimento: '2026-11-17', situacao: 'ABERTA', acertoId: ACERTO },
    ]);
  });

  it('acerto em andamento não tem fechamento; acerto de outro vendedor não existe aqui', () => {
    doServidor(b.db, 'semanas', SEMANA, { numero: 46 });
    doServidor(b.db, 'acertos', ACERTO, {
      vendedor_id: EU,
      semana_id: SEMANA,
      status: 'EM_ACERTO',
      iniciado_em: '2026-11-12T09:00:00.000Z',
    });
    expect(pedir<FichaDoAcerto>({ operacao: 'acertos.ver', id: ACERTO }).fechamento).toBeNull();
    const dele = idDeTeste(14);
    doServidor(b.db, 'acertos', dele, {
      vendedor_id: OUTRO,
      semana_id: SEMANA,
      status: 'ACERTADO',
    });
    expect(recusado({ operacao: 'acertos.ver', id: dele })).toContain('Não encontramos');
  });

  it('o líquido negativo (a loja deve ao vendedor) chega como o servidor gravou', () => {
    acertoFechado({ liquido: '-35.00', recebido_no_acerto: '0.00', ficou_devendo: '-35.00' });
    const f = pedir<FichaDoAcerto>({ operacao: 'acertos.ver', id: ACERTO });
    expect(f.fechamento?.liquido).toBe('-35.00');
    expect(f.fechamento?.ficouDevendo).toBe('-35.00');
  });
});
