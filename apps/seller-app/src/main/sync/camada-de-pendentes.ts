import type { BancoAberto } from '../banco/banco-local';

/**
 * Campos do cadastro que o vendedor altera (nome no `CLIENTE_ALTERADO` → coluna local). É a mesma lista do servidor
 * (`ClientesDoVendedor.CAMPOS_DO_VENDEDOR`): responsável e ativo são só da dona.
 */
export const CAMPOS_DO_CLIENTE: Readonly<Record<string, string>> = Object.freeze({
  nome: 'nome',
  cpf: 'cpf',
  telefone: 'telefone',
  whatsapp: 'whatsapp',
  observacoes: 'observacoes',
  dataNascimento: 'data_nascimento',
});

export interface Alteracao {
  antes: string | null;
  depois: string | null;
}

export interface DadosDaAlteracaoDeCliente {
  clienteId: string;
  versaoBase: number | null;
  campos: Record<string, Alteracao>;
}

/** Lê o `dados` de um `CLIENTE_ALTERADO` guardado. O JSON foi gravado por {@link operacoes-locais}; nunca é remontado. */
export function lerAlteracao(dados: string): DadosDaAlteracaoDeCliente | null {
  try {
    const v = JSON.parse(dados) as Partial<DadosDaAlteracaoDeCliente>;
    if (typeof v.clienteId !== 'string' || typeof v.campos !== 'object' || v.campos === null)
      return null;
    return { clienteId: v.clienteId, versaoBase: v.versaoBase ?? null, campos: v.campos };
  } catch {
    return null;
  }
}

/**
 * Camada de pendentes (ADR-0013, item 8): depois que um lote do pull trocou a cópia de um cliente, as alterações desse
 * cliente ainda AGUARDANDO envio são reaplicadas por cima, campo a campo (valor `depois`), na ordem em que foram feitas.
 * Assim o vendedor não vê a edição dele sumir enquanto ela não chega ao servidor. A `version` local não muda (é a do
 * servidor), então a conferência por resumo continua batendo. Roda dentro da transação do lote.
 */
export function reaplicarPendentes(db: BancoAberto, clienteIds: Iterable<string>): void {
  const alvo = new Set(clienteIds);
  if (alvo.size === 0) return;
  const pendentes = db
    .prepare(
      "SELECT dados FROM outbox WHERE tipo = 'CLIENTE_ALTERADO' AND situacao = 'AGUARDANDO' ORDER BY ordem",
    )
    .all() as { dados: string }[];
  for (const p of pendentes) {
    const a = lerAlteracao(p.dados);
    if (!a || !alvo.has(a.clienteId)) continue;
    aplicarCampos(db, a.clienteId, a.campos, 'depois');
  }
}

/**
 * Grava na cópia local os valores `depois` (ou `antes`, para desfazer) de uma alteração. Só os campos da lista do
 * vendedor; o resto é ignorado, como no servidor.
 */
export function aplicarCampos(
  db: BancoAberto,
  clienteId: string,
  campos: Record<string, Alteracao>,
  lado: 'antes' | 'depois',
): void {
  for (const [campo, alteracao] of Object.entries(campos)) {
    const coluna = CAMPOS_DO_CLIENTE[campo];
    if (!coluna || typeof alteracao !== 'object' || alteracao === null) continue;
    const valor = alteracao[lado];
    db.prepare(`UPDATE clientes SET ${coluna} = ? WHERE id = ?`).run(valor ?? null, clienteId);
  }
}
