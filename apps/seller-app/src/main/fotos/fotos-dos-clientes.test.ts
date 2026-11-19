import { randomBytes } from 'node:crypto';
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { TipoDeFotoDoCliente } from '../../shared/operacoes-do-consignado';
import { colocarFotoNaFila } from '../sync/operacoes-do-consignado';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from '../sync/testes/banco-de-teste';
import type { Resultado } from '../tipos';
import {
  chaveDasFotosDe,
  FotosDosClientes,
  type RespostaDeFotoDoCliente,
  sha256Hex,
  type TransporteDeFotosDoCliente,
} from './fotos-dos-clientes';

/**
 * As fotos do cliente (tarefa 9.2; ADR-0016, item 9): arquivo criptografado no disco, hash conferido, fila de envio que
 * recomeça sozinha e que nunca trava nada.
 */
let pasta: string;
let b: BancoDeTeste;
let respostas: number[];
let enviados: { clienteId: string; tipo: string; bytes: Buffer }[];
let semRede: boolean;

const chave = chaveDasFotosDe(randomBytes(32));
const jpeg = (n = 200): Buffer =>
  Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), randomBytes(n)]);
const comoEndereco = (bytes: Buffer): string =>
  `data:image/jpeg;base64,${bytes.toString('base64')}`;

const transporte: TransporteDeFotosDoCliente = {
  enviar(
    _token,
    clienteId,
    tipo: TipoDeFotoDoCliente,
    bytes,
  ): Promise<Resultado<RespostaDeFotoDoCliente>> {
    if (semRede) return Promise.resolve({ ok: false, falha: { tipo: 'sem-conexao' } });
    enviados.push({ clienteId, tipo, bytes });
    return Promise.resolve({ ok: true, dados: { status: respostas.shift() ?? 201 } });
  },
  baixar(): Promise<Resultado<RespostaDeFotoDoCliente>> {
    return Promise.resolve({ ok: true, dados: { status: 404 } });
  },
};

function fotos(): FotosDosClientes {
  return new FotosDosClientes({
    pasta,
    chave: () => chave,
    banco: () => b.db,
    transporte,
    comToken: (f) => f('token-de-teste'),
  });
}

beforeEach(() => {
  pasta = mkdtempSync(join(tmpdir(), 'corona-fotos-clientes-'));
  b = criarBancoDeTeste();
  respostas = [];
  enviados = [];
  semRede = false;
});
afterEach(() => {
  b.fechar();
  rmSync(pasta, { recursive: true, force: true });
});

describe('fotos do cliente criptografadas', () => {
  it('guarda o JPEG criptografado (o arquivo no disco não tem o JPEG), devolve o SHA-256 e lê de volta igual', () => {
    const f = fotos();
    const bytes = jpeg();
    const hash = f.guardar(comoEndereco(bytes));
    expect(hash).toBe(sha256Hex(bytes));
    const arquivos = readdirSync(pasta);
    expect(arquivos).toEqual([`${hash as string}.bin`]);
    const noDisco = readFileSync(join(pasta, arquivos[0] as string));
    expect(noDisco.includes(bytes.subarray(4, 40))).toBe(false);
    expect(f.ver(hash as string)).toBe(comoEndereco(bytes));
    // Repetir é inofensivo.
    expect(f.guardar(comoEndereco(bytes))).toBe(hash);
  });

  it('recusa o que não é JPEG, o que é grande demais e a leitura com a chave errada', () => {
    const f = fotos();
    expect(f.guardar('data:image/jpeg;base64,AAAA')).toBeNull();
    expect(f.guardar(`data:image/png;base64,${jpeg().toString('base64')}`)).toBeNull();
    expect(f.guardar('texto qualquer')).toBeNull();
    expect(
      f.guardar(
        comoEndereco(
          Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), randomBytes(11 * 1024 * 1024)]),
        ),
      ),
    ).toBeNull();
    const hash = f.guardar(comoEndereco(jpeg())) as string;
    const outra = new FotosDosClientes({
      pasta,
      chave: () => chaveDasFotosDe(randomBytes(32)),
      banco: () => b.db,
      transporte,
      comToken: (g) => g('t'),
    });
    expect(outra.ver(hash)).toBeNull();
    expect(
      new FotosDosClientes({
        pasta,
        chave: () => null,
        banco: () => b.db,
        transporte,
        comToken: (g) => g('t'),
      }).ver(hash),
    ).toBeNull();
  });

  it('arquivo estragado não é entregue', () => {
    const f = fotos();
    const hash = f.guardar(comoEndereco(jpeg())) as string;
    const caminho = join(pasta, `${hash}.bin`);
    const dados = readFileSync(caminho);
    dados[dados.length - 1] = (dados[dados.length - 1] ?? 0) ^ 0xff;
    rmSync(caminho);
    // Escreve de volta o arquivo adulterado.
    writeFileSync(caminho, dados);
    expect(f.ver(hash)).toBeNull();
  });
});

describe('fila de envio das fotos', () => {
  it('sobe as fotos que faltam, marca como enviadas e não sobe de novo', async () => {
    const f = fotos();
    const cliente = idDeTeste(3);
    const hash = f.guardar(comoEndereco(jpeg())) as string;
    colocarFotoNaFila(b.db, cliente, 'cliente', hash);
    await f.rodar();
    expect(enviados).toHaveLength(1);
    expect(enviados[0]?.tipo).toBe('cliente');
    expect(sha256Hex(enviados[0]?.bytes as Buffer)).toBe(hash);
    await f.rodar();
    expect(enviados).toHaveLength(1);
    expect(b.db.prepare('SELECT enviada_em FROM fila_fotos_cliente').get()).not.toEqual({
      enviada_em: null,
    });
  });

  it('sem internet fica na fila; 409 (cadastro ainda não chegou) tenta de novo no ciclo seguinte; recomeça depois de fechar o programa', async () => {
    const cliente = idDeTeste(3);
    const hash = fotos().guardar(comoEndereco(jpeg())) as string;
    colocarFotoNaFila(b.db, cliente, 'documento-frente', hash);
    semRede = true;
    await fotos().rodar();
    expect(enviados).toHaveLength(0);
    semRede = false;
    respostas = [409];
    await fotos().rodar();
    expect(enviados).toHaveLength(1);
    expect(b.db.prepare('SELECT tentativas, enviada_em FROM fila_fotos_cliente').get()).toEqual({
      tentativas: 1,
      enviada_em: null,
    });
    // "Fechou o programa": outro objeto, o mesmo banco; a foto ainda está na fila e agora sobe.
    await fotos().rodar();
    expect(enviados).toHaveLength(2);
    expect(
      (
        b.db.prepare('SELECT enviada_em FROM fila_fotos_cliente').get() as {
          enviada_em: string | null;
        }
      ).enviada_em,
    ).not.toBeNull();
  });

  it('a fila guarda uma foto por cliente e tipo, e todas sobem', async () => {
    const f = fotos();
    const cliente = idDeTeste(3);
    for (let i = 0; i < 5; i += 1) {
      const hash = f.guardar(comoEndereco(jpeg(300 + i))) as string;
      colocarFotoNaFila(b.db, cliente, i % 2 === 0 ? 'cliente' : 'documento-verso', hash);
    }
    // A chave é (cliente, tipo): sobram duas linhas, e as duas sobem.
    await f.rodar();
    expect(enviados).toHaveLength(2);
  });
});
