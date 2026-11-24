import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { aplicarLote } from '../sync/aplicador-do-pull';
import { OperacoesLocais } from '../sync/operacoes-locais';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from '../sync/testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from '../sync/testes/dados-da-maleta';
import type {
  Cliente,
  Conferencia,
  CotacaoDoDia,
  EnvioNaoAceito,
  Maleta,
  RelatorioAReceber,
  RelatorioDeVendas,
  PaginaDeVendas,
  Venda,
} from '../../shared/dados';
import type { PedidoDeChamada } from '../../shared/operacoes';
import { ConsultasLocais, diaEmBrasilia } from './consultas-locais';

/**
 * As telas leem do banco local (tarefa 5.7): SQLCipher de verdade, arquivo de verdade, nenhuma rede. A resposta tem o
 * mesmo formato que a API dava, e tudo funciona "sem internet" porque nada aqui sabe o que é internet.
 */
let b: BancoDeTeste;
let m: MaletaDeTeste;
let consultas: ConsultasLocais;
let agora = new Date('2026-11-12T13:00:00.000Z'); // 10:00 em Brasília
let avisos: number;

function cotacao(data: string, usd: string, pyg: string) {
  return {
    tabela: 'cotacoes',
    id: idDeTeste(9),
    version: 1,
    deletedAt: null,
    campos: { data_cotacao: data, usd_venda: usd, pyg_venda: pyg },
  };
}

function pedir<T>(pedido: PedidoDeChamada): T {
  const r = consultas.chamar(pedido);
  if (!r.ok) throw new Error(JSON.stringify(r.falha));
  return r.dados as T;
}

beforeEach(() => {
  b = criarBancoDeTeste();
  m = receberMaleta(b.db);
  agora = new Date('2026-11-12T13:00:00.000Z');
  avisos = 0;
  aplicarLote(
    b.db,
    [
      {
        tabela: 'produtos',
        id: m.produtoA,
        version: 1,
        deletedAt: null,
        campos: { nome: 'Anel de prata', preco_venda: '33.33', ativo: 1 },
      },
      {
        tabela: 'produtos',
        id: m.produtoB,
        version: 1,
        deletedAt: null,
        campos: { nome: 'Brinco de ouro', preco_venda: '10.00', ativo: 1 },
      },
      {
        tabela: 'produto_codigos',
        id: idDeTeste(8),
        version: 1,
        deletedAt: null,
        campos: { produto_id: m.produtoA, codigo: 'AN-001', tipo: 'LOJA', principal: 1, ativo: 1 },
      },
      cotacao('2026-11-10', '5.00000000', '0.00070000'),
    ],
    { cursor: 'c1' },
  );
  consultas = new ConsultasLocais({
    banco: () => b.db,
    operacoes: new OperacoesLocais({ banco: () => b.db, agora: () => agora }),
    agora: () => agora,
    aoMudarOEstado: () => {
      avisos += 1;
    },
  });
});
afterEach(() => b.fechar());

const vendaDe = (quantidade = 1, pago = '33.33') => ({
  operacao: 'vendas.vender' as const,
  venda: {
    id: idDeTeste(5),
    remessaId: m.remessaId,
    clienteId: m.clienteId,
    itens: [{ produtoId: m.produtoA, quantidade }],
    pagamentos: [{ id: idDeTeste(6), valor: pago, forma: 'PIX' as const }],
  },
});

describe('maleta e peças, sem internet', () => {
  it('lista a maleta com as peças, o saldo e o valor em aberto', () => {
    const [maleta] = pedir<Maleta[]>({ operacao: 'maletas.listar' });
    expect(maleta?.status).toBe('ENVIADA');
    expect(maleta?.pecasEmAberto).toBe(4);
    expect(maleta?.valorEmAberto).toBe('109.99');
    const anel = maleta?.itens.find((l) => l.produto.nome === 'Anel de prata');
    expect(anel).toMatchObject({ enviada: 3, vendida: 0, saldo: 3, precoUnitario: '33.33' });
    expect(anel?.produto.codigo).toBe('AN-001');
  });

  it('acha a peça pelo código (sem diferenciar maiúsculas) e pelo nome, só dentro da maleta', () => {
    const lida = pedir<{ produto: { nome: string } }>({
      operacao: 'maletas.peca',
      id: m.remessaId,
      codigo: ' an-001 ',
    });
    expect(lida.produto.nome).toBe('Anel de prata');
    const achadas = pedir<{ produto: { nome: string } }[]>({
      operacao: 'maletas.buscarPeca',
      id: m.remessaId,
      texto: 'brinco',
    });
    expect(achadas.map((l) => l.produto.nome)).toEqual(['Brinco de ouro']);
    const fora = consultas.chamar({ operacao: 'maletas.peca', id: m.remessaId, codigo: 'XYZ' });
    expect(fora).toMatchObject({
      ok: false,
      falha: { mensagem: 'Esta peça não consta na sua maleta.' },
    });
  });

  it('conferência feita sem internet aparece na hora e vai para a fila de envio', () => {
    const feita = pedir<Conferencia>({
      operacao: 'maletas.conferir',
      id: m.remessaId,
      conferenciaId: idDeTeste(7),
      itens: [
        { produtoId: m.produtoA, contada: 2 },
        { produtoId: m.produtoB, contada: 1 },
      ],
    });
    expect(feita.divergencias).toBe(1);
    expect(feita.itens.find((i) => i.produtoNome === 'Anel de prata')).toMatchObject({
      esperado: 3,
      contado: 2,
      diferenca: -1,
    });
    const fila = b.db.prepare("SELECT count(*) AS n FROM outbox WHERE tipo = 'CONFERENCIA'").get();
    expect(fila).toEqual({ n: 1 });
  });
});

describe('venda e estorno sem internet', () => {
  it('a venda baixa a maleta, entra nas listas e nos números, e espera o envio', () => {
    const feita = pedir<Venda>(vendaDe(2, '20.00'));
    expect(feita).toMatchObject({
      status: 'CONCLUIDA',
      total: '66.66',
      pago: '20.00',
      aReceber: '46.66',
      clienteNome: 'Ana',
    });
    // A cotação é a de 10/11, a mais nova até o dia da venda, e vem com a data.
    expect(feita.cotacao).toMatchObject({ data: '2026-11-10', dolar: '5.00000000' });
    expect(feita.totalDolar).toBe('13.33');
    expect(feita.totalGuarani).toBe('95229');

    const [maleta] = pedir<Maleta[]>({ operacao: 'maletas.listar' });
    expect(maleta?.itens.find((l) => l.produto.nome === 'Anel de prata')).toMatchObject({
      vendida: 2,
      saldo: 1,
    });
    expect(pedir<PaginaDeVendas>({ operacao: 'vendas.listar' }).itens).toHaveLength(1);
    expect(b.db.prepare("SELECT situacao FROM outbox WHERE tipo = 'VENDA'").get()).toEqual({
      situacao: 'AGUARDANDO',
    });

    const numeros = pedir<RelatorioDeVendas>({ operacao: 'relatorios.vendas', agrupar: 'cliente' });
    expect(numeros.linhas).toMatchObject([{ nome: 'Ana', quantidade: 1, total: '66.66' }]);
    expect(numeros.totais.totalEmDolar).toBe('13.33');
    const devendo = pedir<RelatorioAReceber>({ operacao: 'relatorios.aReceber' });
    expect(devendo.clientes).toMatchObject([{ clienteNome: 'Ana', aReceber: '46.66' }]);
  });

  it('vender mais do que há na maleta é recusado em português, sem gravar nada', () => {
    const r = consultas.chamar(vendaDe(4));
    expect(r).toMatchObject({
      ok: false,
      falha: { mensagem: 'Não há mais peças desta na sua maleta. Confira a quantidade.' },
    });
    expect(pedir<PaginaDeVendas>({ operacao: 'vendas.listar' }).itens).toHaveLength(0);
  });

  it('o estorno devolve as peças, vira reembolso, zera o a receber e guarda o motivo', () => {
    const feita = pedir<Venda>(vendaDe(2, '20.00'));
    const estornada = pedir<Venda>({
      operacao: 'vendas.estornar',
      id: feita.id,
      estornoId: idDeTeste(7),
      motivo: 'Cliente desistiu',
    });
    expect(estornada).toMatchObject({
      status: 'ESTORNADA',
      aReceber: '0.00',
      pago: '0.00',
      motivoDoEstorno: 'Cliente desistiu',
    });
    expect(estornada.estornadaEm).toBe(agora.toISOString());
    const [maleta] = pedir<Maleta[]>({ operacao: 'maletas.listar' });
    expect(maleta?.itens.find((l) => l.produto.nome === 'Anel de prata')?.saldo).toBe(3);
    expect(pedir<PaginaDeVendas>({ operacao: 'vendas.listar', status: 'CONCLUIDA' }).itens).toHaveLength(
      0,
    );
    expect(pedir<PaginaDeVendas>({ operacao: 'vendas.listar', status: 'ESTORNADA' }).itens).toHaveLength(
      1,
    );
    // Estornada não entra nas contas.
    expect(pedir<RelatorioDeVendas>({ operacao: 'relatorios.vendas' }).linhas).toEqual([]);
    expect(pedir<RelatorioAReceber>({ operacao: 'relatorios.aReceber' }).clientes).toEqual([]);
    const denovo = consultas.chamar({
      operacao: 'vendas.estornar',
      id: feita.id,
      estornoId: idDeTeste(7),
      motivo: 'de novo',
    });
    expect(denovo).toMatchObject({
      ok: false,
      falha: { mensagem: 'Esta venda já foi estornada.' },
    });
  });

  it('filtra as vendas pelo período (dia de Brasília) e pelo cliente', () => {
    pedir<Venda>(vendaDe(1));
    expect(
      pedir<PaginaDeVendas>({ operacao: 'vendas.listar', de: '2026-11-12', ate: '2026-11-12' })
        .itens,
    ).toHaveLength(1);
    expect(
      pedir<PaginaDeVendas>({ operacao: 'vendas.listar', de: '2026-11-13' }).itens,
    ).toHaveLength(0);
    expect(
      pedir<PaginaDeVendas>({ operacao: 'vendas.listar', clienteId: idDeTeste(1) }).itens,
    ).toHaveLength(0);
    expect(diaEmBrasilia('2026-11-12T01:00:00Z')).toBe('2026-11-11');
  });

  it('Minhas vendas por página: a mais nova primeiro, com o total do filtro (issue 270)', () => {
    const ids: string[] = [];
    for (let i = 0; i < 3; i++) {
      agora = new Date(agora.getTime() + 60_000);
      const base = vendaDe(1);
      const feita = pedir<Venda>({
        ...base,
        venda: {
          ...base.venda,
          id: idDeTeste(40 + i),
          pagamentos: [{ id: idDeTeste(50 + i), valor: '33.33', forma: 'PIX' as const }],
        },
      });
      ids.unshift(feita.id);
    }
    const primeira = pedir<PaginaDeVendas>({ operacao: 'vendas.listar', pagina: 0, tamanho: 2 });
    expect(primeira).toMatchObject({ total: 3, pagina: 0, tamanho: 2 });
    expect(primeira.itens.map((v) => v.id)).toEqual(ids.slice(0, 2));
    const segunda = pedir<PaginaDeVendas>({ operacao: 'vendas.listar', pagina: 1, tamanho: 2 });
    expect(segunda.itens.map((v) => v.id)).toEqual(ids.slice(2));
    expect(segunda.total).toBe(3);
    // Sem pagina nem tamanho: a primeira página com 20, como no servidor.
    expect(pedir<PaginaDeVendas>({ operacao: 'vendas.listar' })).toMatchObject({
      total: 3,
      pagina: 0,
      tamanho: 20,
    });
    // O filtro vale antes da página.
    expect(
      pedir<PaginaDeVendas>({ operacao: 'vendas.listar', status: 'ESTORNADA', tamanho: 2 }),
    ).toMatchObject({ total: 0, itens: [] });
  });

  it('recebimentos por forma de pagamento, com o reembolso descontado', () => {
    const feita = pedir<Venda>(vendaDe(1, '33.33'));
    let r = pedir<RelatorioDeVendas>({ operacao: 'relatorios.recebimentos' });
    expect(r.linhas).toMatchObject([{ nome: 'PIX', quantidade: 1, total: '33.33' }]);
    pedir<Venda>({
      operacao: 'vendas.estornar',
      id: feita.id,
      estornoId: idDeTeste(7),
      motivo: 'Erro',
    });
    r = pedir<RelatorioDeVendas>({ operacao: 'relatorios.recebimentos' });
    expect(r.linhas).toMatchObject([{ nome: 'PIX', quantidade: 1, total: '0.00' }]);
  });
});

describe('cotação do dia ou a última, com a data', () => {
  const hoje = () => pedir<CotacaoDoDia>({ operacao: 'cotacao.hoje' });

  it('a mais nova até hoje: a de um dia anterior vem marcada, com a data', () => {
    expect(hoje()).toMatchObject({
      disponivel: true,
      dataDeHoje: '2026-11-12',
      dataDaCotacao: '2026-11-10',
      dolar: '5.00000000',
      deDiaAnterior: true,
    });
  });

  it('a de hoje vale como de hoje, e uma de amanhã nunca vale', () => {
    aplicarLote(b.db, [cotacao('2026-11-12', '5.50000000', '0.00075000')], { cursor: 'c2' });
    aplicarLote(b.db, [cotacao('2026-11-13', '9.00000000', '0.00090000')], { cursor: 'c3' });
    expect(hoje()).toMatchObject({ dataDaCotacao: '2026-11-12', deDiaAnterior: false });
  });

  it('a cotação da loja (da dona) vale desde a hora em que ela lançou, e a tela sabe de onde veio', () => {
    // São 10:00 em Brasília (13:00 UTC). O Banco Central do dia vale desde 00:00; a da dona, desde as 09:30.
    aplicarLote(
      b.db,
      [
        {
          tabela: 'cotacoes',
          id: idDeTeste(9),
          version: 1,
          deletedAt: null,
          campos: {
            data_cotacao: '2026-11-12',
            usd_venda: '5.50000000',
            pyg_venda: '0.00075000',
            origem: 'BANCO_CENTRAL',
            vigente_desde: '2026-11-12T03:00:00Z',
          },
        },
        {
          tabela: 'cotacoes',
          id: idDeTeste(9),
          version: 1,
          deletedAt: null,
          campos: {
            data_cotacao: '2026-11-12',
            usd_venda: '5.80000000',
            pyg_venda: '0.00074074',
            origem: 'DONA',
            vigente_desde: '2026-11-12T12:30:00Z',
            guaranis_por_real: '1350.00000000',
          },
        },
      ],
      { cursor: 'c-dona' },
    );
    expect(hoje()).toMatchObject({
      dolar: '5.80000000',
      origem: 'DONA',
      vigenteDesde: '2026-11-12T12:30:00Z',
      deDiaAnterior: false,
    });
    // Mais cedo, antes de ela lançar a dela, valia a do Banco Central.
    agora = new Date('2026-11-12T12:00:00.000Z');
    expect(hoje()).toMatchObject({ dolar: '5.50000000', origem: 'BANCO_CENTRAL' });
  });

  it('sem nenhuma cotação, diz que não há e a venda segue só em Real', () => {
    b.db.prepare('DELETE FROM cotacoes').run();
    expect(hoje()).toMatchObject({ disponivel: false, dataDaCotacao: null });
    const feita = pedir<Venda>(vendaDe(1));
    expect(feita.cotacao).toBeNull();
    expect(feita.totalDolar).toBeNull();
  });

  it('a cotação é só para mostrar: nada é gravado em Dólar nem em Guarani', () => {
    pedir<Venda>(vendaDe(1));
    const colunas = (b.db.prepare('PRAGMA table_info(vendas)').all() as { name: string }[]).map(
      (c) => c.name,
    );
    expect(colunas.filter((c) => /dolar|guarani|usd|pyg/i.test(c))).toEqual([]);
  });
});

describe('clientes', () => {
  it('lista, busca por nome, telefone e CPF, e só mostra o cadastro completo dos próprios clientes', () => {
    aplicarLote(
      b.db,
      [
        {
          tabela: 'clientes',
          id: idDeTeste(3),
          version: 1,
          deletedAt: null,
          campos: { nome: 'Beatriz', cpf: '11122233344', telefone: '67999991111', ativo: 1 },
        },
      ],
      { cursor: 'c4' },
    );
    const todos = pedir<Cliente[]>({ operacao: 'clientes.listar' });
    expect(todos.map((c) => c.nome)).toEqual(['Ana', 'Beatriz']);
    expect(pedir<Cliente[]>({ operacao: 'clientes.listar', busca: 'beat' })).toHaveLength(1);
    expect(pedir<Cliente[]>({ operacao: 'clientes.listar', busca: '(67) 99999' })).toHaveLength(1);
    expect(pedir<Cliente[]>({ operacao: 'clientes.listar', busca: '1112223' })).toHaveLength(1);
    expect(pedir<Cliente[]>({ operacao: 'clientes.listar', busca: 'zzz' })).toEqual([]);
    expect(todos.every((c) => !c.meu)).toBe(true);
  });

  it('cliente cadastrado sem internet vale na hora; CPF repetido mostra o que já existe', () => {
    const novo = pedir<Cliente>({
      operacao: 'clientes.cadastrar',
      cliente: { id: idDeTeste(3), nome: 'Carla', observacoes: 'Gosta de prata' },
    });
    expect(novo).toMatchObject({ nome: 'Carla', meu: true, observacoes: 'Gosta de prata' });
    const repetido = consultas.chamar({
      operacao: 'clientes.cadastrar',
      cliente: { id: idDeTeste(3), nome: 'Outra Ana', cpf: '123.456.789-09' },
    });
    expect(repetido).toMatchObject({ ok: false, falha: { clienteExistente: { nome: 'Ana' } } });
  });

  it('edita só os clientes que o vendedor atende, campo a campo, e a mudança espera o envio', () => {
    const meu = pedir<Cliente>({
      operacao: 'clientes.cadastrar',
      cliente: { id: idDeTeste(3), nome: 'Carla', telefone: '111' },
    });
    const editado = pedir<Cliente>({
      operacao: 'clientes.alterar',
      id: meu.id,
      campos: { telefone: '67 98888-0000', observacoes: '' },
    });
    expect(editado.telefone).toBe('67 98888-0000');
    const fila = b.db.prepare("SELECT dados FROM outbox WHERE tipo = 'CLIENTE_ALTERADO'").all() as {
      dados: string;
    }[];
    expect(fila).toHaveLength(1);
    const campos = (JSON.parse(fila[0]?.dados ?? '{}') as { campos: unknown }).campos;
    expect(campos).toEqual({ telefone: { antes: '111', depois: '67 98888-0000' } });
    // Cliente da loja que não é dele: recusado, sem gravar.
    const deOutro = consultas.chamar({
      operacao: 'clientes.alterar',
      id: m.clienteId,
      campos: { nome: 'Ana Maria' },
    });
    expect(deOutro).toMatchObject({
      ok: false,
      falha: { mensagem: 'Só o vendedor que atende o cliente pode mudar o cadastro dele.' },
    });
    expect(pedir<Cliente>({ operacao: 'clientes.ver', id: m.clienteId }).nome).toBe('Ana');
  });
});

describe('envios que o servidor não aceitou', () => {
  function recusar(tipo: string, dados: object, motivo: string | null, situacao = 'RECUSADO') {
    b.db
      .prepare(
        `INSERT INTO outbox (operacao_id, tipo, formato, dados, criado_em_local, situacao, ultimo_erro)
         VALUES (?, ?, 1, ?, '2026-11-12T12:00:00.000Z', ?, ?)`,
      )
      .run(idDeTeste(5), tipo, JSON.stringify(dados), situacao, motivo);
  }

  it('lista cada um com o que foi, a hora e o motivo em palavras simples; "Entendi" tira da lista', () => {
    recusar(
      'VENDA',
      { clienteId: m.clienteId },
      'Esta peça já não está na maleta. A dona já foi avisada.',
    );
    recusar('CLIENTE_NOVO', { nome: 'Carla' }, null, 'CONFLITO');
    const lista = pedir<EnvioNaoAceito[]>({ operacao: 'envios.naoAceitos' });
    expect(lista).toMatchObject([
      {
        descricao: 'Venda para Ana',
        ehVenda: true,
        feitoEm: '2026-11-12T12:00:00.000Z',
        motivo: 'Esta peça já não está na maleta. A dona já foi avisada.',
      },
      {
        descricao: 'Cliente novo: Carla',
        ehVenda: false,
        motivo: 'Sem motivo informado. Fale com a dona.',
      },
    ]);
    pedir<null>({ operacao: 'envios.entendi', id: lista[0]?.id ?? '' });
    expect(avisos).toBe(1);
    const resto = pedir<EnvioNaoAceito[]>({ operacao: 'envios.naoAceitos' });
    expect(resto.map((e) => e.descricao)).toEqual(['Cliente novo: Carla']);
    // Nada é apagado: a linha continua na fila, só marcada como entendida.
    expect(b.db.prepare('SELECT count(*) AS n FROM outbox').get()).toEqual({ n: 2 });
  });
});

describe('sem banco aberto', () => {
  it('diz que não está disponível, para a chamada seguir pela internet', () => {
    const fechado = new ConsultasLocais({
      banco: () => null,
      operacoes: new OperacoesLocais({ banco: () => b.db }),
    });
    expect(fechado.disponivel).toBe(false);
  });
});
