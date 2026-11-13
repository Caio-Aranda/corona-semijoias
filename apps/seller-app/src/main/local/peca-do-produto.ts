import type { BancoAberto } from '../banco/banco-local';
import type { PecaDaMaleta } from '../../shared/dados';

/**
 * A peça como a tela mostra (nome, código, foto, categoria e COD Fábrica), lida do banco do computador. Nunca custo
 * nem estoque da loja (o app não recebe). A falta de foto nunca trava nada: `fotoHash` nulo vira "sem foto" na tela.
 */
export function pecaDoProduto(db: BancoAberto, produtoId: string): PecaDaMaleta {
  const p = db
    .prepare(
      `SELECT p.nome, p.foto_principal_hash, g.nome AS categoria
         FROM produtos p LEFT JOIN categorias g ON g.id = p.categoria_id WHERE p.id = ?`,
    )
    .get(produtoId) as
    { nome: string; foto_principal_hash: string | null; categoria: string | null } | undefined;
  const codigos = db
    .prepare(
      `SELECT codigo, tipo FROM produto_codigos
        WHERE produto_id = ? AND deleted_at IS NULL AND ativo IS NOT 0
        ORDER BY principal DESC, codigo`,
    )
    .all(produtoId) as { codigo: string; tipo: string | null }[];
  return {
    id: produtoId,
    nome: p?.nome ?? 'Peça',
    codigo: codigos[0]?.codigo ?? null,
    fotoHash: p?.foto_principal_hash ?? null,
    categoria: p?.categoria ?? null,
    codigoFornecedor: codigos.find((c) => c.tipo === 'FORNECEDOR')?.codigo ?? null,
  };
}
