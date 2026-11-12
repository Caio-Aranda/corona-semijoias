import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { aplicarLote } from './aplicador-do-pull';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';

/** Camada de pendentes (ADR-0013, item 8): a edição do vendedor não some enquanto não chega ao servidor. */
let b: BancoDeTeste;
beforeEach(() => {
  b = criarBancoDeTeste();
});
afterEach(() => b.fechar());

const cliente = (id: string, version: number, campos: Record<string, unknown>) => ({
  tabela: 'clientes',
  id,
  version,
  deletedAt: null,
  campos: { nome: 'Ana', telefone: '11999990000', ...campos },
});

function alteracaoAguardando(clienteId: string, campos: Record<string, unknown>): string {
  const op = idDeTeste();
  b.db
    .prepare(
      "INSERT INTO outbox (operacao_id, tipo, formato, dados, criado_em_local) VALUES (?, 'CLIENTE_ALTERADO', 1, ?, 'agora')",
    )
    .run(op, JSON.stringify({ clienteId, versaoBase: 1, campos }));
  return op;
}

const ler = (id: string) =>
  b.db.prepare('SELECT nome, telefone, observacoes, version FROM clientes WHERE id = ?').get(id);

describe('camada de pendentes', () => {
  it('a alteração aguardando sobrevive ao pull que traz uma versão nova do cliente', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [cliente(id, 1, {})], { cursor: 'c1' });
    alteracaoAguardando(id, { telefone: { antes: '11999990000', depois: '11888880000' } });
    // A dona mudou as observações no servidor: chega a versão 2 com o telefone antigo.
    aplicarLote(b.db, [cliente(id, 2, { observacoes: 'Prefere prata' })], { cursor: 'c2' });
    expect(ler(id)).toEqual({
      nome: 'Ana',
      telefone: '11888880000',
      observacoes: 'Prefere prata',
      version: 2,
    });
  });

  it('várias alterações são reaplicadas na ordem em que foram feitas', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [cliente(id, 1, {})], { cursor: 'c1' });
    alteracaoAguardando(id, { nome: { antes: 'Ana', depois: 'Ana Maria' } });
    alteracaoAguardando(id, { nome: { antes: 'Ana Maria', depois: 'Ana Maria Souza' } });
    aplicarLote(b.db, [cliente(id, 2, {})], { cursor: 'c2' });
    expect(ler(id)).toMatchObject({ nome: 'Ana Maria Souza' });
  });

  it('some quando enviada: o pull seguinte traz o valor do servidor', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [cliente(id, 1, {})], { cursor: 'c1' });
    const op = alteracaoAguardando(id, {
      telefone: { antes: '11999990000', depois: '11777770000' },
    });
    b.db.prepare("UPDATE outbox SET situacao = 'ENVIADO' WHERE operacao_id = ?").run(op);
    aplicarLote(b.db, [cliente(id, 2, { telefone: '11666660000' })], { cursor: 'c2' });
    expect(ler(id)).toMatchObject({ telefone: '11666660000' });
  });

  it('campos que são só da dona nunca são reaplicados', () => {
    const id = idDeTeste();
    aplicarLote(b.db, [cliente(id, 1, { vendedor_responsavel_id: null })], { cursor: 'c1' });
    alteracaoAguardando(id, { vendedor_responsavel_id: { antes: null, depois: idDeTeste() } });
    aplicarLote(b.db, [cliente(id, 2, {})], { cursor: 'c2' });
    expect(
      b.db.prepare('SELECT vendedor_responsavel_id AS v FROM clientes WHERE id = ?').get(id),
    ).toEqual({ v: null });
  });
});
