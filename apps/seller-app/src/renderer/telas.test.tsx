// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type {
  AvisoDeSessao,
  EstadoDaSessao,
  PonteDoCorona,
  ResultadoDeEntrada,
} from '../shared/ponte';
import { App } from './App';

const FORA: EstadoDaSessao = { estado: 'fora' };

function ponteFalsa(parcial: Partial<PonteDoCorona> = {}) {
  let ouvinte: ((a: AvisoDeSessao) => void) | null = null;
  const ponte: PonteDoCorona = {
    entrar: vi.fn(() => Promise.resolve<ResultadoDeEntrada>({ tipo: 'entrou' })),
    trocarSenha: vi.fn(() => Promise.resolve<ResultadoDeEntrada>({ tipo: 'entrou' })),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() => Promise.resolve<EstadoDaSessao>({ estado: 'fora' })),
    chamarApi: vi.fn(() => Promise.resolve({ ok: true as const, dados: {} })),
    aoMudarASessao: (f) => {
      ouvinte = f;
      return () => {
        ouvinte = null;
      };
    },
    ...parcial,
  };
  return { ponte, avisar: (a: AvisoDeSessao) => ouvinte?.(a) };
}

describe('tela de entrar', () => {
  it('tem os campos Usuário e Senha e o botão Entrar', async () => {
    const { ponte } = ponteFalsa();
    render(<App ponte={ponte} />);
    expect(await screen.findByLabelText('Usuário')).toBeInTheDocument();
    expect(screen.getByLabelText('Senha')).toHaveAttribute('type', 'password');
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeInTheDocument();
  });

  it('mostra a mensagem que a API devolveu, sem montar texto próprio', async () => {
    const mensagem = 'Não foi possível entrar. Texto que veio da API, com a orientação dela.';
    const { ponte } = ponteFalsa({
      entrar: vi.fn(() => Promise.resolve<ResultadoDeEntrada>({ tipo: 'recusado', mensagem })),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Usuário'), 'maria');
    await u.type(screen.getByLabelText('Senha'), 'errada123');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(mensagem);
    expect(ponte.entrar).toHaveBeenCalledWith({ usuario: 'maria', senha: 'errada123' });
  });

  it('sem internet mostra o texto do glossário e deixa tentar de novo', async () => {
    const { ponte } = ponteFalsa({
      entrar: vi.fn(() => Promise.resolve<ResultadoDeEntrada>({ tipo: 'sem-conexao' })),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Usuário'), 'maria');
    await u.type(screen.getByLabelText('Senha'), 'qualquer1');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Para entrar no Corona Vendas é preciso internet. Conecte-se e tente de novo.',
    );
    expect(screen.getByRole('button', { name: 'Entrar' })).toBeEnabled();
  });

  it('não envia com campo vazio', async () => {
    const { ponte } = ponteFalsa();
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Entrar' }));
    expect(ponte.entrar).not.toHaveBeenCalled();
  });

  it('entrou: passa para a tela inicial', async () => {
    const estados: EstadoDaSessao[] = [
      { estado: 'fora' },
      { estado: 'dentro', usuario: { nome: 'Maria' } },
    ];
    const { ponte } = ponteFalsa({
      estouLogado: vi.fn(() => Promise.resolve(estados.shift() ?? FORA)),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Usuário'), 'maria');
    await u.type(screen.getByLabelText('Senha'), 'certa1234');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByRole('heading', { name: 'Olá, Maria.' })).toBeInTheDocument();
  });

  it('quando a sessão termina sozinha, avisa e volta para Entrar', async () => {
    const { ponte, avisar } = ponteFalsa({
      estouLogado: vi.fn(() =>
        Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: null }),
      ),
    });
    render(<App ponte={ponte} />);
    await screen.findByRole('heading', { name: 'Olá.' });
    avisar({ estado: 'fora' });
    expect(await screen.findByText('Sua sessão terminou.')).toBeInTheDocument();
    expect(screen.getByLabelText('Usuário')).toBeInTheDocument();
  });
});

describe('troca obrigatória de senha', () => {
  const estado = { estado: 'trocar-senha' } as const;

  it('aparece quando o login devolve o token restrito', async () => {
    const { ponte } = ponteFalsa({ estouLogado: vi.fn(() => Promise.resolve(estado)) });
    render(<App ponte={ponte} />);
    expect(
      await screen.findByRole('heading', { name: 'Crie sua senha nova.' }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Senha nova')).toBeInTheDocument();
    expect(screen.getByLabelText('Repita a senha nova')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Salvar senha nova' })).toBeInTheDocument();
    expect(screen.getByText('pelo menos 8 caracteres;')).toBeInTheDocument();
  });

  it('a tela de entrar leva a ela quando o login devolve o token restrito', async () => {
    const estados: EstadoDaSessao[] = [{ estado: 'fora' }, estado];
    const { ponte } = ponteFalsa({
      entrar: vi.fn(() => Promise.resolve<ResultadoDeEntrada>({ tipo: 'trocar-senha' })),
      estouLogado: vi.fn(() => Promise.resolve(estados.shift() ?? estado)),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Usuário'), 'maria');
    await u.type(screen.getByLabelText('Senha'), 'temporaria1');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(
      await screen.findByRole('heading', { name: 'Crie sua senha nova.' }),
    ).toBeInTheDocument();
  });

  it('ajuda antes de enviar: senha fraca e senhas diferentes não saem da tela', async () => {
    const { ponte } = ponteFalsa({ estouLogado: vi.fn(() => Promise.resolve(estado)) });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Senha nova'), 'curta');
    await u.click(screen.getByRole('button', { name: 'Salvar senha nova' }));
    expect(await screen.findByText(/A senha nova ainda não está boa/)).toBeInTheDocument();

    await u.clear(screen.getByLabelText('Senha nova'));
    await u.type(screen.getByLabelText('Senha nova'), 'Senha1234');
    await u.type(screen.getByLabelText('Repita a senha nova'), 'Senha9999');
    await u.click(screen.getByRole('button', { name: 'Salvar senha nova' }));
    expect(await screen.findByText(/As duas senhas não são iguais/)).toBeInTheDocument();
    expect(ponte.trocarSenha).not.toHaveBeenCalled();
  });

  it('envia a senha nova e mostra a mensagem da API quando o servidor recusa', async () => {
    const mensagem = 'A senha nova precisa ser diferente da temporária. Escolha outra.';
    const { ponte } = ponteFalsa({
      estouLogado: vi.fn(() => Promise.resolve(estado)),
      trocarSenha: vi.fn(() => Promise.resolve<ResultadoDeEntrada>({ tipo: 'recusado', mensagem })),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Senha nova'), 'Senha1234');
    await u.type(screen.getByLabelText('Repita a senha nova'), 'Senha1234');
    await u.click(screen.getByRole('button', { name: 'Salvar senha nova' }));
    await waitFor(() => expect(ponte.trocarSenha).toHaveBeenCalledWith({ senhaNova: 'Senha1234' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(mensagem);
  });
});

describe('aviso de aplicativo desatualizado', () => {
  it('mostra o texto da API e não oferece "Atualizar agora" nem campos de entrada', async () => {
    const { ponte } = ponteFalsa({
      estouLogado: vi.fn(() =>
        Promise.resolve<EstadoDaSessao>({
          estado: 'app-desatualizado',
          mensagem: 'Seu aplicativo está desatualizado. Atualize para continuar usando.',
        }),
      ),
    });
    render(<App ponte={ponte} />);
    expect(await screen.findByText(/Seu aplicativo está desatualizado/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Atualizar agora/ })).toBeNull();
    expect(screen.queryByLabelText('Usuário')).toBeNull();
  });

  it('ao entrar com versão antiga, vai para o aviso', async () => {
    const estados: EstadoDaSessao[] = [
      { estado: 'fora' },
      { estado: 'app-desatualizado', mensagem: 'Texto da API.' },
    ];
    const { ponte } = ponteFalsa({
      entrar: vi.fn(() =>
        Promise.resolve<ResultadoDeEntrada>({
          tipo: 'app-desatualizado',
          mensagem: 'Texto da API.',
        }),
      ),
      estouLogado: vi.fn(() => Promise.resolve(estados.shift() ?? FORA)),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.type(await screen.findByLabelText('Usuário'), 'maria');
    await u.type(screen.getByLabelText('Senha'), 'qualquer1');
    await u.click(screen.getByRole('button', { name: 'Entrar' }));
    expect(await screen.findByText('Texto da API.')).toBeInTheDocument();
  });
});

describe('sem conexão ao abrir', () => {
  it('mostra o texto do glossário e Tentar de novo repete a conferência', async () => {
    const estados: EstadoDaSessao[] = [{ estado: 'sem-conexao' }, { estado: 'fora' }];
    const { ponte } = ponteFalsa({
      estouLogado: vi.fn(() => Promise.resolve(estados.shift() ?? FORA)),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    expect(await screen.findByText('Sem internet.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Tentar de novo' }));
    expect(await screen.findByLabelText('Usuário')).toBeInTheDocument();
  });
});

describe('tela inicial do vendedor', () => {
  const dentro: EstadoDaSessao = { estado: 'dentro', usuario: { nome: 'Maria' } };

  it('tem os quatro botões grandes e o botão Sair', async () => {
    const { ponte } = ponteFalsa({ estouLogado: vi.fn(() => Promise.resolve(dentro)) });
    render(<App ponte={ponte} />);
    for (const nome of ['Vender', 'Minha maleta', 'Meus clientes', 'Meus números']) {
      expect(await screen.findByRole('button', { name: nome })).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Sair' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Olá, Maria.' })).toBeInTheDocument();
  });

  it('botão sem função mostra "Em breve" em linguagem da loja', async () => {
    const { ponte } = ponteFalsa({ estouLogado: vi.fn(() => Promise.resolve(dentro)) });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Minha maleta' }));
    expect(screen.getByText('Em breve.')).toBeInTheDocument();
    expect(screen.getByText(/Minha maleta ainda está sendo preparado/)).toBeInTheDocument();
  });

  it('Sair chama a ponte e volta para Entrar', async () => {
    const estados: EstadoDaSessao[] = [dentro, { estado: 'fora' }];
    const { ponte } = ponteFalsa({
      estouLogado: vi.fn(() => Promise.resolve(estados.shift() ?? FORA)),
    });
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Sair' }));
    expect(ponte.sair).toHaveBeenCalledTimes(1);
    expect(await screen.findByLabelText('Usuário')).toBeInTheDocument();
  });
});
