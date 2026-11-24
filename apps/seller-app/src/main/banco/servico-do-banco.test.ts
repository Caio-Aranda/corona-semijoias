import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { CofreDoSistema } from './chave-do-banco';
import { ServicoDoBanco } from './servico-do-banco';

/** O banco local no processo principal (tarefa 5.2): entrada com internet e sem internet, chave perdida e banco estragado. */

const RAPIDO = { N: 2 ** 12, r: 8, p: 1 };

function cofre(disponivel = true): CofreDoSistema {
  return {
    disponivel: () => disponivel,
    cifrar: (texto) => Buffer.from(`cofre:${texto}`, 'utf8'),
    decifrar: (dados) => {
      const texto = Buffer.from(dados).toString('utf8');
      if (!texto.startsWith('cofre:')) throw new Error('não é deste cofre');
      return texto.slice('cofre:'.length);
    },
  };
}

let pasta: string;
let servicos: ServicoDoBanco[] = [];

beforeEach(() => {
  pasta = join(mkdtempSync(join(tmpdir(), 'corona-servico-')), 'dados');
  servicos = [];
});
afterEach(() => {
  for (const s of servicos) s.fechar();
  rmSync(join(pasta, '..'), { recursive: true, force: true });
});

function servico(c: CofreDoSistema = cofre()): ServicoDoBanco {
  const s = new ServicoDoBanco({
    pasta,
    cofre: c,
    parametros: RAPIDO,
    agora: () => new Date('2026-11-11T12:00:00Z'),
  });
  servicos.push(s);
  return s;
}

function gravarMarca(s: ServicoDoBanco, valor: string): void {
  s.banco!.prepare("INSERT INTO estado_sync VALUES ('marca', ?)").run(valor);
}

const marca = (s: ServicoDoBanco): unknown =>
  (
    s.banco!.prepare("SELECT valor FROM estado_sync WHERE chave = 'marca'").get() as
      { valor?: string } | undefined
  )?.valor;

describe('banco local no processo principal', () => {
  it('a primeira entrada com internet cria a chave e o banco; a seguinte abre o mesmo', async () => {
    const s = servico();
    expect(await s.prepararComInternet('Senha-1')).toMatchObject({ ok: true, reiniciado: false });
    gravarMarca(s, 'primeira');
    s.fechar();

    const outra = servico();
    expect(await outra.prepararComInternet('Senha-1')).toMatchObject({
      ok: true,
      reiniciado: false,
    });
    expect(marca(outra)).toBe('primeira');
  });

  it('sem internet abre só pela senha, conta os erros e bloqueia no quinto', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    gravarMarca(s, 'guardada');
    s.fechar();

    const off = servico();
    const errada = await off.abrirSemInternet('outra');
    expect(errada).toMatchObject({ ok: false, codigo: 'SENHA_ERRADA', restantes: 4 });
    expect(await off.abrirSemInternet('Senha-1')).toMatchObject({ ok: true });
    expect(marca(off)).toBe('guardada');
    off.fechar();

    for (let i = 0; i < 4; i++) await off.abrirSemInternet('outra');
    const bloqueada = await off.abrirSemInternet('outra');
    expect(bloqueada).toMatchObject({ ok: false, codigo: 'SENHA_BLOQUEADA' });
    expect(await off.abrirSemInternet('Senha-1')).toMatchObject({
      ok: false,
      codigo: 'SENHA_BLOQUEADA',
    });
    // Com internet volta, e os dados continuam lá.
    expect(await off.prepararComInternet('Senha-1')).toMatchObject({ ok: true, reiniciado: false });
    expect(marca(off)).toBe('guardada');
  });

  it('sem internet e sem dados neste computador, pede internet', async () => {
    const r = await servico().abrirSemInternet('x');
    expect(r).toMatchObject({ ok: false, codigo: 'SEM_DADOS' });
    expect(r.ok ? '' : r.mensagem).toContain('é preciso internet');
  });

  it('senha redefinida pela dona: entra com a nova e os dados continuam', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-antiga-1');
    gravarMarca(s, 'venda guardada');
    s.fechar();

    const depois = servico();
    expect(await depois.prepararComInternet('Senha-nova-2')).toMatchObject({
      ok: true,
      reiniciado: false,
    });
    expect(marca(depois)).toBe('venda guardada');
  });

  it('chave perdida (outro usuário do Windows): o banco antigo fica guardado à parte e nasce outro vazio', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    gravarMarca(s, 'antiga');
    s.fechar();

    const outroWindows: CofreDoSistema = {
      ...cofre(),
      decifrar: () => {
        throw new Error('outro usuário');
      },
    };
    const novo = servico(outroWindows);
    expect(await novo.prepararComInternet('Senha-2')).toMatchObject({ ok: true, reiniciado: true });
    expect(marca(novo)).toBeUndefined();
    const guardados = readdirSync(pasta).filter((n) => n.includes('.ilegivel-'));
    expect(guardados).toEqual(
      expect.arrayContaining([
        'banco.sqlite.ilegivel-20261111T120000',
        'banco.sqlite.ilegivel-20261111T120000.chave.json',
      ]),
    );
  });

  it('banco estragado com a chave boa: guarda à parte e começa vazio', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    s.fechar();
    rmSync(join(pasta, 'banco.sqlite-wal'), { force: true });
    writeFileSync(join(pasta, 'banco.sqlite'), Buffer.alloc(8192, 7));

    const depois = servico();
    expect(await depois.prepararComInternet('Senha-1')).toMatchObject({
      ok: true,
      reiniciado: true,
    });
    expect(existsSync(join(pasta, 'banco.sqlite.ilegivel-20261111T120000'))).toBe(true);
    // Sem internet o banco estragado não é movido: pede para conectar.
    depois.fechar();
    writeFileSync(join(pasta, 'banco.sqlite'), Buffer.alloc(8192, 7));
    rmSync(join(pasta, 'banco.sqlite-wal'), { force: true });
    expect(await servico().abrirSemInternet('Senha-1')).toMatchObject({
      ok: false,
      codigo: 'BANCO_ILEGIVEL',
    });
  });
});

describe('este computador é de outra pessoa (liberar o computador)', () => {
  it('guarda o banco e a chave antigos (nunca apaga), tira o usuario.txt e deixa o computador livre', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    s.lembrar('vendedor_login', 'ana');
    gravarMarca(s, 'vendas-da-ana');
    s.fechar();
    expect(s.usuarioDoComputador()).toBe('ana');
    expect(s.temDadosLocais).toBe(true);

    expect(s.liberarParaOutraPessoa()).toBe(true);

    expect(s.usuarioDoComputador()).toBeNull();
    expect(s.temDadosLocais).toBe(false);
    expect(existsSync(join(pasta, 'usuario.txt'))).toBe(false);
    expect(existsSync(join(pasta, 'banco.sqlite.ilegivel-20261111T120000'))).toBe(true);
    expect(existsSync(join(pasta, 'banco.sqlite.ilegivel-20261111T120000.chave.json'))).toBe(true);
    // O banco guardado ainda tem as vendas (nada foi apagado).
    const novo = servico();
    expect(await novo.prepararComInternet('Outra-Senha-2')).toMatchObject({ ok: true });
    expect(marca(novo)).toBeUndefined();
  });

  it('com o banco aberto não faz nada', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    expect(s.liberarParaOutraPessoa()).toBe(false);
    expect(s.temDadosLocais).toBe(true);
  });
});
