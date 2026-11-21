import { describe, expect, it } from 'vitest';
import { gerarPdfDaNota, lerNota, type NotaParaPdf } from './pdf-nota';

const texto = (b: Uint8Array) => new TextDecoder('latin1').decode(b);
const paginas = (pdf: string) => (pdf.match(/\/Type \/Page /g) ?? []).length;

function nota(quantas: number, extra: Partial<NotaParaPdf> = {}): NotaParaPdf {
  return {
    loja: 'Corona Semijoias',
    numero: 'S12-V7-1',
    vendedor: 'Maria Souza',
    praca: 'Ciudad del Este',
    semana: '12',
    tipo: 'Nova',
    enviadaEm: '10/11/2026 às 09:30',
    cancelada: false,
    motivoDoCancelamento: '',
    itens: Array.from({ length: quantas }, (_, i) => ({
      codigo: `A${i + 1}`,
      nome: `Anel de ouro número ${i + 1}`,
      tamTon: i === 0 ? 'Ouro rosé' : '',
      quantidade: '2',
      preco: 'R$ 10,00',
      subtotal: 'R$ 20,00',
    })),
    totalPecas: String(quantas * 2),
    totalValor: String(quantas * 20),
    cotacao: { data: '2026-11-09', totalEmDolar: '36.35', totalEmGuarani: '224000.00' },
    ...extra,
  };
}

describe('PDF da nota de remessa', () => {
  it('uma maleta pequena cabe numa página, com cabeçalho, peças, totais e as duas assinaturas', () => {
    const pdf = texto(gerarPdfDaNota(nota(3)));
    expect(pdf.startsWith('%PDF-1.4')).toBe(true);
    expect(paginas(pdf)).toBe(1);
    for (const t of [
      'Corona Semijoias',
      'Nota de remessa',
      'S12-V7-1',
      'Maria Souza',
      'Ciudad del Este',
      'Anel de ouro número 1',
      'Tam./Ton.',
      'Ouro rosé',
      'Total de peças: 6',
      'Valor total: R$ 60,00',
      'Cotação do Banco Central do dia do envio \\(09/11/2026\\)',
      'US$ 36,35',
      'Gs. 224.000',
      'Dona',
      'Representante: Maria Souza',
      'Página 1 de 1',
    ]) {
      expect(pdf).toContain(
        t
          .replace(/ú/g, '\xfa')
          .replace(/ç/g, '\xe7')
          .replace(/ã/g, '\xe3')
          .replace(/á/g, '\xe1')
          .replace(/ó/g, '\xf3'),
      );
    }
    expect(pdf).not.toContain('CANCELADA');
  });

  it('maleta grande vira várias páginas, com cabeçalho repetido e assinatura só na última', () => {
    const pdf = texto(gerarPdfDaNota(nota(120)));
    const n = paginas(pdf);
    expect(n).toBeGreaterThan(2);
    expect(pdf.match(/Corona Semijoias - Nota de remessa/g)).toHaveLength(n - 1);
    expect(pdf.match(/\(C\xf3digo\)/g)).toHaveLength(n);
    expect(pdf.match(/\(Dona\)/g)).toHaveLength(1);
    const assinatura = pdf.indexOf('(Dona)');
    expect(assinatura).toBeGreaterThan(pdf.indexOf(`P\xe1gina ${n - 1} de ${n}`));
    expect(assinatura).toBeLessThan(pdf.indexOf(`P\xe1gina ${n} de ${n}`));
    expect(pdf).toContain('Anel de ouro número 120'.replace('ú', '\xfa'));
  });

  it('maleta cancelada sai com a marca CANCELADA em destaque', () => {
    const pdf = texto(
      gerarPdfDaNota(nota(2, { cancelada: true, motivoDoCancelamento: 'Erro de representante' })),
    );
    expect(pdf).toContain('(CANCELADA)');
    expect(pdf).toContain('Erro de representante');
  });

  it('sem cotação do dia do envio não escreve Dólar nem Guarani', () => {
    const pdf = texto(gerarPdfDaNota(nota(2, { cotacao: null })));
    expect(pdf).not.toContain('US$');
    expect(pdf).not.toContain('Banco Central');
  });

  it('nome comprido quebra em mais de uma linha sem perder texto', () => {
    const longo =
      'Colar longo de pérolas cultivadas com pingente de zircônia e fecho dourado extra';
    const n = nota(1);
    n.itens[0]!.nome = longo;
    const pdf = texto(gerarPdfDaNota(n));
    expect(pdf).toContain('Colar longo');
    expect(pdf).toContain('dourado extra');
  });

  it('lê a resposta da rota da nota e recusa o que não tem peças', () => {
    expect(lerNota(null)).toBeNull();
    const lida = lerNota({
      loja: 'Corona Semijoias',
      numero: 'S1-V1-1',
      vendedor: { nome: 'Ana' },
      semana: { numero: 1 },
      tipo: 'REPOSICAO',
      itens: [
        { codigo: 'X', nome: 'Brinco', tamTon: 'Aro 16', quantidade: 1, precoRemessa: '5.00', subtotal: '5.00' },
      ],
      totalPecas: 1,
      totalValor: '5.00',
    });
    expect(lida).toMatchObject({ vendedor: 'Ana', tipo: 'Reposição', semana: '1', cotacao: null });
    expect(lida?.itens[0]?.preco).toBe('R$ 5,00');
    expect(lida?.itens[0]?.tamTon).toBe('Aro 16');
  });
});
