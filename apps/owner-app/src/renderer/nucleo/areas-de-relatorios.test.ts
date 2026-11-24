import { describe, expect, it } from 'vitest';
import { AREAS_DE_RELATORIOS, areaDaRota, cartoesDaArea, rotaDaArea } from './areas-de-relatorios';
import { MENU, ROTAS, ehRotaDoAcertoAntigo, lerRota } from './rotas';

const GRUPO = MENU.find((g) => g.titulo === 'Relatórios');

describe('menu de relatórios por área', () => {
  it('o menu tem um grupo só "Relatórios", com um item por área', () => {
    expect(GRUPO?.itens.map((i) => i.rotulo)).toEqual([
      'Cadastros',
      'Estoque',
      'Compras',
      'Maletas',
      'Devoluções e trocas',
      'Vendas',
      'Financeiro',
      'Controle',
      'Conferências',
    ]);
    expect(MENU.filter((g) => g.titulo?.startsWith('Relatórios')).length).toBe(1);
  });

  it('nenhum relatório aparece mais como item do menu', () => {
    const chaves = MENU.flatMap((g) => g.itens.map((i) => i.chave));
    expect(chaves.filter((c) => c.startsWith('relatorios/'))).toEqual([]);
  });

  it('cada área tem rota própria e a rota existe', () => {
    for (const a of AREAS_DE_RELATORIOS) {
      expect(ROTAS).toContain(rotaDaArea(a.chave));
      expect(lerRota(`#/${rotaDaArea(a.chave)}`)).toBe(rotaDaArea(a.chave));
    }
  });

  it('cada cartão leva a uma rota que continua existindo, sem repetir em duas áreas', () => {
    const vistas = new Set<string>();
    for (const a of AREAS_DE_RELATORIOS) {
      const cartoes = cartoesDaArea(a.chave);
      expect(cartoes.length).toBeGreaterThan(0);
      for (const c of cartoes) {
        expect(ROTAS).toContain(c.rota);
        expect(c.titulo.length).toBeGreaterThan(0);
        expect(c.explicacao.length).toBeGreaterThan(0);
        expect(vistas.has(c.rota)).toBe(false);
        vistas.add(c.rota);
        expect(areaDaRota(c.rota)).toBe(a.chave);
      }
    }
  });

  it('todos os relatórios que abriam pelo menu continuam com cartão', () => {
    const todos = new Set(
      AREAS_DE_RELATORIOS.flatMap((a) => cartoesDaArea(a.chave).map((c) => c.rota)),
    );
    for (const r of [
      'relatorios/produtos',
      'relatorios/estoque-posicao',
      'relatorios/estoque-conferencia',
      'relatorios/vendas-curva-abc',
      'relatorios/compras-custos',
      'relatorios/maleta-saldo',
      'relatorios/devolucao-conferencia',
      'relatorios/financeiro-caixa',
      'relatorios/controle-acessos',
      'conciliacoes/dinheiro',
      'conciliacoes/conferencia-diaria',
    ]) {
      expect(todos.has(r as never), r).toBe(true);
    }
  });

  it('as áreas certas listam os relatórios certos', () => {
    expect(cartoesDaArea('vendas').map((c) => c.rota)).toContain('relatorios/vendas-lista');
    expect(cartoesDaArea('vendas').map((c) => c.rota)).not.toContain('relatorios/maleta-saldo');
    expect(areaDaRota('relatorios/maleta-saldo')).toBe('maletas');
    expect(areaDaRota('cadastros/produtos')).toBeNull();
    expect(areaDaRota('inicio')).toBeNull();
  });

  it('com o acerto antigo escondido, a área Financeiro não lista os quatro relatórios dele (§7.4)', () => {
    const escondidos = [
      'relatorios/financeiro-acertos',
      'relatorios/financeiro-pendencias',
      'relatorios/financeiro-esperado-contado',
      'relatorios/financeiro-comissoes',
    ];
    const todos = cartoesDaArea('financeiro').map((c) => c.rota);
    const visiveis = cartoesDaArea('financeiro', false).map((c) => c.rota);
    for (const r of escondidos) {
      expect(todos).toContain(r);
      expect(visiveis).not.toContain(r);
      expect(ehRotaDoAcertoAntigo(r as never)).toBe(true);
    }
    expect(visiveis).toContain('relatorios/financeiro-caixa');
  });
});
