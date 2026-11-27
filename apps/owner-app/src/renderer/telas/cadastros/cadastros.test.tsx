// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';

const ID_A = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_B = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

function montar(manipuladores: Record<string, Manipulador>) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () => pagina([{ id: ID_B, nome: 'Joana Representante', codigo: 7 }]),
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
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

async function abrir(m: ReturnType<typeof montar>, item: string, esperar: string | RegExp) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await screen.findByRole('button', { name: item }));
  await screen.findByText(esperar);
  return u;
}

beforeEach(() => {
  window.location.hash = '';
});

describe('Cadastros: lista', () => {
  it('categorias mostram os nomes e o total; o cursor está na busca', async () => {
    const m = montar({
      'categorias.listar': () =>
        pagina([
          { id: ID_A, version: 0, nome: 'Brincos' },
          { id: ID_B, version: 0, nome: 'Anéis' },
        ]),
    });
    await abrir(m, 'Categorias', 'Brincos');
    expect(screen.getByText('2 categorias')).toBeInTheDocument();
    expect(screen.getByLabelText('Buscar pelo nome')).toHaveFocus();
  });

  it('lista vazia e busca sem resultado têm textos diferentes', async () => {
    const m = montar({ 'pracas.listar': () => pagina([]) });
    const u = await abrir(m, 'Praças', /Ainda não há nenhuma praça\./);
    await u.type(screen.getByLabelText('Buscar pelo nome'), 'zzz');
    expect(
      await screen.findByText(
        'Nenhuma praça encontrada. Confira o que você digitou ou limpe a busca.',
      ),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(m.chamadas.filter((c) => c.operacao === 'pracas.listar').at(-1)?.parametros).toEqual({
        pagina: 0,
        tamanho: 20,
        busca: 'zzz',
        situacao: 'ATIVOS',
      }),
    );
  });

  it('semanas mostram datas no formato do Brasil e a situação em português', async () => {
    const m = montar({
      'semanas.listar': () =>
        pagina([
          {
            id: ID_A,
            version: 0,
            numero: 12,
            inicio: '2026-11-09',
            fim: '2026-11-15',
            situacao: 'FECHADA',
          },
        ]),
    });
    await abrir(m, 'Semanas', '09/11/2026');
    const linha = screen.getByRole('row', { name: /09\/11\/2026/ });
    expect(within(linha).getByText('15/11/2026')).toBeInTheDocument();
    expect(within(linha).getByText('Fechada')).toBeInTheDocument();
  });

  it('restrições mostram Sim ou Não, sem a palavra remessa', async () => {
    const m = montar({
      'restricoes.listar': () =>
        pagina([
          { id: ID_A, version: 0, codigo: 'R1', descricao: 'Atraso', bloqueiaRemessa: true },
        ]),
    });
    await abrir(m, 'Restrições', 'Atraso');
    expect(screen.getByText('Impede montar maleta')).toBeInTheDocument();
    expect(screen.queryByText(/remessa/i)).toBeNull();
  });
});

describe('Cadastros: criar e editar', () => {
  it('cria categoria: cursor no nome, Enter envia, id gerado no app', async () => {
    let criada = false;
    const m = montar({
      'categorias.listar': () => pagina(criada ? [{ id: ID_A, version: 0, nome: 'Colares' }] : []),
      'categorias.criar': () => {
        criada = true;
        return { ok: true, dados: { id: ID_A, version: 0, nome: 'Colares' } };
      },
    });
    const u = await abrir(m, 'Categorias', /Ainda não há nenhuma categoria\./);
    await u.click(screen.getByRole('button', { name: 'Nova categoria' }));
    expect(screen.getByLabelText('Nome da categoria')).toHaveFocus();
    await u.keyboard('Colares{Enter}');
    expect(await screen.findByText('Colares foi cadastrada.')).toBeInTheDocument();
    const pedido = m.chamadas.find((c) => c.operacao === 'categorias.criar');
    expect(pedido?.parametros).toMatchObject({ nome: 'Colares' });
    expect(pedido?.parametros?.id).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('o formulário de semana não deixa escolher a situação: a semana só fecha por Fechar semana', async () => {
    const m = montar({ 'semanas.listar': () => pagina([]) });
    const u = await abrir(m, 'Semanas', /Ainda não há nenhuma semana\./);
    await u.click(screen.getByRole('button', { name: 'Nova semana' }));
    expect(screen.getByLabelText('Número da semana')).toBeInTheDocument();
    expect(screen.queryByLabelText('Situação')).toBeNull();
    expect(screen.queryByRole('option', { name: 'Fechada' })).toBeNull();
  });

  it('campo obrigatório vazio mostra o aviso no campo e não chama a API', async () => {
    const m = montar({ 'pracas.listar': () => pagina([]) });
    const u = await abrir(m, 'Praças', /Ainda não há nenhuma praça\./);
    await u.click(screen.getByRole('button', { name: 'Nova praça' }));
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Preencha este campo.')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'pracas.criar')).toBe(false);
  });

  it('cliente: erro do servidor aparece no campo do CPF, com o texto dele', async () => {
    const m = montar({
      'clientes.listar': () => pagina([]),
      'clientes.criar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        codigo: 'DADOS_INVALIDOS',
        mensagem: 'Confira os dados.',
        campos: [{ campo: 'cpf', mensagem: 'Confira o CPF: ele não parece estar certo.' }],
      }),
    });
    const u = await abrir(m, 'Clientes', /Ainda não há nenhum cliente\./);
    await u.click(screen.getByRole('button', { name: 'Novo cliente' }));
    await u.type(screen.getByLabelText('Nome do cliente'), 'Ana');
    await u.type(screen.getByLabelText('Número do documento'), '111');
    await u.type(screen.getByLabelText('Cidade'), 'Foz do Iguaçu');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('Confira o CPF: ele não parece estar certo.'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Número do documento')).toHaveAttribute('aria-invalid', 'true');
    const pedido = m.chamadas.find((c) => c.operacao === 'clientes.criar');
    expect(pedido?.parametros).toMatchObject({
      nome: 'Ana',
      documento: '111',
      documentoTipo: 'CPF',
      endereco: { cidade: 'Foz do Iguaçu' },
    });
  });

  it('cliente: escolhe o representante responsável pela lista', async () => {
    const m = montar({
      'clientes.listar': () => pagina([]),
      'clientes.criar': () => ({ ok: true, dados: { id: ID_A, version: 0, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', /Ainda não há nenhum cliente\./);
    await u.click(screen.getByRole('button', { name: 'Novo cliente' }));
    await u.type(screen.getByLabelText('Nome do cliente'), 'Ana');
    await screen.findByRole('option', { name: 'Joana Representante' });
    await u.selectOptions(screen.getByLabelText('Representante responsável'), ID_B);
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Ana foi cadastrado.');
    expect(m.chamadas.find((c) => c.operacao === 'clientes.criar')?.parametros).toMatchObject({
      vendedorResponsavelId: ID_B,
    });
  });

  it('semana: número e datas vão para a API (a situação nasce Aberta no servidor)', async () => {
    const m = montar({
      'semanas.listar': () => pagina([]),
      'semanas.criar': () => ({ ok: true, dados: {} }),
    });
    const u = await abrir(m, 'Semanas', /Ainda não há nenhuma semana\./);
    await u.click(screen.getByRole('button', { name: 'Nova semana' }));
    await u.type(screen.getByLabelText('Número da semana'), '12');
    await u.type(screen.getByLabelText('Primeiro dia'), '2026-11-09');
    await u.type(screen.getByLabelText('Último dia'), '2026-11-15');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Semana 12 foi cadastrada.');
    expect(m.chamadas.find((c) => c.operacao === 'semanas.criar')?.parametros).toMatchObject({
      numero: 12,
      inicio: '2026-11-09',
      fim: '2026-11-15',
    });
    expect(m.chamadas.find((c) => c.operacao === 'semanas.criar')?.parametros).not.toHaveProperty(
      'situacao',
    );
  });

  it('editar manda a versão lida; 409 mostra o aviso com Atualizar', async () => {
    const m = montar({
      'fornecedores.listar': () =>
        pagina([
          {
            id: ID_A,
            version: 3,
            razaoSocial: 'Ouro Fino',
            cnpj: '',
            endereco: { cidade: 'Foz' },
          },
        ]),
      'fornecedores.editar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CADASTRO_ALTERADO',
        mensagem:
          'Este cadastro foi alterado por outra pessoa. Atualize a tela e faça a alteração de novo.',
      }),
    });
    const u = await abrir(m, 'Fornecedores', 'Ouro Fino');
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    expect(screen.getByLabelText('Cidade')).toHaveValue('Foz');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText(/alterado por outra pessoa/)).toBeInTheDocument();
    expect(m.chamadas.find((c) => c.operacao === 'fornecedores.editar')?.parametros).toMatchObject({
      id: ID_A,
      version: 3,
      razaoSocial: 'Ouro Fino',
    });
    await u.click(screen.getByRole('button', { name: 'Atualizar' }));
    expect(await screen.findByText('Ouro Fino')).toBeInTheDocument();
    expect(screen.queryByLabelText('Cidade')).toBeNull();
  });
});

describe('Cadastros: excluir', () => {
  it('pede confirmação, com foco em Voltar; Voltar não chama a API', async () => {
    const m = montar({
      'pracas.listar': () => pagina([{ id: ID_A, version: 0, nome: 'Centro' }]),
    });
    const u = await abrir(m, 'Praças', 'Centro');
    await u.click(screen.getByRole('button', { name: 'Excluir praça' }));
    const janela = await screen.findByRole('dialog');
    expect(within(janela).getByRole('button', { name: 'Voltar' })).toHaveFocus();
    await u.click(within(janela).getByRole('button', { name: 'Voltar' }));
    expect(m.chamadas.some((c) => c.operacao === 'pracas.excluir')).toBe(false);
  });

  it('confirmar exclui, avisa e atualiza a lista', async () => {
    let excluida = false;
    const m = montar({
      'pracas.listar': () => pagina(excluida ? [] : [{ id: ID_A, version: 0, nome: 'Centro' }]),
      'pracas.excluir': () => {
        excluida = true;
        return { ok: true, dados: undefined };
      },
    });
    const u = await abrir(m, 'Praças', 'Centro');
    await u.click(screen.getByRole('button', { name: 'Excluir praça' }));
    const janela = await screen.findByRole('dialog');
    await u.click(within(janela).getByRole('button', { name: 'Excluir praça' }));
    expect(await screen.findByText('Centro foi excluída.')).toBeInTheDocument();
    expect(await screen.findByText(/Ainda não há nenhuma praça\./)).toBeInTheDocument();
    expect(m.chamadas.find((c) => c.operacao === 'pracas.excluir')?.parametros).toEqual({
      id: ID_A,
    });
  });

  it('praça em uso: mostra o texto do servidor e a praça continua na lista', async () => {
    const m = montar({
      'pracas.listar': () => pagina([{ id: ID_A, version: 0, nome: 'Centro' }]),
      'pracas.excluir': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CADASTRO_EM_USO',
        mensagem:
          'Este cadastro está em uso. Troque a praça ou a restrição dos representantes que a usam e tente de novo.',
      }),
    });
    const u = await abrir(m, 'Praças', 'Centro');
    await u.click(screen.getByRole('button', { name: 'Excluir praça' }));
    const janela = await screen.findByRole('dialog');
    await u.click(within(janela).getByRole('button', { name: 'Excluir praça' }));
    expect(await screen.findByText(/está em uso/)).toBeInTheDocument();
    expect(screen.getByText('Centro')).toBeInTheDocument();
  });
});

describe('Cadastros: ativo e inativo', () => {
  it('mostra só os ativos por padrão e o filtro troca para Inativos e Todos', async () => {
    const m = montar({
      'pracas.listar': (p) => {
        const situacao = p.parametros?.situacao;
        return pagina(
          situacao === 'INATIVOS'
            ? [{ id: ID_B, version: 3, nome: 'Norte', ativo: false }]
            : situacao === 'TODOS'
              ? [
                  { id: ID_A, version: 1, nome: 'Centro', ativo: true },
                  { id: ID_B, version: 3, nome: 'Norte', ativo: false },
                ]
              : [{ id: ID_A, version: 1, nome: 'Centro', ativo: true }],
        );
      },
    });
    const u = await abrir(m, 'Praças', 'Centro');
    expect(screen.queryByText('Norte')).toBeNull();
    expect(screen.getByLabelText('Situação')).toHaveValue('ATIVOS');
    await u.selectOptions(screen.getByLabelText('Situação'), 'TODOS');
    const norte = await screen.findByRole('row', { name: /Norte/ });
    expect(within(norte).getByText('Inativo')).toBeInTheDocument();
    expect(
      within(screen.getByRole('row', { name: /Centro/ })).getByText('Ativo'),
    ).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Situação'), 'INATIVOS');
    await waitFor(() => expect(screen.queryByText('Centro')).toBeNull());
    expect(screen.getByText('Norte')).toBeInTheDocument();
  });

  it('Inativar pede confirmação que explica que pode voltar, e manda a versão lida', async () => {
    let inativa = false;
    const m = montar({
      'pracas.listar': () =>
        pagina(inativa ? [] : [{ id: ID_A, version: 4, nome: 'Centro', ativo: true }]),
      'pracas.inativar': () => {
        inativa = true;
        return { ok: true, dados: { id: ID_A, version: 5, nome: 'Centro', ativo: false } };
      },
    });
    const u = await abrir(m, 'Praças', 'Centro');
    await u.click(screen.getByRole('button', { name: 'Inativar praça' }));
    const janela = await screen.findByRole('dialog');
    expect(within(janela).getByText(/Pode voltar a qualquer hora/)).toBeInTheDocument();
    expect(within(janela).getByText(/continua nos relatórios/)).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Voltar' }));
    expect(m.chamadas.some((c) => c.operacao === 'pracas.inativar')).toBe(false);

    await u.click(screen.getByRole('button', { name: 'Inativar praça' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Inativar praça' }),
    );
    expect(
      await screen.findByText('Centro foi inativada. Pode voltar a qualquer hora.'),
    ).toBeInTheDocument();
    expect(m.chamadas.find((c) => c.operacao === 'pracas.inativar')?.parametros).toEqual({
      id: ID_A,
      version: 4,
    });
  });

  it('Reativar não pede confirmação e avisa que voltou', async () => {
    const m = montar({
      'clientes.listar': () => pagina([{ id: ID_A, version: 2, nome: 'Ana', ativo: false }]),
      'clientes.reativar': () => ({
        ok: true,
        dados: { id: ID_A, version: 3, nome: 'Ana', ativo: true },
      }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    expect(screen.queryByRole('button', { name: 'Inativar cliente' })).toBeNull();
    await u.click(screen.getByRole('button', { name: 'Reativar cliente' }));
    expect(await screen.findByText('Ana voltou a ficar ativo.')).toBeInTheDocument();
    expect(m.chamadas.find((c) => c.operacao === 'clientes.reativar')?.parametros).toEqual({
      id: ID_A,
      version: 2,
    });
  });

  it('se outra pessoa mexeu antes, mostra o aviso do servidor e não diz que inativou', async () => {
    const m = montar({
      'categorias.listar': () => pagina([{ id: ID_A, version: 1, nome: 'Brincos', ativo: true }]),
      'categorias.inativar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CADASTRO_ALTERADO',
        mensagem: 'Outra pessoa alterou este cadastro. Atualize a tela e tente de novo.',
      }),
    });
    const u = await abrir(m, 'Categorias', 'Brincos');
    await u.click(screen.getByRole('button', { name: 'Inativar categoria' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Inativar categoria' }),
    );
    expect(await screen.findByText(/Outra pessoa alterou/)).toBeInTheDocument();
    expect(screen.queryByText(/foi inativada/)).toBeNull();
  });

  it('semanas não têm filtro de situação nem botão de inativar', async () => {
    const m = montar({
      'semanas.listar': () =>
        pagina([
          {
            id: ID_A,
            version: 0,
            numero: 3,
            inicio: '2026-11-09',
            fim: '2026-11-15',
            situacao: 'ABERTA',
          },
        ]),
    });
    await abrir(m, 'Semanas', '09/11/2026');
    expect(screen.queryByRole('button', { name: /Inativar/ })).toBeNull();
    expect(m.chamadas.find((c) => c.operacao === 'semanas.listar')?.parametros).not.toHaveProperty(
      'situacao',
    );
  });
});

describe('Cadastros: data de nascimento do cliente', () => {
  it('o campo é opcional, aparece ao editar e vai para a API', async () => {
    const m = montar({
      'clientes.listar': () =>
        pagina([{ id: ID_A, version: 2, nome: 'Ana', dataNascimento: '1990-05-17', ativo: true }]),
      'clientes.editar': () => ({ ok: true, dados: { id: ID_A, version: 3, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    const campo = await screen.findByLabelText('Data de nascimento');
    expect(campo).toHaveValue('1990-05-17');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Os dados de Ana foram salvos.');
    expect(m.chamadas.find((c) => c.operacao === 'clientes.editar')?.parametros).toMatchObject({
      dataNascimento: '1990-05-17',
      version: 2,
    });
  });

  it('erro do servidor (data no futuro) aparece no campo, em português', async () => {
    const m = montar({
      'clientes.listar': () => pagina([]),
      'clientes.criar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        codigo: 'DADOS_INVALIDOS',
        mensagem: 'Confira os dados.',
        campos: [
          { campo: 'dataNascimento', mensagem: 'A data de nascimento não pode ser no futuro.' },
        ],
      }),
    });
    const u = await abrir(m, 'Clientes', /Ainda não há nenhum cliente\./);
    await u.click(screen.getByRole('button', { name: 'Novo cliente' }));
    await u.type(screen.getByLabelText('Nome do cliente'), 'Ana');
    await u.type(screen.getByLabelText('Data de nascimento'), '2999-01-01');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('A data de nascimento não pode ser no futuro.'),
    ).toBeInTheDocument();
  });
});

describe('Cadastros: cliente do consignado (tarefa 9.7)', () => {
  it('novo cliente: comissão padrão 30, documento de outro tipo (CI), indicado por e como conheceu vão para a API', async () => {
    const m = montar({
      'clientes.listar': () => pagina([]),
      'clientes.criar': () => ({ ok: true, dados: { id: ID_A, version: 0, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', /Ainda não há nenhum cliente\./);
    await u.click(screen.getByRole('button', { name: 'Novo cliente' }));
    expect(screen.getByLabelText('Comissão do cliente (%)')).toHaveValue('30');
    expect(screen.getByLabelText('Tipo do documento')).toHaveValue('CPF');
    await u.type(screen.getByLabelText('Nome do cliente'), 'Ana');
    await u.selectOptions(screen.getByLabelText('Tipo do documento'), 'CI');
    await u.type(screen.getByLabelText('Número do documento'), '1.234.567');
    await u.clear(screen.getByLabelText('Comissão do cliente (%)'));
    await u.type(screen.getByLabelText('Comissão do cliente (%)'), '32,5');
    await u.type(screen.getByLabelText('Indicado por'), 'Dona Rosa');
    await u.type(screen.getByLabelText('Como conheceu a loja'), 'Feira do bairro');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Ana foi cadastrado.');
    expect(m.chamadas.find((c) => c.operacao === 'clientes.criar')?.parametros).toMatchObject({
      nome: 'Ana',
      documentoTipo: 'CI',
      documento: '1.234.567',
      comissaoPercentual: '32.5',
      indicadoPorNome: 'Dona Rosa',
      comoConheceu: 'Feira do bairro',
    });
  });

  it('comissão fora de 0 a 40 não vai para a API e diz o que fazer', async () => {
    const m = montar({
      'clientes.listar': () => pagina([]),
      'clientes.criar': () => ({ ok: true, dados: {} }),
    });
    const u = await abrir(m, 'Clientes', /Ainda não há nenhum cliente\./);
    await u.click(screen.getByRole('button', { name: 'Novo cliente' }));
    await u.type(screen.getByLabelText('Nome do cliente'), 'Ana');
    await u.clear(screen.getByLabelText('Comissão do cliente (%)'));
    await u.type(screen.getByLabelText('Comissão do cliente (%)'), '45');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('Digite a comissão de 0 a 40, por exemplo 30 ou 32,5.'),
    ).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'clientes.criar')).toBe(false);
  });

  it('cliente antigo só com CPF mostra o CPF no campo do documento, e a comissão sem zeros', async () => {
    const m = montar({
      'clientes.listar': () =>
        pagina([
          {
            id: ID_A,
            version: 2,
            nome: 'Ana',
            cpf: '52998224725',
            comissaoPercentual: '30.00',
            ativo: true,
          },
        ]),
      'clientes.editar': () => ({ ok: true, dados: { id: ID_A, version: 3, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    expect(await screen.findByLabelText('Número do documento')).toHaveValue('52998224725');
    expect(screen.getByLabelText('Comissão do cliente (%)')).toHaveValue('30');
  });

  it('D10: com representante já definido ele aparece travado; sem representante dá para escolher', async () => {
    const m = montar({
      'clientes.listar': () =>
        pagina([
          { id: ID_A, version: 2, nome: 'Ana', vendedorResponsavelId: ID_B, ativo: true },
          { id: ID_B, version: 1, nome: 'Bia', ativo: true },
        ]),
      'clientes.editar': () => ({ ok: true, dados: { id: ID_A, version: 3, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    const botoes = screen.getAllByRole('button', { name: 'Editar' });
    await u.click(botoes[0]!);
    await screen.findByRole('option', { name: 'Joana Representante' });
    expect(screen.getByLabelText('Representante responsável')).toBeDisabled();
    expect(
      screen.getByText('Depois de escolhido, o representante do cliente não muda.'),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Cancelar' }));
    await u.click(screen.getAllByRole('button', { name: 'Editar' })[1]!);
    await screen.findByRole('option', { name: 'Joana Representante' });
    expect(screen.getByLabelText('Representante responsável')).toBeEnabled();
  });

  it('cliente indicado por outro cliente cadastrado mantém o indicador ao salvar', async () => {
    const m = montar({
      'clientes.listar': () =>
        pagina([{ id: ID_A, version: 2, nome: 'Ana', indicadoPorClienteId: ID_B, ativo: true }]),
      'clientes.editar': () => ({ ok: true, dados: { id: ID_A, version: 3, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    await screen.findByLabelText('Indicado por');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Os dados de Ana foram salvos.');
    expect(m.chamadas.find((c) => c.operacao === 'clientes.editar')?.parametros).toMatchObject({
      indicadoPorClienteId: ID_B,
    });
  });

  it('apagar "Indicado por" e "Como conheceu" manda os campos vazios; sem mexer, não manda nada deles', async () => {
    const m = montar({
      'clientes.listar': () =>
        pagina([
          {
            id: ID_A,
            version: 2,
            nome: 'Ana',
            indicadoPorNome: 'Dona Rosa',
            comoConheceu: 'Feira',
            ativo: true,
          },
        ]),
      'clientes.editar': () => ({ ok: true, dados: { id: ID_A, version: 3, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    await u.clear(await screen.findByLabelText('Indicado por'));
    await u.clear(screen.getByLabelText('Como conheceu a loja'));
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Os dados de Ana foram salvos.');
    expect(m.chamadas.find((c) => c.operacao === 'clientes.editar')?.parametros).toMatchObject({
      indicadoPorNome: '',
      comoConheceu: '',
    });
  });

  it('cliente que nunca teve indicação não manda os campos vazios ao salvar', async () => {
    const m = montar({
      'clientes.listar': () => pagina([{ id: ID_A, version: 2, nome: 'Ana', ativo: true }]),
      'clientes.editar': () => ({ ok: true, dados: { id: ID_A, version: 3, nome: 'Ana' } }),
    });
    const u = await abrir(m, 'Clientes', 'Ana');
    await u.click(screen.getByRole('button', { name: 'Editar' }));
    await screen.findByLabelText('Indicado por');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Os dados de Ana foram salvos.');
    const p = m.chamadas.find((c) => c.operacao === 'clientes.editar')?.parametros as Record<
      string,
      unknown
    >;
    expect('indicadoPorNome' in p).toBe(false);
    expect('comoConheceu' in p).toBe(false);
  });
});
