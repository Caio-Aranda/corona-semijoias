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
  return chamar({
    operacao: 'consignado.acertoSalvar',
    acerto: {
      id: idDeTeste(7),
      reciboId: idDeTeste(8),
      notaId,
      comissaoPercentual: comissao,
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
    // A coluna Tam./Ton. não existe (o produto não tem esse campo).
    expect(JSON.stringify(r)).not.toMatch(/tamanho|tom\b|ton\b/i);
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
    expect(r.recibo).toBe('0000064-02');
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
    expect(r.jaVendeu.map((n) => n.situacao).sort()).toEqual(['FECHADO', 'PENDENTE']);
    // Itens DX/DV/VD das duas notas acertadas: 1+26, 0+15, 1+11.
    expect([r.itensDx, r.itensDv, r.itensVd]).toEqual([27, 15, 12]);
    expect(r.valorVd.real).toBe('2808.10');
    expect(r.comissao.real).toBe('693.00');
    expect(r.pago.real).toBe('1770.00');
    expect(r.comOCliente).toHaveLength(1);
    expect(r.comOCliente[0]).toMatchObject({ codigo: 'A-1', quantidade: 2, maleta: '0007' });
    expect(r.itensComOCliente).toBe(2);
    expect(r.valorComOCliente.real).toBe('400.00');
    expect(r.deve.real).toBe('345.10');
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
      valor: '498.10',
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
