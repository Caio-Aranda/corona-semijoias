import { describe, expect, it, vi } from 'vitest';
import { ehEnderecoDoWhatsapp, enderecoDoWhatsapp, numeroParaWhatsapp } from '../shared/whatsapp';
import {
  type DependenciasDaSaida,
  nomeDoArquivo,
  SaidaDosRelatorios,
} from './saida-dos-relatorios';

/**
 * Imprimir, salvar em PDF e preparar o WhatsApp (tarefa 9.3; §23.11). O sistema só guarda o PDF e ABRE a conversa com o
 * número e a mensagem prontos; nada é enviado sozinho. Sem internet o PDF fica guardado e o WhatsApp não abre.
 */
function dependencias(parcial: Partial<DependenciasDaSaida> = {}) {
  const gravados: { caminho: string; bytes: Uint8Array }[] = [];
  const abertos: string[] = [];
  const mostrados: string[] = [];
  const d: DependenciasDaSaida = {
    imprimirPagina: () => Promise.resolve(true),
    paginaEmPdf: () => Promise.resolve(new Uint8Array([37, 80, 68, 70])),
    escolherOndeSalvar: (nome) => Promise.resolve(`C:/Docs/${nome}`),
    pastaDosRelatorios: () => 'C:/Docs/Corona Vendas/Relatórios',
    gravar: (caminho, bytes) => {
      gravados.push({ caminho, bytes });
      return Promise.resolve();
    },
    mostrarNaPasta: (c) => {
      mostrados.push(c);
    },
    abrirNoNavegador: (e) => {
      abertos.push(e);
      return Promise.resolve();
    },
    comInternet: () => true,
    ...parcial,
  };
  return { d, saida: new SaidaDosRelatorios(d), gravados, abertos, mostrados };
}

describe('número do WhatsApp', () => {
  it('acrescenta o código do país: Brasil (55) e Paraguai (595)', () => {
    expect(numeroParaWhatsapp('(67) 99999-1234')).toBe('5567999991234');
    expect(numeroParaWhatsapp('67 3431-1234')).toBe('556734311234');
    expect(numeroParaWhatsapp('+55 67 99999-1234')).toBe('5567999991234');
    expect(numeroParaWhatsapp('0981 123 456')).toBe('595981123456');
    expect(numeroParaWhatsapp('+595 981 123456')).toBe('595981123456');
    expect(numeroParaWhatsapp('00 55 67 99999-1234')).toBe('5567999991234');
  });

  it('telefone vazio ou curto não serve', () => {
    expect(numeroParaWhatsapp(null)).toBeNull();
    expect(numeroParaWhatsapp('')).toBeNull();
    expect(numeroParaWhatsapp('123')).toBeNull();
  });

  it('monta o endereço da conversa com a mensagem pronta, e só aceita conversa do WhatsApp', () => {
    const e = enderecoDoWhatsapp('(67) 99999-1234', 'Olá, Ana! Segue a nota 0000064-21.');
    expect(e).toBe(
      'https://wa.me/5567999991234?text=Ol%C3%A1%2C%20Ana!%20Segue%20a%20nota%200000064-21.',
    );
    expect(ehEnderecoDoWhatsapp((e ?? '').split('?')[0] ?? '')).toBe(true);
    expect(enderecoDoWhatsapp(null, 'oi')).toBeNull();
    expect(enderecoDoWhatsapp(null, 'oi', true)).toBe('https://wa.me/?text=oi');
    expect(ehEnderecoDoWhatsapp('https://evil.example/5567999991234')).toBe(false);
    expect(ehEnderecoDoWhatsapp('http://wa.me/5567999991234')).toBe(false);
    expect(ehEnderecoDoWhatsapp('https://wa.me.evil.example/5567999991234')).toBe(false);
    expect(ehEnderecoDoWhatsapp('file:///C:/Windows/System32/calc.exe')).toBe(false);
  });
});

describe('nome do arquivo', () => {
  it('limpa o que o Windows recusa e termina em .pdf', () => {
    expect(nomeDoArquivo('Nota de saida 0000064-21')).toBe('Nota de saida 0000064-21.pdf');
    expect(nomeDoArquivo(String.raw`..\..\Windows\sistema: <a>?`)).toBe('Windows-sistema- -a.pdf');
    expect(nomeDoArquivo('   ')).toBe('relatorio.pdf');
    expect(nomeDoArquivo('Acerto.pdf')).toBe('Acerto.pdf');
  });
});

describe('Imprimir e Salvar em PDF', () => {
  it('imprimir abre a impressão; cancelar não faz nada', async () => {
    expect(await dependencias().saida.imprimir()).toEqual({ ok: true });
    const cancelou = dependencias({ imprimirPagina: () => Promise.resolve(false) });
    expect(await cancelou.saida.imprimir()).toEqual({ ok: false, motivo: 'cancelado' });
  });

  it('salvar em PDF pergunta onde guardar e grava o PDF; cancelar não grava nada', async () => {
    const a = dependencias();
    const r = await a.saida.salvarEmPdf('Nota de saida 0000064-21');
    expect(r).toEqual({ ok: true, arquivo: 'C:/Docs/Nota de saida 0000064-21.pdf' });
    expect(a.gravados).toHaveLength(1);
    expect(Array.from(a.gravados[0]?.bytes ?? [])).toEqual([37, 80, 68, 70]);
    const b = dependencias({ escolherOndeSalvar: () => Promise.resolve(null) });
    expect(await b.saida.salvarEmPdf('x')).toEqual({ ok: false, motivo: 'cancelado' });
    expect(b.gravados).toEqual([]);
  });

  it('erro de disco vira "erro", sem estourar', async () => {
    const a = dependencias({ gravar: () => Promise.reject(new Error('disco cheio')) });
    expect(await a.saida.salvarEmPdf('x')).toEqual({ ok: false, motivo: 'erro' });
  });
});

describe('Preparar para o WhatsApp', () => {
  it('guarda o PDF, mostra a pasta e abre a conversa do cliente com a mensagem pronta (nada é enviado sozinho)', async () => {
    const a = dependencias();
    const r = await a.saida.prepararWhatsapp('(67) 99999-1234', 'Olá!', 'Nota de saida 64-21');
    expect(r).toEqual({
      ok: true,
      arquivo: expect.stringContaining('Nota de saida 64-21.pdf') as string,
      pasta: 'C:/Docs/Corona Vendas/Relatórios',
    });
    expect(a.gravados).toHaveLength(1);
    expect(a.mostrados).toHaveLength(1);
    expect(a.abertos).toEqual(['https://wa.me/5567999991234?text=Ol%C3%A1!']);
  });

  it('sem internet o PDF fica guardado e o WhatsApp não abre', async () => {
    const a = dependencias({ comInternet: () => false });
    const r = await a.saida.prepararWhatsapp('(67) 99999-1234', 'Olá!', 'Nota');
    expect(r).toMatchObject({ ok: false, motivo: 'sem-internet' });
    expect(a.gravados).toHaveLength(1);
    expect(a.abertos).toEqual([]);
  });

  it('sem telefone não guarda nem abre nada', async () => {
    const a = dependencias();
    expect(await a.saida.prepararWhatsapp(null, 'Olá!', 'Nota')).toEqual({
      ok: false,
      motivo: 'sem-telefone',
    });
    expect(a.gravados).toEqual([]);
    expect(a.abertos).toEqual([]);
  });

  it('relatório do próprio representante abre o WhatsApp sem número', async () => {
    const a = dependencias();
    const r = await a.saida.prepararWhatsapp(null, 'Totais da semana 64', 'Totais', true);
    expect(r.ok).toBe(true);
    expect(a.abertos).toEqual(['https://wa.me/?text=Totais%20da%20semana%2064']);
  });

  it('o processo principal só abre endereço do WhatsApp (o telefone não consegue desviar o endereço)', async () => {
    const abrir = vi.fn(() => Promise.resolve());
    const a = dependencias({ abrirNoNavegador: abrir });
    await a.saida.prepararWhatsapp('67999991234/../../evil', 'x', 'Nota');
    for (const [endereco] of abrir.mock.calls as unknown as [string][])
      expect(endereco.startsWith('https://wa.me/')).toBe(true);
  });
});
