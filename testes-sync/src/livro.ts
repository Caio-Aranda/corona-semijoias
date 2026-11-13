/**
 * O "livro" do teste: cada operação que o teste pediu ao app (pela tela), de qual aparelho e vendedor. Na afirmação final,
 * cada uma tem de ter um destino conhecido no servidor: aceita (uma vez só), recusada ou em conflito ("envios com
 * problema") ou em quarentena. Nenhuma pode sumir nem ficar aguardando.
 */
export type TipoDeOperacao =
  | 'VENDA'
  | 'ESTORNO_VENDA'
  | 'CLIENTE_NOVO'
  | 'CLIENTE_ALTERADO'
  | 'CONFERENCIA'
  // Fase 6 (tarefa 6.11): o id do lançamento é o da operação (devolução, pedido, troca ou cancelamento).
  | 'DEVOLUCAO'
  | 'PEDIDO_AUTORIZACAO'
  | 'TROCA'
  | 'CANCELAR_DEVOLUCAO'
  | 'CANCELAR_TROCA';

export interface Lancamento {
  operacaoId: string;
  tipo: TipoDeOperacao;
  aparelho: string;
  vendedorId: string;
  /** Para o estorno: a venda estornada. Para a devolução e o pedido: a venda; para o cancelamento: a devolução ou a troca. */
  vendaId?: string;
  /** Na troca: o id da venda nova que ela cria (conta como venda do livro se a troca for aceita). */
  vendaNovaId?: string;
  /**
   * Perda aceita pelo ADR-0013 (item 14): computador bloqueado cuja quarentena não respondeu nas 3 tentativas apaga os
   * dados mesmo assim. Só nesse caso a operação pode não ter destino no servidor.
   */
  perdaAceita?: boolean;
}

export class Livro {
  private readonly lancamentos: Lancamento[] = [];

  lancar(l: Lancamento): void {
    this.lancamentos.push(l);
  }

  /** Marca as operações do aparelho que podem se perder (ver `perdaAceita`). */
  aceitarPerda(aparelho: string): void {
    for (const l of this.lancamentos) if (l.aparelho === aparelho) l.perdaAceita = true;
  }

  todos(): readonly Lancamento[] {
    return this.lancamentos;
  }

  doVendedor(vendedorId: string): Lancamento[] {
    return this.lancamentos.filter((l) => l.vendedorId === vendedorId);
  }
}
