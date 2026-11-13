import { consultar, texto } from '../banco-do-servidor';
import { Cenario, Tela, type VendedorComAparelho } from '../cenario';
import { envelhecerVenda } from '../maquina-do-tempo';

/**
 * O que os casos da Fase 6 (tarefa 6.11) repetem: um vendedor com a maleta conferida e um cliente, e uma venda enviada
 * que a máquina do tempo envelhece no servidor e que volta ao computador pelo recebimento.
 */
export interface VendedorComMaleta {
  vendedor: VendedorComAparelho;
  maletaId: string;
  clienteId: string;
}

export async function vendedorComMaleta(
  cenario: Cenario,
  nome: string,
  pecas: Record<string, number>,
  /** Relógio do computador errado desde antes do primeiro contato (ms; negativo = atrasado). */
  deslocamentoMs = 0,
): Promise<VendedorComMaleta> {
  const vendedor = await cenario.vendedor(nome, { deslocamentoMs });
  const maletaId = await cenario.dona.maleta(vendedor.vendedorId, pecas);
  await vendedor.aparelho.sincronizar();
  await Tela.conferirTudo(vendedor.aparelho, maletaId);
  const clienteId = await Tela.cliente(vendedor.aparelho, `Cliente da ${nome}`);
  await vendedor.aparelho.sincronizar();
  return { vendedor, maletaId, clienteId };
}

/**
 * Vende, envia e deixa a venda com `dias` dias de idade no servidor; o recebimento leva a data nova ao computador e esta
 * função confere que ela chegou igual (cópia local e servidor com a mesma data, como na vida real).
 */
export async function vendaEnvelhecida(
  v: VendedorComMaleta,
  o: { produtoId: string; quantidade?: number; dias: number },
): Promise<{ vendaId: string; vendidaEm: Date }> {
  const a = v.vendedor.aparelho;
  const vendaId = await Tela.vender(a, {
    maletaId: v.maletaId,
    clienteId: v.clienteId,
    produtoId: o.produtoId,
    ...(o.quantidade ? { quantidade: o.quantidade } : {}),
  });
  await a.sincronizar();
  const vendidaEm = await envelhecerVenda(vendaId, o.dias);
  await a.sincronizar();
  const [local] = a.ler<{ vendida_em: string }>(
    'SELECT vendida_em FROM vendas WHERE id = ?',
    vendaId,
  );
  if (!local || Date.parse(local.vendida_em) !== vendidaEm.getTime())
    throw new Error(
      `${a.nome}: a venda envelhecida chegou ao computador com a data ${local?.vendida_em} (esperava ${vendidaEm.toISOString()}).`,
    );
  return { vendaId, vendidaEm };
}

export const DIA_MS = 86_400_000;

/** Quantas linhas de uma consulta (conta no servidor). */
export async function umaLinha<T extends Record<string, unknown>>(sql: string): Promise<T> {
  const [linha] = await consultar<T>(sql);
  if (!linha) throw new Error(`Nenhuma linha para: ${sql}`);
  return linha;
}

export { texto };
