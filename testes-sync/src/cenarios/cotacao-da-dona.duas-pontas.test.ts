import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { consultar, contar, texto } from '../banco-do-servidor';
import { Cenario, sementeDaRodada, Tela } from '../cenario';
import { registrarCotacaoDeHoje } from '../cotacao-de-teste';
import { esperarAte } from '../esperar';

let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('cotação da dona'));
  // O Banco Central de hoje (vale desde 00:00 de Brasília), como a busca agendada gravaria.
  await registrarCotacaoDeHoje();
});
afterAll(() => cenario?.desligar());

async function cotacaoDaVenda(vendaId: string): Promise<string> {
  const [linha] = await consultar<{ id: string }>(
    `SELECT cotacao_id::text AS id FROM corona.vendas WHERE id = ${texto(vendaId)}`,
  );
  return linha?.id ?? '';
}

/** O tempo de verdade tem de passar entre a venda e a cotação da dona (a ordem das horas é o que se testa). */
async function passarUmPouco(ms = 300): Promise<void> {
  const ate = Date.now() + ms;
  await esperarAte(() => Date.now() > ate, { intervaloMs: 50, descricao: 'o tempo passar' });
}

describe('duas pontas: a dona muda a cotação durante o dia (ADR-0006, item 15)', () => {
  it('a cotação da dona chega ao vendedor; a venda feita antes dela, sem internet, fica com a cotação da hora da venda', async () => {
    const [peca] = await cenario.pecas(1);
    const ana = await cenario.vendedor('Ana');
    const bia = await cenario.vendedor('Bia');
    const maletaAna = await cenario.dona.maleta(ana.vendedorId, { [peca!.id]: 3 });
    const maletaBia = await cenario.dona.maleta(bia.vendedorId, { [peca!.id]: 3 });
    await ana.aparelho.sincronizar();
    await bia.aparelho.sincronizar();

    // A Bia vende sem internet vendo a cotação do Banco Central (a única que o computador dela conhece).
    bia.aparelho.semInternet();
    await Tela.conferirTudo(bia.aparelho, maletaBia);
    const clienteBia = await Tela.cliente(bia.aparelho, 'Cliente da Bia');
    const vendaAntes = await Tela.vender(bia.aparelho, {
      maletaId: maletaBia,
      clienteId: clienteBia,
      produtoId: peca!.id,
    });
    await passarUmPouco();

    // A dona lança a cotação da loja; a Ana, online, recebe no próximo contato e vê "da loja".
    await cenario.dona.chamar('POST', '/api/dona/cotacoes', {
      usd: '5.80',
      guaranisPorReal: '1350',
    });
    await ana.aparelho.sincronizar();
    const naAna = ana.aparelho.ler<{ origem: string; guaranis_por_real: string }>(
      "SELECT origem, guaranis_por_real FROM cotacoes WHERE origem = 'DONA'",
    );
    expect(naAna).toHaveLength(1);
    expect(Number(naAna[0]?.guaranis_por_real)).toBe(1350);
    // Quem lançou nunca vai ao computador do vendedor: nem a coluna existe lá.
    const colunas = ana.aparelho
      .ler<{ name: string }>('PRAGMA table_info(cotacoes)')
      .map((c) => c.name);
    expect(colunas).not.toContain('lancada_por');
    const hoje = await ana.aparelho.pedir<{ origem?: string; dolar?: string }>({
      operacao: 'cotacao.hoje',
    });
    expect(hoje.origem).toBe('DONA');
    expect(hoje.dolar).toBe('5.80000000');

    // A Bia volta à internet: a venda dela (feita antes da cotação da dona) é gravada com a cotação vigente na hora da venda.
    await passarUmPouco();
    bia.aparelho.comInternet();
    await bia.aparelho.sincronizar();
    const [bcb] = await consultar<{ id: string }>(
      "SELECT id::text AS id FROM corona.cotacoes WHERE origem = 'BANCO_CENTRAL' ORDER BY vigente_desde DESC, id DESC LIMIT 1",
    );
    expect(await cotacaoDaVenda(vendaAntes)).toBe(bcb?.id);

    // Uma venda de depois da cotação da dona usa a dela.
    await Tela.conferirTudo(ana.aparelho, maletaAna);
    const clienteAna = await Tela.cliente(ana.aparelho, 'Cliente da Ana');
    const vendaDepois = await Tela.vender(ana.aparelho, {
      maletaId: maletaAna,
      clienteId: clienteAna,
      produtoId: peca!.id,
    });
    await ana.aparelho.sincronizar();
    const [daDona] = await consultar<{ id: string }>(
      "SELECT id::text AS id FROM corona.cotacoes WHERE origem = 'DONA' LIMIT 1",
    );
    expect(await cotacaoDaVenda(vendaDepois)).toBe(daDona?.id);
    expect(
      await contar(
        `SELECT count(*) FROM corona.cotacoes WHERE origem = 'DONA' AND lancada_por IS NOT NULL`,
      ),
    ).toBe(1);

    // A conferência por resumo do pull e a conciliação fecham, com a cotação da dona no meio do dia.
    await cenario.afirmarNoFim([ana.aparelho, bia.aparelho]);
  });
});
