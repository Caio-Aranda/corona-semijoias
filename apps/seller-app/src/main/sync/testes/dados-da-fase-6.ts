import type { BancoAberto } from '../../banco/banco-local';
import { exigirCentavos, textoDeCentavos } from '../../../shared/dinheiro';
import type { PagamentoParaEnviar } from '../../../shared/operacoes';
import { aplicarLote } from '../aplicador-do-pull';
import type { OperacoesLocais } from '../operacoes-locais';
import { idDeTeste } from './banco-de-teste';
import type { MaletaDeTeste } from './dados-da-maleta';

/** Apoio dos testes da tarefa 6.8: maleta conferida, peça a mais, venda paga e linhas do servidor. */

let versao = 100;

/** A maleta passa a `status` pelo servidor (a dona enviou, o vendedor conferiu, o acerto começou...). */
export function mudarMaleta(
  db: BancoAberto,
  remessaId: string,
  status: string,
  campos: Record<string, string | number | null> = {},
): void {
  versao += 1;
  aplicarLote(
    db,
    [
      {
        tabela: 'remessas',
        id: remessaId,
        version: versao,
        deletedAt: null,
        campos: { status, total_valor: '0.00', ...campos },
      },
    ],
    { cursor: null },
  );
}

/** Mais uma peça na maleta (chega do servidor). */
export function plantarPeca(
  db: BancoAberto,
  remessaId: string,
  produtoId: string,
  qtd: number,
  preco: string,
): void {
  aplicarLote(
    db,
    [
      {
        tabela: 'itens_remessa',
        id: idDeTeste(4),
        version: 1,
        deletedAt: null,
        campos: {
          remessa_id: remessaId,
          produto_id: produtoId,
          qtd_enviada: qtd,
          preco_remessa: preco,
        },
      },
      {
        tabela: 'produtos',
        id: produtoId,
        version: 1,
        deletedAt: null,
        campos: { nome: `Peça ${produtoId.slice(-4)}` },
      },
    ],
    { cursor: null },
  );
}

/** Uma linha qualquer que chega do servidor. */
export function doServidor(
  db: BancoAberto,
  tabela: string,
  id: string,
  campos: Record<string, string | number | null>,
  version = 1,
): void {
  aplicarLote(db, [{ tabela, id, version, deletedAt: null, campos }], { cursor: null });
}

/**
 * Venda feita no computador. Sem `pagamentos`, paga o total inteiro em Pix (o preço vem da maleta, como no vender).
 */
export function venderPago(
  db: BancoAberto,
  ops: OperacoesLocais,
  remessaId: string,
  clienteId: string,
  itens: { produtoId: string; quantidade: number; desconto?: string }[],
  pagamentos?: Omit<PagamentoParaEnviar, 'id'>[],
): { vendaId: string; total: string; pagamentoIds: string[] } {
  const vendaId = idDeTeste(5);
  let total = 0n;
  for (const i of itens) {
    const r = db
      .prepare('SELECT preco_remessa FROM itens_remessa WHERE remessa_id = ? AND produto_id = ?')
      .get(remessaId, i.produtoId) as { preco_remessa: string };
    total +=
      exigirCentavos(r.preco_remessa) * BigInt(i.quantidade) - exigirCentavos(i.desconto ?? '0');
  }
  const lista = (pagamentos ?? [{ valor: textoDeCentavos(total), forma: 'PIX' as const }]).map(
    (p) => ({ ...p, id: idDeTeste(6) }),
  );
  const r = ops.vender({ id: vendaId, remessaId, clienteId, itens, pagamentos: lista });
  return { vendaId, total: r.total, pagamentoIds: lista.map((p) => p.id) };
}

export type { MaletaDeTeste };
