import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { contar, texto } from '../banco-do-servidor';
import { Cenario, sementeDaRodada, Tela } from '../cenario';

let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('fumaça'));
});
afterAll(() => cenario?.desligar());

describe('duas pontas: o caminho inteiro, com rede boa', () => {
  it('o vendedor entra, recebe a maleta, vende sem internet e envia; a venda chega uma vez só', async () => {
    const [peca] = await cenario.pecas(1);
    const ana = await cenario.vendedor('Ana');
    const maletaId = await cenario.dona.maleta(ana.vendedorId, { [peca!.id]: 3 });

    await ana.aparelho.sincronizar();
    expect(ana.aparelho.ler('SELECT id FROM remessas WHERE id = ?', maletaId)).toHaveLength(1);

    ana.aparelho.semInternet();
    await Tela.conferirTudo(ana.aparelho, maletaId);
    const clienteId = await Tela.cliente(ana.aparelho, 'Cliente da Fumaça');
    const vendaId = await Tela.vender(ana.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    expect(ana.aparelho.estado().aguardando).toBe(3);

    ana.aparelho.comInternet();
    await ana.aparelho.sincronizar();

    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(vendaId)}`)).toBe(1);
    expect(
      await contar(
        `SELECT coalesce(sum(quantidade), 0) FROM corona.movimentos_remessa WHERE remessa_id = ${texto(maletaId)}`,
      ),
    ).toBe(-1);
    expect(ana.aparelho.outbox().map((o) => o.situacao)).toEqual(['ENVIADO', 'ENVIADO', 'ENVIADO']);

    await cenario.afirmarNoFim([ana.aparelho]);
  });
});
