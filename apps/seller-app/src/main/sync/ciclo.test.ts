import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GuardaDeTokens } from '../guarda-de-tokens';
import type { Credenciais, Resultado } from '../tipos';
import {
  CicloDeSincronizacao,
  type ComToken,
  MAXIMO_DE_BYTES,
  type OrdemDoServidor,
} from './ciclo';
import { CHAVES, gravarEstado, lerEstado } from './estado-sync';
import { OperacoesLocais, saldoDaPeca } from './operacoes-locais';
import { criarTransporteDoSync } from './transporte-do-sync';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { ServidorFalso } from './testes/servidor-falso';

/**
 * O ciclo do motor contra um servidor falso de verdade (`node:http`) e o banco cifrado de verdade (tarefa 5.6; ADR-0013,
 * itens 2, 3, 6, 7 e 19).
 */
let b: BancoDeTeste;
let s: ServidorFalso;
let url: string;
let ops: OperacoesLocais;
let ordens: OrdemDoServidor[];
let esperas: number[];
const AGORA = new Date('2026-11-12T13:00:00.000Z');
const PUSH = '/api/vendedor/sync/push';
const PULL = '/api/vendedor/sync/pull';

const comTokenSimples: ComToken = (fn) => fn('token-de-teste');

function criarCiclo(extra: Partial<ConstructorParameters<typeof CicloDeSincronizacao>[0]> = {}) {
  return new CicloDeSincronizacao({
    banco: () => b.db,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
    comToken: comTokenSimples,
    agora: () => AGORA,
    esperar: (ms) => {
      esperas.push(ms);
      return Promise.resolve();
    },
    aoReceberOrdem: (o) => ordens.push(o),
    ...extra,
  });
}

interface Maleta {
  remessaId: string;
  produtoA: string;
  clienteId: string;
}

/** A dona envia uma maleta (3 peças A a R$ 33,33) e há um cliente; o computador recebe tudo. */
async function maletaRecebida(): Promise<Maleta> {
  const remessaId = idDeTeste(1);
  const produtoA = idDeTeste(2);
  const clienteId = idDeTeste(3);
  s.mudar({ tabela: 'remessas', id: remessaId, deletedAt: null, campos: { status: 'ENVIADA' } });
  s.mudar({
    tabela: 'itens_remessa',
    id: idDeTeste(4),
    deletedAt: null,
    campos: { remessa_id: remessaId, produto_id: produtoA, qtd_enviada: 3, preco_remessa: '33.33' },
  });
  s.mudar({
    tabela: 'produtos',
    id: produtoA,
    deletedAt: null,
    campos: { nome: 'Brinco', preco_venda: '33.33' },
  });
  s.mudar({
    tabela: 'clientes',
    id: clienteId,
    deletedAt: null,
    campos: { nome: 'Ana', cpf: '12345678909' },
  });
  expect(await criarCiclo().executar()).toBe('ok');
  return { remessaId, produtoA, clienteId };
}

const vender = (m: Maleta, quantidade = 1) => {
  const id = idDeTeste(5);
  ops.vender({
    id,
    remessaId: m.remessaId,
    clienteId: m.clienteId,
    itens: [{ produtoId: m.produtoA, quantidade }],
    pagamentos: [],
  });
  return id;
};

const situacao = (id: string) =>
  b.db.prepare('SELECT situacao, tentativas FROM outbox WHERE operacao_id = ?').get(id) as {
    situacao: string;
    tentativas: number;
  };

const operacoesDoPush = (i: number): { operacaoId: string; dados: unknown }[] =>
  (
    JSON.parse(s.pedidosPara(PUSH)[i]?.corpo ?? '{}') as {
      operacoes: { operacaoId: string; dados: unknown }[];
    }
  ).operacoes;

beforeEach(async () => {
  b = criarBancoDeTeste();
  s = new ServidorFalso();
  url = await s.iniciar();
  ops = new OperacoesLocais({ banco: () => b.db, agora: () => AGORA });
  ordens = [];
  esperas = [];
});
afterEach(async () => {
  await s.parar();
  b.fechar();
});

describe('envio (push)', () => {
  it('ACEITO vira ENVIADO e a cópia do servidor troca a provisória sem contar a baixa duas vezes', async () => {
    const m = await maletaRecebida();
    const venda = vender(m, 2);
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
    expect(await criarCiclo().executar()).toBe('ok');
    expect(situacao(venda).situacao).toBe('ENVIADO');
    expect(b.db.prepare('SELECT origem_local FROM vendas WHERE id = ?').get(venda)).toEqual({
      origem_local: 'SERVIDOR',
    });
    expect(contar(b.db, "SELECT 1 FROM movimentos_remessa WHERE origem_local = 'PROVISORIO'")).toBe(
      0,
    );
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
  });

  it('o push vem antes do pull, e o pull repete até temMais = false', async () => {
    const m = await maletaRecebida();
    for (let i = 0; i < 5; i++)
      s.mudar({
        tabela: 'categorias',
        id: idDeTeste(6),
        deletedAt: null,
        campos: { nome: `c${i}` },
      });
    vender(m);
    s.pedidos.length = 0;
    expect(await criarCiclo({ limiteDoPull: 2 }).executar()).toBe('ok');
    const caminhos = s.pedidos.map((p) => p.caminho).filter((c) => c !== '/actuator/health');
    expect(caminhos[0]).toBe(PUSH);
    expect(caminhos.filter((c) => c === PULL).length).toBeGreaterThanOrEqual(4);
    expect(contar(b.db, 'SELECT 1 FROM categorias')).toBe(5);
    expect(s.pedidos.every((p) => p.tipo === 'vendas' && p.versao === '1.0.0')).toBe(true);
  });

  it('RECUSADO desfaz a provisória e o saldo na mesma transação que muda a outbox', async () => {
    const m = await maletaRecebida();
    const venda = vender(m, 3);
    s.resultadoPara = (op) => (op.operacaoId === venda ? 'RECUSADO' : undefined);
    // Falha injetada na mudança da outbox: o desfazer também não pode ficar.
    b.db.exec(
      "CREATE TEMP TRIGGER falha BEFORE UPDATE OF situacao ON outbox WHEN NEW.situacao = 'RECUSADO' BEGIN SELECT RAISE(ABORT, 'falha injetada'); END;",
    );
    expect(await criarCiclo().executar()).toBe('falha');
    expect(situacao(venda).situacao).toBe('AGUARDANDO');
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM vendas WHERE id = ?', venda)).toBe(1);

    b.db.exec('DROP TRIGGER falha');
    expect(await criarCiclo().executar()).toBe('ok');
    const r = b.db
      .prepare(
        'SELECT situacao, codigo_do_resultado AS c, ultimo_erro AS e FROM outbox WHERE operacao_id = ?',
      )
      .get(venda);
    expect(r).toEqual({
      situacao: 'RECUSADO',
      c: 'VENDA_SEM_SALDO',
      e: 'Não há mais peças desta na sua maleta.',
    });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(3);
    expect(contar(b.db, 'SELECT 1 FROM vendas WHERE id = ?', venda)).toBe(0);
  });

  it('RECUSADO é idempotente: a mesma resposta de novo não devolve o saldo duas vezes', async () => {
    const m = await maletaRecebida();
    const venda = vender(m, 1);
    const outra = vender(m, 1);
    const recusa = {
      operacaoId: venda,
      resultado: 'RECUSADO',
      codigo: 'VENDA_SEM_SALDO',
      mensagem: 'x',
    };
    s.roteirosDoPush.push({
      status: 200,
      corpo: { resultados: [recusa, recusa], horaServidor: s.horaServidor },
    });
    await criarCiclo().executar();
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(2);
    // A resposta da recusa chega de novo num envio seguinte (de outra venda): nada muda.
    const terceira = vender(m, 1);
    s.roteirosDoPush.push({
      status: 200,
      corpo: { resultados: [recusa], horaServidor: s.horaServidor },
    });
    await criarCiclo().executar();
    expect(situacao(venda).situacao).toBe('RECUSADO');
    // As outras duas não tiveram resposta: continuam aguardando, e só elas saem do saldo.
    expect(situacao(outra).situacao).toBe('AGUARDANDO');
    expect(situacao(terceira).situacao).toBe('AGUARDANDO');
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(1);
  });

  it('CONFLITO marca CONFLITO e tira a provisória (o servidor guardou outro conteúdo)', async () => {
    const m = await maletaRecebida();
    const venda = vender(m, 1);
    s.resultadoPara = (op) => (op.operacaoId === venda ? 'CONFLITO' : undefined);
    await criarCiclo().executar();
    expect(situacao(venda).situacao).toBe('CONFLITO');
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(3);
  });

  it('NAO_PROCESSADO mantém a ordem, nunca pula e avisa depois de 5 tentativas', async () => {
    const m = await maletaRecebida();
    const a = vender(m, 1);
    const b2 = vender(m, 1);
    s.resultadoPara = (op) => (op.operacaoId === a ? 'NAO_PROCESSADO' : undefined);
    s.pedidos.length = 0;
    for (let i = 0; i < 6; i++) expect(await criarCiclo().executar()).toBe('falha');
    expect(situacao(a)).toEqual({ situacao: 'AGUARDANDO', tentativas: 6 });
    expect(situacao(b2)).toEqual({ situacao: 'AGUARDANDO', tentativas: 0 });
    const pushes = s.pedidosPara(PUSH);
    expect(pushes).toHaveLength(6);
    for (let i = 0; i < pushes.length; i++) expect(operacoesDoPush(i)[0]?.operacaoId).toBe(a);
    expect(s.gravacoes.get(b2)).toBeUndefined();
  });

  it('resposta perdida: o reenvio manda os mesmos bytes e volta JA_RECEBIDO, sem duplicar', async () => {
    const m = await maletaRecebida();
    const venda = vender(m, 1);
    s.roteirosDoPush.push('cair-depois-de-gravar');
    expect(await criarCiclo().executar()).toBe('falha');
    expect(situacao(venda).situacao).toBe('AGUARDANDO');
    expect(await criarCiclo().executar()).toBe('ok');
    expect(situacao(venda).situacao).toBe('ENVIADO');
    expect(s.gravacoes.get(venda)).toBe(1);
    const [primeiro, segundo] = s.pedidosPara(PUSH).map((p) => p.corpo);
    const dadosDe = (corpo: string | undefined) => /"dados":(\{.*\})\}\]\}$/.exec(corpo ?? '')?.[1];
    expect(dadosDe(segundo)).toBe(dadosDe(primeiro));
    expect(dadosDe(primeiro)).toBe(
      (
        b.db.prepare('SELECT dados FROM outbox WHERE operacao_id = ?').get(venda) as {
          dados: string;
        }
      ).dados,
    );
  });

  it('413 manda a metade no próximo lote; o lote nunca passa de 50 operações', async () => {
    for (let i = 0; i < 60; i++) ops.cadastrarCliente({ id: idDeTeste(7), nome: `Cliente ${i}` });
    s.roteirosDoPush.push({ status: 413, corpo: { codigo: 'LOTE_GRANDE_DEMAIS' } });
    expect(await criarCiclo().executar()).toBe('ok');
    const tamanhos = s.pedidosPara(PUSH).map((_, i) => operacoesDoPush(i).length);
    expect(tamanhos[0]).toBe(50);
    expect(tamanhos[1]).toBe(25);
    expect(Math.max(...tamanhos)).toBeLessThanOrEqual(50);
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao = 'ENVIADO'")).toBe(60);
  });

  it('o lote nunca passa de 512 KB', async () => {
    for (let i = 0; i < 12; i++)
      ops.cadastrarCliente({
        id: idDeTeste(7),
        nome: `Cliente ${i}`,
        observacoes: 'x'.repeat(100_000),
      });
    expect(await criarCiclo().executar()).toBe('ok');
    const pushes = s.pedidosPara(PUSH);
    expect(pushes.length).toBeGreaterThan(1);
    for (const p of pushes)
      expect(Buffer.byteLength(p.corpo, 'utf8')).toBeLessThanOrEqual(MAXIMO_DE_BYTES);
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao = 'ENVIADO'")).toBe(12);
  });

  it('429 espera o Retry-After e tenta de novo', async () => {
    ops.cadastrarCliente({ id: idDeTeste(7), nome: 'Bia' });
    s.roteirosDoPush.push({
      status: 429,
      corpo: { codigo: 'ENVIO_EM_ANDAMENTO' },
      cabecalhos: { 'Retry-After': '7' },
    });
    expect(await criarCiclo().executar()).toBe('ok');
    expect(esperas).toEqual([7000]);
    expect(s.pedidosPara(PUSH)).toHaveLength(2);
  });

  it('apagarDados na resposta, com a fila zerada, para o motor pelo ponto de extensão', async () => {
    const id = idDeTeste(7);
    ops.cadastrarCliente({ id, nome: 'Bia' });
    s.roteirosDoPush.push({
      status: 200,
      corpo: {
        resultados: [{ operacaoId: id, resultado: 'ACEITO' }],
        horaServidor: s.horaServidor,
        apagarDados: true,
      },
    });
    expect(await criarCiclo().executar()).toBe('parado');
    expect(ordens).toEqual(['apagar-dados']);
    expect(situacao(id).situacao).toBe('ENVIADO');
  });

  it('apagarDados com operação ainda aguardando (sem resposta): não apaga, a operação fica guardada', async () => {
    const id = idDeTeste(7);
    ops.cadastrarCliente({ id, nome: 'Bia' });
    s.roteirosDoPush.push({
      status: 200,
      corpo: { resultados: [], horaServidor: s.horaServidor, apagarDados: true },
    });
    expect(await criarCiclo().executar()).toBe('falha');
    expect(ordens).toEqual([]);
    expect(situacao(id).situacao).toBe('AGUARDANDO');
  });

  it('computador trocado: operação gravada durante o envio vai no lote seguinte, e só apaga com a fila zerada (issue #127)', async () => {
    const antes = idDeTeste(7);
    const durante = idDeTeste(7);
    const vendaDurante = idDeTeste(7);
    const m = { remessaId: idDeTeste(1), produtoA: idDeTeste(2) };
    s.mudar({
      tabela: 'remessas',
      id: m.remessaId,
      deletedAt: null,
      campos: { status: 'ENVIADA' },
    });
    s.mudar({
      tabela: 'itens_remessa',
      id: idDeTeste(4),
      deletedAt: null,
      campos: {
        remessa_id: m.remessaId,
        produto_id: m.produtoA,
        qtd_enviada: 3,
        preco_remessa: '33.33',
      },
    });
    // A maleta já está no computador (recebida antes da troca); depois a dona troca o computador.
    expect(await criarCiclo().executar()).toBe('ok');
    s.computadorTrocado = true;
    ops.cadastrarCliente({ id: antes, nome: 'Bia' });
    const enviosAntes = s.pedidosPara(PUSH).length;
    // O vendedor não sabe da troca: enquanto o 1º envio está na rede, cadastra um cliente e vende.
    s.durantePush = (n) => {
      if (n !== enviosAntes + 1) return;
      ops.cadastrarCliente({ id: durante, nome: 'Caio' });
      ops.vender({
        id: vendaDurante,
        remessaId: m.remessaId,
        clienteId: durante,
        itens: [{ produtoId: m.produtoA, quantidade: 1 }],
        pagamentos: [],
      });
    };

    expect(await criarCiclo().executar()).toBe('parado');

    const pushes = s.pedidosPara(PUSH).slice(enviosAntes);
    // O 1º lote saiu dizendo "nada mais aguardando" e voltou com a ordem de apagar...
    expect(operacoesDoPush(enviosAntes).map((o) => o.operacaoId)).toEqual([antes]);
    expect(JSON.parse(pushes[0]!.corpo)).toMatchObject({ aguardandoDepoisDeste: 0 });
    // ...mas o computador tinha gravado mais 2: mandou o resto antes de obedecer.
    expect(pushes).toHaveLength(2);
    expect(operacoesDoPush(enviosAntes + 1).map((o) => o.operacaoId)).toEqual([
      durante,
      vendaDurante,
    ]);
    expect(JSON.parse(pushes[1]!.corpo)).toMatchObject({ aguardandoDepoisDeste: 0 });
    expect(ordens).toEqual(['apagar-dados']);
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao = 'AGUARDANDO'")).toBe(0);
    expect(s.operacoes.has(antes)).toBe(true);
    expect(s.operacoes.has(durante)).toBe(true);
    expect(s.operacoes.has(vendaDurante)).toBe(true);
  });
});

describe('ciclo', () => {
  it('sem internet: nenhuma rota de dados é chamada', async () => {
    ops.cadastrarCliente({ id: idDeTeste(7), nome: 'Bia' });
    s.saudavel = false;
    const ciclo = criarCiclo();
    expect(await ciclo.executar()).toBe('sem-internet');
    expect(ciclo.comInternet).toBe(false);
    expect(s.pedidos.every((p) => p.caminho === '/actuator/health')).toBe(true);
  });

  it('um ciclo por vez: dois pedidos ao mesmo tempo viram um', async () => {
    ops.cadastrarCliente({ id: idDeTeste(7), nome: 'Bia' });
    s.atraso = 50;
    const ciclo = criarCiclo();
    const [r1, r2] = await Promise.all([ciclo.executar(), ciclo.executar()]);
    expect(r1).toBe('ok');
    expect(r2).toBe('ok');
    expect(s.pedidosPara(PUSH)).toHaveLength(1);
    expect(s.pedidosPara('/actuator/health')).toHaveLength(1);
  });

  it('sessão vencida: renova uma vez pela fila única e continua', async () => {
    const renovar = vi.fn((): Promise<Resultado<Credenciais>> =>
      Promise.resolve({
        ok: true,
        dados: {
          tokenDeAcesso: 'novo',
          tokenDeRenovacao: 'r2',
          validadeEmSegundos: 900,
          trocaDeSenhaObrigatoria: false,
        },
      }),
    );
    const guarda = new GuardaDeTokens({
      api: {
        entrar: () =>
          Promise.resolve({
            ok: true,
            dados: {
              tokenDeAcesso: 'velho',
              tokenDeRenovacao: 'r1',
              validadeEmSegundos: 900,
              trocaDeSenhaObrigatoria: false,
            },
          }),
        renovar,
        trocarSenha: () => Promise.reject(new Error('não usado')),
        sair: () => Promise.resolve({ ok: true, dados: null }),
      },
      cofre: {
        disponivel: () => Promise.resolve(false),
        cifrar: () => Promise.reject(new Error()),
        decifrar: () => Promise.reject(new Error()),
      },
      arquivo: {
        ler: () => Promise.resolve(null),
        gravar: () => Promise.resolve(),
        apagar: () => Promise.resolve(),
      },
      relogio: { agora: () => Date.now(), agendar: () => () => undefined },
    });
    await guarda.entrar('vendedor', 'senha');
    s.tokenValido = 'novo';
    ops.cadastrarCliente({ id: idDeTeste(7), nome: 'Bia' });
    const ciclo = criarCiclo({ comToken: (fn) => guarda.comToken(fn) });
    expect(await ciclo.executar()).toBe('ok');
    expect(renovar).toHaveBeenCalledTimes(1);
    expect(contar(b.db, "SELECT 1 FROM outbox WHERE situacao = 'ENVIADO'")).toBe(1);
  });
});

describe('época e conferência', () => {
  it('CURSOR_INVALIDO: recarrega do zero mantendo as provisórias e a outbox', async () => {
    const m = await maletaRecebida();
    const venda = vender(m, 1);
    s.resultadoPara = () => 'NAO_PROCESSADO'; // a venda continua aguardando
    s.epoca = 2; // o servidor mudou de época: o cursor guardado não vale mais
    s.pedidos.length = 0;
    await criarCiclo().executar();
    expect(s.pedidosPara(PULL).some((p) => !p.query.get('cursor'))).toBe(true);
    expect(situacao(venda).situacao).toBe('AGUARDANDO');
    expect(b.db.prepare('SELECT origem_local FROM vendas WHERE id = ?').get(venda)).toEqual({
      origem_local: 'PROVISORIO',
    });
    expect(saldoDaPeca(b.db, m.remessaId, m.produtoA)).toBe(2);
    expect(lerEstado(b.db, CHAVES.cursor)).toMatch(/^2:/);
  });

  it('a conferência só roda depois de receber tudo', async () => {
    s.mudar({ tabela: 'categorias', id: idDeTeste(6), deletedAt: null, campos: { nome: 'a' } });
    s.mudar({ tabela: 'categorias', id: idDeTeste(6), deletedAt: null, campos: { nome: 'b' } });
    s.mudar({ tabela: 'categorias', id: idDeTeste(6), deletedAt: null, campos: { nome: 'c' } });
    s.roteirosDoPull.push(
      {
        status: 200,
        corpo: { linhas: [], cursor: '1:0', temMais: true, horaServidor: s.horaServidor },
      },
      { status: 500 },
    );
    expect(await criarCiclo().executar()).toBe('falha');
    expect(s.pedidosPara('/api/vendedor/sync/conferencia')).toHaveLength(0);
    expect(await criarCiclo().executar()).toBe('ok');
    expect(s.pedidosPara('/api/vendedor/sync/conferencia')).toHaveLength(1);
    expect(lerEstado(b.db, CHAVES.conferidoEm)).toBe(s.horaServidor);
    // No mesmo dia não confere de novo.
    await criarCiclo().executar();
    expect(s.pedidosPara('/api/vendedor/sync/conferencia')).toHaveLength(1);
  });

  it('diferença passageira não recarrega nem avisa', async () => {
    s.mudar({ tabela: 'produtos', id: idDeTeste(2), deletedAt: null, campos: { nome: 'p' } });
    s.roteirosDaConferencia.push({
      status: 200,
      corpo: {
        tabelas: [{ tabela: 'produtos', quantidade: 9, resumo: 'outro' }],
        horaServidor: s.horaServidor,
      },
    });
    expect(await criarCiclo().executar()).toBe('ok');
    expect(s.pedidosPara('/api/vendedor/sync/conferencia')).toHaveLength(2);
    expect(s.pedidosPara('/api/vendedor/sync/conferencia/diferencas')).toHaveLength(0);
    expect(s.pedidosPara(PULL).some((p) => p.query.get('tabela'))).toBe(false);
  });

  it('diferença persistente: avisa o servidor e recarrega só aquela tabela', async () => {
    const p = idDeTeste(2);
    s.mudar({ tabela: 'produtos', id: p, deletedAt: null, campos: { nome: 'p' } });
    // A cópia local tem uma peça a mais que o servidor não mostra mais (furo do filtro).
    const sobrando = idDeTeste(2);
    await criarCiclo().executar();
    b.db
      .prepare("INSERT INTO produtos (id, version, nome) VALUES (?, 1, 'sobrando')")
      .run(sobrando);
    gravarEstado(b.db, CHAVES.conferidoEm, null);
    s.pedidos.length = 0;
    expect(await criarCiclo().executar()).toBe('ok');
    const aviso = s.pedidosPara('/api/vendedor/sync/conferencia/diferencas');
    expect(aviso).toHaveLength(1);
    expect(JSON.parse(aviso[0]?.corpo ?? '{}')).toEqual({ tabelas: ['produtos'] });
    expect(s.pedidosPara(PULL).filter((x) => x.query.get('tabela') === 'produtos')).toHaveLength(1);
    expect(contar(b.db, 'SELECT 1 FROM produtos WHERE id = ?', sobrando)).toBe(0);
    expect(contar(b.db, 'SELECT 1 FROM produtos WHERE id = ?', p)).toBe(1);
  });

  it('queda depois de apagar a tabela e antes de recebê-la: a próxima rodada confere e recarrega', async () => {
    const p = idDeTeste(2);
    s.mudar({ tabela: 'produtos', id: p, deletedAt: null, campos: { nome: 'p' } });
    await criarCiclo().executar();
    b.db
      .prepare("INSERT INTO produtos (id, version, nome) VALUES (?, 1, 'sobrando')")
      .run(idDeTeste(2));
    // Conferência do dia (não é depois de carga): o aviso de conferir de novo começa vazio.
    gravarEstado(b.db, CHAVES.conferidoEm, null);
    expect(lerEstado(b.db, CHAVES.conferirDepoisDaCarga)).toBeNull();
    s.cairNoPullDaTabela = 1;
    expect(await criarCiclo().executar()).toBe('falha');
    // A tabela foi apagada, e na MESMA transação ficou marcado que é preciso conferir de novo.
    expect(contar(b.db, 'SELECT 1 FROM produtos')).toBe(0);
    expect(lerEstado(b.db, CHAVES.conferirDepoisDaCarga)).toBe('1');
    expect(await criarCiclo().executar()).toBe('ok');
    expect(contar(b.db, 'SELECT 1 FROM produtos WHERE id = ?', p)).toBe(1);
    expect(lerEstado(b.db, CHAVES.conferirDepoisDaCarga)).toBeNull();
  });

  it('cursor nulo do servidor: mantém o cursor anterior e nunca grava texto vazio', async () => {
    const vazio = { linhas: [], cursor: null, temMais: false, horaServidor: s.horaServidor };
    s.roteirosDoPull.push({ status: 200, corpo: vazio });
    await criarCiclo().executar();
    expect(lerEstado(b.db, CHAVES.cursor)).toBeNull();
    expect(
      b.db.prepare("SELECT count(*) AS n FROM estado_sync WHERE chave = 'cursor'").get(),
    ).toEqual({ n: 0 });

    s.mudar({ tabela: 'categorias', id: idDeTeste(6), deletedAt: null, campos: { nome: 'a' } });
    await criarCiclo().executar();
    const anterior = lerEstado(b.db, CHAVES.cursor);
    expect(anterior).toMatch(/^1:/);
    s.roteirosDoPull.push({ status: 200, corpo: vazio });
    await criarCiclo().executar();
    expect(lerEstado(b.db, CHAVES.cursor)).toBe(anterior);
  });
});
