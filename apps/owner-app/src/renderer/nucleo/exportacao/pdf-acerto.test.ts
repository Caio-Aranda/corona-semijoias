import { describe, expect, it } from 'vitest';
import { gerarPdfDoAcerto, type AcertoParaPdf } from './pdf-acerto';

const texto = (b: Uint8Array) => new TextDecoder('latin1').decode(b);
const paginas = (pdf: string) => (pdf.match(/\/Type \/Page /g) ?? []).length;
const noPdf = (s: string) => s.replace(/[()\\]/g, (c) => `\\${c}`);

function acerto(linhasNaTabela: number, extra: Partial<AcertoParaPdf> = {}): AcertoParaPdf {
  return {
    loja: 'Corona Semijoias',
    titulo: 'Acerto de Ana Representante, semana 45',
    cabecalho: ['Fechado em 12/11/2026 às 12:00.'],
    secoes: [
      {
        titulo: 'Comissão e meta',
        linhas: [
          { texto: 'Vendido na semana: Gs. 750.000 de Gs. 700.000 (meta)' },
          { texto: 'Bateu a meta: comissão de 40%.', negrito: true },
        ],
      },
      {
        titulo: 'Totais do acerto',
        linhas: [
          { rotulo: 'Vendido', valor: 'R$ 1.000,00' },
          { rotulo: 'Valor do acerto', valor: 'R$ 777,77', negrito: true },
        ],
      },
      {
        titulo: 'Peças do acerto',
        tabela: {
          titulos: ['Peça', 'Saiu', 'Faltou'],
          pesos: [3, 1, 1],
          linhas: Array.from({ length: linhasNaTabela }, (_, i) => [
            `Anel de ouro número ${i + 1} (0${i})`,
            '5',
            '1',
          ]),
        },
      },
    ],
    rodape: 'Valores em Dólar e Guarani pela cotação de 12/11/2026. Vale o valor em reais.',
    ...extra,
  };
}

describe('PDF do acerto', () => {
  it('cabe numa página, com título, meta, totais, peças e a frase da cotação', () => {
    const pdf = texto(gerarPdfDoAcerto(acerto(3)));
    expect(pdf.startsWith('%PDF-1.4')).toBe(true);
    expect(paginas(pdf)).toBe(1);
    for (const t of [
      'Corona Semijoias',
      'Acerto de Ana Representante, semana 45',
      'Fechado em 12/11/2026 às 12:00.',
      'Vendido na semana: Gs. 750.000 de Gs. 700.000 (meta)',
      'Bateu a meta: comissão de 40%.',
      'Valor do acerto',
      'R$ 777,77',
      'Anel de ouro número 1 (00)',
      'Valores em Dólar e Guarani pela cotação de 12/11/2026. Vale o valor em reais.',
      'Página 1 de 1',
    ]) {
      expect(pdf, `o PDF deve ter "${t}"`).toContain(noPdf(t));
    }
  });

  it('muitas peças passam para outras páginas e o título repete em cada uma', () => {
    const pdf = texto(gerarPdfDoAcerto(acerto(120)));
    const n = paginas(pdf);
    expect(n).toBeGreaterThan(1);
    expect(pdf).toContain(`Página ${n} de ${n}`);
    expect(pdf).toContain('Anel de ouro número 120');
    expect(
      (pdf.match(/Acerto de Ana Representante, semana 45/g) ?? []).length,
    ).toBeGreaterThanOrEqual(n);
  });

  it('dinheiro nunca passa por conta aqui: o PDF só desenha o texto que recebeu', () => {
    const pdf = texto(
      gerarPdfDoAcerto(
        acerto(1, {
          secoes: [{ titulo: 'Totais', linhas: [{ rotulo: 'Vendido', valor: 'R$ 3,33' }] }],
        }),
      ),
    );
    expect(pdf).toContain('R$ 3,33');
  });
});
