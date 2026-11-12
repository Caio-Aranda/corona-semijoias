import { randomBytes } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { abrirBancoLocal, type BancoAberto } from '../banco/banco-local';
import { enfileirar } from '../sync/outbox';
import { RelogioFalso } from '../sync/testes/relogio-falso';
import type { Resultado } from '../tipos';
import type { RespostaDoServidor } from '../sync/transporte-do-sync';
import {
  ApagadorDeDados,
  ESPERAS_DA_QUARENTENA_MS,
  type LugaresParaApagar,
  type MarcaDoApagar,
} from './apagar-dados';

/**
 * Quarentena e apagar os dados (tarefa 5.8; ADR-0013, item 14): SQLCipher de verdade, arquivos de verdade numa pasta
 * temporária e o servidor falso. O que se prova: com e sem vendas aguardando, quarentena sem resposta (3 tentativas
 * espaçadas, retomadas depois de fechar o app), a ordem (chave primeiro) e que não sobra arquivo nenhum.
 */
let raiz: string;
let lugares: LugaresParaApagar;
let db: BancoAberto | null;
let relogio: RelogioFalso;
let enviados: string[];
let respostas: ('definitiva' | 'sem-resposta' | 'erro-do-servidor' | 'negada' | 'grande')[];
let removidos: string[];
let fechado: number;

const TOKEN = 'token-de-renovacao-revogado';
const COMPUTADOR = '0190a1b2-c3d4-4e5f-8a9b-0c1d2e3f4a5b';

function arquivosEm(pasta: string): string[] {
  if (!existsSync(pasta)) return [];
  const achados: string[] = [];
  for (const nome of readdirSync(pasta)) {
    const caminho = join(pasta, nome);
    if (statSync(caminho).isDirectory()) achados.push(...arquivosEm(caminho));
    else achados.push(caminho);
  }
  return achados;
}

function resposta(status: number): Resultado<RespostaDoServidor> {
  return { ok: true, dados: { status, corpo: { resultados: [] } } };
}

function montar(opcoes: { esperas?: readonly number[] } = {}): ApagadorDeDados {
  return new ApagadorDeDados({
    lugares,
    banco: () => db,
    abrirBancoParaApagar: () => Promise.resolve(db),
    fecharBanco: () => {
      fechado += 1;
      db?.close();
      db = null;
    },
    enviarParaQuarentena: (corpo) => {
      enviados.push(corpo);
      const r = respostas.shift() ?? 'definitiva';
      if (r === 'sem-resposta') return Promise.resolve({ ok: false, falha: { tipo: 'sem-conexao' } });
      if (r === 'erro-do-servidor') return Promise.resolve(resposta(503));
      if (r === 'negada') return Promise.resolve(resposta(401));
      if (r === 'grande') return Promise.resolve(resposta(413));
      return Promise.resolve(resposta(200));
    },
    credenciais: () => Promise.resolve({ token: TOKEN, dispositivoId: COMPUTADOR }),
    relogio,
    remover: async (caminho) => {
      removidos.push(caminho);
      await rm(caminho, { recursive: true, force: true });
    },
    ...opcoes,
  });
}

function venda(id: string): void {
  enfileirar(db!, {
    operacaoId: id,
    tipo: 'CLIENTE_NOVO',
    dados: JSON.stringify({ id, nome: 'Ana' }),
    agora: new Date('2026-11-12T12:00:00.000Z'),
  });
}

/** Deixa as promessas e a entrada e saída reais do disco andarem. */
async function ate(condicao: () => boolean): Promise<void> {
  await vi.waitFor(() => {
    if (!condicao()) throw new Error('ainda não');
  }, { timeout: 5000, interval: 5 });
}

beforeEach(() => {
  raiz = mkdtempSync(join(tmpdir(), 'corona-apagar-'));
  const dados = join(raiz, 'dados');
  mkdirSync(dados, { recursive: true });
  mkdirSync(join(raiz, 'fotos'), { recursive: true });
  mkdirSync(join(raiz, 'sessao'), { recursive: true });
  mkdirSync(join(raiz, 'aparelho'), { recursive: true });
  lugares = {
    chave: join(dados, 'chave.json'),
    banco: join(dados, 'banco.sqlite'),
    pastaDeDados: dados,
    pastasDeFotos: [join(raiz, 'fotos')],
    token: join(raiz, 'sessao', 'renovacao.bin'),
    identificador: join(raiz, 'aparelho', 'identificador.txt'),
    ultimoContato: join(raiz, 'aparelho', 'ultimo-contato.json'),
    marca: join(raiz, 'aparelho', 'apagando.json'),
    pastasDeApoio: [join(raiz, 'sessao'), join(raiz, 'aparelho')],
  };
  writeFileSync(lugares.chave, '{"formato":1}');
  db = abrirBancoLocal({ caminho: lugares.banco, chave: randomBytes(32) });
  writeFileSync(join(dados, 'banco.sqlite.antes-da-migracao-1'), 'copia do banco');
  writeFileSync(join(dados, 'banco.sqlite.ilegivel-2026'), 'banco antigo');
  mkdirSync(join(raiz, 'fotos', 'miniaturas'), { recursive: true });
  writeFileSync(join(raiz, 'fotos', 'miniaturas', 'a.jpg'), 'foto');
  writeFileSync(lugares.token, 'token cifrado');
  writeFileSync(lugares.identificador, COMPUTADOR);
  writeFileSync(lugares.ultimoContato, '{}');
  relogio = new RelogioFalso();
  enviados = [];
  respostas = [];
  removidos = [];
  fechado = 0;
});

afterEach(() => {
  try {
    db?.close();
  } catch {
    /* já fechado */
  }
  rmSync(raiz, { recursive: true, force: true });
});

describe('aparelho bloqueado SEM nada aguardando', () => {
  it('apaga direto, sem falar com o servidor, e não deixa arquivo nenhum', async () => {
    const apagador = montar();
    await apagador.receberOrdem();

    expect(enviados).toEqual([]);
    expect(apagador.situacao).toBe('APAGADO');
    expect(arquivosEm(raiz)).toEqual([]);
    expect(readdirSync(raiz)).toEqual([]); // nem as pastas ficam
  });
});

describe('aparelho bloqueado COM vendas aguardando', () => {
  beforeEach(() => {
    venda('0190a1b2-c3d4-7e5f-8a9b-000000000001');
    venda('0190a1b2-c3d4-7e5f-8a9b-000000000002');
  });

  it('espera a primeira tentativa, manda tudo à quarentena e só depois apaga', async () => {
    const apagador = montar();
    const feito = apagador.receberOrdem();
    await ate(() => relogio.pendentes().length > 0);
    // Nada foi enviado nem apagado antes da espera da primeira tentativa.
    expect(enviados).toEqual([]);
    expect(existsSync(lugares.chave)).toBe(true);

    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[0]!);
    await feito;

    expect(enviados).toHaveLength(1);
    const corpo = JSON.parse(enviados[0]!) as {
      tokenDeRenovacao: string;
      dispositivoId: string;
      operacoes: { operacaoId: string; tipo: string }[];
    };
    expect(corpo.tokenDeRenovacao).toBe(TOKEN);
    expect(corpo.dispositivoId).toBe(COMPUTADOR);
    expect(corpo.operacoes.map((o) => o.operacaoId)).toEqual([
      '0190a1b2-c3d4-7e5f-8a9b-000000000001',
      '0190a1b2-c3d4-7e5f-8a9b-000000000002',
    ]);
    expect(arquivosEm(raiz)).toEqual([]);
  });

  it('uma queda de rede passageira não leva a apagar: a tentativa seguinte ainda recebe as vendas', async () => {
    respostas = ['sem-resposta', 'definitiva'];
    const apagador = montar();
    const feito = apagador.receberOrdem();
    await ate(() => relogio.pendentes().length > 0);
    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[0]!); // 1ª tentativa: sem resposta
    await ate(() => enviados.length === 1 && relogio.pendentes().length > 0);

    // Entre uma tentativa e outra, nada foi apagado: o banco e a chave continuam lá.
    expect(existsSync(lugares.chave)).toBe(true);
    expect(existsSync(lugares.banco)).toBe(true);
    expect(removidos).toEqual([]);
    expect(relogio.pendentes()[0]).toBe(ESPERAS_DA_QUARENTENA_MS[1]);

    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[1]!); // 2ª tentativa: responde
    await feito;
    expect(enviados).toHaveLength(2);
    expect(arquivosEm(raiz)).toEqual([]);
  });

  it('sem resposta nas 3 tentativas espaçadas (30 s, 2 min, 10 min), apaga como manda o ADR', async () => {
    respostas = ['sem-resposta', 'erro-do-servidor', 'sem-resposta'];
    const apagador = montar();
    const feito = apagador.receberOrdem();
    for (const espera of ESPERAS_DA_QUARENTENA_MS) {
      await ate(() => relogio.pendentes().length > 0);
      expect(relogio.pendentes()[0]).toBe(espera);
      await relogio.andar(espera);
    }
    await feito;
    expect(enviados).toHaveLength(3);
    expect(apagador.situacao).toBe('APAGADO');
    expect(arquivosEm(raiz)).toEqual([]);
  });

  it('resposta definitiva do servidor, mesmo recusando, libera o apagar', async () => {
    respostas = ['negada'];
    const apagador = montar();
    const feito = apagador.receberOrdem();
    await ate(() => relogio.pendentes().length > 0);
    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[0]!);
    await feito;
    expect(enviados).toHaveLength(1);
    expect(arquivosEm(raiz)).toEqual([]);
  });

  it('lote grande demais (413) é dividido ao meio até caber', async () => {
    respostas = ['grande', 'definitiva', 'definitiva'];
    const apagador = montar();
    const feito = apagador.receberOrdem();
    await ate(() => relogio.pendentes().length > 0);
    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[0]!);
    await feito;
    expect(enviados).toHaveLength(3);
    const tamanhos = enviados.map((e) => (JSON.parse(e) as { operacoes: unknown[] }).operacoes.length);
    expect(tamanhos).toEqual([2, 1, 1]);
  });

  it('se o aplicativo fechar no meio, ao abrir retoma a contagem sem zerar', async () => {
    respostas = ['sem-resposta'];
    const primeiro = montar();
    void primeiro.receberOrdem();
    await ate(() => relogio.pendentes().length > 0);
    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[0]!); // 1ª tentativa sem resposta
    await ate(() => {
      try {
        return (JSON.parse(readFileSync(lugares.marca, 'utf8')) as MarcaDoApagar).tentativas === 1;
      } catch {
        return false;
      }
    });
    // "Fecha" o aplicativo: nada mais roda. A marca guardou a 1ª tentativa e a hora da seguinte.
    const marca = JSON.parse(readFileSync(lugares.marca, 'utf8')) as MarcaDoApagar;
    expect(marca).toMatchObject({ fase: 'QUARENTENA', tentativas: 1 });
    expect(marca.proximaTentativaEm).not.toBeNull();

    // Abre de novo: um apagador novo, o mesmo disco, o mesmo banco ainda lá.
    const relogio2 = new RelogioFalso();
    await relogio2.andar(relogio.agora());
    const segundo = new ApagadorDeDados({
      lugares,
      banco: () => db,
      abrirBancoParaApagar: () => Promise.resolve(db),
      fecharBanco: () => {
        db?.close();
        db = null;
      },
      enviarParaQuarentena: (corpo) => {
        enviados.push(corpo);
        return Promise.resolve(resposta(200));
      },
      credenciais: () => Promise.resolve({ token: TOKEN, dispositivoId: COMPUTADOR }),
      relogio: relogio2,
    });
    expect(await segundo.emAndamento()).toBe(true);
    const feito = segundo.retomar();
    await ate(() => relogio2.pendentes().length > 0);
    // Continua da 2ª tentativa: espera a de 2 minutos, não recomeça pela de 30 segundos.
    expect(relogio2.pendentes()[0]).toBe(ESPERAS_DA_QUARENTENA_MS[1]);
    await relogio2.andar(ESPERAS_DA_QUARENTENA_MS[1]!);
    await feito;
    expect(enviados).toHaveLength(2);
    expect(arquivosEm(raiz)).toEqual([]);
  });

  it('relógio mexido: a espera nunca passa da prevista para aquela tentativa', async () => {
    mkdirSync(join(raiz, 'aparelho'), { recursive: true });
    const longe: MarcaDoApagar = {
      versao: 1,
      fase: 'QUARENTENA',
      tentativas: 1,
      proximaTentativaEm: new Date(relogio.agora() + 40 * 86_400_000).toISOString(),
      iniciadoEm: new Date(relogio.agora()).toISOString(),
    };
    writeFileSync(lugares.marca, JSON.stringify(longe));
    const apagador = montar();
    const feito = apagador.retomar();
    await ate(() => relogio.pendentes().length > 0);
    expect(relogio.pendentes()[0]).toBe(ESPERAS_DA_QUARENTENA_MS[1]);
    await relogio.andar(ESPERAS_DA_QUARENTENA_MS[1]!);
    await feito;
    expect(arquivosEm(raiz)).toEqual([]);
  });
});

describe('apagar', () => {
  it('a chave sai primeiro; depois o banco, o WAL, o resto, as fotos, o token e o identificador; a marca por último', async () => {
    // Uma gravação deixa o WAL de verdade ao lado do banco.
    db!.exec('CREATE TABLE IF NOT EXISTS t (x)');
    db!.prepare('INSERT INTO t VALUES (1)').run();
    const apagador = montar();
    await apagador.receberOrdem();

    const nome = (c: string) => c.replace(raiz, '').replace(/\\/g, '/');
    const ordem = removidos.map(nome);
    expect(ordem[0]).toBe('/dados/chave.json');
    expect(ordem.indexOf('/dados/banco.sqlite')).toBeGreaterThan(0);
    expect(ordem.slice(1, 5)).toEqual([
      '/dados/banco.sqlite',
      '/dados/banco.sqlite-wal',
      '/dados/banco.sqlite-shm',
      '/dados/banco.sqlite-journal',
    ]);
    const posicao = (c: string) => ordem.indexOf(c);
    expect(posicao('/dados')).toBeGreaterThan(posicao('/dados/banco.sqlite-shm'));
    expect(posicao('/fotos')).toBeGreaterThan(posicao('/dados'));
    expect(posicao('/sessao/renovacao.bin')).toBeGreaterThan(posicao('/fotos'));
    expect(posicao('/aparelho/identificador.txt')).toBeGreaterThan(posicao('/sessao/renovacao.bin'));
    expect(posicao('/aparelho/apagando.json')).toBeGreaterThan(posicao('/aparelho/ultimo-contato.json'));
    expect(fechado).toBe(1); // o banco foi fechado antes de apagar o arquivo
    expect(readdirSync(raiz)).toEqual([]);
  });

  it('se cair no meio do apagar, ao abrir termina o que faltava', async () => {
    // A queda: a chave já saiu, o banco ainda está lá, e a marca diz "apagando".
    rmSync(lugares.chave);
    mkdirSync(join(raiz, 'aparelho'), { recursive: true });
    const marca: MarcaDoApagar = {
      versao: 1,
      fase: 'APAGANDO',
      tentativas: 1,
      proximaTentativaEm: null,
      iniciadoEm: new Date(0).toISOString(),
    };
    writeFileSync(lugares.marca, JSON.stringify(marca));
    const apagador = montar();
    expect(await apagador.emAndamento()).toBe(true);
    await apagador.retomar();
    expect(enviados).toEqual([]); // já tinha passado da quarentena
    expect(readdirSync(raiz)).toEqual([]);
  });

  it('marca estragada: apagar é o lado seguro', async () => {
    writeFileSync(lugares.marca, '{{{ não é json');
    const apagador = montar();
    await apagador.retomar();
    expect(readdirSync(raiz)).toEqual([]);
  });

  it('sem marca e sem ordem, nada é apagado', async () => {
    const apagador = montar();
    await apagador.retomar();
    expect(existsSync(lugares.banco)).toBe(true);
    expect(removidos).toEqual([]);
  });

  it('a mesma ordem duas vezes não apaga duas vezes', async () => {
    const apagador = montar();
    await Promise.all([apagador.receberOrdem(), apagador.receberOrdem()]);
    await apagador.receberOrdem();
    expect(removidos.filter((c) => c.endsWith('chave.json'))).toHaveLength(1);
  });
});
