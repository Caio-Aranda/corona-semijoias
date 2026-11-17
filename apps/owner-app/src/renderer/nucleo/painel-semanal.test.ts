import { describe, expect, it } from 'vitest';
import { gerarCsv } from './exportacao/csv';
import { montarGrade } from './exportacao/grade';
import {
  guaraniNaTela,
  lerMoedas,
  lerPainel,
  pedidoDeExportacao,
  registrosDaOrigem,
} from './painel-semanal';

const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_SEM = '7a1d6c2e-0b3f-4a8e-9d52-5e1f0c3b8a11';
const ID_PRACA = '5d0c1c0b-6a53-4a4e-a1c3-0f6f3a2b9e77';

const M = (real: string, dolar: string | null, guarani: string | null) => ({
  emReais: real,
  emDolar: dolar,
  emGuarani: guarani,
});

const BRUTO = {
  semana: { id: ID_SEM, numero: 46, inicio: '2026-11-09', fim: '2026-11-15', situacao: 'ABERTA' },
  previaIncluiSemanasAnteriores: true,
  avisoDaPrevia: 'Prévia.',
  pedidos: [
    {
      acertoId: null,
      vendedorId: ID_VEND,
      vendedor: 'Maria Souza',
      pracaId: ID_PRACA,
      praca: 'Ciudad del Este',
      situacao: 'EM_ABERTO',
      previa: true,
      dataDaCotacao: '2026-11-12',
      enviadoPecas: 10,
      devolvidoPecas: 2,
      vendidasPecas: 6,
      emFaltaPecas: 2,
      enviado: M('1000.00', '200.00', '1400000'),
      devolvido: M('230.00', '46.00', '322000'),
      vendidoLiquido: M('430.00', '86.00', '602000'),
      comissao: M('86.00', '17.20', '120400'),
      liquido: M('344.00', '68.80', '481600'),
      maletas: [{ id: 'm1', numero: 'M-12', tipo: 'NOVA', situacao: 'ABERTA' }],
    },
    { semId: 'sem vendedorId: ignorada' },
  ],
  totais: {
    novasMaletas: {
      quantidade: 1,
      pecas: 10,
      valor: M('1000.00', '200.00', '1400000'),
      origem: {
        rota: '/api/dona/relatorios/maleta/remessas',
        filtros: { semanaId: ID_SEM, vendedorId: ID_VEND, outro: 'x' },
      },
    },
    vendas: {
      quantidade: 4,
      liquido: M('430.00', '86.00', '602000'),
      origem: {
        rota: '/api/dona/relatorios/vendas/vendas',
        filtros: { de: '2026-11-09', ate: '2026-11-15', pracaId: ID_PRACA },
      },
    },
    previa: { linhas: 1, semCotacao: false },
    total: {
      enviado: M('1000.00', '200.00', '1400000'),
      vendido: M('430.00', '86.00', '602000'),
      liquido: M('344.00', '68.80', '481600'),
    },
  },
};

describe('painel da semana: leitura da resposta da API', () => {
  it('lê a semana, a linha e as três moedas sem fazer conta', () => {
    const p = lerPainel(BRUTO);
    expect(p.semana).toMatchObject({ numero: 46, inicio: '2026-11-09', fim: '2026-11-15' });
    expect(p.linhas).toHaveLength(1);
    const l = p.linhas[0]!;
    expect(l.vendedor).toBe('Maria Souza');
    expect(l.previa).toBe(true);
    expect(l.enviado).toEqual({ real: '1000.00', dolar: '200.00', guarani: '1400000.00' });
    expect(l.maletas[0]?.numero).toBe('M-12');
    expect(p.previaIncluiSemanasAnteriores).toBe(true);
  });

  it('o que não tem o formato esperado vira vazio, sem derrubar', () => {
    const p = lerPainel(null);
    expect(p.linhas).toEqual([]);
    expect(p.semana).toBeNull();
    expect(lerMoedas({ emReais: 'abc' })).toBeNull();
    expect(lerMoedas({ emReais: '10.00' })).toEqual({ real: '10.00', dolar: null, guarani: null });
  });

  it('monta os cartões dos totais com a origem de cada número', () => {
    const p = lerPainel(BRUTO);
    const novas = p.cartoes.find((c) => c.chave === 'novasMaletas')!;
    expect(novas.itens.find((i) => i.rotulo === 'Peças')?.quantidade).toBe(10);
    expect(novas.origens).toHaveLength(1);
    const total = p.cartoes.find((c) => c.chave === 'total')!;
    expect(total.origens).toEqual([]);
  });

  it('Guarani sai inteiro, com pontos', () => {
    expect(guaraniNaTela('1400000.00')).toBe('Gs. 1.400.000');
  });
});

describe('painel da semana: do total ao registro', () => {
  it('leva ao relatório de onde o número saiu, só com os filtros que a tela entende', () => {
    const p = lerPainel(BRUTO);
    const origem = p.cartoes.find((c) => c.chave === 'novasMaletas')!.origens[0]!.origem;
    expect(registrosDaOrigem(origem)).toEqual({
      relatorio: 'maleta-remessas',
      filtros: { semanaId: ID_SEM, vendedorId: ID_VEND },
    });
    const vendas = p.cartoes.find((c) => c.chave === 'vendas')!.origens[0]!.origem;
    expect(registrosDaOrigem(vendas)).toEqual({
      relatorio: 'vendas-lista',
      filtros: { de: '2026-11-09', ate: '2026-11-15', pracaId: ID_PRACA },
    });
  });

  it('rota desconhecida não vira botão', () => {
    expect(registrosDaOrigem({ rota: '/api/dona/outra', filtros: {} })).toBeNull();
  });
});

describe('painel da semana: exportação', () => {
  it('a grade leva as três moedas de cada linha e a data da cotação, e os totais da API', () => {
    const p = lerPainel(BRUTO);
    const grade = montarGrade(
      pedidoDeExportacao(p, {
        filtrosEmPalavras: ['Semana: 46'],
        geradoEm: '12/11/2026 às 09:30',
        geradoPor: 'Dona',
      }),
    );
    const i = grade.titulos.indexOf('Enviado (R$)');
    expect(i).toBeGreaterThan(0);
    expect(grade.titulos.slice(i, i + 3)).toEqual([
      'Enviado (R$)',
      'Enviado (US$)',
      'Enviado (Gs.)',
    ]);
    expect(grade.linhas[0]!.slice(i, i + 3).map((c) => c.v)).toEqual([
      '1000.00',
      '200.00',
      '1400000',
    ]);
    expect(grade.titulos).toContain('Data da cotação');
    const total = grade.rodape.find((r) => r[0]!.v === 'Total enviado')!;
    expect(total.slice(i, i + 3).map((c) => c.v)).toEqual(['1000.00', '200.00', '1400000']);
    const csv = gerarCsv(grade);
    expect(csv).toContain('Maria Souza');
    expect(csv).toContain('Maleta em aberto');
  });
});
