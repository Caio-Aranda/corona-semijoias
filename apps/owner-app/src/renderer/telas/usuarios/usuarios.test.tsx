// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';

const ID_MARIA = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_JOSE = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_VENDEDOR = '5d41402a-bc4b-4a2a-9c1f-aa11bb22cc33';

const usuario = (o: Record<string, unknown>) => ({
  id: ID_MARIA,
  version: 1,
  nome: 'Maria Souza',
  login: 'maria',
  perfil: 'ADMIN',
  ativo: true,
  trocaDeSenhaObrigatoria: false,
  ...o,
});

type Resposta = ResultadoDeChamada | Promise<ResultadoDeChamada>;
type Manipulador = (pedido: PedidoDeChamada) => Resposta;

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () => ({
      ok: true,
      dados: { itens: [{ id: ID_VENDEDOR, nome: 'Joana Vendedora', codigo: 7 }] },
    }),
    'usuarios.listar': () => ({
      ok: true,
      dados: {
        itens: [
          usuario({}),
          usuario({
            id: ID_JOSE,
            nome: 'José Lima',
            login: 'jose',
            perfil: 'VENDEDOR',
            ativo: false,
          }),
        ],
        pagina: 0,
        tamanho: 20,
        total: 2,
      },
    }),
  };
  const todos = { ...padrao, ...manipuladores };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn(async (pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = todos[pedido.operacao];
      return m ? m(pedido) : { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
  };
  return { ponte, chamadas };
}

async function abrirUsuarios(m: ReturnType<typeof montar>) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Usuários' }));
  await screen.findByText('Maria Souza');
  return u;
}

beforeEach(() => {
  window.location.hash = '';
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('Pessoas → Usuários: lista', () => {
  it('mostra os usuários com tipo de acesso e situação em português', async () => {
    await abrirUsuarios(montar());
    const linhaJose = screen.getByRole('row', { name: /José Lima/ });
    expect(within(linhaJose).getByText('Vendedor')).toBeInTheDocument();
    expect(within(linhaJose).getByText('Desativado')).toBeInTheDocument();
    expect(within(linhaJose).getByRole('button', { name: 'Reativar usuário' })).toBeInTheDocument();
    const linhaMaria = screen.getByRole('row', { name: /Maria Souza/ });
    expect(within(linhaMaria).getByText('Dona')).toBeInTheDocument();
    expect(
      within(linhaMaria).getByRole('button', { name: 'Desativar usuário' }),
    ).toBeInTheDocument();
    expect(screen.getByText('2 usuários')).toBeInTheDocument();
  });

  it('busca e filtros vão para a API; o cursor já está na busca', async () => {
    const m = montar();
    const u = await abrirUsuarios(m);
    expect(screen.getByLabelText('Buscar por nome ou usuário')).toHaveFocus();
    await u.type(screen.getByLabelText('Buscar por nome ou usuário'), 'jose');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'VENDEDOR');
    await u.selectOptions(screen.getByLabelText('Situação'), 'desativados');
    await waitFor(() => {
      const ultima = m.chamadas.filter((c) => c.operacao === 'usuarios.listar').at(-1);
      expect(ultima?.parametros).toEqual({
        pagina: 0,
        tamanho: 20,
        busca: 'jose',
        perfil: 'VENDEDOR',
        ativo: false,
      });
    });
  });

  it('lista vazia e busca sem resultado têm textos diferentes', async () => {
    const vazia: Manipulador = () => ({
      ok: true,
      dados: { itens: [], pagina: 0, tamanho: 20, total: 0 },
    });
    const m = montar({ 'usuarios.listar': vazia });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Usuários' }));
    expect(await screen.findByText('Ainda não há nenhum usuário.')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Situação'), 'ativos');
    expect(
      await screen.findByText(
        'Nenhum usuário encontrado. Confira o que você digitou ou limpe a busca.',
      ),
    ).toBeInTheDocument();
  });

  it('erro ao buscar mostra o texto do servidor e deixa tentar de novo', async () => {
    let chamada = 0;
    const m = montar({
      'usuarios.listar': () => {
        chamada += 1;
        return chamada === 1
          ? { ok: false, motivo: 'erro', status: 500 }
          : { ok: true, dados: { itens: [usuario({})], pagina: 0, tamanho: 20, total: 1 } };
      },
    });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Usuários' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Tente de novo/);
    await u.click(screen.getByRole('button', { name: 'Tentar de novo' }));
    expect(await screen.findByText('Maria Souza')).toBeInTheDocument();
  });
});

describe('Usuários: sem internet com a pessoa dentro', () => {
  it('mostra o texto do glossário, mantém a tela e tenta de novo sozinho', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let chamada = 0;
      const m = montar({
        'usuarios.listar': () => {
          chamada += 1;
          return chamada === 1
            ? { ok: false, motivo: 'sem-conexao' }
            : { ok: true, dados: { itens: [usuario({})], pagina: 0, tamanho: 20, total: 1 } };
        },
      });
      const u = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
      render(<App ponte={m.ponte} />);
      await u.click(await screen.findByRole('button', { name: 'Usuários' }));
      expect(
        await screen.findByText('Sem conexão com a internet. Tente de novo.'),
      ).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(16_000);
      expect(await screen.findByText('Maria Souza')).toBeInTheDocument();
      expect(
        screen.queryByText('Sem conexão com a internet. Tente de novo.'),
      ).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('Usuários: cadastro', () => {
  const criar: Manipulador = () => ({
    ok: true,
    dados: {
      id: ID_JOSE,
      nome: 'Ana Paula',
      login: 'ana.paula',
      perfil: 'ADMIN',
      senhaTemporaria: 'Xk9-temp-Qz42',
      senhaTemporariaVenceEm: '2026-11-17T15:00:00Z',
    },
  });

  it('o cursor já está no Nome e Enter salva; a senha temporária aparece uma vez', async () => {
    const m = montar({ 'usuarios.criar': criar });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    const nome = await screen.findByLabelText('Nome');
    expect(nome).toHaveFocus();
    await u.type(nome, 'Ana Paula');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'ADMIN');
    await u.type(screen.getByLabelText('Usuário'), 'ana.paula{Enter}');

    expect(await screen.findByTestId('senha-temporaria')).toHaveTextContent('Xk9-temp-Qz42');
    expect(
      screen.getByText('Anote esta senha agora. Ela não aparece de novo.'),
    ).toBeInTheDocument();
    expect(screen.getByText('A senha vale até 17/11/2026.')).toBeInTheDocument();
    const pedido = m.chamadas.find((c) => c.operacao === 'usuarios.criar');
    expect(pedido?.parametros).toEqual({ nome: 'Ana Paula', login: 'ana.paula', perfil: 'ADMIN' });
  });

  it('a senha não vai para o disco nem para o registro e some ao fechar', async () => {
    const registro = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const aviso = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const erro = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const m = montar({ 'usuarios.criar': criar });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    await u.type(await screen.findByLabelText('Nome'), 'Ana Paula');
    await u.type(screen.getByLabelText('Usuário'), 'ana.paula');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'ADMIN');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByTestId('senha-temporaria');

    for (const guarda of [window.localStorage, window.sessionStorage]) {
      expect(JSON.stringify({ ...guarda })).not.toContain('Xk9-temp-Qz42');
    }
    for (const espiao of [registro, aviso, erro]) {
      expect(JSON.stringify(espiao.mock.calls)).not.toContain('Xk9-temp-Qz42');
    }

    await u.click(screen.getByRole('button', { name: 'Já anotei, fechar' }));
    expect(screen.queryByText('Xk9-temp-Qz42')).not.toBeInTheDocument();
  });

  it('a senha some ao sair da tela de usuários', async () => {
    const m = montar({ 'usuarios.criar': criar });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    await u.type(await screen.findByLabelText('Nome'), 'Ana Paula');
    await u.type(screen.getByLabelText('Usuário'), 'ana.paula');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'ADMIN');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByTestId('senha-temporaria');
    await u.click(screen.getByRole('button', { name: 'Início' }));
    await u.click(screen.getByRole('button', { name: 'Usuários' }));
    await screen.findByText('Maria Souza');
    expect(screen.queryByText('Xk9-temp-Qz42')).not.toBeInTheDocument();
  });

  it('o botão copiar usa a área de transferência e avisa', async () => {
    const escrever = vi.fn(() => Promise.resolve());
    const m = montar({ 'usuarios.criar': criar });
    const u = await abrirUsuarios(m);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: escrever },
      configurable: true,
    });
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    await u.type(await screen.findByLabelText('Nome'), 'Ana Paula');
    await u.type(screen.getByLabelText('Usuário'), 'ana.paula');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'ADMIN');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await u.click(await screen.findByRole('button', { name: 'Copiar senha' }));
    expect(escrever).toHaveBeenCalledWith('Xk9-temp-Qz42');
    expect(await screen.findByText('Senha copiada.')).toBeInTheDocument();
  });

  it('vendedor precisa de um vendedor escolhido e vai ligado ao acesso', async () => {
    const m = montar({ 'usuarios.criar': criar });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    await u.type(await screen.findByLabelText('Nome'), 'Joana');
    await u.type(screen.getByLabelText('Usuário'), 'joana');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Escolha o vendedor deste acesso.')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'usuarios.criar')).toBe(false);
    await u.selectOptions(screen.getByLabelText('Vendedor'), 'Joana Vendedora');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByTestId('senha-temporaria');
    const pedido = m.chamadas.find((c) => c.operacao === 'usuarios.criar');
    expect(pedido?.parametros).toMatchObject({ perfil: 'VENDEDOR', vendedorId: ID_VENDEDOR });
  });

  it('usuário repetido aparece no próprio campo, com o texto do servidor', async () => {
    const m = montar({
      'usuarios.criar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'LOGIN_JA_USADO',
        mensagem: 'Este usuário já existe. Escolha outro, por exemplo juntando o sobrenome.',
      }),
    });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    await u.type(await screen.findByLabelText('Nome'), 'Ana');
    await u.type(screen.getByLabelText('Usuário'), 'maria');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'ADMIN');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    const campo = screen.getByLabelText('Usuário');
    await waitFor(() => expect(campo).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByText(/Este usuário já existe/)).toBeInTheDocument();
  });

  it('campo recusado pelo servidor (400) aparece no campo', async () => {
    const m = montar({
      'usuarios.criar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Confira os campos.',
        campos: [{ campo: 'nome', mensagem: 'O nome é grande demais. Use até 120 letras.' }],
      }),
    });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Novo usuário' }));
    await u.type(await screen.findByLabelText('Nome'), 'Ana');
    await u.type(screen.getByLabelText('Usuário'), 'ana');
    await u.selectOptions(screen.getByLabelText('Tipo de acesso'), 'ADMIN');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('O nome é grande demais. Use até 120 letras.'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Nome')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('Usuários: edição', () => {
  it('manda a versão lida; 409 de versão pede para atualizar e volta à lista atualizada', async () => {
    let listas = 0;
    const m = montar({
      'usuarios.listar': () => {
        listas += 1;
        return {
          ok: true,
          dados: {
            itens: [usuario({ version: listas })],
            pagina: 0,
            tamanho: 20,
            total: 1,
          },
        };
      },
      'usuarios.editar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CADASTRO_ALTERADO',
        mensagem:
          'Este cadastro foi alterado por outra pessoa. Atualize a tela e faça a alteração de novo.',
      }),
    });
    const u = await abrirUsuarios(m);
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    const nome = await screen.findByLabelText('Nome');
    expect(screen.getByLabelText('Usuário')).toHaveAttribute('readonly');
    await u.clear(nome);
    await u.type(nome, 'Maria S.');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText(/alterado por outra pessoa/)).toBeInTheDocument();
    const pedido = m.chamadas.find((c) => c.operacao === 'usuarios.editar');
    expect(pedido?.parametros).toEqual({
      id: ID_MARIA,
      nome: 'Maria S.',
      perfil: 'ADMIN',
      version: 1,
    });
    await u.click(screen.getByRole('button', { name: 'Atualizar' }));
    expect(await screen.findByRole('button', { name: 'Editar' })).toBeInTheDocument();
    expect(listas).toBe(2);
  });
});

describe('Usuários: desativar, reativar e nova senha', () => {
  it('desativar explica que a pessoa sai na hora e só age depois de confirmar', async () => {
    const m = montar({ 'usuarios.desativar': () => ({ ok: true, dados: {} }) });
    const u = await abrirUsuarios(m);
    const linha = screen.getByRole('row', { name: /Maria Souza/ });
    await u.click(within(linha).getByRole('button', { name: 'Desativar usuário' }));
    const janela = screen.getByRole('dialog');
    expect(janela).toHaveTextContent('vai sair do sistema agora');
    expect(within(janela).getByRole('button', { name: 'Voltar' })).toHaveFocus();
    expect(m.chamadas.some((c) => c.operacao === 'usuarios.desativar')).toBe(false);
    await u.click(within(janela).getByRole('button', { name: 'Desativar usuário' }));
    await screen.findByText('Maria Souza foi desativado e saiu do sistema.');
    expect(m.chamadas.find((c) => c.operacao === 'usuarios.desativar')?.parametros).toEqual({
      id: ID_MARIA,
    });
  });

  it('Voltar não desativa ninguém', async () => {
    const m = montar();
    const u = await abrirUsuarios(m);
    const linha = screen.getByRole('row', { name: /Maria Souza/ });
    await u.click(within(linha).getByRole('button', { name: 'Desativar usuário' }));
    await u.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Voltar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'usuarios.desativar')).toBe(false);
  });

  it('regra do servidor (última administradora) vira a mensagem dele, sem código', async () => {
    const m = montar({
      'usuarios.desativar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'ULTIMA_ADMINISTRADORA',
        mensagem:
          'Precisa continuar existindo pelo menos uma pessoa administradora com acesso. Dê o acesso de administradora a outra pessoa antes de fazer esta mudança.',
      }),
    });
    const u = await abrirUsuarios(m);
    const linha = screen.getByRole('row', { name: /Maria Souza/ });
    await u.click(within(linha).getByRole('button', { name: 'Desativar usuário' }));
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Desativar usuário' }),
    );
    const alerta = await screen.findByRole('alert');
    expect(alerta).toHaveTextContent('pelo menos uma pessoa administradora');
    expect(alerta).not.toHaveTextContent(/ULTIMA|409/);
  });

  it('reativar explica o que acontece', async () => {
    const m = montar({ 'usuarios.reativar': () => ({ ok: true, dados: {} }) });
    const u = await abrirUsuarios(m);
    const linha = screen.getByRole('row', { name: /José Lima/ });
    await u.click(within(linha).getByRole('button', { name: 'Reativar usuário' }));
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'volta a poder entrar com a senha que já tinha',
    );
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Reativar usuário' }),
    );
    await screen.findByText('José Lima foi reativado.');
  });

  it('nova senha temporária mostra a senha uma vez', async () => {
    const m = montar({
      'usuarios.redefinirSenha': () => ({
        ok: true,
        dados: { id: ID_JOSE, nome: 'José Lima', senhaTemporaria: 'Nova-Senha-77' },
      }),
    });
    const u = await abrirUsuarios(m);
    const linha = screen.getByRole('row', { name: /José Lima/ });
    await u.click(within(linha).getByRole('button', { name: 'Nova senha temporária' }));
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'quem estiver dentro sai do sistema agora',
    );
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Criar senha temporária' }),
    );
    expect(await screen.findByTestId('senha-temporaria')).toHaveTextContent('Nova-Senha-77');
  });
});
