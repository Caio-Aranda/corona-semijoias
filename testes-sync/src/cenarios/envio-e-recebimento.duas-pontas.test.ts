import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { contar, texto } from '../banco-do-servidor';
import { Cenario, sementeDaRodada, Tela } from '../cenario';

/**
 * Cenários 1 a 4 do plano (ADR-0013, itens 2 e 19; ESPECIFICACAO §10): queda no meio do envio e reenvio sem duplicar,
 * o mesmo com lotes de 50, queda no meio do recebimento e o cancelamento (estorno) de venda já enviada.
 */
let cenario: Cenario;

beforeAll(async () => {
  cenario = await Cenario.criar(sementeDaRodada('envio e recebimento'));
});
afterAll(() => cenario?.desligar());

const PUSH = '/api/vendedor/sync/push';
const PULL = '/api/vendedor/sync/pull';

async function saldoNoServidor(maletaId: string, produtoId: string): Promise<number> {
  return contar(`
    SELECT i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m
             WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id), 0)
      FROM corona.itens_remessa i
     WHERE i.remessa_id = ${texto(maletaId)} AND i.produto_id = ${texto(produtoId)} AND i.deleted_at IS NULL`);
}

describe('duas pontas: envio e recebimento com a rede caindo', () => {
  it('1. a conexão cai depois de o servidor gravar a venda: o reenvio volta "já recebido" e a venda existe uma vez', async () => {
    const [peca] = await cenario.pecas(1);
    const ana = await cenario.vendedor('Ana');
    const maletaId = await cenario.dona.maleta(ana.vendedorId, { [peca!.id]: 3 });
    await ana.aparelho.sincronizar();

    ana.aparelho.semInternet();
    await Tela.conferirTudo(ana.aparelho, maletaId);
    const clienteId = await Tela.cliente(ana.aparelho, 'Cliente do Corte');
    const vendaId = await Tela.vender(ana.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    expect(ana.aparelho.estado().vendasAguardando).toBe(1);

    // A internet volta, mas o primeiro envio corta depois de o servidor gravar tudo.
    ana.aparelho.comInternet({
      sorteio: [],
      porCaminho: [{ caminho: PUSH, roteiro: ['CORTE_DEPOIS_DE_GRAVAR'] }],
    });
    await ana.aparelho.sincronizar();

    const pushes = ana.aparelho.proxy.historico().filter((p) => p.caminho.startsWith(PUSH));
    expect(pushes[0]).toMatchObject({
      falha: 'CORTE_DEPOIS_DE_GRAVAR',
      servidorRespondeu: true,
      statusDoServidor: 200,
    });
    expect(pushes.length).toBeGreaterThanOrEqual(2);
    // O servidor guardou a primeira resposta (ACEITO) e respondeu "já recebido" ao reenvio.
    const venda = ana.aparelho.outbox().find((o) => o.operacao_id === vendaId);
    expect(venda).toMatchObject({ situacao: 'ENVIADO', codigo_do_resultado: 'JA_RECEBIDO' });
    expect(await contar(`SELECT count(*) FROM corona.vendas WHERE id = ${texto(vendaId)}`)).toBe(1);
    expect(await saldoNoServidor(maletaId, peca!.id)).toBe(2);
    expect(ana.aparelho.ler<{ n: number }>('SELECT count(*) AS n FROM vendas')[0]!.n).toBe(1);

    await cenario.afirmarNoFim([ana.aparelho]);
  });

  it('2. lotes de 50: corte depois de gravar no 2º lote, 502 no 3º e banda baixa; o que entrou fica, o resto entra uma vez', async () => {
    const pecas = await cenario.pecas(3, { estoque: 60 });
    const bia = await cenario.vendedor('Bia');
    const maletaId = await cenario.dona.maleta(
      bia.vendedorId,
      Object.fromEntries(pecas.map((p) => [p.id, 50])),
    );
    await bia.aparelho.sincronizar();

    bia.aparelho.semInternet();
    await Tela.conferirTudo(bia.aparelho, maletaId);
    const clienteId = await Tela.cliente(bia.aparelho, 'Cliente dos Lotes');
    const vendas: string[] = [];
    for (let i = 0; i < 128; i++) {
      vendas.push(
        await Tela.vender(bia.aparelho, { maletaId, clienteId, produtoId: pecas[i % 3]!.id }),
      );
    }
    expect(bia.aparelho.estado().aguardando).toBe(130);

    bia.aparelho.comInternet({
      sorteio: [],
      bytesPorSegundo: 200_000,
      porCaminho: [{ caminho: PUSH, roteiro: [undefined, 'CORTE_DEPOIS_DE_GRAVAR', 'ERRO_502'] }],
    });
    await bia.aparelho.sincronizar(120_000);

    const pushes = bia.aparelho.proxy.historico().filter((p) => p.caminho.startsWith(PUSH));
    expect(pushes.slice(0, 3).map((p) => p.falha)).toEqual([
      undefined,
      'CORTE_DEPOIS_DE_GRAVAR',
      'ERRO_502',
    ]);
    expect(
      await contar(
        `SELECT count(*) FROM corona.vendas WHERE vendedor_id = ${texto(bia.vendedorId)}`,
      ),
    ).toBe(128);
    const codigos = bia.aparelho.outbox().map((o) => o.codigo_do_resultado);
    // O 2º lote (50 operações) foi gravado e a resposta se perdeu: voltou como "já recebido".
    expect(codigos.filter((c) => c === 'JA_RECEBIDO')).toHaveLength(50);
    for (const p of pecas) {
      const vendidas = vendas.filter((_, i) => pecas[i % 3]!.id === p.id).length;
      expect(await saldoNoServidor(maletaId, p.id)).toBe(50 - vendidas);
    }

    await cenario.afirmarNoFim([bia.aparelho]);
  });

  it('3. a conexão cai no meio do recebimento: o cursor não anda pela metade e a cópia fica igual ao oráculo', async () => {
    // Mais de uma página (500 linhas) de dados da loja: clientes que a dona cadastra.
    const lote = Array.from({ length: 520 }, (_, i) => i);
    for (let i = 0; i < lote.length; i += 20) {
      await Promise.all(
        lote.slice(i, i + 20).map((n) =>
          cenario.dona.chamar('POST', '/api/dona/clientes', {
            id: randomUUID(),
            nome: `Cliente da Carga ${n} ${randomUUID().slice(0, 6)}`,
          }),
        ),
      );
    }
    const [peca] = await cenario.pecas(1);
    const v = await cenario.dona.vendedor(`Carla ${randomUUID().slice(0, 6)}`);
    await cenario.dona.maleta(v.vendedorId, { [peca!.id]: 2 });
    const carla = await cenario.aparelho('Carla');
    carla.vendedorId = v.vendedorId;
    // A carga inicial: a 2ª página corta no meio da resposta; a 3ª tentativa leva um 502; a resposta da 1ª se perde.
    carla.rede({
      sorteio: [],
      porCaminho: [
        {
          caminho: PULL,
          roteiro: [undefined, 'CORTE_NO_MEIO_DA_RESPOSTA', 'ERRO_502', 'CORTE_DEPOIS_DE_GRAVAR'],
        },
      ],
    });
    await carla.primeiraEntrada(v.login, v.senhaTemporaria, 'Senha12345vendas');
    await carla.sincronizar(120_000);

    const pulls = carla.proxy.historico().filter((p) => p.caminho.startsWith(PULL));
    expect(pulls.slice(0, 4).map((p) => p.falha)).toEqual([
      undefined,
      'CORTE_NO_MEIO_DA_RESPOSTA',
      'ERRO_502',
      'CORTE_DEPOIS_DE_GRAVAR',
    ]);
    // O pedido depois de cada falha usa o mesmo cursor de antes dela (nada aplicado pela metade).
    const cursores = pulls.map((p) => new URL(p.caminho, 'http://x').searchParams.get('cursor'));
    expect(cursores[1]).not.toBeNull();
    expect(cursores[2]).toBe(cursores[1]);
    expect(cursores[3]).toBe(cursores[1]);
    expect(cursores[4]).toBe(cursores[1]);

    await cenario.afirmarNoFim([carla]);
  });

  it('4. estorno sem internet de venda já enviada, com corte depois de gravar: um estorno só e o saldo volta', async () => {
    const [peca] = await cenario.pecas(1);
    const dani = await cenario.vendedor('Dani');
    const maletaId = await cenario.dona.maleta(dani.vendedorId, { [peca!.id]: 2 });
    await dani.aparelho.sincronizar();
    await Tela.conferirTudo(dani.aparelho, maletaId);
    const clienteId = await Tela.cliente(dani.aparelho, 'Cliente do Estorno');
    const vendaId = await Tela.vender(dani.aparelho, { maletaId, clienteId, produtoId: peca!.id });
    await dani.aparelho.sincronizar();
    expect(await saldoNoServidor(maletaId, peca!.id)).toBe(1);

    dani.aparelho.semInternet();
    const estornoId = await Tela.estornar(dani.aparelho, vendaId);
    // Sem internet, o computador já mostra a peça de volta (provisório).
    expect(
      dani.aparelho.ler<{ s: number }>(
        `SELECT sum(quantidade) AS s FROM movimentos_remessa WHERE remessa_id = ?`,
        maletaId,
      )[0]!.s,
    ).toBe(0);

    dani.aparelho.comInternet({
      sorteio: [],
      porCaminho: [{ caminho: PUSH, roteiro: ['CORTE_DEPOIS_DE_GRAVAR'] }],
    });
    await dani.aparelho.sincronizar();

    expect(
      await contar(
        `SELECT count(*) FROM corona.vendas WHERE id = ${texto(vendaId)} AND status = 'ESTORNADA' AND estorno_id = ${texto(estornoId)}`,
      ),
    ).toBe(1);
    expect(
      await contar(
        `SELECT count(*) FROM corona.movimentos_remessa WHERE venda_id = ${texto(vendaId)} AND tipo = 'ESTORNO_VENDA'`,
      ),
    ).toBe(1);
    expect(await saldoNoServidor(maletaId, peca!.id)).toBe(2);
    const [venda] = dani.aparelho.ler<{ status: string; origem_local: string }>(
      'SELECT status, origem_local FROM vendas WHERE id = ?',
      vendaId,
    );
    expect(venda).toEqual({ status: 'ESTORNADA', origem_local: 'SERVIDOR' });

    await cenario.afirmarNoFim([dani.aparelho]);
  });
});
