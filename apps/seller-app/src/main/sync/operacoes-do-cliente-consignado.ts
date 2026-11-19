import type { BancoAberto } from '../banco/banco-local';
import { CAMPOS_DO_CLIENTE } from './camada-de-pendentes';
import { comissaoComDuasCasas, colocarFotoNaFila } from './operacoes-do-consignado';
import { ErroDaOperacao } from './operacoes-locais';
import { enfileirar } from './outbox';
import { uuidV7 } from './uuid';

/**
 * Mudanças no cadastro do cliente do consignado (tarefa 9.2; ADR-0016, item 4): alterar campos e trocar uma das três fotos.
 * Cada uma guarda o valor de antes e o novo, já mostra o novo na cópia local e põe na outbox o `CLIENTE_ALTERADO` no
 * formato 2. A camada de pendentes mantém o valor novo por cima do que chega do servidor até a operação ser aceita.
 */

const HASH = /^[0-9a-f]{64}$/;

const limpo = (v: string | null | undefined): string | null => {
  const t = (v ?? '').trim();
  return t === '' ? null : t;
};

/** Campos que precisam continuar preenchidos (a tela exige; o servidor também). */
const OBRIGATORIOS = new Set([
  'nome',
  'enderecoLogradouro',
  'enderecoNumero',
  'enderecoBairro',
  'enderecoCidade',
  'enderecoUf',
]);

/** Os campos que o representante muda pela tela (os hashes das fotos só mudam por `trocarFotoDoCliente`). */
const EDITAVEIS = new Set([
  ...OBRIGATORIOS,
  'documento',
  'documentoTipo',
  'dataNascimento',
  'telefone',
  'whatsapp',
  'enderecoComplemento',
  'enderecoCep',
  'indicadoPorNome',
  'comoConheceu',
  'comissaoPercentual',
  'observacoes',
]);

export function alterarClienteDoConsignado(
  db: BancoAberto,
  agora: Date,
  clienteId: string,
  novos: Readonly<Record<string, string | undefined>>,
): { operacaoId: string } | null {
  const atual = db
    .prepare('SELECT * FROM clientes WHERE id = ? AND deleted_at IS NULL')
    .get(clienteId) as Record<string, unknown> | undefined;
  if (!atual) throw new ErroDaOperacao('CLIENTE_NAO_ENCONTRADO');
  const campos: Record<string, { antes: string | null; depois: string | null }> = {};
  for (const [campo, valor] of Object.entries(novos)) {
    const coluna = CAMPOS_DO_CLIENTE[campo];
    if (!coluna || valor === undefined || !EDITAVEIS.has(campo)) continue;
    let depois = limpo(valor);
    if (OBRIGATORIOS.has(campo) && depois === null)
      throw new ErroDaOperacao('CADASTRO_DO_CLIENTE_INCOMPLETO');
    if (campo === 'comissaoPercentual') depois = comissaoComDuasCasas(depois ?? '30');
    const antes = (atual[coluna] as string | null | undefined) ?? null;
    if (antes === depois) continue;
    campos[campo] = { antes, depois };
  }
  if (Object.keys(campos).length === 0) return null;
  const telefone = campos['telefone'] ? campos['telefone'].depois : (atual['telefone'] as string | null);
  const whatsapp = campos['whatsapp'] ? campos['whatsapp'].depois : (atual['whatsapp'] as string | null);
  if (!telefone && !whatsapp) throw new ErroDaOperacao('CADASTRO_DO_CLIENTE_INCOMPLETO');
  for (const [campo, a] of Object.entries(campos))
    db.prepare(`UPDATE clientes SET ${CAMPOS_DO_CLIENTE[campo]} = ? WHERE id = ?`).run(
      a.depois,
      clienteId,
    );
  const operacaoId = uuidV7(agora);
  enfileirar(db, {
    operacaoId,
    tipo: 'CLIENTE_ALTERADO',
    dados: JSON.stringify({ clienteId, versaoBase: atual['version'] ?? null, campos }),
    agora,
    formato: 2,
  });
  return { operacaoId };
}

const COLUNA_DA_FOTO = {
  cliente: ['fotoHash', 'foto_hash'],
  'documento-frente': ['fotoDocumentoFrenteHash', 'foto_documento_frente_hash'],
  'documento-verso': ['fotoDocumentoVersoHash', 'foto_documento_verso_hash'],
} as const;

export function trocarFotoDoCliente(
  db: BancoAberto,
  agora: Date,
  clienteId: string,
  tipo: keyof typeof COLUNA_DA_FOTO,
  hash: string,
): { operacaoId: string } | null {
  if (!HASH.test(hash)) throw new ErroDaOperacao('FOTO_INVALIDA');
  const [campo, coluna] = COLUNA_DA_FOTO[tipo];
  const atual = db
    .prepare(`SELECT ${coluna} AS antes, version FROM clientes WHERE id = ? AND deleted_at IS NULL`)
    .get(clienteId) as { antes: string | null; version: number } | undefined;
  if (!atual) throw new ErroDaOperacao('CLIENTE_NAO_ENCONTRADO');
  if (atual.antes === hash) return null;
  db.prepare(`UPDATE clientes SET ${coluna} = ? WHERE id = ?`).run(hash, clienteId);
  colocarFotoNaFila(db, clienteId, tipo, hash);
  const operacaoId = uuidV7(agora);
  enfileirar(db, {
    operacaoId,
    tipo: 'CLIENTE_ALTERADO',
    dados: JSON.stringify({
      clienteId,
      versaoBase: atual.version,
      campos: { [campo]: { antes: atual.antes, depois: hash } },
    }),
    agora,
    formato: 2,
  });
  return { operacaoId };
}
