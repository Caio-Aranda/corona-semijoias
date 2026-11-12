import { randomUUID } from 'node:crypto';
import { afterAll, describe, it } from 'vitest';
import type { Aparelho } from './aparelho';
import {
  Cenario,
  SENHA_DO_VENDEDOR,
  sementeDaRodada,
  Tela,
  type VendedorComAparelho,
} from './cenario';
import { esperarAte } from './esperar';
import type { PlanoDeRede } from './proxy-de-rede-ruim';
import { criarSorteio, type Sorteio } from './sorteio';

/**
 * Estresse de duas pontas (ADR-0013, item 20): 5 vendedores (um troca de computador no meio), a dona mexendo pela API
 * (preço, foto, códigos, maleta nova) e cada computador com a própria rede ruim sorteada (atraso, queda, corte depois de
 * gravar, 502, corte no meio da resposta, banda). Vendas, estornos, clientes e conferências sorteados.
 *
 * Duração por `CORONA_SYNC_DURACAO` (ISO-8601, padrão `PT1M`: o curto de todo PR que mexe no sync; o semanal usa
 * `PT10M`). No fim: rede boa, todos enviam e recebem até zerar, e a afirmação final (oráculo, livro, marcadores e
 * conciliação). A semente repete as decisões da rede e do roteiro; a ordem exata entre os computadores não se repete
 * (rede e API de verdade), por isso só o estado final é afirmado.
 */
function duracaoMs(): number {
  const texto = process.env['CORONA_SYNC_DURACAO'] ?? 'PT1M';
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(texto);
  if (!m || texto === 'PT')
    throw new Error(`CORONA_SYNC_DURACAO inválida: ${texto} (use, por exemplo, PT1M ou PT10M)`);
  return ((Number(m[1] ?? 0) * 60 + Number(m[2] ?? 0)) * 60 + Number(m[3] ?? 0)) * 1000;
}

const DURACAO = duracaoMs();
const semente = sementeDaRodada(`estresse ${DURACAO / 1000}s`);
let cenario: Cenario | undefined;
afterAll(() => cenario?.desligar());

function redeRuim(): PlanoDeRede {
  return {
    sorteio: [
      { falha: 'QUEDA_ANTES', peso: 2 },
      { falha: 'ERRO_502', peso: 2 },
      { falha: 'CORTE_DEPOIS_DE_GRAVAR', peso: 3 },
      { falha: 'CORTE_NO_MEIO_DA_RESPOSTA', peso: 1 },
    ],
    chanceDeFalha: 0.2,
    atrasoMs: { minimo: 0, maximo: 150 },
    bytesPorSegundo: 400_000,
  };
}

const esperar = (ms: number) => new Promise((ok) => setTimeout(ok, ms));
const escolher = <T>(s: Sorteio, lista: readonly T[]): T | undefined =>
  lista.length === 0 ? undefined : lista[Math.floor(s() * lista.length)];

/** O que um vendedor faz na "tela", sorteado, até o fim (ou até o computador ser desligado). */
async function trabalhar(
  a: Aparelho,
  s: Sorteio,
  fim: number,
  contagem: Map<string, number>,
): Promise<void> {
  const conferidas = new Set<string>();
  const clientes: string[] = [];
  const contar = (o: string) => contagem.set(o, (contagem.get(o) ?? 0) + 1);
  while (
    Date.now() < fim &&
    !a.avisos.some((x) => x.estado === 'desligando' || x.estado === 'desligado')
  ) {
    try {
      // Maleta nova que chegou: confere antes de vender.
      for (const m of a.ler<{ id: string; status: string }>(
        "SELECT id, status FROM remessas WHERE deleted_at IS NULL AND status IN ('ENVIADA', 'CONFERIDA')",
      )) {
        if (m.status === 'CONFERIDA') conferidas.add(m.id);
        else if (!conferidas.has(m.id)) {
          await Tela.conferirTudo(a, m.id);
          conferidas.add(m.id);
          contar('conferências');
        }
      }
      if (clientes.length === 0 || s() < 0.12) {
        clientes.push(await Tela.cliente(a, `Cliente ${randomUUID().slice(0, 8)}`));
        contar('clientes');
      }
      const r = s();
      if (r < 0.6) {
        const maleta = escolher(s, [...conferidas]);
        const pecas = maleta
          ? a
              .ler<{ produto_id: string; saldo: number }>(
                `SELECT i.produto_id, i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM movimentos_remessa m
                        WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.deleted_at IS NULL), 0) AS saldo
                 FROM itens_remessa i WHERE i.remessa_id = ? AND i.deleted_at IS NULL`,
                maleta,
              )
              .filter((p) => p.saldo > 0)
          : [];
        const peca = escolher(s, pecas);
        if (maleta && peca) {
          await Tela.vender(a, {
            maletaId: maleta,
            clienteId: escolher(s, clientes)!,
            produtoId: peca.produto_id,
            pago: s() < 0.8,
          });
          contar('vendas');
        }
      } else if (r < 0.75) {
        const venda = escolher(
          s,
          a.ler<{ id: string }>(
            `SELECT id FROM vendas WHERE status = 'CONCLUIDA' AND deleted_at IS NULL
                AND id NOT IN (SELECT json_extract(dados, '$.vendaId') FROM outbox WHERE tipo = 'ESTORNO_VENDA')`,
          ),
        );
        if (venda) {
          await Tela.estornar(a, venda.id);
          contar('estornos');
        }
      } else if (r < 0.8) {
        // Fica sem internet um pouco e volta com a rede ruim.
        a.semInternet();
        await esperar(500 + s() * 2500);
        a.comInternet(redeRuim());
        contar('quedas de internet');
      }
    } catch (erro) {
      // Recusa do próprio app (sem saldo, computador desligando...): faz parte; o livro só tem o que o app aceitou.
      contar(
        `recusas no app: ${erro instanceof Error ? erro.message.split(':')[1]?.trim().slice(0, 40) : 'erro'}`,
      );
    }
    await esperar(80 + s() * 320);
  }
}

describe('duas pontas: estresse', () => {
  it(
    `5 vendedores, a dona mexendo e rede ruim por ${DURACAO / 1000} s: no fim, tudo igual ao oráculo e nada duplicado ou perdido`,
    { timeout: DURACAO + 8 * 60_000 },
    async () => {
      cenario = await Cenario.criar(semente);
      const c = cenario;
      const s = criarSorteio(semente);
      const pecas = [
        ...(await c.pecas(4, { estoque: 400, comFoto: true })),
        ...(await c.pecas(4, { estoque: 400 })),
      ];
      const vendedores: VendedorComAparelho[] = [];
      for (let i = 0; i < 5; i++) vendedores.push(await c.vendedor(`V${i}`));
      for (const v of vendedores) {
        const escolhidas = [...pecas].sort(() => s() - 0.5).slice(0, 4);
        await c.dona.maleta(v.vendedorId, Object.fromEntries(escolhidas.map((p) => [p.id, 10])));
      }
      await Promise.all(vendedores.map((v) => v.aparelho.sincronizar(120_000)));
      for (const v of vendedores) v.aparelho.rede(redeRuim());

      const inicio = Date.now();
      const fim = inicio + DURACAO;
      const contagem = new Map<string, number>();
      const trabalhos = vendedores.map((v, i) =>
        trabalhar(v.aparelho, criarSorteio(semente + 100 + i), fim, contagem),
      );

      // A troca de computador do V4 na metade do tempo.
      const v4 = vendedores[4]!;
      const troca = (async () => {
        await esperar(DURACAO / 2);
        const novo = await c.aparelho('V4-novo');
        novo.vendedorId = v4.vendedorId;
        novo.rede(redeRuim());
        await esperarAte(
          async () => (await novo.entrar(v4.login, SENHA_DO_VENDEDOR)).tipo === 'entrou',
          {
            limiteMs: 120_000,
            descricao: 'o computador novo do V4 entrar',
          },
        );
        const lista = await c.dona.aparelhos(v4.vendedorId);
        await c.dona.liberar(lista.find((x) => x.situacao === 'AGUARDANDO_LIBERACAO')!.id);
        contagem.set('trocas de computador', 1);
        return novo;
      })();

      // A dona mexendo enquanto isso.
      const dona = (async () => {
        const sd = criarSorteio(semente + 7);
        while (Date.now() < fim) {
          const r = sd();
          const peca = escolher(sd, pecas)!;
          if (r < 0.3) {
            await c.dona.mudarPreco(peca.id, (40 + Math.floor(sd() * 60)).toFixed(2));
            contagem.set('preços mudados', (contagem.get('preços mudados') ?? 0) + 1);
          } else if (r < 0.5) {
            await c.dona.trocarFoto(peca.id, Math.floor(sd() * 0xffffff));
            contagem.set('fotos trocadas', (contagem.get('fotos trocadas') ?? 0) + 1);
          } else if (r < 0.7) {
            await c.dona.alternarCodigoSecundario(peca.id);
            contagem.set('códigos mexidos', (contagem.get('códigos mexidos') ?? 0) + 1);
          } else if (r < 0.8) {
            const v = escolher(sd, vendedores)!;
            await c.dona.maleta(v.vendedorId, { [peca.id]: 3 });
            contagem.set('maletas novas', (contagem.get('maletas novas') ?? 0) + 1);
          }
          await esperar(800 + sd() * 1500);
        }
      })();

      const novo = await troca;
      const trabalhoDoNovo = trabalhar(novo, criarSorteio(semente + 200), fim, contagem);
      await Promise.all([...trabalhos, trabalhoDoNovo, dona]);

      // Fim: rede boa. O computador trocado termina de enviar e apaga; os outros enviam e recebem até zerar.
      v4.aparelho.comInternet();
      await esperarAte(() => v4.aparelho.avisos.some((x) => x.estado === 'desligado'), {
        limiteMs: 120_000,
        descricao: () =>
          `o computador trocado do V4 apagar os dados (${JSON.stringify(v4.aparelho.estado())})`,
      });
      const vivos = [...vendedores.slice(0, 4).map((v) => v.aparelho), novo];
      console.log(
        `Estresse (semente=${semente}, ${Math.round((Date.now() - inicio) / 1000)} s): ${JSON.stringify(Object.fromEntries(contagem))}; ` +
          `pedidos pela rede: ${c.aparelhos.map((a) => `${a.nome}=${a.proxy.historico().length}`).join(', ')}`,
      );
      await c.afirmarNoFim(vivos);
      if (v4.aparelho.arquivos().length > 0)
        throw new Error(
          `O computador trocado deixou arquivos: ${v4.aparelho.arquivos().join(', ')}`,
        );
    },
  );
});
