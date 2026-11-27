import { EXEMPLOS_DO_CONSIGNADO as exemplos } from '@corona/shared/exemplos-do-consignado';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DetalheDaNota, PainelDaSemana } from '../../shared/dados-do-consignado';
import type { PedidoDeChamada } from '../../shared/operacoes';
import type {
  NotaDeSaida,
  PecasVendidasPorCliente,
  RelatorioDoAcerto,
  SituacaoDaMaleta,
  SituacaoDoCliente,
  TotaisDaSemanaNoRelatorio,
} from '../../shared/relatorios-do-consignado';
import { validarPedidoDoConsignado } from '../../shared/validacao-do-consignado';
import { ConsultasLocais } from '../local/consultas-locais';
import { aplicarLote } from './aplicador-do-pull';
import { CHAVES, gravarEstado } from './estado-sync';
import { OperacoesLocais } from './operacoes-locais';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';

/**
 * Os relatórios do consignado ao cliente (tarefa 9.3; §23.11), montados do banco local, sem internet. Os valores de cada nota
 * e acerto são os do caso de teste obrigatório (nota 0000064-21, `consignado-acerto.json`, o mesmo arquivo do servidor); as
 * conversões são cada lançamento pela cotação do seu dia (US$ 1 = R$ 5,00; Gs. 1 = R$ 0,00075).
 */
let b: BancoDeTeste;
let agora: Date;
let consultas: ConsultasLocais;
let operacoes: OperacoesLocais;

const maleta = idDeTeste(1);
const cliente = idDeTeste(3);
const produto = { A: idDeTeste(2), B: idDeTeste(2), C: idDeTeste(2), D: idDeTeste(2) };

function chamar<T>(pedido: PedidoDeChamada): T {
  agora = new Date(agora.getTime() + 60_000);
  const r = consultas.chamar(pedido);
  if (!r.ok) throw new Error(JSON.stringify(r.falha));
  return r.dados as T;
}

const relatorio = <T>(r: Record<string, unknown>) =>
  chamar<T>({ operacao: 'consignado.relatorio', relatorio: r } as PedidoDeChamada);

function receber(): void {
  const item = (remessa: string, p: string, qtd: number, preco: string) => ({
    tabela: 'itens_remessa' as const,
    id: idDeTeste(4),
    version: 1,
    deletedAt: null,
    campos: { remessa_id: remessa, produto_id: p, qtd_enviada: qtd, preco_remessa: preco },
  });
  const codigo = (p: string, c: string) => ({
    tabela: 'produto_codigos' as const,
    id: idDeTeste(5),
    version: 1,
    deletedAt: null,
    campos: { produto_id: p, codigo: c, tipo: 'FORNECEDOR', principal: 1, ativo: 1 },
  });
  const prod = (p: string, nome: string) => ({
    tabela: 'produtos' as const,
    id: p,
    version: 1,
    deletedAt: null,
    campos: { nome, ativo: 1 },
  });
  aplicarLote(
    b.db,
    [
      {
        tabela: 'remessas',
        id: maleta,
        version: 1,
        deletedAt: null,
        campos: {
          status: 'ENVIADA',
          enviada_em: '2026-11-01T12:00:00.000Z',
          codigo: '0007',
          praca_nome: 'PONTA PORÃ - MS',
        },
      },
      prod(produto.A, 'Brinco A'),
      prod(produto.B, 'Colar B'),
      prod(produto.C, 'Anel C'),
      prod(produto.D, 'Conjunto D'),
      codigo(produto.A, 'A-1'),
      codigo(produto.B, 'B-1'),
      codigo(produto.C, 'C-1'),
      codigo(produto.D, 'D-1'),
      item(maleta, produto.A, 12, '200.00'),
      item(maleta, produto.B, 10, '187.50'),
      item(maleta, produto.C, 10, '233.75'),
      item(maleta, produto.D, 2, '498.10'),
      {
        tabela: 'clientes',
        id: cliente,
        version: 1,
        deletedAt: null,
        campos: {
          nome: 'Vendedora Ana',
          telefone: '(67) 99999-0000',
          documento: '123.456.789-00',
          endereco_logradouro: 'Rua das Flores',
          endereco_numero: '10',
          endereco_bairro: 'Centro',
          endereco_cidade: 'Ponta Porã',
          endereco_uf: 'MS',
          comissao_percentual: '30.00',
          vendedor_responsavel_id: idDeTeste(9),
        },
      },
      {
        tabela: 'cotacoes',
        id: idDeTeste(11),
        version: 1,
        deletedAt: null,
        campos: {
          data_cotacao: '2026-11-09',
          usd_venda: '5.00000000',
          pyg_venda: '0.00075000',
        },
      },
    ],
    { cursor: 'c-1' },
  );
  b.db
    .prepare("INSERT OR REPLACE INTO estado_sync (chave, valor) VALUES ('vendedor_nome', 'Rosa')")
    .run();
}

beforeEach(() => {
  b = criarBancoDeTeste();
  agora = new Date('2026-11-10T12:00:00.000Z');
  operacoes = new OperacoesLocais({ banco: () => b.db, agora: () => agora });
  consultas = new ConsultasLocais({ banco: () => b.db, operacoes, agora: () => agora });
  receber();
});
afterEach(() => b.fechar());

function pedido(itens: [string, number][]): string {
  chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
  for (const [codigo, qtd] of itens)
    for (let i = 0; i < qtd; i += 1) chamar({ operacao: 'consignado.pedidoBipar', codigo });
  const id = idDeTeste(6);
  chamar({ operacao: 'consignado.pedidoFinalizar', id });
  return id;
}

function acertar(
  notaId: string,
  devolvidos: { produtoId: string; quantidade: number }[],
  comissao: string,
  formas: { forma: 'DINHEIRO' | 'PIX' | 'DEBITO' | 'CREDITO'; valor: string }[],
) {
  // Sem cotação, a comissão é a "abaixo da meta" (o 1.3.0 não manda porcentagem): fixa a da loja neste teste (a mesma na meta, para a cotação não mudar a conta).
  gravarEstado(
    b.db,
    CHAVES.parametros,
    JSON.stringify({
      consignadoMetaGuarani: 700000,
      consignadoComissaoAbaixoDaMeta: Number(comissao),
      consignadoComissaoNaMeta: Number(comissao),
      consignadoDiasDoRetorno: 45,
    }),
  );
  return chamar({
    operacao: 'consignado.acertoSalvar',
    acerto: {
      id: idDeTeste(7),
      reciboId: idDeTeste(8),
      notaId,
      devolvidos: devolvidos.map((d) => ({ remessaId: maleta, ...d })),
      formas,
    },
  });
}

/** A nota 0000063-xx (498,10 pendentes) e a 0000064-xx do exemplo, ainda sem acerto. */
function cenario() {
  chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 63 });
  const antiga = pedido([['D-1', 1]]);
  acertar(antiga, [], '0', []);
  chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
  const nova = pedido([
    ['A-1', 10],
    ['B-1', 8],
    ['C-1', 8],
  ]);
  return { antiga, nova };
}

const devolvidos = () => [
  { produtoId: produto.A, quantidade: 5 },
  { produtoId: produto.B, quantidade: 6 },
  { produtoId: produto.C, quantidade: 4 },
];
const pagos = () => [
  { forma: 'DINHEIRO' as const, valor: exemplos.notaDeExemplo.pago.DINHEIRO },
  { forma: 'PIX' as const, valor: exemplos.notaDeExemplo.pago.PIX },
];

describe('Nota de saída', () => {
  it('tem cabeçalho com Representante e Praça, o cliente, as peças sem Tam./Ton., Itens DX, Total DX, a pendência e a cotação do dia', () => {
    const { nova } = cenario();
    const r = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: nova });
    expect(r.cabecalho.representante).toBe('Rosa');
    expect(r.cabecalho.praca).toBe('PONTA PORÃ - MS');
    expect(r.numero).toBe('0000064-01');
    expect(r.cliente).toMatchObject({
      nome: 'Vendedora Ana',
      documento: '123.456.789-00',
      telefone: '(67) 99999-0000',
    });
    expect(r.cliente.endereco).toBe('Rua das Flores, 10 · Centro · Ponta Porã - MS');
    // O código da maleta é o do servidor (0007), não o M- provisório.
    expect(r.maletas).toEqual(['0007']);
    expect(
      r.linhas
        .map((l) => [l.codigo, l.quantidade, l.preco])
        .sort((x, y) => String(x[0]).localeCompare(String(y[0]))),
    ).toEqual([
      ['A-1', 10, '200.00'],
      ['B-1', 8, '187.50'],
      ['C-1', 8, '233.75'],
    ]);
    // Sem Tam./Ton. no cadastro da peça, a coluna sai vazia (null).
    expect(r.linhas.every((l) => l.tamTon === null)).toBe(true);
    expect(r.itensDx).toBe(exemplos.notaDeExemplo.esperado.itensDx);
    expect(r.totalDx).toEqual({ real: '5370.00', dolar: '1074.00', guarani: '7160000' });
    // Pendência anterior de 498,10 (nota 0000063), também pela cotação do dia dela.
    expect(r.pendenciaAnterior).toEqual({ real: '498.10', dolar: '99.62', guarani: '664133' });
    expect(r.pendencias).toHaveLength(1);
  });

  it('sem cotação, só o Real (a tela escreve "Sem cotação")', () => {
    const { nova } = cenario();
    b.db.prepare('DELETE FROM cotacoes').run();
    const r = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: nova });
    expect(r.totalDx).toEqual({ real: '5370.00', dolar: null, guarani: null });
  });

  it('sem cotação, a linha zerada não mostra Dólar e Guarani em zero (só o Real)', () => {
    const { antiga } = cenario();
    b.db.prepare('DELETE FROM cotacoes').run();
    const r = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: antiga });
    expect(r.pendenciaAnterior).toEqual({ real: '0.00', dolar: null, guarani: null });
  });

  it('é gerada de novo na Reposição, com as peças novas', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
    const id = pedido([['A-1', 2]]);
    chamar({
      operacao: 'consignado.reposicao',
      reposicao: {
        id: idDeTeste(12),
        notaId: id,
        itens: [{ produtoId: produto.B, remessaId: maleta, quantidade: 3 }],
      },
    });
    const r = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: id });
    expect(r.itensDx).toBe(5);
    expect(r.linhas.map((l) => l.codigo)).toEqual(['A-1', 'B-1']);
  });
});

describe('Relatório do acerto', () => {
  it('traz DX, DV e VD, comissão, pendências, total a receber, pago por forma e quanto ficou devendo (nota 0000064-21)', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    const e = exemplos.notaDeExemplo.esperado;
    const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
    expect([r.itensDx, r.itensDv, r.itensVd]).toEqual([e.itensDx, e.itensDv, e.itensVd]);
    expect(r.valorDx.real).toBe(e.valorDx);
    expect(r.valorDv.real).toBe(e.valorDv);
    expect(r.valorVd).toEqual({ real: '2310.00', dolar: '462.00', guarani: '3080000' });
    expect(r.vendaPercentual).toBe(e.vendaPercentual);
    expect(r.comissao.real).toBe(e.comissao);
    expect(r.valorLiquido.real).toBe(e.bruto);
    expect(r.totalPendenciasAnteriores.real).toBe(exemplos.notaDeExemplo.pendenciasAnteriores);
    expect(r.totalAReceber.real).toBe(e.totalAReceber);
    expect(r.pago.map((f) => [f.forma, f.valor.real])).toEqual([
      ['DINHEIRO', exemplos.notaDeExemplo.pago.DINHEIRO],
      ['PIX', exemplos.notaDeExemplo.pago.PIX],
    ]);
    expect(r.totalPago.real).toBe(e.totalPago);
    expect(r.ficouDevendo).toEqual({ real: '345.10', dolar: '69.02', guarani: '460133' });
    expect(r.recibo).toBe('R 0000064-01');
    // Só as peças vendidas (DX − DV) aparecem, com o código da maleta do servidor.
    expect(
      r.vendidas
        .map((l) => [l.codigo, l.quantidade, l.maleta])
        .sort((x, y) => String(x[0]).localeCompare(String(y[0]))),
    ).toEqual([
      ['A-1', 5, '0007'],
      ['B-1', 2, '0007'],
      ['C-1', 4, '0007'],
    ]);
  });

  describe('linha da meta (§23.13, item 6)', () => {
    /** Depois do acerto, a loja volta a valer a meta de verdade: 700.000, 30% abaixo e 40% na meta. */
    function metaDeVerdade() {
      gravarEstado(
        b.db,
        CHAVES.parametros,
        JSON.stringify({
          consignadoMetaGuarani: 700000,
          consignadoComissaoAbaixoDaMeta: 30,
          consignadoComissaoNaMeta: 40,
          consignadoDiasDoRetorno: 45,
        }),
      );
    }

    it('bateu a meta: vendido em Guarani, meta e a comissão de 40%', () => {
      const { nova } = cenario();
      acertar(nova, devolvidos(), '40', pagos());
      metaDeVerdade();
      const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
      expect(r.meta).toEqual({
        vendidoGuarani: '3080000',
        metaGuarani: '700000',
        percentual: '40',
        bateu: true,
        faltouGuarani: null,
        cotacaoData: '2026-11-09',
      });
    });

    it('abaixo da meta: diz quanto faltou', () => {
      const { nova } = cenario();
      b.db.prepare("UPDATE cotacoes SET pyg_venda = '0.00400000'").run();
      acertar(nova, devolvidos(), '30', pagos());
      metaDeVerdade();
      const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
      expect(r.meta).toEqual({
        vendidoGuarani: '577500',
        metaGuarani: '700000',
        percentual: '30',
        bateu: false,
        faltouGuarani: '122500',
        cotacaoData: '2026-11-09',
      });
    });

    it('sem cotação: vale a porcentagem de abaixo da meta, sem valor em Guarani', () => {
      const { nova } = cenario();
      b.db.prepare('DELETE FROM cotacoes').run();
      acertar(nova, devolvidos(), '30', pagos());
      metaDeVerdade();
      const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
      expect(r.meta).toEqual({
        vendidoGuarani: null,
        metaGuarani: '700000',
        percentual: '30',
        bateu: false,
        faltouGuarani: null,
        cotacaoData: null,
      });
    });

    it('sem linha quando a meta mudou e a comissão gravada já não bate com ela', () => {
      const { nova } = cenario();
      acertar(nova, devolvidos(), '35', pagos());
      metaDeVerdade();
      expect(relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova }).meta).toBeNull();
    });
  });

  it('nota ainda sem acerto não tem relatório do acerto, e diz o que fazer', () => {
    const { nova } = cenario();
    const r = consultas.chamar({
      operacao: 'consignado.relatorio',
      relatorio: { tipo: 'ACERTO', notaId: nova },
    });
    expect(r.ok).toBe(false);
    expect(JSON.stringify(r)).toContain('NOTA_SEM_ACERTO');
    expect(JSON.stringify(r)).toContain('Faça a cobrança');
  });
});

describe('Situação do cliente', () => {
  it('mostra o que já vendeu (DX, DV, VD, comissão, líquido, pago) e o que falta com ele, com quanto deve', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    // Uma terceira nota ainda com o cliente.
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 65 });
    pedido([['A-1', 2]]);
    const r = relatorio<SituacaoDoCliente>({ tipo: 'SITUACAO_DO_CLIENTE', clienteId: cliente });
    // "O que já vendeu" lista só as notas Fechadas; a Pendente aparece à parte, com quanto ainda deve (§23.11).
    expect(r.jaVendeu.map((n) => n.situacao)).toEqual(['FECHADO']);
    expect(r.pendentes).toHaveLength(1);
    expect(r.pendentes[0]).toMatchObject({ situacao: 'PENDENTE' });
    expect(r.pendentes[0]?.ficouDevendo.real).toBe('345.10');
    expect(r.pago.real).toBe(r.jaVendeu[0]?.pago.real);
    // Cada nota da lista traz Dólar e Guarani pela cotação do dia do acerto dela.
    expect(r.jaVendeu[0]?.valorVd.dolar).not.toBeNull();
    expect(r.pendentes[0]?.valorVd.guarani).not.toBeNull();
    expect(r.comOCliente).toHaveLength(1);
    expect(r.comOCliente[0]).toMatchObject({ codigo: 'A-1', quantidade: 2, maleta: '0007' });
    expect(r.itensComOCliente).toBe(2);
    expect(r.valorComOCliente.real).toBe('400.00');
    expect(r.deve.real).toBe('345.10');
  });
});

describe('Situação do cliente com pagamento lançado depois do acerto (defeito 5 do relatório 9.10)', () => {
  it('a nota Fechada quitada por Lançar pagamento mostra o pago, não R$ 0,00', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
    const nota = pedido([['A-1', 2]]);
    // Nada devolvido, nada pago no ato: bruto = 400,00 - 30% = 280,00 fica pendente.
    acertar(nota, [], '30', []);
    chamar({
      operacao: 'consignado.pagamento',
      pagamento: {
        id: idDeTeste(11),
        clienteId: cliente,
        formas: [{ forma: 'PIX', valor: '280.00' }],
      },
    });
    const r = relatorio<SituacaoDoCliente>({ tipo: 'SITUACAO_DO_CLIENTE', clienteId: cliente });
    expect(r.jaVendeu).toHaveLength(1);
    expect(r.jaVendeu[0]?.situacao).toBe('FECHADO');
    expect(r.jaVendeu[0]?.pago.real).toBe('280.00');
    expect(r.pago.real).toBe('280.00');
  });
});

describe('Situação do cliente: pago com recibo no acerto e recibo depois (defeito 5)', () => {
  it('pagamento parcial no ato mais recibo depois: cada nota mostra o seu pago, sem contar duas vezes, com Dólar e Guarani', () => {
    const { nova } = cenario();
    // No acerto pagou 1.770,00: quita a nota antiga (498,10) e 1.271,90 da nova (bruto 1.617,00); ficam 345,10.
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    chamar({
      operacao: 'consignado.pagamento',
      pagamento: {
        id: idDeTeste(11),
        clienteId: cliente,
        formas: [{ forma: 'PIX', valor: '345.10' }],
      },
    });
    const r = relatorio<SituacaoDoCliente>({ tipo: 'SITUACAO_DO_CLIENTE', clienteId: cliente });
    expect(r.jaVendeu.map((n) => n.situacao)).toEqual(['FECHADO', 'FECHADO']);
    const antiga = r.jaVendeu.find((n) => n.valorVd.real === '498.10');
    const nova2 = r.jaVendeu.find((n) => n.valorVd.real === '2310.00');
    expect(antiga?.pago).toEqual({ real: '498.10', dolar: '99.62', guarani: '664133' });
    expect(nova2?.pago).toEqual({ real: '1617.00', dolar: '323.40', guarani: '2156000' });
    // 498,10 + 1.617,00 = 2.115,10 (o total a receber); o recibo do acerto não conta em dobro.
    expect(r.pago.real).toBe('2115.10');
    expect(r.pago.dolar).toBe('423.02');
    expect(r.pendentes).toEqual([]);
  });

  it('estornar o recibo lançado depois: a nota volta a "O que falta" e o pago some da linha e do total', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
    const nota = pedido([['A-1', 2]]);
    acertar(nota, [], '30', []);
    const pago = chamar<{ cliente: { pagamentos: { reciboId: string }[] } }>({
      operacao: 'consignado.pagamento',
      pagamento: {
        id: idDeTeste(11),
        clienteId: cliente,
        formas: [{ forma: 'PIX', valor: '280.00' }],
      },
    });
    chamar({
      operacao: 'consignado.estornarRecibo',
      id: idDeTeste(12),
      reciboId: pago.cliente.pagamentos[0]?.reciboId as string,
      motivo: 'Pix lançado errado',
    });
    const r = relatorio<SituacaoDoCliente>({ tipo: 'SITUACAO_DO_CLIENTE', clienteId: cliente });
    expect(r.jaVendeu).toEqual([]);
    expect(r.pago.real).toBe('0.00');
    expect(r.pendentes[0]?.ficouDevendo.real).toBe('280.00');
  });
});

describe('Peças vendidas por cliente', () => {
  it('lista cada peça vendida do período, com quantidade e valor, e o total', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    const dia = '2026-11-10';
    const r = relatorio<PecasVendidasPorCliente>({
      tipo: 'PECAS_VENDIDAS',
      clienteId: cliente,
      de: dia,
      ate: dia,
    });
    // As duas notas foram acertadas em 10/11/2026: D-1 (1 peça) e as do exemplo (11 peças).
    expect(r.itensVd).toBe(12);
    expect(r.valorVd.real).toBe('2808.10');
    expect(r.linhas.find((l) => l.codigo === 'D-1')).toMatchObject({
      quantidade: 1,
      valor: { real: '498.10', dolar: '99.62', guarani: '664133' },
    });
    const fora = relatorio<PecasVendidasPorCliente>({
      tipo: 'PECAS_VENDIDAS',
      clienteId: cliente,
      de: '2026-10-01',
      ate: '2026-10-31',
    });
    expect(fora.linhas).toEqual([]);
    expect(fora.valorVd.real).toBe('0.00');
  });
});

describe('Totais da semana', () => {
  it('repete a aba Totais, com DX, DV e VD em valores e itens, comissão, líquido, recebido por forma e pendente', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    const tela = chamar<PainelDaSemana>({ operacao: 'consignado.painel' }).totais;
    const r = relatorio<TotaisDaSemanaNoRelatorio>({ tipo: 'TOTAIS_DA_SEMANA' });
    expect(r.semana).toBe(64);
    expect([r.itensDx, r.itensDv, r.itensVd]).toEqual([tela.itensDx, tela.itensDv, tela.itensVd]);
    expect(r.valorDx.real).toBe(tela.valorDx);
    expect(r.valorDv.real).toBe(tela.valorDv);
    expect(r.valorVd).toEqual({ real: '2310.00', dolar: '462.00', guarani: '3080000' });
    expect(r.comissao.real).toBe(tela.comissao);
    expect(r.valorLiquido.real).toBe(tela.liquido);
    expect(r.recebido.real).toBe(tela.recebido);
    expect(r.recebidoPorForma.map((f) => f.valor.real)).toEqual([
      tela.recebidoPorForma.DINHEIRO,
      tela.recebidoPorForma.PIX,
      tela.recebidoPorForma.DEBITO,
      tela.recebidoPorForma.CREDITO,
    ]);
    expect(r.pendente.real).toBe(tela.pendente);
  });

  it('conta quantas notas bateram a meta, só entre as notas acertadas', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), '40', pagos());
    const r = relatorio<TotaisDaSemanaNoRelatorio>({ tipo: 'TOTAIS_DA_SEMANA' });
    expect([r.notasQueBateramAMeta, r.notasAcertadas]).toEqual([1, 1]);
  });

  it('sem cotação a nota acertada conta como não bateu', () => {
    const { nova } = cenario();
    b.db.prepare('DELETE FROM cotacoes').run();
    acertar(nova, devolvidos(), '30', pagos());
    const r = relatorio<TotaisDaSemanaNoRelatorio>({ tipo: 'TOTAIS_DA_SEMANA' });
    expect([r.notasQueBateramAMeta, r.notasAcertadas]).toEqual([0, 1]);
  });
});

describe('Situação da maleta', () => {
  it('mostra as peças na maleta, com clientes (com quem) e vendidas, e o DX, DV e VD da maleta', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 65 });
    pedido([['A-1', 2]]);
    const r = relatorio<SituacaoDaMaleta>({ tipo: 'SITUACAO_DA_MALETA', remessaId: maleta });
    expect(r.maleta).toBe('0007');
    const a = r.pecas.find((p) => p.codigo === 'A-1');
    expect(a).toMatchObject({ enviada: 12, comClientes: 2, vendida: 5 });
    expect(a?.comQuem).toEqual(['Vendedora Ana (0000065-01): 2']);
    expect(r.totalEnviada).toBe(34);
    // DX = 1 + 26 + 2 itens; DV = 15; VD = 12 (as duas notas acertadas).
    expect(r.itensDx).toBe(29);
    expect(r.itensDv).toBe(15);
    expect(r.itensVd).toBe(12);
    expect(r.valorComClientes.real).toBe('400.00');
  });
});

describe('o pedido de relatório na ponte', () => {
  it('só passa o que bate com o formato', () => {
    const v = (relatorio: unknown) =>
      validarPedidoDoConsignado('consignado.relatorio', { relatorio });
    expect(v({ tipo: 'NOTA_DE_SAIDA', notaId: idDeTeste(6) })).not.toBeNull();
    expect(v({ tipo: 'NOTA_DE_SAIDA', notaId: 'nao-e-uuid' })).toBeNull();
    expect(v({ tipo: 'TOTAIS_DA_SEMANA' })).not.toBeNull();
    expect(
      v({ tipo: 'PECAS_VENDIDAS', clienteId: cliente, de: '2026-11-02', ate: '2026-11-01' }),
    ).toBeNull();
    expect(
      v({ tipo: 'PECAS_VENDIDAS', clienteId: cliente, de: '2026-11-01', ate: '2026-11-02' }),
    ).not.toBeNull();
    expect(v({ tipo: 'OUTRO' })).toBeNull();
    expect(v('texto')).toBeNull();
  });

  it('o relatório monta sem internet e sem tocar na outbox (só lê)', () => {
    const { nova } = cenario();
    const antes = b.db.prepare('SELECT count(*) AS n FROM outbox').get() as { n: number };
    relatorio({ tipo: 'NOTA_DE_SAIDA', notaId: nova });
    const depois = b.db.prepare('SELECT count(*) AS n FROM outbox').get() as { n: number };
    expect(depois.n).toBe(antes.n);
    // Nenhum dado de custo ou de outro representante: o relatório é montado só das tabelas locais.
    expect(JSON.stringify(relatorio({ tipo: 'NOTA_DE_SAIDA', notaId: nova }))).not.toMatch(
      /custo|fornecedor/i,
    );
  });
});

describe('os totais do relatório repetem a nota da tela', () => {
  it('o total DX da nota de saída é o da tela da nota', () => {
    const { nova } = cenario();
    const tela = chamar<DetalheDaNota>({ operacao: 'consignado.nota', id: nova });
    const r = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: nova });
    expect(r.totalDx.real).toBe(tela.valorDx);
  });
});

describe('código da maleta e praça (V053 do servidor, migração local 10)', () => {
  it('a tela mostra o código do servidor no lugar do M- provisório, e a Pesquisar e a Cobrança o aceitam', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 7 });
    pedido([['A-1', 1]]);
    const maletas = chamar<{ id: string; codigo: string }[]>({ operacao: 'consignado.maletas' });
    expect(maletas.find((m) => m.id === maleta)?.codigo).toBe('0007');
    const pesquisa = chamar<{ notas: { maletas: string[] }[]; maletas: { codigo: string }[] }>({
      operacao: 'consignado.pesquisar',
      texto: '0007',
    });
    expect(pesquisa.maletas.map((m) => m.codigo)).toEqual(['0007']);
    expect(pesquisa.notas[0]?.maletas).toEqual(['0007']);
    const cobranca = chamar<unknown[]>({
      operacao: 'consignado.notas',
      texto: '0007',
      soEmAberto: true,
    });
    expect(cobranca).toHaveLength(1);
  });

  it('o cabeçalho da tela principal leva a praça do representante', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 7 });
    const painel = chamar<PainelDaSemana>({ operacao: 'consignado.painel' });
    expect(painel.praca).toBe('PONTA PORÃ - MS');
    expect(painel.representante).toBe('Rosa');
  });

  it('maleta que ainda não trouxe o código usa o provisório', () => {
    b.db.prepare('UPDATE remessas SET codigo = NULL, praca_nome = NULL').run();
    const maletas = chamar<{ codigo: string }[]>({ operacao: 'consignado.maletas' });
    expect(maletas[0]?.codigo).toMatch(/^M-[0-9A-F]{6}$/);
    expect(chamar<PainelDaSemana>({ operacao: 'consignado.painel' }).praca).toBeNull();
  });
});

describe('Dólar e Guarani em cada linha (tarefa 9.8, §23.11)', () => {
  it('cada linha usa a cotação do dia do seu lançamento; a linha de outro dia usa a outra cotação', () => {
    const { nova } = cenario();
    // Uma cotação de outro dia (US$ 1 = R$ 10,00; Gs. 1 = R$ 0,0005), depois da primeira.
    aplicarLote(
      b.db,
      [
        {
          tabela: 'cotacoes',
          id: idDeTeste(11),
          version: 1,
          deletedAt: null,
          campos: { data_cotacao: '2026-11-12', usd_venda: '10.00000000', pyg_venda: '0.00050000' },
        },
      ],
      { cursor: 'c-9' },
    );
    // Acerto no dia 12 (a Dt. Acerto passa a ser a data do acerto e a cotação desse dia).
    agora = new Date('2026-11-12T15:00:00.000Z');
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
    const linhaA = r.vendidas.find((l) => l.codigo === 'A-1');
    // 5 peças de 200,00 = 1000,00: Dólar a 10,00 e Guarani a 0,0005.
    expect(linhaA?.valor).toEqual({ real: '1000.00', dolar: '100.00', guarani: '2000000' });
    // A nota de saída (saída em 10/11) continua pela cotação do dia 9.
    const saida = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: nova });
    expect(saida.linhas.find((l) => l.codigo === 'A-1')?.subTotal.dolar).toBe('400.00');
  });

  it('sem cotação para o dia, a linha sai só em Real (Dólar e Guarani nulos)', () => {
    const { nova } = cenario();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    b.db.prepare('DELETE FROM cotacoes').run();
    const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
    expect(r.vendidas[0]?.valor.dolar).toBeNull();
    expect(r.vendidas[0]?.valor.guarani).toBeNull();
    expect(r.vendidas[0]?.valor.real).not.toBe('');
  });
});

describe('Tam./Ton. nos relatórios (tarefa 9.8)', () => {
  it('a Nota de saída e o Relatório do acerto trazem o Tam./Ton. de cada peça (vazio quando não tem)', () => {
    const { nova } = cenario();
    aplicarLote(
      b.db,
      [
        {
          tabela: 'produtos',
          id: produto.A,
          version: 2,
          deletedAt: null,
          campos: { nome: 'Brinco A', ativo: 1, tam_ton: 'Dourado' },
        },
      ],
      { cursor: 'c-9' },
    );
    const saida = relatorio<NotaDeSaida>({ tipo: 'NOTA_DE_SAIDA', notaId: nova });
    expect(saida.linhas.find((l) => l.codigo === 'A-1')?.tamTon).toBe('Dourado');
    expect(saida.linhas.find((l) => l.codigo === 'B-1')?.tamTon).toBeNull();
    acertar(nova, devolvidos(), exemplos.notaDeExemplo.comissaoPercentual, pagos());
    const r = relatorio<RelatorioDoAcerto>({ tipo: 'ACERTO', notaId: nova });
    expect(r.vendidas.find((l) => l.codigo === 'A-1')?.tamTon).toBe('Dourado');
    expect(r.vendidas.find((l) => l.codigo === 'C-1')?.tamTon).toBeNull();
  });
});
