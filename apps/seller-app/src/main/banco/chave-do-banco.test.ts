import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  ChaveDoBanco,
  ChavePerdida,
  type CofreDoSistema,
  ERROS_ATE_BLOQUEAR,
  PARAMETROS_PADRAO,
} from './chave-do-banco';

/**
 * A chave do banco local embrulhada pela senha do vendedor, com o resgate pelo cofre do Windows (tarefa 5.2; ADR-0005 e
 * ADR-0013, linhas datadas de 11/11/2026). O scrypt de verdade, com parâmetros menores só pela velocidade.
 */

const RAPIDO = { N: 2 ** 12, r: 8, p: 1 };

/** Cofre de mentira: cifra trocando os bytes (só o teste o conhece). */
function cofre(disponivel = true): CofreDoSistema {
  return {
    disponivel: () => disponivel,
    cifrar: (texto) => Buffer.from(`cofre:${texto}`, 'utf8').map((b) => b ^ 0x5a),
    decifrar: (dados) => {
      const texto = Buffer.from(Buffer.from(dados).map((b) => b ^ 0x5a)).toString('utf8');
      if (!texto.startsWith('cofre:')) throw new Error('não é deste cofre');
      return texto.slice('cofre:'.length);
    },
  };
}

let pasta: string;
let arquivo: string;

beforeEach(() => {
  pasta = mkdtempSync(join(tmpdir(), 'corona-chave-'));
  arquivo = join(pasta, 'dados', 'chave.json');
});
afterEach(() => rmSync(pasta, { recursive: true, force: true }));

describe('chave do banco local', () => {
  it('a senha certa devolve a mesma chave, e o arquivo não tem a chave em claro', async () => {
    const guarda = new ChaveDoBanco(arquivo, cofre(), RAPIDO);
    const chave = await guarda.criar('Senha-do-Vendedor-1');
    expect(chave).toHaveLength(32);
    const r = await guarda.abrirComSenha('Senha-do-Vendedor-1');
    expect(r).toEqual({ ok: true, chave });

    const texto = readFileSync(arquivo, 'utf8');
    expect(texto).not.toContain(chave.toString('hex'));
    expect(texto).not.toContain(chave.toString('base64'));
    expect(texto).not.toContain('Senha-do-Vendedor-1');
  });

  it('cinco erros seguidos bloqueiam até entrar com internet, mesmo com a senha certa', async () => {
    const guarda = new ChaveDoBanco(arquivo, cofre(), RAPIDO);
    await guarda.criar('Senha-certa-1');
    for (let i = 1; i < ERROS_ATE_BLOQUEAR; i++) {
      expect(await guarda.abrirComSenha('errada')).toEqual({
        ok: false,
        motivo: 'senha-errada',
        restantes: ERROS_ATE_BLOQUEAR - i,
      });
    }
    expect(await guarda.abrirComSenha('errada')).toEqual({ ok: false, motivo: 'bloqueado' });
    expect(await guarda.abrirComSenha('Senha-certa-1')).toEqual({ ok: false, motivo: 'bloqueado' });
    // A conta fica no disco: fechar e abrir o app não desbloqueia.
    expect(await new ChaveDoBanco(arquivo, cofre(), RAPIDO).abrirComSenha('Senha-certa-1')).toEqual(
      {
        ok: false,
        motivo: 'bloqueado',
      },
    );

    // Com internet (senha conferida pelo servidor), volta.
    await guarda.confirmarComInternet('Senha-certa-1');
    expect(guarda.errosSeguidos()).toBe(0);
    expect((await guarda.abrirComSenha('Senha-certa-1')).ok).toBe(true);
  });

  it('acertar a senha zera os erros', async () => {
    const guarda = new ChaveDoBanco(arquivo, cofre(), RAPIDO);
    await guarda.criar('Senha-certa-1');
    await guarda.abrirComSenha('errada');
    await guarda.abrirComSenha('errada');
    expect(guarda.errosSeguidos()).toBe(2);
    expect((await guarda.abrirComSenha('Senha-certa-1')).ok).toBe(true);
    expect(guarda.errosSeguidos()).toBe(0);
  });

  it('senha redefinida pela dona: com internet, a chave sai pelo cofre e é embrulhada de novo', async () => {
    const guarda = new ChaveDoBanco(arquivo, cofre(), RAPIDO);
    const chave = await guarda.criar('Senha-antiga-1');
    expect(await guarda.confirmarComInternet('Senha-nova-2')).toEqual(chave);
    expect(await guarda.abrirComSenha('Senha-nova-2')).toEqual({ ok: true, chave });
    expect((await guarda.abrirComSenha('Senha-antiga-1')).ok).toBe(false);
  });

  it('sem o cofre e sem a senha certa, a chave está perdida (nunca se cria outra por cima)', async () => {
    const semCofre = new ChaveDoBanco(arquivo, cofre(false), RAPIDO);
    await semCofre.criar('Senha-antiga-1');
    await expect(semCofre.confirmarComInternet('Senha-nova-2')).rejects.toBeInstanceOf(
      ChavePerdida,
    );
    await expect(semCofre.criar('Outra-senha-3')).rejects.toThrow(/já existe/);

    // O cofre de outro usuário do Windows (ou de outro computador) também não abre.
    const outroCofre: CofreDoSistema = {
      ...cofre(),
      decifrar: () => {
        throw new Error('outro usuário');
      },
    };
    rmSync(arquivo);
    await new ChaveDoBanco(arquivo, cofre(), RAPIDO).criar('Senha-1');
    await expect(
      new ChaveDoBanco(arquivo, outroCofre, RAPIDO).confirmarComInternet('Senha-2'),
    ).rejects.toBeInstanceOf(ChavePerdida);
  });

  it('sem arquivo não há chave para abrir; apagar remove a chave', async () => {
    const guarda = new ChaveDoBanco(arquivo, cofre(), RAPIDO);
    expect(await guarda.abrirComSenha('x')).toEqual({ ok: false, motivo: 'sem-chave' });
    await guarda.criar('Senha-1');
    guarda.apagar();
    expect(guarda.existe()).toBe(false);
  });

  it('o padrão do aplicativo é o verificador lento (scrypt com 128 MB), gravado no arquivo', async () => {
    expect(PARAMETROS_PADRAO.N).toBeGreaterThanOrEqual(2 ** 17);
    expect(PARAMETROS_PADRAO.r).toBe(8);
    const guarda = new ChaveDoBanco(arquivo, cofre());
    const inicio = performance.now();
    await guarda.criar('Senha-1');
    const ms = performance.now() - inicio;
    const gravado = JSON.parse(readFileSync(arquivo, 'utf8')) as {
      kdf: { nome: string; N: number };
    };
    expect(gravado.kdf).toMatchObject({ nome: 'scrypt', N: 2 ** 17 });
    // Lento de propósito (atrapalha a força bruta), mas aceitável ao abrir o app.
    expect(ms).toBeGreaterThan(20);
    expect(ms).toBeLessThan(5_000);
  }, 20_000);
});
