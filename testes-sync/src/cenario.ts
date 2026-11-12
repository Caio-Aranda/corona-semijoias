import { randomUUID } from 'node:crypto';
import { Aparelho, type OpcoesDoAparelho } from './aparelho';
import { afirmarNoFim, type AfirmacaoFinal } from './afirmacoes';
import { Dona, type VendedorDeTeste } from './dona';
import { Livro, type TipoDeOperacao } from './livro';

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

function lancar(a: Aparelho, tipo: TipoDeOperacao, operacaoId: string, vendaId?: string): void {
  a.livro?.lancar({
    operacaoId,
    tipo,
    aparelho: a.nome,
    vendedorId: a.vendedorId,
    ...(vendaId ? { vendaId } : {}),
  });
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
};
