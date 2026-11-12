import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BancoAberto } from '../banco/banco-local';
import { MotorDeSincronizacao } from './motor';
import { OperacoesLocais } from './operacoes-locais';
import {
  type AppQueFecha,
  ESPERA_MAXIMA_DO_ENVIO_MS,
  PreparoDaAtualizacao,
  prepararAntesDeInstalar,
} from './preparo-da-atualizacao';
import { criarTransporteDoSync } from './transporte-do-sync';
import { type BancoDeTeste, contar, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';
import { RelogioFalso } from './testes/relogio-falso';
import { ServidorFalso } from './testes/servidor-falso';

/**
 * Antes de instalar a atualização (tarefa 5.13; ADR-0013, item 17): o motor para, espera o envio em andamento (no
 * máximo 30 s), tenta mais um envio se houver o que enviar e fecha o banco. A outbox atravessa a atualização no
 * próprio arquivo: nada se perde nem duplica, nem quando a atualização chega no meio de um envio. SQLCipher de verdade.
 */

const PUSH = '/api/vendedor/sync/push';
const TOKEN = 'token-de-teste';

let b: BancoDeTeste;
let s: ServidorFalso;
let url: string;
let relogio: RelogioFalso;
let motor: MotorDeSincronizacao;
let ops: OperacoesLocais;
let aberto: BancoAberto | null;
let passos: string[];

function criarMotor(versaoDoApp = '1.0.0'): MotorDeSincronizacao {
  return new MotorDeSincronizacao({
    banco: () => aberto,
    transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp }),
    comToken: (fn) => fn(TOKEN),
    relogio,
  });
}

function preparo(extra: Partial<ConstructorParameters<typeof PreparoDaAtualizacao>[0]> = {}) {
  return new PreparoDaAtualizacao({
    motor,
    fecharBanco: () => {
      passos.push('fechou o banco');
      aberto?.close();
      aberto = null;
    },
    relogio,
    registrar: (m) => passos.push(m),
    ...extra,
  });
}

function cliente(nome: string): string {
  return ops.cadastrarCliente({ id: idDeTeste(), nome }).operacaoId;
}

function aguardando(db: BancoAberto): number {
  return contar(db, "SELECT 1 FROM outbox WHERE situacao = 'AGUARDANDO'");
}

beforeEach(async () => {
  b = criarBancoDeTeste();
  aberto = b.db;
  s = new ServidorFalso();
  s.tokenValido = TOKEN;
  url = await s.iniciar();
  relogio = new RelogioFalso();
  passos = [];
  motor = criarMotor();
  ops = new OperacoesLocais({
    banco: () => {
      if (!aberto) throw new Error('banco fechado');
      return aberto;
    },
  });
});
afterEach(async () => {
  motor.desligar();
  await s.parar();
  b.fechar();
});

describe('preparar a atualização', () => {
  it('sem nada aguardando: para o motor e fecha o banco, sem chamar o servidor', async () => {
    const r = await preparo().preparar();
    expect(motor.ligado).toBe(false);
    expect(r).toEqual({ esperou: 'nada', maisUm: 'nao-precisou', aguardandoAoFechar: 0 });
    expect(passos).toContain('fechou o banco');
    expect(aberto).toBeNull();
    expect(s.pedidosPara(PUSH)).toHaveLength(0);
  });

  it('com vendas aguardando e internet: tenta mais um envio antes de fechar e nada fica para trás', async () => {
    const a = cliente('Ana');
    const c = cliente('Bia');
    const r = await preparo().preparar();
    expect(r).toEqual({ esperou: 'nada', maisUm: 'ok', aguardandoAoFechar: 0 });
    expect(s.gravacoes.get(a)).toBe(1);
    expect(s.gravacoes.get(c)).toBe(1);
    // Só o envio: o recebimento fica para a versão nova (a atualização não espera um pull inteiro).
    expect(s.pedidosPara('/api/vendedor/sync/pull')).toHaveLength(0);
    const db = b.reabrir();
    expect(aguardando(db)).toBe(0);
  });

  it('sem internet: não espera nada, fecha o banco e as vendas continuam guardadas para depois', async () => {
    const a = cliente('Ana');
    s.saudavel = false;
    const r = await preparo().preparar();
    expect(r).toEqual({ esperou: 'nada', maisUm: 'sem-internet', aguardandoAoFechar: 1 });
    expect(aberto).toBeNull();
    const db = b.reabrir();
    expect(aguardando(db)).toBe(1);
    // A versão nova abre e envia: chega uma vez só.
    s.saudavel = true;
    aberto = db;
    motor = criarMotor('1.1.0');
    expect(await motor.enviarAntesDeFechar()).toBe('ok');
    expect(s.gravacoes.get(a)).toBe(1);
  });

  it('espera o envio em andamento terminar e só depois tenta mais um e fecha', async () => {
    cliente('Ana');
    s.atraso = 150;
    motor.ligar();
    await vi.waitFor(() => expect(motor.envioEmAndamento()).not.toBeNull());
    const enquantoEnviava = cliente('Bia');
    const r = await preparo().preparar();
    expect(r.esperou).toBe('terminou');
    expect(r.aguardandoAoFechar).toBe(0);
    expect(s.gravacoes.get(enquantoEnviava)).toBe(1);
    expect(passos.at(-1)).toBe('fechou o banco');
  });

  it('atualização no meio de um envio que passa de 30 s: fecha mesmo assim e nada se perde nem duplica', async () => {
    const a = cliente('Ana');
    s.atraso = 400;
    motor.ligar();
    await vi.waitFor(() => expect(s.pedidosPara(PUSH).length).toBeGreaterThan(0));
    const pronto = preparo().preparar();
    // O envio não respondeu em 30 s: o preparo desiste de esperar e não tenta mais um.
    await relogio.andar(ESPERA_MAXIMA_DO_ENVIO_MS);
    const r = await pronto;
    expect(r.esperou).toBe('tempo-esgotado');
    expect(r.maisUm).toBe('nao-tentou');
    expect(aberto).toBeNull();
    // O servidor gravou e respondeu depois que o banco fechou: a resposta se perde, sem quebrar nada.
    await vi.waitFor(() => expect(s.gravacoes.get(a)).toBe(1));
    await new Promise((r) => setTimeout(r, 600));

    // A versão nova abre o mesmo arquivo: a operação ainda aguarda e o reenvio é JA_RECEBIDO.
    s.atraso = 0;
    aberto = b.reabrir();
    expect(aguardando(aberto)).toBe(1);
    motor = criarMotor('1.1.0');
    expect(await motor.enviarAntesDeFechar()).toBe('ok');
    expect(aguardando(aberto)).toBe(0);
    expect(s.gravacoes.get(a)).toBe(1);
  });

  it('o processo morre no meio do envio: ao abrir, o que aguardava continua lá e vai uma vez só', async () => {
    const a = cliente('Ana');
    s.roteirosDoPush.push('cair-depois-de-gravar');
    expect(await motor.enviarAntesDeFechar()).toBe('falha');
    // Sem fechar limpo (simula o processo morto): só reabre o arquivo.
    aberto = b.reabrir();
    expect(aguardando(aberto)).toBe(1);
    expect(await motor.enviarAntesDeFechar()).toBe('ok');
    expect(s.gravacoes.get(a)).toBe(1);
    expect(aguardando(aberto)).toBe(0);
  });

  it('pedir duas vezes (dois "fechar" seguidos) faz o preparo uma vez só', async () => {
    cliente('Ana');
    const p = preparo();
    const [r1, r2] = await Promise.all([p.preparar(), p.preparar()]);
    expect(r1).toBe(r2);
    expect(passos.filter((x) => x === 'fechou o banco')).toHaveLength(1);
    expect(s.pedidosPara(PUSH)).toHaveLength(1);
  });

  it('um erro no meio nunca impede de fechar o banco', async () => {
    cliente('Ana');
    const r = await preparo({
      motor: {
        desligar: () => motor.desligar(),
        envioEmAndamento: () => null,
        aguardandoEnvio: () => {
          throw new Error('defeito');
        },
        enviarAntesDeFechar: () => motor.enviarAntesDeFechar(),
      },
    }).preparar();
    expect(aberto).toBeNull();
    expect(r.maisUm).toBe('nao-tentou');
  });

  it('depois de parar, gravar não religa o motor nem começa envio novo', async () => {
    motor.ligar();
    await preparo({ fecharBanco: () => passos.push('fechou o banco') }).preparar();
    const antes = s.pedidos.length;
    cliente('Ana');
    motor.gravou();
    await relogio.andar(120_000);
    expect(motor.ligado).toBe(false);
    expect(s.pedidos.length).toBe(antes);
  });
});

describe('app abaixo da versão mínima', () => {
  it('envia as vendas guardadas, não pede dados (que dariam 426) e avisa que está desatualizado', async () => {
    s.versaoMinima = '2.0.0';
    const a = cliente('Ana');
    const ordens: string[] = [];
    motor = new MotorDeSincronizacao({
      banco: () => aberto,
      transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
      comToken: (fn) => fn(TOKEN),
      relogio,
      aoReceberOrdem: (o) => ordens.push(o),
    });
    motor.ligar();
    await vi.waitFor(() => expect(motor.estado().appDesatualizado).toBe(true));
    await vi.waitFor(() => expect(motor.estado().enviando).toBe(false));
    expect(s.gravacoes.get(a)).toBe(1);
    expect(s.pedidosPara('/api/vendedor/sync/pull')).toHaveLength(0);
    expect(ordens).toEqual([]);
    // O motor continua ligado: a venda feita depois também é enviada.
    expect(motor.ligado).toBe(true);
    const depois = cliente('Bia');
    motor.gravou();
    await relogio.andar(3_000);
    await vi.waitFor(() => expect(s.gravacoes.get(depois)).toBe(1));
    expect(s.pedidosPara('/api/vendedor/sync/pull')).toHaveLength(0);
  });

  it('sem nada a enviar, o 426 do recebimento não para o motor: a venda feita depois ainda vai', async () => {
    s.versaoMinima = '2.0.0';
    const ordens: string[] = [];
    motor = new MotorDeSincronizacao({
      banco: () => aberto,
      transporte: criarTransporteDoSync({ baseUrl: url, versaoDoApp: '1.0.0' }),
      comToken: (fn) => fn(TOKEN),
      relogio,
      aoReceberOrdem: (o) => ordens.push(o),
    });
    motor.ligar();
    await vi.waitFor(() => expect(motor.estado().appDesatualizado).toBe(true));
    await vi.waitFor(() => expect(motor.envioEmAndamento()).toBeNull());
    expect(ordens).toEqual([]);
    expect(motor.ligado).toBe(true);
    const pulls = s.pedidosPara('/api/vendedor/sync/pull').length;
    const a = cliente('Ana');
    motor.gravou();
    await relogio.andar(3_000);
    await vi.waitFor(() => expect(s.gravacoes.get(a)).toBe(1));
    await vi.waitFor(() => expect(motor.envioEmAndamento()).toBeNull());
    expect(s.pedidosPara('/api/vendedor/sync/pull')).toHaveLength(pulls);
  });

  it('em dia, o aviso some e o recebimento volta', async () => {
    s.versaoMinima = '1.0.0';
    motor.ligar();
    await vi.waitFor(() =>
      expect(s.pedidosPara('/api/vendedor/sync/pull').length).toBeGreaterThan(0),
    );
    expect(motor.estado().appDesatualizado).toBe(false);
  });
});

describe('fechar o programa com versão nova baixada', () => {
  function appFalso() {
    let ouvinte: ((e: { preventDefault(): void }) => void) | null = null;
    const fechamentos: boolean[] = [];
    const app: AppQueFecha & { fechar(): boolean } = {
      on: (_evento, f) => {
        ouvinte = f;
      },
      quit: () => {
        fechamentos.push(app.fechar());
      },
      /** Dispara o before-quit; devolve se o fechamento seguiu (não foi segurado). */
      fechar: () => {
        let segurou = false;
        ouvinte?.({ preventDefault: () => (segurou = true) });
        return !segurou;
      },
    };
    return { app, fechamentos };
  }

  it('sem versão nova, fecha na hora, sem preparo', () => {
    const { app } = appFalso();
    const preparar = vi.fn(() => Promise.resolve());
    prepararAntesDeInstalar(app, { preparar }, () => false);
    expect(app.fechar()).toBe(true);
    expect(preparar).not.toHaveBeenCalled();
  });

  it('com versão nova, segura o fechamento, prepara uma vez e só então fecha de verdade', async () => {
    const { app, fechamentos } = appFalso();
    let terminar: () => void = () => undefined;
    const preparar = vi.fn(() => new Promise<void>((r) => (terminar = r)));
    prepararAntesDeInstalar(app, { preparar }, () => true);
    expect(app.fechar()).toBe(false);
    // A pessoa clica em fechar de novo enquanto prepara: continua segurado, sem segundo preparo.
    expect(app.fechar()).toBe(false);
    expect(preparar).toHaveBeenCalledTimes(1);
    expect(fechamentos).toEqual([]);
    terminar();
    await vi.waitFor(() => expect(fechamentos).toEqual([true]));
  });

  it('se o preparo falhar, fecha mesmo assim (a outbox está no arquivo)', async () => {
    const { app, fechamentos } = appFalso();
    prepararAntesDeInstalar(app, { preparar: () => Promise.reject(new Error('x')) }, () => true);
    expect(app.fechar()).toBe(false);
    await vi.waitFor(() => expect(fechamentos).toEqual([true]));
  });
});
