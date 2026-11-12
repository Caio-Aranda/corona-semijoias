// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';

const ID_VEND = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_PC_NOVO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_PC_ANTIGO = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_ENVIO = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_CPF = '4e5e28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';
const ID_VENDA = '5f6e28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

function montar(extras: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({
      ok: true,
      dados: {
        avisos: [
          {
            tipo: 'ENVIOS_COM_PROBLEMA',
            titulo: 'Há 2 envios com problema.',
            texto: 'São vendas que não puderam entrar.',
            dias: [],
          },
        ],
      },
    }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () => ({
      ok: true,
      dados: { itens: [{ id: ID_VEND, nome: 'Joana Vendedora', ativo: true }] },
    }),
    'sincronizacao.aparelhos': () => ({
      ok: true,
      dados: [
        {
          id: ID_PC_ANTIGO,
          situacao: 'ATIVO',
          ultimoContatoEm: '2026-11-10T15:30:00Z',
          aguardandoEnvio: 3,
        },
        {
          id: ID_PC_NOVO,
          situacao: 'AGUARDANDO_LIBERACAO',
          ultimoContatoEm: '2026-11-11T12:00:00Z',
        },
      ],
    }),
    'sincronizacao.envios': () => ({
      ok: true,
      dados: [
        {
          id: ID_ENVIO,
          tipo: 'VENDA',
          resultado: 'RECUSADO',
          mensagem: 'Faltou peça na maleta.',
          recebidoEm: '2026-11-10T15:30:00Z',
          vendedorNome: 'Joana Vendedora',
        },
        {
          id: ID_CPF,
          tipo: 'CLIENTE_NOVO',
          resultado: 'CONFLITO',
          codigo: 'CLIENTE_CPF_JA_CADASTRADO',
          mensagem: 'Este CPF já existe.',
          recebidoEm: '2026-11-10T16:30:00Z',
          vendedorNome: 'Joana Vendedora',
          dados: { nome: 'Ana Maria', cpf: '123.456.789-09' },
        },
      ],
    }),
    'sincronizacao.quarentena': () => ({
      ok: true,
      dados: [
        {
          id: ID_VENDA,
          tipo: 'VENDA',
          vendedorNome: 'Joana Vendedora',
          recebidoEm: '2026-11-11T10:00:00Z',
        },
      ],
    }),
    'sincronizacao.valoresSubstituidos': () => ({
      ok: true,
      dados: [
        {
          id: ID_VENDA,
          clienteNome: 'Dora',
          quemMudou: 'Joana',
          quando: '2026-11-11T10:00:00Z',
          campos: [{ campo: 'telefone', valorAnterior: '1111', valorNovo: '2222' }],
        },
      ],
    }),
    ...extras,
  };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn(async (pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = padrao[pedido.operacao];
      return m ? m(pedido) : { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

const ultima = (m: ReturnType<typeof montar>, op: string) =>
  m.chamadas.filter((c) => c.operacao === op).at(-1)?.parametros;

async function abrir(m: ReturnType<typeof montar>, item: string, titulo: string) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  await u.click(within(nav).getByRole('button', { name: item }));
  await screen.findByRole('heading', { name: titulo, level: 1 });
  return u;
}

describe('Computadores dos vendedores (tarefa 5.10)', () => {
  it('mostra os computadores e só oferece Trocar para o que espera liberação', async () => {
    const m = montar();
    const u = await abrir(m, 'Computadores', 'Computadores');
    await u.selectOptions(await screen.findByLabelText('Vendedor'), ID_VEND);
    expect(await screen.findByText('Em uso')).toBeInTheDocument();
    expect(screen.getByText('Aguardando liberação')).toBeInTheDocument();
    expect(screen.getAllByText('Nunca enviou tudo')).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: /Trocar computador de/ })).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: /Bloquear computador de/ })).toHaveLength(2);
  });

  it('trocar computador pede confirmação clara e só então chama a API', async () => {
    const m = montar({ 'sincronizacao.liberarAparelho': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Computadores', 'Computadores');
    await u.selectOptions(await screen.findByLabelText('Vendedor'), ID_VEND);
    await u.click(await screen.findByRole('button', { name: /Trocar computador de/ }));
    expect(screen.getByText(/O computador antigo deixa de vender/)).toBeInTheDocument();
    expect(ultima(m, 'sincronizacao.liberarAparelho')).toBeUndefined();
    await u.click(screen.getByRole('button', { name: 'Trocar computador' }));
    await waitFor(() =>
      expect(ultima(m, 'sincronizacao.liberarAparelho')).toEqual({ id: ID_PC_NOVO }),
    );
    expect(await screen.findByText('Computador trocado.')).toBeInTheDocument();
  });

  it('bloquear avisa que não tem volta e cancelar não chama a API', async () => {
    const m = montar({ 'sincronizacao.bloquearAparelho': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Computadores', 'Computadores');
    await u.selectOptions(await screen.findByLabelText('Vendedor'), ID_VEND);
    await u.click((await screen.findAllByRole('button', { name: /Bloquear computador de/ }))[0]!);
    expect(screen.getByText(/Isso não tem volta/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(ultima(m, 'sincronizacao.bloquearAparelho')).toBeUndefined();
    await u.click((await screen.findAllByRole('button', { name: /Bloquear computador de/ }))[0]!);
    await u.click(screen.getByRole('button', { name: 'Bloquear computador' }));
    await waitFor(() =>
      expect(ultima(m, 'sincronizacao.bloquearAparelho')).toEqual({ id: ID_PC_ANTIGO }),
    );
  });
});

describe('Envios com problema (tarefa 5.10)', () => {
  it('separa o CPF repetido, tenta de novo e descarta só com motivo', async () => {
    const m = montar({
      'sincronizacao.tentarDeNovo': () => ({ ok: true, dados: {} }),
      'sincronizacao.descartarEnvio': () => ({ ok: true, dados: {} }),
    });
    const u = await abrir(m, 'Envios com problema', 'Envios com problema');
    expect(await screen.findByText('Faltou peça na maleta.')).toBeInTheDocument();
    expect(screen.getByText('Ana Maria')).toBeInTheDocument();
    expect(screen.getByText('123.456.789-09')).toBeInTheDocument();
    // CPF repetido não oferece "Tentar de novo".
    expect(screen.getAllByRole('button', { name: /Tentar de novo o envio/ })).toHaveLength(1);

    await u.click(screen.getByRole('button', { name: /Tentar de novo o envio/ }));
    await waitFor(() => expect(ultima(m, 'sincronizacao.tentarDeNovo')).toEqual({ id: ID_ENVIO }));
    expect(await screen.findByText('Pronto: o envio entrou.')).toBeInTheDocument();

    await u.click((await screen.findAllByRole('button', { name: /Descartar o envio/ }))[0]!);
    await u.click(screen.getByRole('button', { name: 'Descartar' }));
    expect(await screen.findByText('Diga o motivo de descartar este envio.')).toBeInTheDocument();
    expect(ultima(m, 'sincronizacao.descartarEnvio')).toBeUndefined();
    await u.type(screen.getByLabelText('Motivo'), 'Venda repetida');
    await u.click(screen.getByRole('button', { name: 'Descartar' }));
    await waitFor(() =>
      expect(ultima(m, 'sincronizacao.descartarEnvio')).toEqual({
        id: ID_ENVIO,
        motivo: 'Venda repetida',
      }),
    );
  });

  it('nenhuma palavra técnica na tela', async () => {
    const m = montar();
    await abrir(m, 'Envios com problema', 'Envios com problema');
    await screen.findByText('Faltou peça na maleta.');
    expect(document.body.textContent).not.toMatch(/quarentena|sincroniza|offline|aparelho|token/i);
  });
});

describe('Vendas de computador bloqueado (tarefa 5.10)', () => {
  it('aprova depois de confirmar e mostra quando o servidor recusa', async () => {
    const m = montar({
      'sincronizacao.aprovarQuarentena': () => ({
        ok: true,
        dados: { resultado: 'RECUSADO', mensagem: 'Faltou peça na maleta.' },
      }),
    });
    const u = await abrir(m, 'Vendas de computador bloqueado', 'Vendas de computador bloqueado');
    await u.click(await screen.findByRole('button', { name: /Aprovar o envio de/ }));
    expect(screen.getByText(/Ele passa a valer/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Aprovar' }));
    await waitFor(() =>
      expect(ultima(m, 'sincronizacao.aprovarQuarentena')).toEqual({ id: ID_VENDA }),
    );
    expect(await screen.findByText(/Ele foi para Envios com problema/)).toBeInTheDocument();
  });

  it('descartar exige motivo', async () => {
    const m = montar({ 'sincronizacao.descartarQuarentena': () => ({ ok: true, dados: null }) });
    const u = await abrir(m, 'Vendas de computador bloqueado', 'Vendas de computador bloqueado');
    await u.click(await screen.findByRole('button', { name: /Descartar o envio de/ }));
    await u.click(screen.getByRole('button', { name: 'Descartar' }));
    expect(ultima(m, 'sincronizacao.descartarQuarentena')).toBeUndefined();
    await u.type(screen.getByLabelText('Motivo'), 'Venda duvidosa');
    await u.click(screen.getByRole('button', { name: 'Descartar' }));
    await waitFor(() =>
      expect(ultima(m, 'sincronizacao.descartarQuarentena')).toEqual({
        id: ID_VENDA,
        motivo: 'Venda duvidosa',
      }),
    );
  });
});

describe('Clientes alterados pelo vendedor e avisos (tarefa 5.10)', () => {
  it('mostra o valor que era e o que passou a ser', async () => {
    const m = montar();
    await abrir(m, 'Clientes alterados pelo vendedor', 'Clientes alterados pelo vendedor');
    expect(await screen.findByText('Dora')).toBeInTheDocument();
    expect(screen.getByText(/Era 1111; Passou a ser 2222/)).toBeInTheDocument();
  });

  it('o aviso de envios com problema leva à tela certa', async () => {
    window.location.hash = '';
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await screen.findByText('Há 2 envios com problema.');
    await u.click(screen.getByRole('button', { name: 'Abrir' }));
    await screen.findByRole('heading', { name: 'Envios com problema', level: 1 });
  });
});
