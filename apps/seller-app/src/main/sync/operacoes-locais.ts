import { textoDeGravacaoBloqueada } from '../../shared/prazo';
import { type BancoAberto, gravar } from '../banco/banco-local';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import type {
  ClienteParaCadastrar,
  ContagemDaPeca,
  FormaDePagamento,
  VendaParaEnviar,
} from '../../shared/operacoes';
import { type Alteracao, CAMPOS_DO_CLIENTE } from './camada-de-pendentes';
import { CHAVES, lerEstado, registrarHoraLocal } from './estado-sync';
import { enfileirar, marcarProvisoria, type TipoDeOperacao } from './outbox';
import { uuidV7 } from './uuid';

/** Por que uma operação feita no computador não foi gravada. Cada código tem o texto do glossário. */
export type CodigoDaOperacao =
  | 'PECA_FORA_DA_MALETA'
  | 'SEM_SALDO'
  | 'DESCONTO_MAIOR_QUE_A_PECA'
  | 'DESCONTO_ACIMA_DO_LIMITE'
  | 'PAGO_PASSA_DO_TOTAL'
  | 'PAGAMENTO_SEM_VALOR'
  | 'VENDA_NAO_ENCONTRADA'
  | 'VENDA_JA_ESTORNADA'
  | 'CLIENTE_JA_CADASTRADO'
  | 'CLIENTE_NAO_ENCONTRADO'
  | 'MALETA_NAO_ENCONTRADA'
  | 'PRAZO_ESGOTADO'
  | 'RELOGIO_ERRADO';

/** Textos do glossário (parte "Motor de envio do Corona Vendas", tarefa 5.6). */
export const MENSAGENS_DAS_OPERACOES: Readonly<Record<CodigoDaOperacao, string>> = Object.freeze({
  PECA_FORA_DA_MALETA: 'Esta peça não consta na sua maleta.',
  SEM_SALDO: 'Não há mais peças desta na sua maleta. Confira a quantidade.',
  DESCONTO_MAIOR_QUE_A_PECA: 'O desconto é maior que o valor da peça.',
  DESCONTO_ACIMA_DO_LIMITE: 'O desconto passa do limite que a dona definiu. Diminua o desconto.',
  PAGO_PASSA_DO_TOTAL: 'O valor pago passa do total da venda. Confira os pagamentos.',
  PAGAMENTO_SEM_VALOR: 'O valor do pagamento precisa ser maior que zero.',
  VENDA_NAO_ENCONTRADA:
    'Não encontramos esta venda neste computador. Atualize a tela e tente de novo.',
  VENDA_JA_ESTORNADA: 'Esta venda já foi estornada.',
  CLIENTE_JA_CADASTRADO: 'Este cliente já está cadastrado.',
  CLIENTE_NAO_ENCONTRADO:
    'Não encontramos este cliente neste computador. Atualize a tela e tente de novo.',
  MALETA_NAO_ENCONTRADA:
    'Não encontramos esta maleta neste computador. Atualize a tela e tente de novo.',
  // Prazo sem internet (tarefa 5.8): os mesmos textos do glossário que a tela do prazo usa.
  PRAZO_ESGOTADO: textoDeGravacaoBloqueada('PRAZO'),
  RELOGIO_ERRADO: textoDeGravacaoBloqueada('RELOGIO'),
});

export interface ClienteJaCadastrado {
  id: string;
  nome: string;
  telefone?: string;
  cidade?: string;
}

export class ErroDaOperacao extends Error {
  constructor(
    readonly codigo: CodigoDaOperacao,
    readonly clienteExistente?: ClienteJaCadastrado,
  ) {
    super(
      codigo === 'CLIENTE_JA_CADASTRADO' && clienteExistente
        ? `Este cliente já está cadastrado: ${clienteExistente.nome}.`
        : MENSAGENS_DAS_OPERACOES[codigo],
    );
    this.name = 'ErroDaOperacao';
  }
}

export interface OpcoesDasOperacoes {
  banco: () => BancoAberto;
  agora?: () => Date;
  /** Avisado depois de cada gravação (o motor envia 2 s depois, agrupando gravações seguidas). */
  aoGravar?: () => void;
  /** Lança `ErroDaOperacao` (`PRAZO_ESGOTADO` ou `RELOGIO_ERRADO`) quando o computador não pode gravar (tarefa 5.8). */
  podeGravar?: () => void;
}

/** Campos do cadastro que o vendedor altera (os mesmos do `CLIENTE_ALTERADO`). */
export type CampoDoCliente =
  'nome' | 'cpf' | 'telefone' | 'whatsapp' | 'observacoes' | 'dataNascimento';

/**
 * As cinco operações do vendedor no computador (ADR-0013, itens 2 e 7). Cada uma grava a cópia PROVISÓRIA e a linha
 * da outbox NA MESMA transação: ou as duas existem, ou nenhuma (disco cheio desfaz tudo, com o texto do glossário). O
 * `dados` guardado é o JSON exato do formato 1 da tarefa 5.5 e nunca é remontado. Totais e saldo são prévia, em
 * centavos inteiros: o que vale é o que o servidor devolve.
 */
export class OperacoesLocais {
  constructor(private readonly o: OpcoesDasOperacoes) {}

  private agora(): Date {
    return this.o.agora?.() ?? new Date();
  }

  private executar<T>(fn: (db: BancoAberto, agora: Date) => T): T {
    // Prazo sem internet acabado (ou relógio errado): nada novo grava, mas o envio do que já existe continua (tarefa 5.8).
    this.o.podeGravar?.();
    const db = this.o.banco();
    const agora = this.agora();
    const r = gravar(db, () => {
      registrarHoraLocal(db, agora);
      return fn(db, agora);
    });
    this.o.aoGravar?.();
    return r;
  }

  /** Venda sem internet: venda, itens, baixa da maleta e pagamentos provisórios, e a operação `VENDA`. */
  vender(venda: VendaParaEnviar): { operacaoId: string; total: string } {
    return this.executar((db, agora) => {
      const remessa = db
        .prepare('SELECT id FROM remessas WHERE id = ? AND deleted_at IS NULL')
        .get(venda.remessaId);
      if (!remessa) throw new ErroDaOperacao('MALETA_NAO_ENCONTRADA');

      let subtotal = 0n;
      let desconto = 0n;
      // A quantidade de cada peça na venda inteira: duas linhas da mesma peça não passam juntas do saldo.
      const porPeca = new Map<string, number>();
      for (const item of venda.itens)
        porPeca.set(item.produtoId, (porPeca.get(item.produtoId) ?? 0) + item.quantidade);
      const linhas = venda.itens.map((item) => {
        const peca = db
          .prepare(
            `SELECT preco_remessa FROM itens_remessa
              WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL LIMIT 1`,
          )
          .get(venda.remessaId, item.produtoId) as { preco_remessa: string } | undefined;
        if (!peca) throw new ErroDaOperacao('PECA_FORA_DA_MALETA');
        if ((porPeca.get(item.produtoId) ?? 0) > saldoDaPeca(db, venda.remessaId, item.produtoId))
          throw new ErroDaOperacao('SEM_SALDO');
        const preco = exigirCentavos(peca.preco_remessa);
        const valor = preco * BigInt(item.quantidade);
        const d = exigirCentavos(item.desconto ?? '0');
        if (d < 0n || d > valor) throw new ErroDaOperacao('DESCONTO_MAIOR_QUE_A_PECA');
        subtotal += valor;
        desconto += d;
        return { item, preco, desconto: d };
      });
      const limite = limiteDeDesconto(db);
      if (limite !== null && desconto * 100n > subtotal * BigInt(limite))
        throw new ErroDaOperacao('DESCONTO_ACIMA_DO_LIMITE');
      const total = subtotal - desconto;
      let pago = 0n;
      for (const p of venda.pagamentos) {
        const valor = exigirCentavos(p.valor);
        if (valor <= 0n) throw new ErroDaOperacao('PAGAMENTO_SEM_VALOR');
        pago += valor;
      }
      if (pago > total) throw new ErroDaOperacao('PAGO_PASSA_DO_TOTAL');

      const vendidaEm = agora.toISOString();
      const dados = JSON.stringify({
        id: venda.id,
        remessaId: venda.remessaId,
        clienteId: venda.clienteId,
        itens: linhas.map((l) => ({
          produtoId: l.item.produtoId,
          quantidade: l.item.quantidade,
          desconto: textoDeCentavos(l.desconto),
        })),
        pagamentos: venda.pagamentos.map((p) => ({
          id: p.id,
          valor: textoDeCentavos(exigirCentavos(p.valor)),
          forma: p.forma,
        })),
        totalConferido: textoDeCentavos(total),
        vendidaEm,
      });

      inserir(db, 'vendas', venda.id, venda.id, {
        cliente_id: venda.clienteId,
        remessa_id: venda.remessaId,
        status: 'CONCLUIDA',
        subtotal: textoDeCentavos(subtotal),
        desconto: textoDeCentavos(desconto),
        total: textoDeCentavos(total),
        vendida_em: vendidaEm,
      });
      for (const l of linhas) {
        inserir(db, 'itens_venda', uuidV7(agora), venda.id, {
          venda_id: venda.id,
          produto_id: l.item.produtoId,
          quantidade: l.item.quantidade,
          preco_unitario: textoDeCentavos(l.preco),
          desconto: textoDeCentavos(l.desconto),
        });
        inserir(db, 'movimentos_remessa', uuidV7(agora), venda.id, {
          remessa_id: venda.remessaId,
          produto_id: l.item.produtoId,
          tipo: 'VENDA',
          quantidade: -l.item.quantidade,
          venda_id: venda.id,
        });
      }
      for (const p of venda.pagamentos) {
        inserir(db, 'pagamentos_cliente', p.id, venda.id, {
          venda_id: venda.id,
          tipo: 'PAGAMENTO',
          forma: p.forma,
          valor: textoDeCentavos(exigirCentavos(p.valor)),
          pago_em: vendidaEm,
        });
      }
      enfileirar(db, { operacaoId: venda.id, tipo: 'VENDA', dados, agora });
      return { operacaoId: venda.id, total: textoDeCentavos(total) };
    });
  }

  /** Estorno sem internet: as peças voltam para a maleta e o que foi pago volta como reembolso, provisórios. */
  estornar(estorno: { id: string; vendaId: string; motivo: string }): { operacaoId: string } {
    return this.executar((db, agora) => {
      const venda = db
        .prepare('SELECT status, remessa_id FROM vendas WHERE id = ? AND deleted_at IS NULL')
        .get(estorno.vendaId) as { status: string; remessa_id: string } | undefined;
      if (!venda) throw new ErroDaOperacao('VENDA_NAO_ENCONTRADA');
      const jaPedido = db
        .prepare(
          `SELECT 1 FROM outbox WHERE tipo = 'ESTORNO_VENDA' AND situacao IN ('AGUARDANDO', 'ENVIADO')
              AND json_extract(dados, '$.vendaId') = ?`,
        )
        .get(estorno.vendaId);
      if (venda.status !== 'CONCLUIDA' || jaPedido) throw new ErroDaOperacao('VENDA_JA_ESTORNADA');

      const estornadoEm = agora.toISOString();
      const dados = JSON.stringify({
        id: estorno.id,
        vendaId: estorno.vendaId,
        motivo: estorno.motivo,
        estornadoEm,
      });
      const baixas = db
        .prepare(
          `SELECT id, produto_id, quantidade FROM movimentos_remessa
            WHERE venda_id = ? AND tipo = 'VENDA' AND estorna_id IS NULL AND deleted_at IS NULL ORDER BY produto_id, id`,
        )
        .all(estorno.vendaId) as { id: string; produto_id: string; quantidade: number }[];
      for (const baixa of baixas) {
        inserir(db, 'movimentos_remessa', uuidV7(agora), estorno.id, {
          remessa_id: venda.remessa_id,
          produto_id: baixa.produto_id,
          tipo: 'ESTORNO_VENDA',
          quantidade: -baixa.quantidade,
          venda_id: estorno.vendaId,
          estorna_id: baixa.id,
        });
      }
      const pagos = db
        .prepare(
          `SELECT forma, valor FROM pagamentos_cliente
            WHERE venda_id = ? AND tipo = 'PAGAMENTO' AND deleted_at IS NULL ORDER BY pago_em, id`,
        )
        .all(estorno.vendaId) as { forma: FormaDePagamento; valor: string }[];
      for (const p of pagos) {
        inserir(db, 'pagamentos_cliente', uuidV7(agora), estorno.id, {
          venda_id: estorno.vendaId,
          tipo: 'REEMBOLSO',
          forma: p.forma,
          valor: textoDeCentavos(-exigirCentavos(p.valor)),
          pago_em: estornadoEm,
        });
      }
      enfileirar(db, { operacaoId: estorno.id, tipo: 'ESTORNO_VENDA', dados, agora });
      return { operacaoId: estorno.id };
    });
  }

  /** Cliente novo sem internet. CPF repetido é conferido no diretório da loja guardado no computador. */
  cadastrarCliente(c: ClienteParaCadastrar): { operacaoId: string } {
    return this.executar((db, agora) => {
      if (c.cpf) conferirCpfLivre(db, c.cpf, c.id);
      const dados = JSON.stringify({
        id: c.id,
        nome: c.nome,
        cpf: c.cpf ?? null,
        dataNascimento: c.dataNascimento ?? null,
        telefone: c.telefone ?? null,
        whatsapp: c.whatsapp ?? null,
        observacoes: c.observacoes ?? null,
      });
      inserir(db, 'clientes', c.id, c.id, {
        nome: c.nome,
        cpf: c.cpf ?? null,
        data_nascimento: c.dataNascimento ?? null,
        telefone: c.telefone ?? null,
        whatsapp: c.whatsapp ?? null,
        observacoes: c.observacoes ?? null,
        ativo: 1,
      });
      enfileirar(db, { operacaoId: c.id, tipo: 'CLIENTE_NOVO', dados, agora });
      return { operacaoId: c.id };
    });
  }

  /**
   * Alteração do cadastro, campo a campo (ADR-0013, item 8): guarda o valor de antes e o novo de cada campo que mudou e
   * já mostra o novo na cópia local. Sem mudança, nada é gravado (`null`).
   */
  alterarCliente(
    clienteId: string,
    novos: Partial<Record<CampoDoCliente, string | null>>,
  ): { operacaoId: string } | null {
    return this.executar((db, agora) => {
      const atual = db
        .prepare('SELECT * FROM clientes WHERE id = ? AND deleted_at IS NULL')
        .get(clienteId) as Record<string, unknown> | undefined;
      if (!atual) throw new ErroDaOperacao('CLIENTE_NAO_ENCONTRADO');
      const campos: Record<string, Alteracao> = {};
      for (const [campo, depois] of Object.entries(novos)) {
        const coluna = CAMPOS_DO_CLIENTE[campo];
        if (!coluna || depois === undefined) continue;
        const antes = (atual[coluna] as string | null | undefined) ?? null;
        if (antes === depois) continue;
        campos[campo] = { antes, depois };
      }
      if (Object.keys(campos).length === 0) return null;
      const novoCpf = campos['cpf']?.depois;
      if (novoCpf) conferirCpfLivre(db, novoCpf, clienteId);
      for (const [campo, a] of Object.entries(campos)) {
        db.prepare(`UPDATE clientes SET ${CAMPOS_DO_CLIENTE[campo]} = ? WHERE id = ?`).run(
          a.depois,
          clienteId,
        );
      }
      const operacaoId = uuidV7(agora);
      const dados = JSON.stringify({ clienteId, versaoBase: atual['version'], campos });
      enfileirar(db, { operacaoId, tipo: 'CLIENTE_ALTERADO', dados, agora });
      return { operacaoId };
    });
  }

  /** Conferência da maleta sem internet. O esperado de cada peça é o saldo que o computador conhece (prévia). */
  conferir(c: { id: string; remessaId: string; itens: ContagemDaPeca[] }): { operacaoId: string } {
    return this.executar((db, agora) => {
      const remessa = db
        .prepare('SELECT id FROM remessas WHERE id = ? AND deleted_at IS NULL')
        .get(c.remessaId);
      if (!remessa) throw new ErroDaOperacao('MALETA_NAO_ENCONTRADA');
      const conferidaEm = agora.toISOString();
      const dados = JSON.stringify({
        id: c.id,
        remessaId: c.remessaId,
        itens: c.itens.map((i) => ({ produtoId: i.produtoId, contada: i.contada })),
      });
      let divergencias = 0;
      for (const i of c.itens) {
        const esperado = saldoDaPeca(db, c.remessaId, i.produtoId);
        if (esperado !== i.contada) divergencias += 1;
        inserir(db, 'itens_conferencia', uuidV7(agora), c.id, {
          conferencia_id: c.id,
          produto_id: i.produtoId,
          esperado,
          contado: i.contada,
        });
      }
      inserir(db, 'conferencias', c.id, c.id, {
        remessa_id: c.remessaId,
        divergencias,
        conferida_em: conferidaEm,
      });
      enfileirar(db, { operacaoId: c.id, tipo: 'CONFERENCIA', dados, agora });
      return { operacaoId: c.id };
    });
  }
}

/**
 * Saldo de uma peça na maleta, como o servidor calcula: `qtd_enviada` mais os movimentos (do servidor e provisórios).
 * É prévia: o que vale é o saldo do servidor.
 */
export function saldoDaPeca(db: BancoAberto, remessaId: string, produtoId: string): number {
  const r = db
    .prepare(
      `SELECT
         coalesce((SELECT sum(qtd_enviada) FROM itens_remessa
                    WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL), 0)
       + coalesce((SELECT sum(quantidade) FROM movimentos_remessa
                    WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL), 0) AS saldo`,
    )
    .get(remessaId, produtoId, remessaId, produtoId) as { saldo: number };
  return r.saldo;
}

function limiteDeDesconto(db: BancoAberto): number | null {
  const texto = lerEstado(db, CHAVES.parametros);
  if (!texto) return null;
  try {
    const p = JSON.parse(texto) as { limiteDescontoPercentual?: unknown };
    return typeof p.limiteDescontoPercentual === 'number' &&
      Number.isInteger(p.limiteDescontoPercentual)
      ? p.limiteDescontoPercentual
      : null;
  } catch {
    return null;
  }
}

const soDigitos = (cpf: string): string => cpf.replace(/\D/g, '');

function conferirCpfLivre(db: BancoAberto, cpf: string, proprioId: string): void {
  const alvo = soDigitos(cpf);
  if (!alvo) return;
  const candidatos = db
    .prepare(
      `SELECT id, nome, cpf, telefone, endereco_cidade FROM clientes
        WHERE cpf IS NOT NULL AND deleted_at IS NULL AND id <> ?`,
    )
    .all(proprioId) as {
    id: string;
    nome: string;
    cpf: string;
    telefone: string | null;
    endereco_cidade: string | null;
  }[];
  const achado = candidatos.find((c) => soDigitos(c.cpf) === alvo);
  if (!achado) return;
  throw new ErroDaOperacao('CLIENTE_JA_CADASTRADO', {
    id: achado.id,
    nome: achado.nome,
    ...(achado.telefone ? { telefone: achado.telefone } : {}),
    ...(achado.endereco_cidade ? { cidade: achado.endereco_cidade } : {}),
  });
}

function inserir(
  db: BancoAberto,
  tabela: string,
  id: string,
  operacaoId: string,
  colunas: Record<string, string | number | null>,
): void {
  const nomes = ['id', 'version', 'origem_local', ...Object.keys(colunas)];
  db.prepare(
    `INSERT INTO ${tabela} (${nomes.join(', ')}) VALUES (${nomes.map(() => '?').join(', ')})`,
  ).run(id, 1, 'PROVISORIO', ...Object.values(colunas));
  marcarProvisoria(db, tabela, id, operacaoId);
}

export type { TipoDeOperacao };
