import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ChaveDoBanco, type CofreDoSistema } from '../banco/chave-do-banco';
import { ServicoDoBanco } from '../banco/servico-do-banco';
import { enfileirar, lerAguardando } from '../sync/outbox';

/**
 * O banco para o apagar e a identidade guardada (tarefa 5.8): o cofre do Windows abre a chave sem senha só para ler o
 * que ainda não foi enviado; sem o cofre, nada abre. SQLCipher de verdade.
 */
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

let raiz: string;
let pasta: string;
const abertos: ServicoDoBanco[] = [];

function servico(
  c: CofreDoSistema = cofre(),
  aoAbrir?: ConstructorParameters<typeof ServicoDoBanco>[0]['aoAbrirComInternet'],
) {
  const s = new ServicoDoBanco({
    pasta,
    cofre: c,
    parametros: RAPIDO,
    ...(aoAbrir ? { aoAbrirComInternet: aoAbrir } : {}),
  });
  abertos.push(s);
  return s;
}

beforeEach(() => {
  raiz = mkdtempSync(join(tmpdir(), 'corona-apagar-banco-'));
  pasta = join(raiz, 'dados');
});
afterEach(() => {
  for (const s of abertos.splice(0)) s.fechar();
  rmSync(raiz, { recursive: true, force: true });
});

describe('banco para o apagar', () => {
  it('sem banco ainda, não há dados locais; depois da primeira entrada há', async () => {
    const s = servico();
    expect(s.temDadosLocais).toBe(false);
    await s.prepararComInternet('Senha-1');
    expect(s.temDadosLocais).toBe(true);
  });

  it('o cofre abre o banco sem senha e dá para ler o que aguarda envio', async () => {
    const primeiro = servico();
    await primeiro.prepararComInternet('Senha-1');
    enfileirar(primeiro.banco!, {
      operacaoId: '0190a1b2-c3d4-7e5f-8a9b-000000000009',
      tipo: 'CLIENTE_NOVO',
      dados: '{"id":"x"}',
      agora: new Date('2026-11-12T12:00:00Z'),
    });
    primeiro.fechar();

    const segundo = servico();
    const db = await segundo.abrirParaApagar();
    expect(db).not.toBeNull();
    expect(lerAguardando(db!, 10)).toHaveLength(1);
  });

  it('sem o cofre do Windows, nada abre sem a senha (e o banco continua ilegível)', async () => {
    const primeiro = servico(cofre(false));
    await primeiro.prepararComInternet('Senha-1');
    primeiro.fechar();
    const segundo = servico(cofre(false));
    expect(await segundo.abrirParaApagar()).toBeNull();
    expect(segundo.banco).toBeNull();
  });

  it('o cofre de outro usuário do Windows não abre a chave', async () => {
    const primeiro = servico();
    await primeiro.prepararComInternet('Senha-1');
    primeiro.fechar();
    const outro: CofreDoSistema = {
      disponivel: () => true,
      cifrar: (t) => Buffer.from(t),
      decifrar: () => {
        throw new Error('outro usuário do Windows');
      },
    };
    expect(await servico(outro).abrirParaApagar()).toBeNull();
  });

  it('ChaveDoBanco.abrirSoPeloCofre devolve a mesma chave que a senha; sem arquivo, nulo', async () => {
    const arquivo = join(pasta, 'chave.json');
    const chave = new ChaveDoBanco(arquivo, cofre(), RAPIDO);
    expect(await chave.abrirSoPeloCofre()).toBeNull();
    const criada = await chave.criar('Senha-1');
    const pelaSenha = await chave.abrirComSenha('Senha-1');
    const pelaCofre = await chave.abrirSoPeloCofre();
    expect(pelaCofre?.equals(criada)).toBe(true);
    expect(pelaSenha.ok && pelaSenha.chave.equals(criada)).toBe(true);
  });
});

describe('quem entrou e o contato do login', () => {
  it('lembra o login e o nome dentro do próprio banco cifrado', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    s.lembrar('vendedor_login', 'ana');
    s.lembrar('vendedor_nome', 'Ana Souza');
    s.fechar();
    const outro = servico();
    await outro.abrirSemInternet('Senha-1');
    expect(outro.lembrado('vendedor_login')).toBe('ana');
    expect(outro.lembrado('vendedor_nome')).toBe('Ana Souza');
  });

  it('chama o gancho do contato do login depois de abrir com internet, e só com internet', async () => {
    let chamadas = 0;
    const s = servico(cofre(), () => {
      chamadas += 1;
    });
    await s.prepararComInternet('Senha-1');
    expect(chamadas).toBe(1);
    s.fechar();
    const sem = servico(cofre(), () => {
      chamadas += 1;
    });
    await sem.abrirSemInternet('Senha-1');
    expect(chamadas).toBe(1);
  });

  it('sem internet, cinco senhas erradas bloqueiam até entrar com internet', async () => {
    const s = servico();
    await s.prepararComInternet('Senha-1');
    s.fechar();
    const outro = servico();
    for (let i = 0; i < 4; i++) {
      expect(await outro.abrirSemInternet('errada')).toMatchObject({
        ok: false,
        codigo: 'SENHA_ERRADA',
      });
    }
    expect(await outro.abrirSemInternet('errada')).toMatchObject({
      ok: false,
      codigo: 'SENHA_BLOQUEADA',
    });
    expect(await outro.abrirSemInternet('Senha-1')).toMatchObject({
      ok: false,
      codigo: 'SENHA_BLOQUEADA',
    });
  });
});
