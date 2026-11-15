import { randomUUID } from 'node:crypto';
import { Aparelho, type OpcoesDoAparelho } from './aparelho';
import { afirmarNoFim, type AfirmacaoFinal } from './afirmacoes';
import { Dona, type VendedorDeTeste } from './dona';
import { Livro, type TipoDeOperacao } from './livro';
import type { ResultadoDeChamada } from '../../apps/seller-app/src/shared/ponte';
import type {
  MotivoDeDevolucao,
  PagamentoParaEnviar,
  PecaQueVolta,
} from '../../apps/seller-app/src/shared/operacoes';

/** A semente da rodada: `CORONA_SYNC_SEMENTE` ou a hora. Impressa no início de cada arquivo. */
export function sementeDaRodada(nomeDoArquivo: string): number {
  const doAmbiente = process.env['CORONA_SYNC_SEMENTE'];
  const semente = doAmbiente ? Number(doAmbiente) : Date.now() % 2_147_483_647;
  if (!Number.isFinite(semente)) throw new Error(`CORONA_SYNC_SEMENTE inválida: ${doAmbiente}`);
  console.log(`Duas pontas (${nomeDoArquivo}): semente=${semente}`);
  return semente;
}

export const SENHA_DO_VENDEDOR = 'Senha12345vendas';

export interface VendedorComAparelho extends VendedorDeTeste {
  aparelho: Aparelho;
}

/**
 * O que todo cenário precisa: a dona, uma categoria e os aparelhos ligados (para desligar no fim). Cada cenário cria os
 * próprios vendedores, peças e maletas, então não depende de outro.
 */
export class Cenario {
  readonly aparelhos: Aparelho[] = [];
  readonly livro = new Livro();
  private jaAfirmados = 0;
  private proximaSemente: number;

  private constructor(
    readonly dona: Dona,
    readonly categoriaId: string,
    readonly semente: number,
  ) {
    this.proximaSemente = semente;
  }

  static async criar(semente: number): Promise<Cenario> {
    const dona = await Dona.entrar();
    const categoriaId = await dona.categoria(`Categoria ${randomUUID().slice(0, 8)}`);
    return new Cenario(dona, categoriaId, semente);
  }

  /** Um computador novo; cada um com o próprio proxy e a própria semente (semente da rodada + número do aparelho). */
  async aparelho(nome: string, o: Partial<OpcoesDoAparelho> = {}): Promise<Aparelho> {
    const a = await Aparelho.ligar({ nome, semente: this.proximaSemente++, ...o });
    a.livro = this.livro;
    this.aparelhos.push(a);
    return a;
  }

  /** Vendedor novo, com o computador dele já liberado (o primeiro nasce ativo), a senha trocada e o banco criado. */
  async vendedor(nome: string, o: Partial<OpcoesDoAparelho> = {}): Promise<VendedorComAparelho> {
    const v = await this.dona.vendedor(`${nome} ${randomUUID().slice(0, 6)}`);
    const aparelho = await this.aparelho(nome, o);
    aparelho.vendedorId = v.vendedorId;
    await aparelho.primeiraEntrada(v.login, v.senhaTemporaria, SENHA_DO_VENDEDOR);
    return { ...v, aparelho };
  }

  /** Peças com preço e estoque na loja; com foto se `comFoto`. */
  async pecas(
    quantas: number,
    o: { preco?: string; estoque?: number; comFoto?: boolean } = {},
  ): Promise<Array<{ id: string; codigo: string }>> {
    const r: Array<{ id: string; codigo: string }> = [];
    for (let i = 0; i < quantas; i++) {
      r.push(
        await this.dona.peca({
          categoriaId: this.categoriaId,
          nome: `Peça ${randomUUID().slice(0, 8)}`,
          preco: o.preco ?? '50.00',
          estoque: o.estoque ?? 20,
          ...(o.comFoto ? { foto: Math.floor(Math.random() * 0xffffff) } : {}),
        }),
      );
    }
    return r;
  }

  /** A afirmação final (oráculo, livro, marcadores e conciliação) para os computadores vivos. */
  async afirmarNoFim(
    vivos: Aparelho[],
    o: Partial<Omit<AfirmacaoFinal, 'vivos'>> = {},
  ): Promise<void> {
    // A varredura e o relatório olham os computadores deste caso (os de casos anteriores já foram afirmados).
    const doCaso = this.aparelhos.slice(this.jaAfirmados);
    this.jaAfirmados = this.aparelhos.length;
    await afirmarNoFim({
      semente: this.semente,
      dona: this.dona,
      livro: this.livro,
      todos: doCaso,
      vivos,
      ...o,
    });
  }

  async desligar(): Promise<void> {
    for (const a of this.aparelhos.splice(0)) await a.desligar().catch(() => undefined);
  }
}

function lancar(
  a: Aparelho,
  tipo: TipoDeOperacao,
  operacaoId: string,
  vendaId?: string,
  vendaNovaId?: string,
): void {
  a.livro?.lancar({
    operacaoId,
    tipo,
    aparelho: a.nome,
    vendedorId: a.vendedorId,
    ...(vendaId ? { vendaId } : {}),
    ...(vendaNovaId ? { vendaNovaId } : {}),
  });
}

/** O que o teste conta de uma peça que volta: o estado é o da peça (padrão: boa). */
export interface PecaQueVoltaDoTeste {
  produtoId: string;
  quantidade: number;
  estado?: 'BOA' | 'DEFEITO';
}

const voltas = (itens: readonly PecaQueVoltaDoTeste[]): PecaQueVolta[] =>
  itens.map((i) => ({
    produtoId: i.produtoId,
    quantidade: i.quantidade,
    estado: i.estado ?? 'BOA',
  }));

/** Uma operação da Fase 6: a tela pediu, e só o que o app aceitou vai para o livro. O resultado volta sem exigir sucesso. */
export interface Tentativa {
  id: string;
  resultado: ResultadoDeChamada;
}

/** O texto da recusa do app (para o teste conferir o texto do glossário). */
export function mensagemDaRecusa(r: ResultadoDeChamada): string {
  if (r.ok) throw new Error('Esperava uma recusa e o app aceitou.');
  return r.mensagem ?? '';
}

function exigir(a: Aparelho, o: string, t: Tentativa): string {
  if (!t.resultado.ok)
    throw new Error(
      `${a.nome}: ${o} não deu certo: ${t.resultado.motivo} (${t.resultado.mensagem ?? ''})`,
    );
  return t.id;
}

/** Centavos inteiros de um texto "80.00" (o teste nunca usa ponto flutuante para dinheiro). */
function centavos(v: string): bigint {
  const [r, c = ''] = v.split('.');
  return BigInt(r!) * 100n + BigInt(c.padEnd(2, '0').slice(0, 2));
}
function reais(c: bigint): string {
  return `${c / 100n}.${String(c % 100n).padStart(2, '0')}`;
}

export interface OpcoesDeDevolucao {
  vendaId: string;
  itens: readonly PecaQueVoltaDoTeste[];
  motivo?: MotivoDeDevolucao;
  observacao?: string;
  forma?: 'DINHEIRO' | 'PIX' | 'CARTAO' | 'CHEQUE';
}

export interface OpcoesDeTroca {
  vendaId: string;
  devolve: readonly PecaQueVoltaDoTeste[];
  maletaId: string;
  leva: ReadonlyArray<{ produtoId: string; quantidade: number }>;
  motivo?: MotivoDeDevolucao;
  /** O que o cliente pagou da diferença (só quando a peça nova é mais cara). */
  pagou?: ReadonlyArray<{ valor: string; forma: 'DINHEIRO' | 'PIX' | 'CARTAO' | 'CHEQUE' }>;
}

export interface OpcoesDeVendaComCredito {
  maletaId: string;
  clienteId: string;
  produtoId: string;
  quantidade?: number;
  creditoId: string;
  /** Quanto do crédito usar; se faltar, o saldo que o computador mostra, até o total da venda. */
  valorDoCredito?: string;
}

/**
 * Atalhos da "tela" do vendedor: conferir a maleta, cadastrar cliente, vender e estornar. Cada operação que o app
 * aceitou vai para o livro do cenário.
 */
export const Tela = {
  async conferirTudo(a: Aparelho, maletaId: string): Promise<string> {
    const pecas = a.ler<{ produto_id: string; saldo: number }>(
      `SELECT i.produto_id, i.qtd_enviada + coalesce((SELECT sum(m.quantidade) FROM movimentos_remessa m
                WHERE m.remessa_id = i.remessa_id AND m.produto_id = i.produto_id AND m.deleted_at IS NULL), 0) AS saldo
         FROM itens_remessa i WHERE i.remessa_id = ? AND i.deleted_at IS NULL`,
      maletaId,
    );
    if (pecas.length === 0)
      throw new Error(`${a.nome}: a maleta ${maletaId} não chegou ao computador.`);
    const conferenciaId = randomUUID();
    await a.pedir({
      operacao: 'maletas.conferir',
      id: maletaId,
      conferenciaId,
      itens: pecas.map((p) => ({ produtoId: p.produto_id, contada: p.saldo })),
    });
    lancar(a, 'CONFERENCIA', conferenciaId);
    return conferenciaId;
  },

  async cliente(a: Aparelho, nome: string, cpf?: string): Promise<string> {
    const id = randomUUID();
    await a.pedir({
      operacao: 'clientes.cadastrar',
      cliente: { id, nome, ...(cpf ? { cpf } : {}), telefone: '(19) 99999-0000' },
    });
    lancar(a, 'CLIENTE_NOVO', id);
    return id;
  },

  /** Venda à vista em dinheiro de `quantidade` da peça (o valor vem do preço da maleta no computador). */
  async vender(
    a: Aparelho,
    o: {
      maletaId: string;
      clienteId: string;
      produtoId: string;
      quantidade?: number;
      pago?: boolean;
    },
  ): Promise<string> {
    const id = randomUUID();
    const quantidade = o.quantidade ?? 1;
    const [peca] = a.ler<{ preco_remessa: string }>(
      'SELECT preco_remessa FROM itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL',
      o.maletaId,
      o.produtoId,
    );
    if (!peca) throw new Error(`${a.nome}: a peça ${o.produtoId} não está na maleta.`);
    const total = (Number(peca.preco_remessa) * quantidade).toFixed(2);
    await a.pedir({
      operacao: 'vendas.vender',
      venda: {
        id,
        remessaId: o.maletaId,
        clienteId: o.clienteId,
        itens: [{ produtoId: o.produtoId, quantidade }],
        pagamentos: o.pago === false ? [] : [{ id: randomUUID(), valor: total, forma: 'DINHEIRO' }],
        totalConferido: total,
      },
    });
    lancar(a, 'VENDA', id);
    return id;
  },

  async estornar(a: Aparelho, vendaId: string): Promise<string> {
    const estornoId = randomUUID();
    await a.pedir({
      operacao: 'vendas.estornar',
      id: vendaId,
      estornoId,
      motivo: 'Cliente desistiu',
    });
    lancar(a, 'ESTORNO_VENDA', estornoId, vendaId);
    return estornoId;
  },

  // --- Fase 7 (tarefa 7.11): venda a prazo, recebimento do cliente e estorno. Só ids, quantidade de parcelas e vencimento ---

  /** Venda a prazo: o `pagoAgora` (opcional) é o que o cliente deu na hora; o resto vira as parcelas que o servidor divide. */
  async venderAPrazo(
    a: Aparelho,
    o: {
      maletaId: string;
      clienteId: string;
      produtoId: string;
      parcelas: number;
      primeiroVencimento: string;
      pagoAgora?: string;
    },
  ): Promise<string> {
    const id = randomUUID();
    await a.pedir({
      operacao: 'vendas.vender',
      venda: {
        id,
        remessaId: o.maletaId,
        clienteId: o.clienteId,
        itens: [{ produtoId: o.produtoId, quantidade: 1 }],
        pagamentos: o.pagoAgora
          ? [{ id: randomUUID(), valor: o.pagoAgora, forma: 'DINHEIRO' }]
          : [],
        parcelamento: { quantidade: o.parcelas, primeiroVencimento: o.primeiroVencimento },
      },
    });
    lancar(a, 'VENDA', id);
    return id;
  },

  /** O cliente paga depois da venda (Dinheiro ou Transferência, gravada como PIX). */
  async receber(
    a: Aparelho,
    o: { vendaId: string; valor: string; forma?: 'DINHEIRO' | 'PIX' },
  ): Promise<string> {
    const id = randomUUID();
    await a.pedir({
      operacao: 'recebimentos.receber',
      recebimento: { id, vendaId: o.vendaId, valor: o.valor, forma: o.forma ?? 'DINHEIRO' },
    });
    lancar(a, 'RECEBIMENTO_CLIENTE', id, o.vendaId);
    return id;
  },

  /** Estorna, no mesmo dia, um recebimento do cliente. */
  async estornarRecebimento(a: Aparelho, recebimentoId: string, vendaId: string): Promise<string> {
    const estornoId = randomUUID();
    await a.pedir({
      operacao: 'recebimentos.estornar',
      id: recebimentoId,
      estornoId,
      motivo: 'Valor errado (teste)',
    });
    lancar(a, 'ESTORNO_RECEBIMENTO_CLIENTE', estornoId, vendaId);
    return estornoId;
  },

  // --- Fase 6 (tarefa 6.11): os mesmos pedidos da ponte; nenhum valor é calculado aqui, só ids, peças e estados ---

  /** Tenta registrar a devolução (a recusa do app volta no resultado). */
  async tentarDevolver(a: Aparelho, o: OpcoesDeDevolucao): Promise<Tentativa> {
    const id = randomUUID();
    const resultado = await a.chamar({
      operacao: 'devolucoes.registrar',
      devolucao: {
        id,
        vendaId: o.vendaId,
        motivo: o.motivo ?? 'ARREPENDIMENTO',
        ...(o.observacao ? { observacao: o.observacao } : {}),
        formaReembolso: o.forma ?? 'DINHEIRO',
        itens: voltas(o.itens),
      },
    });
    if (resultado.ok) lancar(a, 'DEVOLUCAO', id, o.vendaId);
    return { id, resultado };
  },

  async devolver(a: Aparelho, o: OpcoesDeDevolucao): Promise<string> {
    return exigir(a, 'devolucoes.registrar', await Tela.tentarDevolver(a, o));
  },

  async pedirAutorizacao(
    a: Aparelho,
    o: {
      vendaId: string;
      itens: readonly PecaQueVoltaDoTeste[];
      motivo?: MotivoDeDevolucao;
      observacao?: string;
    },
  ): Promise<string> {
    const id = randomUUID();
    const resultado = await a.chamar({
      operacao: 'autorizacoes.pedir',
      pedido: {
        id,
        vendaId: o.vendaId,
        motivo: o.motivo ?? 'ARREPENDIMENTO',
        ...(o.observacao ? { observacao: o.observacao } : {}),
        itens: voltas(o.itens),
      },
    });
    exigir(a, 'autorizacoes.pedir', { id, resultado });
    lancar(a, 'PEDIDO_AUTORIZACAO', id, o.vendaId);
    return id;
  },

  /** Troca: devolve `devolve` da venda e leva `leva` da maleta. Os ids novos (troca, devolução, venda, crédito) são do teste. */
  async tentarTrocar(
    a: Aparelho,
    o: OpcoesDeTroca,
  ): Promise<Tentativa & { devolucaoId: string; vendaNovaId: string; creditoId: string }> {
    const id = randomUUID();
    const devolucaoId = randomUUID();
    const vendaNovaId = randomUUID();
    const creditoId = randomUUID();
    const pagamentos: PagamentoParaEnviar[] = (o.pagou ?? []).map((p) => ({
      id: randomUUID(),
      valor: p.valor,
      forma: p.forma,
    }));
    const resultado = await a.chamar({
      operacao: 'trocas.registrar',
      troca: {
        id,
        creditoId,
        pagamentoDoCreditoId: randomUUID(),
        devolucao: {
          id: devolucaoId,
          vendaId: o.vendaId,
          motivo: o.motivo ?? 'TAMANHO',
          itens: voltas(o.devolve),
        },
        venda: {
          id: vendaNovaId,
          remessaId: o.maletaId,
          itens: o.leva.map((i) => ({ produtoId: i.produtoId, quantidade: i.quantidade })),
          pagamentos,
        },
      },
    });
    if (resultado.ok) lancar(a, 'TROCA', id, o.vendaId, vendaNovaId);
    return { id, resultado, devolucaoId, vendaNovaId, creditoId };
  },

  async trocar(
    a: Aparelho,
    o: OpcoesDeTroca,
  ): Promise<{ id: string; devolucaoId: string; vendaNovaId: string; creditoId: string }> {
    const t = await Tela.tentarTrocar(a, o);
    exigir(a, 'trocas.registrar', t);
    return {
      id: t.id,
      devolucaoId: t.devolucaoId,
      vendaNovaId: t.vendaNovaId,
      creditoId: t.creditoId,
    };
  },

  /** Venda de `quantidade` da peça paga em parte com o crédito do cliente (o resto em dinheiro). */
  async tentarVenderComCredito(a: Aparelho, o: OpcoesDeVendaComCredito): Promise<Tentativa> {
    const id = randomUUID();
    const quantidade = o.quantidade ?? 1;
    const [peca] = a.ler<{ preco_remessa: string }>(
      'SELECT preco_remessa FROM itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL',
      o.maletaId,
      o.produtoId,
    );
    if (!peca) throw new Error(`${a.nome}: a peça ${o.produtoId} não está na maleta.`);
    const total = centavos(peca.preco_remessa) * BigInt(quantidade);
    let credito: bigint;
    if (o.valorDoCredito) credito = centavos(o.valorDoCredito);
    else {
      const c = await a.pedir<{ creditos: Array<{ id: string; saldo: string }> }>({
        operacao: 'creditos.doCliente',
        clienteId: o.clienteId,
      });
      const meu = c.creditos.find((x) => x.id === o.creditoId);
      const saldo = meu ? centavos(meu.saldo) : 0n;
      credito = saldo < total ? saldo : total;
    }
    const dinheiro = total - credito;
    const resultado = await a.chamar({
      operacao: 'vendas.vender',
      venda: {
        id,
        remessaId: o.maletaId,
        clienteId: o.clienteId,
        itens: [{ produtoId: o.produtoId, quantidade }],
        pagamentos: [
          { id: randomUUID(), valor: reais(credito), forma: 'CREDITO', creditoId: o.creditoId },
          ...(dinheiro > 0n
            ? [{ id: randomUUID(), valor: reais(dinheiro), forma: 'DINHEIRO' as const }]
            : []),
        ],
        totalConferido: reais(total),
      },
    });
    if (resultado.ok) lancar(a, 'VENDA', id);
    return { id, resultado };
  },

  async venderComCredito(a: Aparelho, o: OpcoesDeVendaComCredito): Promise<string> {
    return exigir(a, 'vendas.vender (crédito)', await Tela.tentarVenderComCredito(a, o));
  },

  async tentarCancelarDevolucao(
    a: Aparelho,
    devolucaoId: string,
    motivo = 'Cliente voltou atrás (teste)',
  ): Promise<Tentativa> {
    const id = randomUUID();
    const resultado = await a.chamar({
      operacao: 'devolucoes.cancelar',
      id: devolucaoId,
      cancelamentoId: id,
      motivo,
    });
    if (resultado.ok) lancar(a, 'CANCELAR_DEVOLUCAO', id, devolucaoId);
    return { id, resultado };
  },

  async cancelarDevolucao(a: Aparelho, devolucaoId: string, motivo?: string): Promise<string> {
    return exigir(
      a,
      'devolucoes.cancelar',
      await Tela.tentarCancelarDevolucao(a, devolucaoId, motivo),
    );
  },

  async tentarCancelarTroca(
    a: Aparelho,
    trocaId: string,
    motivo = 'Cliente voltou atrás (teste)',
  ): Promise<Tentativa> {
    const id = randomUUID();
    const resultado = await a.chamar({
      operacao: 'trocas.cancelar',
      id: trocaId,
      cancelamentoId: id,
      motivo,
    });
    if (resultado.ok) lancar(a, 'CANCELAR_TROCA', id, trocaId);
    return { id, resultado };
  },

  async cancelarTroca(a: Aparelho, trocaId: string, motivo?: string): Promise<string> {
    return exigir(a, 'trocas.cancelar', await Tela.tentarCancelarTroca(a, trocaId, motivo));
  },
};
