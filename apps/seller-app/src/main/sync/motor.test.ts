import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { type EstadoDaSincronizacao, limparEstadoDaSincronizacao } from '../../shared/ponte';
import { MotorDeSincronizacao } from './motor';
import { OperacoesLocais } from './operacoes-locais';
import { criarTransporteDoSync } from './transporte-do-sync';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { RelogioFalso } from './testes/relogio-falso';
import { ServidorFalso } from './testes/servidor-falso';

/**
 * O estado que atravessa a ponte (garantia 3 da aprovação da 5.6): só contadores, sim/não e datas. Nenhum token,
 * caminho do banco, id ou dado de cliente.
 */
const CAMPOS = [
  'aguardando',
  'comInternet',
  'dadosAte',
  'enviando',
  'fotosBaixadas',
  'fotosTotal',
  'naoConseguiuEnviar',
  'prazo',
  'recusadas',
  'vendasAguardando',
];
const TOKEN = 'token-secreto-do-vendedor';
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

let b: BancoDeTeste;
let s: ServidorFalso;
let estados: EstadoDaSincronizacao[];
let motor: MotorDeSincronizacao;
let ops: OperacoesLocais;

beforeEach(async () => {
  b = criarBancoDeTeste();
  s = new ServidorFalso();
  s.tokenValido = TOKEN;
  const url = await s.iniciar();
  estados = [];
  motor = new MotorDeSincronizacao({
    banco: () => b.db,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
    comToken: (fn) => fn(TOKEN),
    relogio: new RelogioFalso(),
    aoMudarEstado: (e) => estados.push(e),
  });
  ops = new OperacoesLocais({ banco: () => b.db, aoGravar: () => motor.gravou() });
});
afterEach(async () => {
  motor.desligar();
  await s.parar();
  b.fechar();
});

async function cicloTerminado(): Promise<void> {
  await vi.waitFor(() => {
    const e = motor.estado();
    expect(e.comInternet).not.toBeNull();
    expect(e.enviando).toBe(false);
  });
}

describe('estado da ponte', () => {
  it('tem só os campos documentados, com os tipos certos', async () => {
    ops.cadastrarCliente({ id: idDeTeste(), nome: 'Maria da Silva', cpf: '98765432100' });
    expect(motor.estado()).toEqual({
      comInternet: null,
      enviando: false,
      aguardando: 1,
      vendasAguardando: 0,
      recusadas: 0,
      dadosAte: null,
      naoConseguiuEnviar: false,
      prazo: { situacao: 'EM_DIA', diasRestantes: 0, motivo: null },
      fotosBaixadas: 0,
      fotosTotal: 0,
    });
    motor.ligar();
    await cicloTerminado();
    const e = motor.estado();
    expect(Object.keys(e).sort()).toEqual(CAMPOS);
    expect(e).toMatchObject({ comInternet: true, aguardando: 0, dadosAte: s.horaServidor });
  });

  it('varredura: nenhum token, caminho do banco, id ou dado de cliente no que sai', async () => {
    const recusada = idDeTeste();
    s.resultadoPara = (op) => (op.operacaoId === recusada ? 'RECUSADO' : undefined);
    ops.cadastrarCliente({
      id: idDeTeste(),
      nome: 'Maria da Silva',
      cpf: '98765432100',
      telefone: '11912345678',
    });
    ops.cadastrarCliente({ id: recusada, nome: 'Joana Recusada' });
    motor.ligar();
    await cicloTerminado();
    const tudo = JSON.stringify([...estados, motor.estado()]);
    expect(estados.length).toBeGreaterThan(1);
    for (const proibido of [
      TOKEN,
      b.caminho,
      'Maria',
      'Joana',
      '98765432100',
      '11912345678',
      'sqlite',
    ]) {
      expect(tudo).not.toContain(proibido);
    }
    expect(tudo).not.toMatch(UUID);
    expect(motor.estado().recusadas).toBe(1);
    for (const e of estados) expect(Object.keys(e).sort()).toEqual(CAMPOS);
  });

  it('avisa "não conseguiu enviar" depois de 5 tentativas seguidas da mesma operação', async () => {
    s.resultadoPara = () => 'NAO_PROCESSADO';
    ops.cadastrarCliente({ id: idDeTeste(), nome: 'Bia' });
    b.db.prepare('UPDATE outbox SET tentativas = 4').run();
    motor.ligar();
    await cicloTerminado();
    expect(motor.estado()).toMatchObject({ aguardando: 1, naoConseguiuEnviar: true });
  });

  it('a limpeza descarta tudo que não é contador, sim/não ou data', () => {
    const sujo = {
      comInternet: true,
      enviando: 'sim',
      aguardando: -3,
      vendasAguardando: 2.5,
      recusadas: 1,
      dadosAte: 'C:\\Users\\vendedor\\dados\\banco.sqlite',
      naoConseguiuEnviar: true,
      token: TOKEN,
      clienteId: idDeTeste(),
    };
    expect(limparEstadoDaSincronizacao(sujo)).toEqual({
      comInternet: true,
      enviando: false,
      aguardando: 0,
      vendasAguardando: 0,
      recusadas: 1,
      dadosAte: null,
      naoConseguiuEnviar: true,
      prazo: { situacao: 'EM_DIA', diasRestantes: 0, motivo: null },
      fotosBaixadas: 0,
      fotosTotal: 0,
    });
  });

  it('sem banco aberto o motor não liga', () => {
    const semBanco = new MotorDeSincronizacao({
      banco: () => null,
      transporte: criarTransporteDoSync({ baseUrl: 'http://127.0.0.1:1', versaoDoApp: '1.0.0' }),
      comToken: (fn) => fn(TOKEN),
      relogio: new RelogioFalso(),
    });
    semBanco.ligar();
    expect(semBanco.ligado).toBe(false);
  });
});
