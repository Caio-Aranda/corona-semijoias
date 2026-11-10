import { describe, expect, it } from 'vitest';
import { soAtivosMaisAtual } from './escolhas';

const itens = [
  { id: 'a', nome: 'Centro', ativo: true },
  { id: 'b', nome: 'Norte', ativo: false },
  { id: 'c', nome: 'Sul', ativo: false },
];

describe('soAtivosMaisAtual', () => {
  it('esconde os inativos das escolhas', () => {
    expect(soAtivosMaisAtual(itens, '', '(inativa)').map((i) => i.id)).toEqual(['a']);
  });

  it('mantém o inativo que já estava escolhido, com a marca', () => {
    const r = soAtivosMaisAtual(itens, 'b', '(inativa)');
    expect(r.map((i) => i.nome)).toEqual(['Centro', 'Norte (inativa)']);
  });
});
