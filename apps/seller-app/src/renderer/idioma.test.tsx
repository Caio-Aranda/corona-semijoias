// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PonteDoCorona } from '../shared/ponte';
import { App } from './App';
import { MENSAGENS_DO_BANCO } from '../main/banco/banco-local';
import { MENSAGENS_DA_SENHA } from '../main/banco/servico-do-banco';
import { MENSAGENS_DAS_OPERACOES } from '../main/sync/operacoes-locais';
import { ERROS_ES, mensagemDoErro } from './erros-es';
import {
  IDIOMA_PADRAO,
  definirIdioma,
  idiomaAtual,
  iniciarIdioma,
  reiniciarIdioma,
  type GuardadorDoIdioma,
  type Idioma,
} from './idioma';
import { TEXTOS, TEXTOS_ES, TEXTOS_PT } from './textos';

/** Todos os caminhos de um dicionário ("venda.formas.PIX"), com o tipo da folha (texto, função, lista). */
function folhas(obj: unknown, prefixo = ''): Map<string, string> {
  const achadas = new Map<string, string>();
  if (typeof obj === 'string') achadas.set(prefixo, 'texto');
  else if (typeof obj === 'function') achadas.set(prefixo, `funcao(${obj.length})`);
  else if (Array.isArray(obj)) achadas.set(prefixo, `lista(${obj.length})`);
  else if (obj && typeof obj === 'object') {
    for (const [k, v] of Object.entries(obj)) {
      for (const [c, t] of folhas(v, prefixo ? `${prefixo}.${k}` : k)) achadas.set(c, t);
    }
  }
  return achadas;
}

function textosVazios(d: unknown, caminho = ''): string[] {
  if (typeof d === 'string') return d.trim() === '' && !caminho.endsWith('acoes') ? [caminho] : [];
  if (d && typeof d === 'object') {
    return Object.entries(d).flatMap(([k, v]) => textosVazios(v, caminho ? `${caminho}.${k}` : k));
  }
  return [];
}

describe('os dois dicionários de textos têm as mesmas chaves', () => {
  const pt = folhas(TEXTOS_PT);
  const es = folhas(TEXTOS_ES);

  it('não falta nenhuma chave em espanhol', () => {
    expect([...pt.keys()].filter((k) => !es.has(k))).toEqual([]);
  });

  it('não sobra nenhuma chave em espanhol', () => {
    expect([...es.keys()].filter((k) => !pt.has(k))).toEqual([]);
  });

  it('cada chave tem o mesmo tipo (texto, função com os mesmos parâmetros ou lista do mesmo tamanho)', () => {
    const diferentes = [...pt].filter(([k, t]) => es.get(k) !== t).map(([k]) => k);
    expect(diferentes).toEqual([]);
  });

  it('nenhum texto fica vazio por esquecimento (só as colunas de ações)', () => {
    expect(pt.size).toBeGreaterThan(300);
    expect(textosVazios(TEXTOS_ES)).toEqual([]);
    expect(textosVazios(TEXTOS_PT)).toEqual([]);
  });

  it('as funções de texto dão frase nos dois idiomas', () => {
    expect(TEXTOS_ES.venda.janelaTexto('Ana')).toContain('Ana');
    expect(TEXTOS_PT.venda.janelaTexto('Ana')).toContain('Ana');
    expect(TEXTOS_ES.envio.vendasAguardando(3)).toBe('3 ventas esperando envío');
    expect(TEXTOS_ES.envio.vendasAguardando(1)).toBe('1 venta esperando envío');
  });
});

/** Um "computador" de mentira: guarda o idioma como o processo principal guarda (num arquivo), sem navegador. */
function guardadorFalso(inicial: Idioma | null): GuardadorDoIdioma & { guardado: Idioma | null } {
  const g = {
    guardado: inicial,
    lerIdioma: () => Promise.resolve(g.guardado),
    gravarIdioma: (i: Idioma) => {
      g.guardado = i;
      return Promise.resolve();
    },
  };
  return g;
}

describe('a escolha do idioma', () => {
  beforeEach(() => {
    reiniciarIdioma();
  });

  it('o padrão é o espanhol', () => {
    expect(IDIOMA_PADRAO).toBe('es');
    expect(idiomaAtual()).toBe('es');
    expect(TEXTOS.botoes.sair).toBe('Salir');
  });

  it('trocar vale na hora e fica guardado no computador', () => {
    const g = guardadorFalso(null);
    void iniciarIdioma(g);
    definirIdioma('pt');
    expect(TEXTOS.botoes.sair).toBe('Sair');
    expect(g.guardado).toBe('pt');
    definirIdioma('es');
    expect(TEXTOS.botoes.sair).toBe('Salir');
    expect(g.guardado).toBe('es');
  });

  it('na próxima abertura lê o que ficou guardado', async () => {
    await iniciarIdioma(guardadorFalso('pt'));
    expect(idiomaAtual()).toBe('pt');
    expect(TEXTOS.botoes.sair).toBe('Sair');
  });

  it('nunca escolheu, valor estranho ou falha na leitura: fica o espanhol', async () => {
    await iniciarIdioma(guardadorFalso(null));
    expect(idiomaAtual()).toBe('es');
    await iniciarIdioma(guardadorFalso('fr' as Idioma));
    expect(idiomaAtual()).toBe('es');
    await iniciarIdioma({
      lerIdioma: () => Promise.reject(new Error('sem arquivo')),
      gravarIdioma: () => Promise.resolve(),
    });
    expect(idiomaAtual()).toBe('es');
  });

  it('se o guardador falhar ao gravar, a troca ainda vale até fechar o programa', () => {
    void iniciarIdioma({
      lerIdioma: () => Promise.resolve(null),
      gravarIdioma: () => Promise.reject(new Error('disco cheio')),
    });
    definirIdioma('pt');
    expect(idiomaAtual()).toBe('pt');
  });
});

describe('mensagens de erro pelo código', () => {
  it('em espanhol, o texto próprio do código; em português, a mensagem que veio', () => {
    expect(mensagemDoErro('es', 'SEM_SALDO', 'Não há mais peças', 'x')).toBe(ERROS_ES.SEM_SALDO);
    expect(mensagemDoErro('pt', 'SEM_SALDO', 'Não há mais peças', 'x')).toBe('Não há mais peças');
  });

  it('código desconhecido: a mensagem como veio; sem mensagem: o texto padrão', () => {
    expect(mensagemDoErro('es', 'CODIGO_NOVO', 'Mensagem da API', 'x')).toBe('Mensagem da API');
    expect(mensagemDoErro('es', undefined, undefined, 'padrão')).toBe('padrão');
    expect(mensagemDoErro('es', 'toString', undefined, 'padrão')).toBe('padrão');
  });

  it('todo código que o próprio computador dá tem texto em espanhol', () => {
    const codigos = [
      ...Object.keys(MENSAGENS_DAS_OPERACOES),
      ...Object.keys(MENSAGENS_DO_BANCO),
      ...Object.keys(MENSAGENS_DA_SENHA),
      'MALETA_SEM_CONFERENCIA',
      'CONSULTA_SO_COM_INTERNET',
      'ACERTO_NAO_ENCONTRADO_NO_COMPUTADOR',
      'CLIENTE_DE_OUTRO_VENDEDOR',
      'SEM_BANCO_LOCAL',
    ];
    expect(codigos.filter((c) => !(c in ERROS_ES))).toEqual([]);
  });
});

function ponteDeFora(): PonteDoCorona {
  return {
    estouLogado: vi.fn().mockResolvedValue({ estado: 'fora' }),
    entrar: vi.fn().mockResolvedValue({
      tipo: 'recusado',
      mensagem: 'Usuário ou senha incorretos.',
      codigo: 'LOGIN_INVALIDO',
    }),
    aoMudarASessao: vi.fn().mockReturnValue(() => undefined),
    aoMudarAAtualizacao: vi.fn().mockReturnValue(() => undefined),
    aoMudarASincronizacao: vi.fn().mockReturnValue(() => undefined),
  } as unknown as PonteDoCorona;
}

describe('a tela de entrada nos dois idiomas', () => {
  beforeEach(() => {
    reiniciarIdioma();
  });

  it('abre em espanhol e a troca muda a tela na hora, sem reiniciar', async () => {
    const usuario = userEvent.setup();
    render(<App ponte={ponteDeFora()} />);
    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(screen.getByLabelText('Usuario')).toBeInTheDocument();
    expect(screen.getByLabelText('Contraseña')).toBeInTheDocument();

    await usuario.click(screen.getByRole('button', { name: 'Português' }));
    expect(screen.getByLabelText('Usuário')).toBeInTheDocument();
    expect(screen.getByLabelText('Senha')).toBeInTheDocument();

    await usuario.click(screen.getByRole('button', { name: 'Español' }));
    expect(screen.getByLabelText('Usuario')).toBeInTheDocument();
  });

  it('o erro de entrada sai pelo código no idioma de quem usa', async () => {
    const usuario = userEvent.setup();
    render(<App ponte={ponteDeFora()} />);
    await usuario.type(await screen.findByLabelText('Usuario'), 'ana');
    await usuario.type(screen.getByLabelText('Contraseña'), 'segredo123');
    await usuario.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(
      await screen.findByText(/Usuario o contraseña incorrectos/, { exact: false }),
    ).toBeInTheDocument();

    act(() => definirIdioma('pt'));
    await usuario.type(screen.getByLabelText('Senha'), 'x');
    await usuario.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByText('Usuário ou senha incorretos.')).toBeInTheDocument();
  });
});
