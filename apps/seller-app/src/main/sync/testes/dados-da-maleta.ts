import type { BancoAberto } from '../../banco/banco-local';
import { aplicarLote } from '../aplicador-do-pull';
import { idDeTeste } from './banco-de-teste';

export interface MaletaDeTeste {
  remessaId: string;
  produtoA: string;
  produtoB: string;
  clienteId: string;
}

/** Uma maleta recebida do servidor: 3 peças A a R$ 33,33 e 1 peça B a R$ 10,00, e um cliente. */
export function receberMaleta(db: BancoAberto): MaletaDeTeste {
  const remessaId = idDeTeste(1);
  const produtoA = idDeTeste(2);
  const produtoB = idDeTeste(2);
  const clienteId = idDeTeste(3);
  aplicarLote(
    db,
    [
      {
        tabela: 'remessas',
        id: remessaId,
        version: 1,
        deletedAt: null,
        campos: { status: 'ENVIADA', total_valor: '109.99' },
      },
      linhaDeItem(remessaId, produtoA, 3, '33.33'),
      linhaDeItem(remessaId, produtoB, 1, '10.00'),
      {
        tabela: 'clientes',
        id: clienteId,
        version: 1,
        deletedAt: null,
        campos: { nome: 'Ana', cpf: '12345678909', telefone: '11999990000' },
      },
    ],
    { cursor: 'c-maleta' },
  );
  return { remessaId, produtoA, produtoB, clienteId };
}

function linhaDeItem(remessaId: string, produtoId: string, qtd: number, preco: string) {
  return {
    tabela: 'itens_remessa',
    id: idDeTeste(4),
    version: 1,
    deletedAt: null,
    campos: {
      remessa_id: remessaId,
      produto_id: produtoId,
      qtd_enviada: qtd,
      preco_remessa: preco,
    },
  };
}
