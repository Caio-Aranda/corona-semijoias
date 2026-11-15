import { MESES_ATE_O_PRIMEIRO_VENCIMENTO, maisMeses } from '../../shared/datas-das-parcelas';
import { diaEmBrasilia } from '../../shared/garantia';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import type { ParcelamentoParaEnviar, RecebimentoParaEnviar } from '../../shared/operacoes';
import type { BancoAberto } from '../banco/banco-local';
import type { CodigoDaFase7 } from './mensagens-da-fase-7';
import {
  acharVenda,
  emAbertoDaVenda,
  temDevolucaoAtiva,
  vendaEstornada,
} from './operacoes-da-fase-6';
import { ErroDaOperacao, inserir } from './operacoes-locais';
import { enfileirar } from './outbox';

/**
 * Parcelamento, recebimento do cliente e estorno do recebimento no computador (tarefa 7.11; ADR-0012, item 7). Cada
 * operação confere o que o servidor também recusaria (com o mesmo texto), grava a linha PROVISÓRIA em `pagamentos_cliente`
 * como o servidor gravaria e põe na outbox o JSON exato do formato 1, montado uma vez e nunca remontado. **O computador
 * nunca calcula parcela, baixa de parcela nem comissão**: a divisão, a distribuição pelas parcelas e a cópia das contas
 * só chegam do servidor.
 */

const recusar = (codigo: CodigoDaFase7, detalhe?: string): never => {
  throw new ErroDaOperacao(codigo, undefined, detalhe);
};

const LIMITE_DO_MOTIVO = 400;

/**
 * O parcelamento pedido para uma venda (ou para a venda nova de uma troca): só se sobra valor a prazo, com o primeiro
 * vencimento de hoje (Brasília) até um ano. Devolve o objeto que vai no JSON, ou `undefined` sem parcelamento.
 */
export function conferirParcelamento(
  parcelamento: ParcelamentoParaEnviar | undefined,
  aPrazo: bigint,
  agora: Date,
): ParcelamentoParaEnviar | undefined {
  if (!parcelamento) return undefined;
  if (aPrazo <= 0n) recusar('PARCELAMENTO_SEM_SALDO');
  const hoje = diaEmBrasilia(agora);
  if (parcelamento.primeiroVencimento < hoje) recusar('VENCIMENTO_NO_PASSADO');
  if (parcelamento.primeiroVencimento > maisMeses(hoje, MESES_ATE_O_PRIMEIRO_VENCIMENTO))
    recusar('VENCIMENTO_LONGE_DEMAIS');
  return {
    quantidade: parcelamento.quantidade,
    primeiroVencimento: parcelamento.primeiroVencimento,
  };
}

const reais = (centavos: bigint): string => textoDeCentavos(centavos).replace('.', ',');

/** O cliente paga, depois da venda, parte ou o que falta. O servidor distribui o valor pelas parcelas. */
export function registrarRecebimento(
  db: BancoAberto,
  agora: Date,
  r: RecebimentoParaEnviar,
): { operacaoId: string } {
  const venda = acharVenda(db, r.vendaId);
  if (vendaEstornada(db, venda)) throw new ErroDaOperacao('VENDA_JA_ESTORNADA');
  const valor = exigirCentavos(r.valor);
  if (valor <= 0n) throw new ErroDaOperacao('PAGAMENTO_SEM_VALOR');
  const aberto = emAbertoDaVenda(db, venda);
  if (aberto <= 0n) recusar('VENDA_SEM_VALOR_EM_ABERTO');
  if (valor > aberto)
    recusar(
      'RECEBIMENTO_ACIMA_DO_EM_ABERTO',
      `O valor passa do que falta pagar nesta venda (R$ ${reais(aberto)}). Confira o valor.`,
    );
  const recebidoEm = agora.toISOString();
  const dados = JSON.stringify({
    id: r.id,
    vendaId: r.vendaId,
    valor: textoDeCentavos(valor),
    forma: r.forma,
    recebidoEm,
  });
  inserir(db, 'pagamentos_cliente', r.id, r.id, {
    venda_id: r.vendaId,
    tipo: 'PAGAMENTO',
    forma: r.forma,
    valor: textoDeCentavos(valor),
    pago_em: recebidoEm,
    posterior: 1,
  });
  enfileirar(db, { operacaoId: r.id, tipo: 'RECEBIMENTO_CLIENTE', dados, agora });
  return { operacaoId: r.id };
}

interface PagamentoAchado {
  id: string;
  venda_id: string;
  tipo: string | null;
  forma: string | null;
  valor: string;
  pago_em: string | null;
  posterior: number | null;
}

/** Desfaz um recebimento feito no mesmo dia (Brasília): as parcelas voltam a ficar em aberto quando o servidor aceitar. */
export function estornarRecebimento(
  db: BancoAberto,
  agora: Date,
  e: { id: string; recebimentoId: string; motivo: string },
): { operacaoId: string } {
  const motivo = e.motivo.trim();
  if (motivo === '' || motivo.length > LIMITE_DO_MOTIVO)
    recusar('MOTIVO_DO_ESTORNO_DO_RECEBIMENTO');
  const achado = db
    .prepare(
      `SELECT id, venda_id, tipo, forma, valor, pago_em, posterior FROM pagamentos_cliente
        WHERE id = ? AND deleted_at IS NULL`,
    )
    .get(e.recebimentoId) as PagamentoAchado | undefined;
  if (!achado || achado.tipo !== 'PAGAMENTO') return recusar('RECEBIMENTO_NAO_ENCONTRADO');
  // A venda tem de ser deste vendedor (o pull só traz as dele).
  const venda = acharVenda(db, achado.venda_id);
  if (achado.posterior !== 1) recusar('PAGAMENTO_NA_VENDA_SO_PELO_ESTORNO_DA_VENDA');
  const jaEstornado = db
    .prepare(
      `SELECT 1 FROM pagamentos_cliente WHERE estorna_id = ? AND deleted_at IS NULL
       UNION ALL
       SELECT 1 FROM outbox WHERE tipo = 'ESTORNO_RECEBIMENTO_CLIENTE' AND situacao IN ('AGUARDANDO', 'ENVIADO')
          AND json_extract(dados, '$.recebimentoId') = ?`,
    )
    .get(achado.id, achado.id);
  if (jaEstornado) recusar('RECEBIMENTO_JA_ESTORNADO');
  if (vendaEstornada(db, venda)) throw new ErroDaOperacao('VENDA_JA_ESTORNADA');
  if (temDevolucaoAtiva(db, venda.id)) throw new ErroDaOperacao('VENDA_COM_DEVOLUCAO');
  if (!achado.pago_em || diaEmBrasilia(achado.pago_em) !== diaEmBrasilia(agora))
    recusar('ESTORNO_DE_RECEBIMENTO_SO_DA_DONA');

  const estornadoEm = agora.toISOString();
  const dados = JSON.stringify({ id: e.id, recebimentoId: achado.id, motivo, estornadoEm });
  inserir(db, 'pagamentos_cliente', e.id, e.id, {
    venda_id: achado.venda_id,
    tipo: 'ESTORNO_PAGAMENTO',
    forma: achado.forma,
    valor: textoDeCentavos(-exigirCentavos(achado.valor)),
    pago_em: estornadoEm,
    posterior: 1,
    estorna_id: achado.id,
  });
  enfileirar(db, { operacaoId: e.id, tipo: 'ESTORNO_RECEBIMENTO_CLIENTE', dados, agora });
  return { operacaoId: e.id };
}
