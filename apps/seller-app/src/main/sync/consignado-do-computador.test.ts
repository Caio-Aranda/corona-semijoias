import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { EXEMPLOS_DO_CONSIGNADO as exemplos } from '@corona/shared/exemplos-do-consignado';
import type {
  ClienteCompleto,
  DetalheDaNota,
  MaletaDoConsignado,
  PainelDaSemana,
  PedidoEmMontagem,
  PrevisaoDoAcerto,
} from '../../shared/dados-do-consignado';
import type { PedidoDeChamada } from '../../shared/operacoes';
import { ConsultasLocais } from '../local/consultas-locais';
import { aplicarLote } from './aplicador-do-pull';
import { desfazerOperacao } from './outbox';
import { ErroDaOperacao, OperacoesLocais } from './operacoes-locais';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';

/**
 * O motor local do consignado ao cliente (tarefa 9.2; ADR-0016): nota, acerto, pagamento, estorno, saldo da maleta e a
 * outbox. A nota 0000064-21 e a pendência da 0000063-32 vêm do MESMO arquivo de exemplos que o servidor lê
 * (`packages/shared/exemplos/consignado-acerto.json`): as duas contas não podem se afastar.
 */
let b: BancoDeTeste;
let agora: Date;
let consultas: ConsultasLocais;
let operacoes: OperacoesLocais;

const maleta = idDeTeste(1);
const outraMaleta = idDeTeste(1);
const cliente = idDeTeste(3);
const produto = { A: idDeTeste(2), B: idDeTeste(2), C: idDeTeste(2), D: idDeTeste(2) };

function chamar<T>(pedido: PedidoDeChamada): T {
  agora = new Date(agora.getTime() + 60_000);
  const r = consultas.chamar(pedido);
  if (!r.ok) {
    const f = r.falha;
    throw new ErroDaOperacao(
      ((f.tipo === 'recusada' ? f.codigo : undefined) ?? 'SEM_SALDO') as never,
      undefined,
      f.tipo === 'recusada' ? f.mensagem : undefined,
    );
  }
  return r.dados as T;
}

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
        campos: { status: 'ENVIADA', enviada_em: '2026-11-01T12:00:00.000Z' },
      },
      {
        tabela: 'remessas',
        id: outraMaleta,
        version: 1,
        deletedAt: null,
        campos: { status: 'ENVIADA', enviada_em: '2026-11-02T12:00:00.000Z' },
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
      // O mesmo código D-1 também está na outra maleta, para o aviso "está na maleta X".
      item(outraMaleta, produto.B, 5, '187.50'),
      {
        tabela: 'clientes',
        id: cliente,
        version: 1,
        deletedAt: null,
        campos: {
          nome: 'Vendedora Ana',
          telefone: '67999990000',
          comissao_percentual: '30.00',
          vendedor_responsavel_id: idDeTeste(9),
        },
      },
    ],
    { cursor: 'c-1' },
  );
}

beforeEach(() => {
  b = criarBancoDeTeste();
  agora = new Date('2026-11-10T12:00:00.000Z');
  operacoes = new OperacoesLocais({ banco: () => b.db, agora: () => agora });
  consultas = new ConsultasLocais({ banco: () => b.db, operacoes, agora: () => agora });
  receber();
});
afterEach(() => b.fechar());

/** Abre a semana `numero`, faz um pedido do cliente e finaliza; devolve o id da nota. */
function pedido(itens: [string, number][], remessa = maleta): string {
  chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: remessa });
  for (const [codigo, qtd] of itens)
    for (let i = 0; i < qtd; i += 1) {
      const r = chamar<{ resultado: string }>({ operacao: 'consignado.pedidoBipar', codigo });
      expect(r.resultado).toBe('ADICIONADA');
    }
  const id = idDeTeste(6);
  chamar({ operacao: 'consignado.pedidoFinalizar', id });
  return id;
}

function acertar(
  notaId: string,
  devolvidos: { produtoId: string; quantidade: number; remessaId?: string }[],
  comissao: string,
  formas: { forma: 'DINHEIRO' | 'PIX' | 'DEBITO' | 'CREDITO'; valor: string }[],
) {
  return chamar<{ situacao: string; nota: DetalheDaNota }>({
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

const painel = () => chamar<PainelDaSemana>({ operacao: 'consignado.painel' });
const clienteVisto = () => chamar<ClienteCompleto>({ operacao: 'consignado.cliente', id: cliente });
const nota = (id: string) => chamar<DetalheDaNota>({ operacao: 'consignado.nota', id });
const maletas = () => chamar<MaletaDoConsignado[]>({ operacao: 'consignado.maletas' });
const saldoDe = (m: MaletaDoConsignado[], id: string, p: string) =>
  m.find((x) => x.id === id)?.pecas.find((x) => x.produtoId === p);

describe('semana e Novo Pedido', () => {
  it('sem semana aberta o pedido não finaliza; Nova semana abre e fecha a anterior sem mudar nada no lugar', () => {
    expect(painel().semSemana).toBe(true);
    chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
    chamar({ operacao: 'consignado.pedidoBipar', codigo: 'A-1' });
    expect(() => chamar({ operacao: 'consignado.pedidoFinalizar', id: idDeTeste(6) })).toThrowError(
      /semana/i,
    );

    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
    const id = idDeTeste(6);
    const r = chamar<{ numeroDaNota: string }>({ operacao: 'consignado.pedidoFinalizar', id });
    expect(r.numeroDaNota).toBe('0000064-01');
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10) });
    const p = painel();
    expect(p.semana?.numero).toBe(65);
    expect(p.podeNovoPedido).toBe(true);
    const antiga = chamar<PainelDaSemana>({
      operacao: 'consignado.painel',
      semanaId: p.semanas[1]?.id as string,
    });
    expect(antiga.semana?.situacao).toBe('FECHADA');
    expect(antiga.podeNovoPedido).toBe(false);
    expect(antiga.pedidos).toHaveLength(1);
  });

  it('bipe: código fora da maleta, em outra maleta, sem saldo; mesma linha soma 1; a nota nasce imutável e a peça sai da maleta', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
    expect(
      chamar<{ resultado: string }>({ operacao: 'consignado.pedidoBipar', codigo: 'X-9' })
        .resultado,
    ).toBe('FORA_DA_MALETA');
    // B existe nas duas maletas: lido na maleta escolhida, entra; já o produto que só existe na outra, avisa.
    expect(
      chamar<{ resultado: string }>({ operacao: 'consignado.pedidoBipar', codigo: 'b-1 ' })
        .resultado,
    ).toBe('ADICIONADA');
    const dois = chamar<{ pedido: PedidoEmMontagem }>({
      operacao: 'consignado.pedidoBipar',
      codigo: 'B-1',
    });
    expect(dois.pedido.itens).toHaveLength(1);
    expect(dois.pedido.itens[0]?.quantidade).toBe(2);
    expect(dois.pedido.totalDeItens).toBe(2);
    expect(dois.pedido.totalDx).toBe('375.00');
    // Trocar a maleta e continuar bipando põe a peça de OUTRA maleta na mesma nota (outra linha).
    chamar({ operacao: 'consignado.pedidoDefinir', remessaId: outraMaleta });
    const tres = chamar<{ pedido: PedidoEmMontagem }>({
      operacao: 'consignado.pedidoBipar',
      codigo: 'B-1',
    });
    expect(tres.pedido.itens).toHaveLength(2);
    // Acima do disponível da maleta: nada é adicionado.
    chamar({ operacao: 'consignado.pedidoDefinir', remessaId: maleta });
    // Há 2 peças D na maleta: as duas primeiras entram, a terceira não.
    for (let i = 0; i < 2; i += 1)
      expect(
        chamar<{ resultado: string }>({ operacao: 'consignado.pedidoBipar', codigo: 'D-1' })
          .resultado,
      ).toBe('ADICIONADA');
    const alem = chamar<{ resultado: string; disponivel?: number }>({
      operacao: 'consignado.pedidoBipar',
      codigo: 'D-1',
    });
    expect(alem.resultado).toBe('SEM_SALDO');
    expect(alem.disponivel).toBe(2);
  });

  it('o código só da outra maleta diz em qual maleta está', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    // Só a outra maleta tem este produto novo.
    const p = idDeTeste(2);
    aplicarLote(
      b.db,
      [
        {
          tabela: 'produtos',
          id: p,
          version: 1,
          deletedAt: null,
          campos: { nome: 'Pulseira E', ativo: 1 },
        },
        {
          tabela: 'produto_codigos',
          id: idDeTeste(5),
          version: 1,
          deletedAt: null,
          campos: { produto_id: p, codigo: 'E-1', tipo: 'FORNECEDOR', principal: 1, ativo: 1 },
        },
        {
          tabela: 'itens_remessa',
          id: idDeTeste(4),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: outraMaleta,
            produto_id: p,
            qtd_enviada: 3,
            preco_remessa: '50.00',
          },
        },
      ],
      { cursor: 'c-2' },
    );
    chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
    const r = chamar<{ resultado: string; maleta?: string }>({
      operacao: 'consignado.pedidoBipar',
      codigo: 'E-1',
    });
    expect(r.resultado).toBe('EM_OUTRA_MALETA');
    expect(r.maleta).toMatch(/^M-[0-9A-F]{6}$/);
    expect(chamar<PedidoEmMontagem>({ operacao: 'consignado.pedidoVer' }).itens).toHaveLength(0);
  });
});

describe('a nota 0000064-21 e a pendência da 0000063-32 (mesmo arquivo de exemplos do servidor)', () => {
  function cenario() {
    const n = exemplos.notaDeExemplo;
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 63 });
    const antiga = pedido([['D-1', 1]]);
    // Nota antiga: tudo vendido, sem comissão e nada pago: o bruto (498,10) fica pendente.
    const a63 = acertar(antiga, [], '0', []);
    expect(a63.situacao).toBe('PENDENTE');
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
    const nova = pedido([
      ['A-1', 10],
      ['B-1', 8],
      ['C-1', 8],
    ]);
    return { n, antiga, nova };
  }

  it('prévia e acerto saem número por número, e a nota antiga passa a Fechado sozinha', () => {
    const { n, antiga, nova } = cenario();
    expect(clienteVisto().pendencia).toBe(n.pendenciasAnteriores);
    const devolvidos = [
      { produtoId: produto.A, remessaId: maleta, quantidade: 5 },
      { produtoId: produto.B, remessaId: maleta, quantidade: 6 },
      { produtoId: produto.C, remessaId: maleta, quantidade: 4 },
    ];
    const formas = [
      { forma: 'DINHEIRO' as const, valor: n.pago.DINHEIRO },
      { forma: 'PIX' as const, valor: n.pago.PIX },
    ];
    const previa = chamar<PrevisaoDoAcerto>({
      operacao: 'consignado.acertoPrevia',
      acerto: { notaId: nova, devolvidos, comissaoPercentual: n.comissaoPercentual, formas },
    });
    const e = n.esperado;
    expect(previa.itensDx).toBe(e.itensDx);
    expect(previa.itensDv).toBe(e.itensDv);
    expect(previa.itensVd).toBe(e.itensVd);
    expect(previa.valorDx).toBe(e.valorDx);
    expect(previa.valorDv).toBe(e.valorDv);
    expect(previa.valorVd).toBe(e.valorVd);
    expect(previa.vendaPercentual).toBe(e.vendaPercentual);
    expect(previa.comissao).toBe(e.comissao);
    expect(previa.bruto).toBe(e.bruto);
    expect(previa.pendenciasAnteriores).toBe(n.pendenciasAnteriores);
    expect(previa.totalAReceber).toBe(e.totalAReceber);
    expect(previa.totalPago).toBe(e.totalPago);
    expect(previa.ficouDevendo).toBe(e.ficouDevendo);

    const salvo = acertar(nova, devolvidos, n.comissaoPercentual, formas);
    expect(salvo.situacao).toBe('PENDENTE');
    expect(nota(antiga).situacao).toBe('FECHADO');
    expect(nota(nova).situacao).toBe('PENDENTE');
    expect(nota(nova).saldoDaNota).toBe(e.ficouDevendo);
    expect(clienteVisto().pendencia).toBe(e.ficouDevendo);
    // O recibo é da semana do pagamento e tem contador próprio (nota 01 e recibo R 01 na semana 64).
    expect(nota(nova).acerto?.recibo?.numero).toBe('R 0000064-01');
    // Peças que voltaram ficam disponíveis na maleta; as vendidas saem do estoque da maleta.
    const a = saldoDe(maletas(), maleta, produto.A);
    expect(a?.enviada).toBe(12);
    expect(a?.disponivel).toBe(12 - 10 + 5);
    expect(a?.vendida).toBe(5);
    expect(a?.comClientes).toBe(0);
    // Os totais da semana: DX, DV, VD, comissão, líquido, recebido por forma e pendente.
    const t = painel().totais;
    expect(t.valorVd).toBe(e.valorVd);
    expect(t.comissao).toBe(e.comissao);
    expect(t.liquido).toBe(e.bruto);
    expect(t.recebidoPorForma.DINHEIRO).toBe(n.pago.DINHEIRO);
    expect(t.recebidoPorForma.PIX).toBe(n.pago.PIX);
    expect(t.pendente).toBe(e.ficouDevendo);
  });

  it('uma sobra que já estava "a aplicar" (chegou pelo pull) abate a pendência antes do acerto, como o servidor', () => {
    const { antiga, nova } = cenario();
    const semana = chamar<PainelDaSemana>({ operacao: 'consignado.painel' }).semana?.id as string;
    // Um recibo do cliente de 50,00 sem aplicação nenhuma: o servidor o põe no débito mais antigo no próximo acerto.
    aplicarLote(
      b.db,
      [
        {
          tabela: 'recibos_consignado',
          id: idDeTeste(12),
          version: 1,
          deletedAt: null,
          campos: {
            cliente_id: cliente,
            semana_id: semana,
            sequencia: 90,
            pago_em: '2026-11-05T12:00:00.000Z',
            total: '50.00',
          },
        },
      ],
      { cursor: 'c-3' },
    );
    const feito = acertar(nova, [], '30', []);
    expect(feito.situacao).toBe('PENDENTE');
    // A pendência antiga (498,10) já saiu 50,00 sem esperar o servidor.
    expect(nota(antiga).saldoDaNota).toBe('448.10');
    // E o que o computador "viu" no acerto já soma só as pendências que restavam (como o servidor, que reaplica antes).
    const op = b.db
      .prepare("SELECT dados FROM outbox WHERE tipo = 'ACERTO_NOTA' ORDER BY rowid DESC")
      .get() as {
      dados: string;
    };
    const dados = JSON.parse(op.dados) as { visto: { pendenciasAnteriores: string } };
    expect(dados.visto.pendenciasAnteriores).toBe('448.10');
  });

  it('a outbox leva os tipos novos no formato 1, com o que o computador viu', () => {
    const { n, nova } = cenario();
    acertar(
      nova,
      [
        { produtoId: produto.A, remessaId: maleta, quantidade: 5 },
        { produtoId: produto.B, remessaId: maleta, quantidade: 6 },
        { produtoId: produto.C, remessaId: maleta, quantidade: 4 },
      ],
      n.comissaoPercentual,
      [
        { forma: 'DINHEIRO', valor: n.pago.DINHEIRO },
        { forma: 'PIX', valor: n.pago.PIX },
      ],
    );
    const linhas = b.db.prepare('SELECT tipo, formato, dados FROM outbox ORDER BY ordem').all() as {
      tipo: string;
      formato: number;
      dados: string;
    }[];
    expect(linhas.map((l) => l.tipo)).toEqual([
      'SEMANA_NOVA',
      'NOTA_CONSIGNADO',
      'ACERTO_NOTA',
      'SEMANA_NOVA',
      'NOTA_CONSIGNADO',
      'ACERTO_NOTA',
    ]);
    expect(linhas.every((l) => l.formato === 1)).toBe(true);
    const acerto = JSON.parse(linhas[5]?.dados ?? '{}') as {
      visto: Record<string, string>;
      recibo: { formas: unknown[]; sequencia: number };
      devolvidos: unknown[];
      comissaoPercentual: string;
    };
    expect(acerto.visto['ficouDevendo']).toBe(n.esperado.ficouDevendo);
    expect(acerto.visto['pendenciasAnteriores']).toBe(n.pendenciasAnteriores);
    expect(acerto.comissaoPercentual).toBe('30.00');
    expect(acerto.devolvidos).toHaveLength(3);
    expect(acerto.recibo.formas).toHaveLength(2);
    // Dinheiro sempre com duas casas, em texto (nunca ponto flutuante).
    expect(linhas[4]?.dados).toContain('"totalDxVisto":"5370.00"');
  });

  it('recusas: devolver mais que o levado, comissão fora de 0 a 40, pagar mais que o devido, segundo acerto', () => {
    const { nova } = cenario();
    const base = {
      notaId: nova,
      formas: [] as { forma: 'DINHEIRO'; valor: string }[],
      comissaoPercentual: '30',
    };
    expect(() =>
      chamar({
        operacao: 'consignado.acertoPrevia',
        acerto: {
          ...base,
          devolvidos: [{ produtoId: produto.A, remessaId: maleta, quantidade: 11 }],
        },
      }),
    ).toThrowError(/devolução passa/i);
    expect(() =>
      chamar({
        operacao: 'consignado.acertoPrevia',
        acerto: { ...base, comissaoPercentual: '40.01', devolvidos: [] },
      }),
    ).toThrowError(/entre 0% e 40%/);
    expect(() =>
      chamar({
        operacao: 'consignado.acertoPrevia',
        acerto: { ...base, comissaoPercentual: '-1', devolvidos: [] },
      }),
    ).toThrowError(/entre 0% e 40%/);
    const muito = [{ forma: 'DINHEIRO' as const, valor: '99999.00' }];
    expect(() => acertar(nova, [], '30', muito)).toThrowError(/passa do que o cliente deve/);
    acertar(nova, [], '30', []);
    expect(() => acertar(nova, [], '30', [])).toThrowError(/já foi acertada/);
  });

  it('Lançar pagamento abate o mais antigo e não passa do devido; estornar reabre a pendência', () => {
    const { antiga, nova } = cenario();
    // Nenhum pagamento no acerto: o cliente deve 498,10 (63) + o bruto da nova (nada devolvido, 30%).
    acertar(nova, [], '30', []);
    const antes = Number(clienteVisto().pendencia);
    expect(antes).toBeGreaterThan(498.1);
    const pagar = (valor: string) =>
      chamar<{ cliente: ClienteCompleto }>({
        operacao: 'consignado.pagamento',
        pagamento: { id: idDeTeste(11), clienteId: cliente, formas: [{ forma: 'PIX', valor }] },
      });
    const r = pagar('498.10');
    expect(r.cliente.debitos).toHaveLength(1); // a nota mais antiga quitou primeiro
    expect(nota(antiga).situacao).toBe('FECHADO');
    expect(nota(nova).situacao).toBe('PENDENTE');
    expect(() => pagar('99999.00')).toThrowError(/passa do que o cliente deve/);
    // Estornar o pagamento lançado reabre a pendência.
    const reciboId = r.cliente.pagamentos[0]?.reciboId as string;
    expect(r.cliente.pagamentos[0]?.podeEstornar).toBe(true);
    const depois = chamar<{ cliente: ClienteCompleto }>({
      operacao: 'consignado.estornarRecibo',
      id: idDeTeste(12),
      reciboId,
      motivo: 'Pix lançado errado',
    });
    expect(Number(depois.cliente.pendencia)).toBeCloseTo(antes, 2);
    expect(nota(antiga).situacao).toBe('PENDENTE');
    expect(() =>
      chamar({
        operacao: 'consignado.estornarRecibo',
        id: idDeTeste(12),
        reciboId,
        motivo: 'de novo',
      }),
    ).toThrowError(/já foi estornado/);
    expect(() =>
      chamar({ operacao: 'consignado.estornarRecibo', id: idDeTeste(12), reciboId, motivo: ' ' }),
    ).toThrowError(/motivo/i);
  });

  it('Lançar pagamento com data: um dia que já passou vale; depois de hoje é recusado', () => {
    const { nova } = cenario();
    acertar(nova, [], '30', []);
    const pagar = (data: string) =>
      chamar<{ cliente: ClienteCompleto }>({
        operacao: 'consignado.pagamento',
        pagamento: {
          id: idDeTeste(13),
          clienteId: cliente,
          formas: [{ forma: 'PIX', valor: '10.00' }],
          data,
        },
      });
    expect(() => pagar('2026-11-20')).toThrowError(/depois de hoje/);
    const r = pagar('2026-11-05');
    expect(r.cliente.pagamentos[0]?.pagoEm?.slice(0, 10)).toBe('2026-11-05');
  });

  it('estornar o acerto devolve a nota a Em aberto, desfaz os retornos da maleta e reabre o que o recibo dele quitou', () => {
    const { n, antiga, nova } = cenario();
    const devolvidos = [{ produtoId: produto.A, remessaId: maleta, quantidade: 5 }];
    const feito = acertar(nova, devolvidos, n.comissaoPercentual, [
      { forma: 'DINHEIRO', valor: '500.00' },
    ]);
    expect(feito.nota.acerto).not.toBeNull();
    expect(nota(antiga).situacao).toBe('FECHADO');
    const acertoId = feito.nota.acerto?.id as string;
    chamar({
      operacao: 'consignado.estornarAcerto',
      id: idDeTeste(13),
      acertoId,
      motivo: 'Contei errado',
    });
    expect(nota(nova).situacao).toBe('EM_ABERTO');
    expect(nota(nova).aceitaMudanca).toBe(true);
    expect(nota(antiga).situacao).toBe('PENDENTE');
    expect(clienteVisto().pendencia).toBe(n.pendenciasAnteriores);
    const a = saldoDe(maletas(), maleta, produto.A);
    expect(a?.disponivel).toBe(12 - 10);
    expect(a?.comClientes).toBe(10);
    expect(() =>
      chamar({
        operacao: 'consignado.estornarAcerto',
        id: idDeTeste(13),
        acertoId,
        motivo: 'de novo',
      }),
    ).toThrowError(/já foi estornado/);
  });

  it('operação recusada pelo servidor volta o espelho: sem acerto, sem recibo, o saldo da maleta volta', () => {
    const { nova } = cenario();
    const feito = acertar(
      nova,
      [{ produtoId: produto.A, remessaId: maleta, quantidade: 5 }],
      '30',
      [{ forma: 'PIX', valor: '100.00' }],
    );
    expect(feito.nota.acerto).not.toBeNull();
    const operacaoDoAcerto = (
      b.db
        .prepare(
          "SELECT operacao_id FROM outbox WHERE tipo = 'ACERTO_NOTA' ORDER BY ordem DESC LIMIT 1",
        )
        .get() as { operacao_id: string }
    ).operacao_id;
    desfazerOperacao(b.db, operacaoDoAcerto);
    expect(nota(nova).situacao).toBe('EM_ABERTO');
    expect(nota(nova).acerto).toBeNull();
    expect(saldoDe(maletas(), maleta, produto.A)?.disponivel).toBe(2);
    expect(painel().totais.recebido).toBe('0.00');
  });
});

describe('Reposição, Troca e Remarcar', () => {
  it('Reposição na semana atual entra na mesma nota; em semana anterior nasce nota nova com a mesma Dt. Cobrança', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    const n1 = pedido([['A-1', 2]]);
    const r1 = chamar<{ novaNota: boolean; nota: DetalheDaNota }>({
      operacao: 'consignado.reposicao',
      reposicao: {
        id: idDeTeste(14),
        notaId: n1,
        itens: [{ produtoId: produto.B, remessaId: maleta, quantidade: 1 }],
      },
    });
    expect(r1.novaNota).toBe(false);
    expect(r1.nota.linhas).toHaveLength(2);
    expect(r1.nota.itensDx).toBe(3);
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10) });
    const dataAntes = nota(n1).dataCobranca;
    const r2 = chamar<{ novaNota: boolean; notaId: string; nota: DetalheDaNota }>({
      operacao: 'consignado.reposicao',
      reposicao: {
        id: idDeTeste(14),
        notaId: n1,
        itens: [{ produtoId: produto.C, remessaId: maleta, quantidade: 1 }],
      },
    });
    expect(r2.novaNota).toBe(true);
    expect(r2.nota.itensDx).toBe(1);
    expect(r2.nota.dataCobranca).toBe(dataAntes);
    expect(r2.nota.numero).toMatch(/^0000002-/);
    const tipos = (
      b.db.prepare('SELECT tipo FROM outbox ORDER BY ordem').all() as { tipo: string }[]
    ).map((x) => x.tipo);
    expect(tipos).toEqual([
      'SEMANA_NOVA',
      'NOTA_CONSIGNADO',
      'REPOSICAO_NOTA',
      'SEMANA_NOVA',
      'NOTA_CONSIGNADO',
    ]);
  });

  it('Troca: a peça volta à maleta de onde saiu e a nova entra, numa operação só; nota acertada não troca', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    const n1 = pedido([['A-1', 2]]);
    const r = chamar<{ nota: DetalheDaNota }>({
      operacao: 'consignado.trocaNaNota',
      troca: {
        id: idDeTeste(15),
        notaId: n1,
        volta: { produtoId: produto.A, remessaId: maleta },
        entra: { produtoId: produto.C, remessaId: maleta },
      },
    });
    expect(r.nota.linhas.map((l) => [l.produtoId === produto.A ? 'A' : 'C', l.dx])).toEqual([
      ['A', 1],
      ['C', 1],
    ]);
    expect(r.nota.valorDx).toBe('433.75');
    const m = maletas();
    expect(saldoDe(m, maleta, produto.A)?.disponivel).toBe(11);
    expect(saldoDe(m, maleta, produto.C)?.disponivel).toBe(9);
    expect(() =>
      chamar({
        operacao: 'consignado.trocaNaNota',
        troca: {
          id: idDeTeste(15),
          notaId: n1,
          volta: { produtoId: produto.D, remessaId: maleta },
          entra: { produtoId: produto.C, remessaId: maleta },
        },
      }),
    ).toThrowError(/não está na nota/);
    acertar(n1, [], '0', []);
    expect(() =>
      chamar({
        operacao: 'consignado.trocaNaNota',
        troca: {
          id: idDeTeste(15),
          notaId: n1,
          volta: { produtoId: produto.A, remessaId: maleta },
          entra: { produtoId: produto.C, remessaId: maleta },
        },
      }),
    ).toThrowError(/já foi acertada/);
  });

  it('nota Em aberto mostra a comissão do cliente em Comis. % (só o valor em R$ fica zerado)', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    pedido([['A-1', 1]]);
    const linha = painel().pedidos[0];
    expect(linha?.situacao).toBe('EM_ABERTO');
    expect(linha?.comissaoPercentual).toBe('30.00');
    expect(linha?.comissao).toBe('0.00');
  });

  it('Remarcar muda a Dt. Cobrança e a nota fica Remarcado; zerada até o acerto', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    const n1 = pedido([['A-1', 1]]);
    const r = chamar<{ nota: DetalheDaNota }>({
      operacao: 'consignado.remarcar',
      id: idDeTeste(16),
      notaId: n1,
      dataCobranca: '2027-02-01',
    });
    expect(r.nota.situacao).toBe('REMARCADO');
    expect(r.nota.dataCobranca).toBe('2027-02-01');
    const linha = painel().pedidos[0];
    expect(linha?.situacao).toBe('REMARCADO');
    expect(linha?.valorVd).toBe('0.00');
    expect(linha?.valorLiquido).toBe('0.00');
    // Tarefa 9.6: Comis. % não fica vazia; mostra a comissão do cliente (só o valor em R$ é zerado).
    expect(linha?.comissaoPercentual).toBe('30.00');
    expect(linha?.comissao).toBe('0.00');
    expect(() =>
      chamar({
        operacao: 'consignado.remarcar',
        id: idDeTeste(16),
        notaId: n1,
        dataCobranca: '2027-13-40',
      }),
    ).toThrowError(/data de cobrança/i);
  });
});

describe('cliente do representante', () => {
  const fotos = {
    fotoHash: 'a'.repeat(64),
    fotoDocumentoFrenteHash: 'b'.repeat(64),
    fotoDocumentoVersoHash: 'c'.repeat(64),
  };

  it('cadastro com fotos vai no formato 2, as fotos entram na fila e o cadastro incompleto é recusado', () => {
    const id = idDeTeste(3);
    expect(() =>
      chamar({
        operacao: 'consignado.clienteCadastrar',
        cliente: {
          id,
          nome: 'Sem endereço',
          telefone: '1',
          ...fotos,
          enderecoLogradouro: '',
          enderecoNumero: '',
          enderecoBairro: '',
          enderecoCidade: '',
          enderecoUf: '',
        },
      }),
    ).toThrowError(/Preencha o nome/);
    expect(() =>
      chamar({
        operacao: 'consignado.clienteCadastrar',
        cliente: {
          id,
          nome: 'Sem foto',
          telefone: '1',
          enderecoLogradouro: 'Rua A',
          enderecoNumero: '1',
          enderecoBairro: 'Centro',
          enderecoCidade: 'Ponta Porã',
          enderecoUf: 'MS',
          fotoHash: '',
          fotoDocumentoFrenteHash: '',
          fotoDocumentoVersoHash: '',
        },
      }),
    ).toThrowError(/Faltam fotos/);
    expect(() =>
      chamar({
        operacao: 'consignado.clienteCadastrar',
        cliente: {
          id,
          nome: 'Comissão alta',
          telefone: '1',
          enderecoLogradouro: 'Rua A',
          enderecoNumero: '1',
          enderecoBairro: 'Centro',
          enderecoCidade: 'Ponta Porã',
          enderecoUf: 'MS',
          comissaoPercentual: '41',
          ...fotos,
        },
      }),
    ).toThrowError(/entre 0% e 40%/);
    const c = chamar<ClienteCompleto>({
      operacao: 'consignado.clienteCadastrar',
      cliente: {
        id,
        nome: 'Maria',
        telefone: '67 99999-0000',
        enderecoLogradouro: 'Rua A',
        enderecoNumero: '10',
        enderecoBairro: 'Centro',
        enderecoCidade: 'Ponta Porã',
        enderecoUf: 'MS',
        documento: '123',
        documentoTipo: 'CI',
        ...fotos,
      },
    });
    expect(c.comissaoPercentual).toBe('30.00');
    expect(c.fotosEsperandoEnvio).toBe(3);
    const op = b.db
      .prepare("SELECT tipo, formato, dados FROM outbox WHERE tipo = 'CLIENTE_NOVO'")
      .get() as { formato: number; dados: string };
    expect(op.formato).toBe(2);
    const dados = JSON.parse(op.dados) as Record<string, unknown>;
    expect(dados['fotoHash']).toBe(fotos.fotoHash);
    expect(dados['comissaoPercentual']).toBe('30.00');
    expect(dados['documentoTipo']).toBe('CI');
  });

  it('alterar o cadastro guarda antes e depois no formato 2; a comissão acima de 40 é recusada; trocar foto recomeça o envio', () => {
    const r = chamar<ClienteCompleto>({
      operacao: 'consignado.clienteAlterar',
      id: cliente,
      campos: { enderecoCidade: 'Pedro Juan', comissaoPercentual: '35' },
    });
    expect(r.enderecoCidade).toBe('Pedro Juan');
    expect(r.comissaoPercentual).toBe('35.00');
    const op = b.db
      .prepare("SELECT formato, dados FROM outbox WHERE tipo = 'CLIENTE_ALTERADO'")
      .get() as { formato: number; dados: string };
    expect(op.formato).toBe(2);
    expect(
      (JSON.parse(op.dados) as { campos: Record<string, unknown> }).campos['comissaoPercentual'],
    ).toEqual({
      antes: '30.00',
      depois: '35.00',
    });
    expect(() =>
      chamar({
        operacao: 'consignado.clienteAlterar',
        id: cliente,
        campos: { comissaoPercentual: '60' },
      }),
    ).toThrowError(/entre 0% e 40%/);
    chamar({
      operacao: 'consignado.clienteTrocarFoto',
      id: cliente,
      tipo: 'cliente',
      hash: 'd'.repeat(64),
    });
    const fila = b.db
      .prepare('SELECT tipo, hash, enviada_em FROM fila_fotos_cliente WHERE cliente_id = ?')
      .all(cliente) as { tipo: string; hash: string; enviada_em: string | null }[];
    expect(fila).toEqual([{ tipo: 'cliente', hash: 'd'.repeat(64), enviada_em: null }]);
  });

  it('a pesquisa acha nota por nome, número e código da maleta, e cliente por nome', () => {
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 7 });
    pedido([['A-1', 1]]);
    const porNome = chamar<{ notas: unknown[]; clientes: unknown[] }>({
      operacao: 'consignado.pesquisar',
      texto: 'ana',
    });
    expect(porNome.notas).toHaveLength(1);
    expect(porNome.clientes).toHaveLength(1);
    // Sem diferenciar acento nem maiúscula: "ÁNA" acha a mesma cliente.
    expect(
      chamar<{ clientes: unknown[] }>({ operacao: 'consignado.pesquisar', texto: 'ÁNA' }).clientes,
    ).toHaveLength(1);
    expect(
      chamar<{ notas: unknown[] }>({ operacao: 'consignado.pesquisar', texto: '0000007-01' }).notas,
    ).toHaveLength(1);
    const codigo = maletas().find((m) => m.id === maleta)?.codigo as string;
    expect(
      chamar<{ notas: unknown[]; maletas: unknown[] }>({
        operacao: 'consignado.pesquisar',
        texto: codigo.toLowerCase(),
      }).maletas,
    ).toHaveLength(1);
    expect(
      chamar<{ notas: unknown[] }>({ operacao: 'consignado.pesquisar', texto: 'ninguém' }).notas,
    ).toHaveLength(0);
  });
});

describe('Dt. Acerto (tarefa 9.8): a data do acerto e a ordem dos débitos', () => {
  const pecaE = idDeTeste(2);

  /** Uma peça de 100,00 em quantidade, como a do exemplo compartilhado com o servidor. */
  function receberPecaDeCem(): void {
    aplicarLote(
      b.db,
      [
        {
          tabela: 'produtos',
          id: pecaE,
          version: 1,
          deletedAt: null,
          campos: { nome: 'Pulseira E', ativo: 1 },
        },
        {
          tabela: 'produto_codigos',
          id: idDeTeste(5),
          version: 1,
          deletedAt: null,
          campos: { produto_id: pecaE, codigo: 'E-1', tipo: 'FORNECEDOR', principal: 1, ativo: 1 },
        },
        {
          tabela: 'itens_remessa',
          id: idDeTeste(4),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: maleta,
            produto_id: pecaE,
            qtd_enviada: 40,
            preco_remessa: exemplos.ordemDosDebitosComDataPassada.precoDaPeca,
          },
        },
        ...(['2026-11-08', '2026-11-09', '2026-11-10'] as const).map((dia, i) => ({
          tabela: 'cotacoes' as const,
          id: idDeTeste(11),
          version: 1,
          deletedAt: null,
          campos: {
            data_cotacao: dia,
            usd_venda: `${String(5 + i)}.00000000`,
            pyg_venda: '0.00075000',
          },
        })),
      ],
      { cursor: 'c-2' },
    );
  }

  /** Duas notas de peças de 100,00 com a saída em 2/11; devolve os ids e deixa o relógio em 10/11 às 10h de Brasília. */
  function duasNotas(): { notaA: string; notaB: string } {
    receberPecaDeCem();
    const ex = exemplos.ordemDosDebitosComDataPassada;
    agora = new Date('2026-11-02T13:00:00.000Z');
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 64 });
    const notaA = pedido([['E-1', ex.pecasDaNotaA]]);
    const notaB = pedido([['E-1', ex.pecasDaNotaB]]);
    agora = new Date('2026-11-10T13:00:00.000Z');
    return { notaA, notaB };
  }

  const salvar = (notaId: string, formas: { forma: 'DINHEIRO'; valor: string }[], data?: string) =>
    chamar<{ situacao: string; nota: DetalheDaNota }>({
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: idDeTeste(7),
        reciboId: idDeTeste(8),
        notaId,
        comissaoPercentual: '0',
        devolvidos: [],
        formas,
        ...(data ? { data } : {}),
      },
    });

  it('a ordem dos débitos é a do lançamento: o acerto com data de um dia antes não passa na frente (mesmos números do servidor)', () => {
    const ex = exemplos.ordemDosDebitosComDataPassada;
    const { notaA, notaB } = duasNotas();
    salvar(notaA, []);
    expect(nota(notaA).saldoDaNota).toBe('100.00');
    agora = new Date('2026-11-10T18:00:00.000Z');
    salvar(notaB, [{ forma: 'DINHEIRO', valor: ex.pagoNoAcertoB }], '2026-11-09');

    expect(nota(notaA).saldoDaNota).toBe(ex.esperado.saldoDeA);
    expect(nota(notaB).saldoDaNota).toBe(ex.esperado.saldoDeB);
    expect(nota(notaA).situacao).toBe(ex.esperado.situacaoDeA);
    expect(nota(notaB).situacao).toBe(ex.esperado.situacaoDeB);
    const aplicacoes = b.db
      .prepare('SELECT acerto_id, valor FROM aplicacoes_recibo ORDER BY CAST(valor AS REAL) DESC')
      .all() as { acerto_id: string; valor: string }[];
    expect(aplicacoes.map((a) => a.valor)).toEqual([
      ex.esperado.aplicadoEmA,
      ex.esperado.aplicadoEmB,
    ]);
    // Um Lançar pagamento de 150 quita B.
    chamar({
      operacao: 'consignado.pagamento',
      pagamento: {
        id: idDeTeste(13),
        clienteId: cliente,
        formas: [{ forma: 'PIX', valor: '150.00' }],
      },
    });
    expect(nota(notaB).situacao).toBe('FECHADO');
  });

  it('estornar o acerto A devolve a sobra ao "a aplicar" e ela entra no débito mais antigo (mesmos números do servidor)', () => {
    const ex = exemplos.sobraDepoisDeEstorno;
    const { notaA, notaB } = duasNotas();
    salvar(notaA, []);
    agora = new Date('2026-11-10T14:00:00.000Z');
    salvar(notaB, [{ forma: 'DINHEIRO', valor: ex.pagoNoAcertoB }]);
    expect(nota(notaB).saldoDaNota).toBe(ex.esperado.saldoDeBAntesDoEstorno);

    chamar({
      operacao: 'consignado.estornarAcerto',
      id: idDeTeste(14),
      acertoId: nota(notaA).acerto?.id as string,
      motivo: 'Contei errado',
    });

    expect(nota(notaB).saldoDaNota).toBe(ex.esperado.saldoDeBDepoisDoEstorno);
    expect(nota(notaB).situacao).toBe(ex.esperado.situacaoDeBDepoisDoEstorno);
    expect(clienteVisto().pendencia).toBe(ex.esperado.pendenciaDoClienteDepoisDoEstorno);
    // O recibo de B tem 250,00; 200,00 valem em B (50,00 ficam "a aplicar": o cliente pagou a mais).
    const valendo = b.db
      .prepare(
        `SELECT coalesce(sum(CAST(valor AS REAL)), 0) AS soma FROM aplicacoes_recibo WHERE deleted_at IS NULL AND acerto_id = ?`,
      )
      .get(nota(notaB).acerto?.id) as { soma: number };
    expect(valendo.soma.toFixed(2)).toBe('200.00');
    const total = (
      b.db
        .prepare('SELECT total FROM recibos_consignado WHERE acerto_id = ?')
        .get(nota(notaB).acerto?.id) as {
        total: string;
      }
    ).total;
    expect((Number(total) - valendo.soma).toFixed(2)).toBe(
      ex.esperado.aAplicarDoReciboDepoisDoEstorno,
    );
    // A ficha do cliente também mostra essa sobra "a aplicar".
    expect(clienteVisto().sobraAAplicar).toBe(ex.esperado.aAplicarDoReciboDepoisDoEstorno);
    // E o acerto de B guarda o que ficou devendo no dia; o saldo de hoje é o da nota (o que a tela mostra como "Deve agora").
    expect(nota(notaB).acerto?.ficouDevendo).not.toBe(nota(notaB).saldoDaNota);
  });

  it('data passada grava meio-dia de Brasília, o lançamento fica em registrado_em e o recibo usa o mesmo dia', () => {
    const { notaB } = duasNotas();
    const r = salvar(notaB, [{ forma: 'DINHEIRO', valor: '50.00' }], '2026-11-08');
    const a = b.db
      .prepare('SELECT acertado_em, registrado_em, recibo_id FROM acertos_nota')
      .get() as { acertado_em: string; registrado_em: string; recibo_id: string };
    expect(a.acertado_em).toBe('2026-11-08T15:00:00.000Z');
    expect(a.registrado_em.startsWith('2026-11-10T')).toBe(true);
    const recibo = b.db
      .prepare('SELECT pago_em FROM recibos_consignado WHERE id = ?')
      .get(a.recibo_id) as { pago_em: string };
    expect(recibo.pago_em).toBe('2026-11-08T15:00:00.000Z');
    expect(r.nota.acerto?.recibo?.numero).toBe('R 0000064-01');
    // A outbox leva a data só quando ela veio; o momento declarado continua sendo o do lançamento.
    const op = b.db.prepare("SELECT dados FROM outbox WHERE tipo = 'ACERTO_NOTA'").get() as {
      dados: string;
    };
    const dados = JSON.parse(op.dados) as { acertadoEm: string; dataDoAcerto?: string };
    expect(dados.dataDoAcerto).toBe('2026-11-08');
    expect(dados.acertadoEm.startsWith('2026-11-10T')).toBe(true);
  });

  it('sem data (ou só com hoje) a outbox não leva dataDoAcerto e o resumo é o de antes', () => {
    const { notaA } = duasNotas();
    salvar(notaA, [], '2026-11-10');
    const op = b.db.prepare("SELECT dados FROM outbox WHERE tipo = 'ACERTO_NOTA'").get() as {
      dados: string;
    };
    expect(JSON.parse(op.dados)).not.toHaveProperty('dataDoAcerto');
  });

  it('data no futuro e data antes do dia da saída da nota são recusadas sem gravar nada', () => {
    const { notaA } = duasNotas();
    expect(() => salvar(notaA, [], '2026-11-11')).toThrowError(/depois de hoje/);
    expect(() => salvar(notaA, [], '2026-11-01')).toThrowError(/antes do dia em que a nota saiu/);
    expect(b.db.prepare('SELECT count(*) AS n FROM acertos_nota').get()).toEqual({ n: 0 });
    // O mesmo dia da saída vale.
    expect(salvar(notaA, [], '2026-11-02').situacao).toBe('PENDENTE');
  });

  it('relógio do computador atrasado: a data vale pelo dia que ele declara (nunca depois), como no servidor', () => {
    const { notaA } = duasNotas();
    // O computador está em 5/11 (a nota saiu em 2/11): depois do dia dele é recusado; o dia dele e os anteriores valem.
    agora = new Date('2026-11-05T13:00:00.000Z');
    expect(() => salvar(notaA, [], '2026-11-06')).toThrowError(/depois de hoje/);
    const r = salvar(notaA, [], '2026-11-03');
    expect(r.situacao).toBe('PENDENTE');
    const a = b.db.prepare('SELECT acertado_em, registrado_em FROM acertos_nota').get() as {
      acertado_em: string;
      registrado_em: string;
    };
    expect(a.acertado_em).toBe('2026-11-03T15:00:00.000Z');
    expect(a.registrado_em.startsWith('2026-11-05T')).toBe(true);
  });

  it('a prévia mostra a cotação do dia escolhido; sem cotação até o dia, "Sem cotação" (cotacao nula)', () => {
    const { notaA } = duasNotas();
    const previa = (data?: string) =>
      chamar<PrevisaoDoAcerto>({
        operacao: 'consignado.acertoPrevia',
        acerto: {
          notaId: notaA,
          devolvidos: [],
          comissaoPercentual: '0',
          formas: [],
          ...(data ? { data } : {}),
        },
      });
    expect(previa('2026-11-08').cotacao?.dolar).toBe('5.00000000');
    expect(previa().cotacao?.dolar).toBe('7.00000000');
    expect(previa('2026-11-03').cotacao).toBeNull();
  });
});

describe('Tam./Ton. da peça (tarefa 9.8)', () => {
  it('o pull de formato 2 grava tam_ton; o Novo Pedido e a Cobrança mostram a coluna, vazia quando a peça não tem', () => {
    // O servidor manda a coluna junto com o produto (formato 2); aqui, a peça A chega com "17".
    aplicarLote(
      b.db,
      [
        {
          tabela: 'produtos',
          id: produto.A,
          version: 2,
          deletedAt: null,
          campos: { nome: 'Brinco A', ativo: 1, tam_ton: '17' },
        },
      ],
      { cursor: 'c-2' },
    );
    expect(b.db.prepare('SELECT tam_ton FROM produtos WHERE id = ?').get(produto.A)).toEqual({
      tam_ton: '17',
    });
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 1 });
    chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
    chamar({ operacao: 'consignado.pedidoBipar', codigo: 'A-1' });
    const r = chamar<{ pedido: PedidoEmMontagem }>({
      operacao: 'consignado.pedidoBipar',
      codigo: 'B-1',
    });
    expect(
      r.pedido.itens
        .map((i) => [i.codigo, i.tamTon])
        .sort((a, b) => String(a[0]).localeCompare(String(b[0]))),
    ).toEqual([
      ['A-1', '17'],
      ['B-1', null],
    ]);
    const id = idDeTeste(6);
    chamar({ operacao: 'consignado.pedidoFinalizar', id });
    const n = nota(id);
    expect(
      n.linhas
        .map((l) => [l.codigo, l.tamTon])
        .sort((a, b) => String(a[0]).localeCompare(String(b[0]))),
    ).toEqual([
      ['A-1', '17'],
      ['B-1', null],
    ]);
  });
});
