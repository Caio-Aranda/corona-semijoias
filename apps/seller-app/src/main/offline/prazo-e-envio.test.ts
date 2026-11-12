import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { registrarContato } from '../sync/estado-sync';
import { CicloDeSincronizacao } from '../sync/ciclo';
import { ErroDaOperacao, OperacoesLocais } from '../sync/operacoes-locais';
import { criarTransporteDoSync } from '../sync/transporte-do-sync';
import {
  type BancoDeTeste,
  contar,
  criarBancoDeTeste,
  idDeTeste,
} from '../sync/testes/banco-de-teste';
import { type MaletaDeTeste, receberMaleta } from '../sync/testes/dados-da-maleta';
import { ServidorFalso } from '../sync/testes/servidor-falso';
import { ControleDoPrazo, DIA_MS } from './prazo-offline';
import { textoDeGravacaoBloqueada } from '../../shared/prazo';

/**
 * Prazo esgotado bloqueia o que grava (venda, estorno, conferência, cliente) e continua enviando o que já existe
 * (tarefa 5.8; ADR-0013, item 12). Banco cifrado de verdade e servidor falso de verdade.
 */
const CONTATO = new Date('2026-11-01T12:00:00.000Z');
let b: BancoDeTeste;
let m: MaletaDeTeste;
let agora: Date;
let prazo: ControleDoPrazo;
let ops: OperacoesLocais;
let servidor: ServidorFalso;
let url: string;

const venda = (id = idDeTeste(5)) => ({
  id,
  remessaId: m.remessaId,
  clienteId: m.clienteId,
  itens: [{ produtoId: m.produtoA, quantidade: 1, desconto: '0.00' }],
  pagamentos: [{ id: idDeTeste(6), valor: '10.00', forma: 'PIX' as const }],
});

beforeEach(async () => {
  b = criarBancoDeTeste();
  m = receberMaleta(b.db);
  agora = CONTATO;
  registrarContato(b.db, CONTATO.toISOString(), CONTATO);
  prazo = new ControleDoPrazo({ banco: () => b.db, agora: () => agora });
  ops = new OperacoesLocais({
    banco: () => b.db,
    agora: () => agora,
    podeGravar: () => {
      const s = prazo.situacao();
      if (s.situacao === 'ESGOTADO') {
        throw new ErroDaOperacao(s.motivo === 'RELOGIO' ? 'RELOGIO_ERRADO' : 'PRAZO_ESGOTADO');
      }
    },
  });
  servidor = new ServidorFalso();
  url = await servidor.iniciar();
});
afterEach(async () => {
  await servidor.parar();
  b.fechar();
});

describe('prazo esgotado e gravação', () => {
  it('dentro do prazo, grava; esgotado, nenhuma operação grava e nada entra na fila', () => {
    agora = new Date(CONTATO.getTime() + 2 * DIA_MS);
    ops.vender(venda());
    expect(contar(b.db, 'SELECT 1 FROM outbox')).toBe(1);

    agora = new Date(CONTATO.getTime() + 10 * DIA_MS);
    const tentativas: (() => unknown)[] = [
      () => ops.vender(venda(idDeTeste(5))),
      () => ops.estornar({ vendaId: idDeTeste(5), motivo: 'Teste' } as never),
      () => ops.cadastrarCliente({ nome: 'Bia' } as never),
      () => ops.conferir({ remessaId: m.remessaId, itens: [] } as never),
    ];
    for (const tentar of tentativas) {
      expect(tentar).toThrowError(textoDeGravacaoBloqueada('PRAZO'));
    }
    expect(contar(b.db, 'SELECT 1 FROM outbox')).toBe(1);
    expect(contar(b.db, 'SELECT 1 FROM vendas')).toBe(1);
  });

  it('no limite: 9 dias 23h59m59s ainda grava, 10 dias cheios não', () => {
    agora = new Date(CONTATO.getTime() + 10 * DIA_MS - 1000);
    ops.vender(venda());
    agora = new Date(CONTATO.getTime() + 10 * DIA_MS);
    expect(() => ops.vender(venda(idDeTeste(5)))).toThrowError(ErroDaOperacao);
  });

  it('relógio voltado para trás: não grava e a mensagem manda conferir a data e conectar', () => {
    agora = new Date(CONTATO.getTime() + 3 * DIA_MS);
    prazo.batida();
    ops.vender(venda());
    agora = new Date(CONTATO.getTime() + 3 * DIA_MS - 3_600_000);
    expect(() => ops.vender(venda(idDeTeste(5)))).toThrowError(
      /Confira a data e a hora do computador e conecte-se à internet/,
    );
  });

  it('relógio adiantado e depois corrigido cai em "atrasado": não grava até conectar', () => {
    agora = new Date(CONTATO.getTime() + 8 * DIA_MS); // adiantaram o relógio
    prazo.batida();
    agora = new Date(CONTATO.getTime() + 1 * DIA_MS); // e acertaram de novo
    expect(prazo.situacao()).toMatchObject({ situacao: 'ESGOTADO', motivo: 'RELOGIO' });
    expect(() => ops.vender(venda())).toThrowError(ErroDaOperacao);
    // Conectar libera: o servidor responde e o par é refeito.
    registrarContato(b.db, '2026-11-02T12:00:00.000Z', agora);
    ops.vender(venda());
    expect(contar(b.db, 'SELECT 1 FROM outbox')).toBe(1);
  });
});

describe('prazo esgotado e envio', () => {
  it('continua enviando o que já estava guardado quando a internet volta', async () => {
    agora = new Date(CONTATO.getTime() + 2 * DIA_MS);
    ops.vender(venda());
    agora = new Date(CONTATO.getTime() + 12 * DIA_MS); // prazo estourado, ainda sem internet
    expect(prazo.situacao().situacao).toBe('ESGOTADO');
    expect(() => ops.vender(venda(idDeTeste(5)))).toThrowError(ErroDaOperacao);

    const ciclo = new CicloDeSincronizacao({
      banco: () => b.db,
      transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
      comToken: (fn) => fn('token'),
      agora: () => agora,
      esperar: () => Promise.resolve(),
    });
    expect(await ciclo.executar()).toBe('ok');
    expect(b.db.prepare('SELECT situacao FROM outbox').all() as { situacao: string }[]).toEqual([
      { situacao: 'ENVIADO' },
    ]);

    // A primeira resposta válida do servidor refaz o contato e libera o prazo.
    expect(prazo.situacao().situacao).toBe('EM_DIA');
  });
});
