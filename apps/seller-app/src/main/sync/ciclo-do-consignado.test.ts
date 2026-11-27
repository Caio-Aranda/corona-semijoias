import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { DetalheDaNota, PainelDaSemana } from '../../shared/dados-do-consignado';
import type { PedidoDeChamada } from '../../shared/operacoes';
import { ConsultasLocais } from '../local/consultas-locais';
import { CicloDeSincronizacao } from './ciclo';
import { OperacoesLocais } from './operacoes-locais';
import { criarTransporteDoSync } from './transporte-do-sync';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { type LinhaDoServidor, ServidorFalso } from './testes/servidor-falso';

/**
 * A volta da internet com as operações do consignado ao cliente (tarefa 9.2; plano 9.1, seção 7): o servidor falso de
 * verdade e o banco cifrado de verdade. O computador trabalha sem internet (semana, pedido, acerto com pagamento), o ciclo
 * manda os tipos novos NA ORDEM e com os bytes guardados, a cópia do servidor (mesmos ids) troca a provisória, e o que o
 * servidor recusa desfaz tudo o que a operação gravou.
 */
let b: BancoDeTeste;
let s: ServidorFalso;
let url: string;
let consultas: ConsultasLocais;
let agora: Date;
const T0 = new Date('2026-11-12T13:00:00.000Z');

const maleta = idDeTeste(1);
const produto = idDeTeste(2);
const cliente = idDeTeste(3);

const ciclo = () =>
  new CicloDeSincronizacao({
    banco: () => b.db,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
    comToken: (fn) => fn('token-de-teste'),
    agora: () => agora,
    esperar: () => Promise.resolve(),
  });

function chamar<T>(pedido: PedidoDeChamada): T {
  agora = new Date(agora.getTime() + 60_000);
  const r = consultas.chamar(pedido);
  if (!r.ok) throw new Error(`recusado: ${JSON.stringify(r.falha)}`);
  return r.dados as T;
}

beforeEach(async () => {
  b = criarBancoDeTeste();
  s = new ServidorFalso();
  url = await s.iniciar();
  agora = new Date(T0);
  consultas = new ConsultasLocais({
    banco: () => b.db,
    operacoes: new OperacoesLocais({ banco: () => b.db, agora: () => agora }),
    agora: () => agora,
  });
  s.mudar({
    tabela: 'remessas',
    id: maleta,
    deletedAt: null,
    campos: { status: 'ENVIADA', enviada_em: '2026-11-01T12:00:00.000Z' },
  });
  s.mudar({
    tabela: 'produtos',
    id: produto,
    deletedAt: null,
    campos: { nome: 'Brinco A', ativo: true },
  });
  s.mudar({
    tabela: 'produto_codigos',
    id: idDeTeste(5),
    deletedAt: null,
    campos: {
      produto_id: produto,
      codigo: 'A-1',
      tipo: 'FORNECEDOR',
      principal: true,
      ativo: true,
    },
  });
  s.mudar({
    tabela: 'itens_remessa',
    id: idDeTeste(4),
    deletedAt: null,
    campos: { remessa_id: maleta, produto_id: produto, qtd_enviada: 10, preco_remessa: '200.00' },
  });
  s.mudar({
    tabela: 'clientes',
    id: cliente,
    deletedAt: null,
    campos: { nome: 'Vendedora Ana', telefone: '1', comissao_percentual: '30.00', ativo: true },
  });
  expect(await ciclo().executar()).toBe('ok');
});
afterEach(async () => {
  b.fechar();
  await s.parar();
});

/** O que o servidor grava ao aceitar cada tipo (mesmos ids do que o app mandou): a cópia que troca a provisória. */
function copiaDoServidor(op: { tipo: string; dados: Record<string, unknown> }): LinhaDoServidor[] {
  const d = op.dados;
  const linha = (
    tabela: string,
    id: unknown,
    campos: Record<string, unknown>,
  ): LinhaDoServidor => ({ tabela, id: String(id), version: 1, deletedAt: null, campos });
  switch (op.tipo) {
    case 'SEMANA_NOVA':
      return [
        linha('semanas_vendedor', d['id'], {
          numero: d['numero'],
          aberta_em: d['abertaEm'],
          situacao: 'ABERTA',
        }),
      ];
    case 'NOTA_CONSIGNADO': {
      const itens = d['itens'] as {
        id: string;
        produtoId: string;
        remessaId: string;
        quantidade: number;
        precoUnitario: string;
        movimentoId: string;
      }[];
      return [
        linha('notas_consignado', d['id'], {
          semana_id: d['semanaId'],
          sequencia: d['sequencia'],
          cliente_id: d['clienteId'],
          saida_em: d['saidaEm'],
          data_cobranca: d['dataCobranca'],
          situacao: 'EM_ABERTO',
        }),
        ...itens.flatMap((i) => [
          linha('itens_nota_consignado', i.id, {
            nota_id: d['id'],
            produto_id: i.produtoId,
            remessa_id: i.remessaId,
            origem: 'SAIDA',
            quantidade: i.quantidade,
            preco_unitario: i.precoUnitario,
          }),
          linha('movimentos_remessa', i.movimentoId, {
            remessa_id: i.remessaId,
            produto_id: i.produtoId,
            tipo: 'CONSIGNADO_SAIDA',
            quantidade: -i.quantidade,
            nota_id: d['id'],
          }),
        ]),
      ];
    }
    default:
      return [];
  }
}

describe('o ciclo com os tipos do consignado ao cliente', () => {
  it('manda semana e nota na ordem, com os bytes guardados; a cópia do servidor troca a provisória e nada duplica', async () => {
    const vistas: { tipo: string; formato: number }[] = [];
    s.resultadoPara = (op) => {
      vistas.push({ tipo: op.tipo, formato: op.formato });
      return undefined;
    };
    s.linhasDaOperacao = copiaDoServidor;
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 65 });
    chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
    for (let i = 0; i < 3; i += 1) chamar({ operacao: 'consignado.pedidoBipar', codigo: 'A-1' });
    const antes = chamar<PainelDaSemana>({ operacao: 'consignado.painel' });
    chamar({ operacao: 'consignado.pedidoFinalizar', id: idDeTeste(6) });
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao = 'AGUARDANDO'")).toBe(2);
    const guardado = (
      b.db.prepare("SELECT dados FROM outbox WHERE tipo = 'NOTA_CONSIGNADO'").get() as {
        dados: string;
      }
    ).dados;

    expect(await ciclo().executar()).toBe('ok');
    expect(vistas).toEqual([
      { tipo: 'SEMANA_NOVA', formato: 1 },
      { tipo: 'NOTA_CONSIGNADO', formato: 1 },
    ]);
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao = 'ENVIADO'")).toBe(2);
    expect(s.operacoes.get(idDeTeste(6))).toBeUndefined();
    // O servidor leu exatamente os bytes que o app guardou na hora da operação.
    const lida = [...s.operacoes.values()].find((c) => c.includes('"totalDxVisto"'));
    expect(lida).toBe(JSON.stringify(JSON.parse(guardado)));
    // A cópia do servidor chegou (mesmos ids) e nenhuma linha provisória sobrou.
    expect(contar(b.db, "SELECT 1 FROM notas_consignado WHERE origem_local = 'SERVIDOR'")).toBe(1);
    expect(contar(b.db, "SELECT 1 FROM semanas_vendedor WHERE origem_local = 'SERVIDOR'")).toBe(1);
    expect(
      contar(b.db, "SELECT 1 FROM itens_nota_consignado WHERE origem_local = 'PROVISORIO'"),
    ).toBe(0);
    expect(
      contar(
        b.db,
        "SELECT 1 FROM movimentos_remessa WHERE tipo = 'CONSIGNADO_SAIDA' AND origem_local = 'PROVISORIO'",
      ),
    ).toBe(0);
    // A tela mostra o mesmo de antes de enviar: a nota e o saldo da maleta não mudaram com a troca provisória → servidor.
    const depois = chamar<PainelDaSemana>({ operacao: 'consignado.painel' });
    expect(depois.pedidos.map((p) => [p.numero, p.valorDx, p.situacao])).toEqual([
      ['0000065-01', '600.00', 'EM_ABERTO'],
    ]);
    expect(antes.semana?.numero).toBe(65);
    // Segundo ciclo: nada novo, nada duplicado.
    expect(await ciclo().executar()).toBe('ok');
    expect([...s.gravacoes.values()].every((n) => n === 1)).toBe(true);
  });

  it('o acerto recusado pelo servidor volta a nota para Em aberto e devolve as peças à maleta, sem apagar o aviso', async () => {
    s.linhasDaOperacao = copiaDoServidor;
    chamar({ operacao: 'consignado.semanaNova', id: idDeTeste(10), numero: 65 });
    chamar({ operacao: 'consignado.pedidoDefinir', clienteId: cliente, remessaId: maleta });
    for (let i = 0; i < 4; i += 1) chamar({ operacao: 'consignado.pedidoBipar', codigo: 'A-1' });
    const notaId = idDeTeste(6);
    chamar({ operacao: 'consignado.pedidoFinalizar', id: notaId });
    expect(await ciclo().executar()).toBe('ok');

    // Sem internet: o acerto fica na fila e a nota já aparece como Pendente.
    const r = chamar<{ nota: DetalheDaNota }>({
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: idDeTeste(7),
        reciboId: idDeTeste(8),
        notaId,
        devolvidos: [{ produtoId: produto, remessaId: maleta, quantidade: 1 }],
        formas: [{ forma: 'DINHEIRO', valor: '100.00' }],
      },
    });
    expect(r.nota.situacao).toBe('PENDENTE');
    expect(
      contar(b.db, "SELECT 1 FROM outbox WHERE tipo = 'ACERTO_NOTA' AND situacao = 'AGUARDANDO'"),
    ).toBe(1);

    // O servidor recusa o acerto: o computador desfaz tudo o que ele gravou.
    s.resultadoPara = (op) => (op.tipo === 'ACERTO_NOTA' ? 'RECUSADO' : undefined);
    expect(await ciclo().executar()).toBe('ok');
    const linha = b.db.prepare("SELECT situacao FROM outbox WHERE tipo = 'ACERTO_NOTA'").get() as {
      situacao: string;
    };
    expect(linha.situacao).toBe('RECUSADO');
    const nota = chamar<DetalheDaNota>({ operacao: 'consignado.nota', id: notaId });
    expect(nota.situacao).toBe('EM_ABERTO');
    expect(nota.acerto).toBeNull();
    expect(contar(b.db, "SELECT 1 FROM recibos_consignado WHERE origem_local = 'PROVISORIO'")).toBe(
      0,
    );
    expect(contar(b.db, "SELECT 1 FROM movimentos_remessa WHERE tipo = 'CONSIGNADO_RETORNO'")).toBe(
      0,
    );
    expect(painelRecebido()).toBe('0.00');
  });
});

function painelRecebido(): string {
  return chamar<PainelDaSemana>({ operacao: 'consignado.painel' }).totais.recebido;
}
