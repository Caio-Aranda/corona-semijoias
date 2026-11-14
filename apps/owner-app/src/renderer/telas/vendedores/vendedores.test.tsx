// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';

const entregar = vi.hoisted(() => vi.fn());
vi.mock('../../nucleo/arquivos', async (importar) => ({
  ...(await importar<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: entregar,
}));

const ID_V = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_PRACA = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_OUTRA_PRACA = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_REF = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_DOC = '4e4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const JOANA = {
  id: ID_V,
  version: 3,
  codigo: 12,
  nome: 'Joana Souza',
  ativo: true,
  telefone1: '(11) 99999-0000',
  pracaId: ID_PRACA,
  limiteConsignacao: '1500.00',
  comissaoPercentual: '12.50',
  estrangeiro: false,
};

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'pracas.listar': () =>
      pagina([
        { id: ID_PRACA, version: 0, nome: 'Centro' },
        { id: ID_OUTRA_PRACA, version: 0, nome: 'Fronteira' },
      ]),
    'restricoes.listar': () => pagina([]),
    'usuarios.listar': () => pagina([]),
    'vendedores.listar': () => pagina([JOANA]),
    'vendedores.ver': () => ({ ok: true, dados: JOANA }),
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

const ultima = (m: ReturnType<typeof montar>, op: string) =>
  m.chamadas.filter((c) => c.operacao === op).at(-1)?.parametros;

async function abrirLista(m: ReturnType<typeof montar>) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Vendedores' }));
  await screen.findByText('Joana Souza');
  return u;
}

async function abrirFicha(m: ReturnType<typeof montar>) {
  const u = await abrirLista(m);
  await u.click(screen.getByRole('button', { name: 'Abrir cadastro' }));
  await screen.findByLabelText('Nome do vendedor');
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  entregar.mockClear();
});

describe('Vendedores: lista', () => {
  it('mostra código, nome, telefone, praça e situação', async () => {
    const m = montar();
    await abrirLista(m);
    const linha = screen.getByRole('row', { name: /Joana Souza/ });
    expect(within(linha).getByText('12')).toBeInTheDocument();
    expect(within(linha).getByText('(11) 99999-0000')).toBeInTheDocument();
    expect(await within(linha).findByText('Centro')).toBeInTheDocument();
    expect(within(linha).getByText('Ativo')).toBeInTheDocument();
  });

  it('mostra o limite de cada vendedor: o valor, "Sem limite" ou "Limite não definido"', async () => {
    const m = montar({
      'vendedores.listar': () =>
        pagina([
          JOANA,
          { ...JOANA, id: ID_REF, nome: 'Ana Livre', limiteConsignacao: null, semLimite: true },
          { ...JOANA, id: ID_DOC, nome: 'Bia Antiga', limiteConsignacao: null, semLimite: false },
          { ...JOANA, id: ID_PRACA, nome: 'Carla Zero', limiteConsignacao: '0.00', semLimite: false },
        ]),
    });
    await abrirLista(m);
    expect(screen.getByRole('columnheader', { name: 'Limite de consignação' })).toBeInTheDocument();
    const celula = (nome: RegExp, texto: string) =>
      within(screen.getByRole('row', { name: nome })).getByText(texto);
    expect(celula(/Joana Souza/, 'R$ 1.500,00')).toBeInTheDocument();
    expect(celula(/Ana Livre/, 'Sem limite')).toBeInTheDocument();
    expect(celula(/Bia Antiga/, 'Limite não definido')).toBeInTheDocument();
    // Zero vale zero: nunca aparece como "Sem limite".
    expect(celula(/Carla Zero/, 'R$ 0,00')).toBeInTheDocument();
  });

  it('a situação vai à API e a praça separa a lista na tela', async () => {
    const m = montar({
      'vendedores.listar': () =>
        pagina([
          JOANA,
          { ...JOANA, id: ID_REF, nome: 'Ana da Fronteira', pracaId: ID_OUTRA_PRACA },
        ]),
    });
    const u = await abrirLista(m);
    expect(await screen.findByText('Ana da Fronteira')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Situação'), 'Ativos');
    await waitFor(() =>
      expect(ultima(m, 'vendedores.listar')).toMatchObject({ situacao: 'ATIVOS' }),
    );
    await u.selectOptions(screen.getByLabelText('Praça'), 'Fronteira');
    await waitFor(() => expect(screen.queryByText('Joana Souza')).toBeNull());
    expect(screen.getByText('Ana da Fronteira')).toBeInTheDocument();
    expect(ultima(m, 'vendedores.listar')).toMatchObject({ pagina: 0, tamanho: 100 });
  });

  it('busca sem resultado tem texto próprio', async () => {
    const m = montar({ 'vendedores.listar': () => pagina([]) });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Vendedores' }));
    await screen.findByText('Ainda não há nenhum vendedor.');
    await u.type(screen.getByLabelText('Buscar por nome, CPF, CNPJ, telefone ou código'), 'zzz');
    expect(
      await screen.findByText(
        'Nenhum vendedor encontrado. Confira o que você digitou ou limpe a busca.',
      ),
    ).toBeInTheDocument();
  });
});

describe('Vendedores: ficha, aba Cadastro', () => {
  it('vendedor novo: só o Cadastro está liberado; salvar manda limite e comissão em reais e libera as abas', async () => {
    const m = montar({
      'vendedores.criar': (p) => ({
        ok: true,
        dados: { ...JOANA, nome: (p.parametros as { nome: string }).nome, version: 0 },
      }),
    });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Vendedores' }));
    await u.click(await screen.findByRole('button', { name: 'Novo vendedor' }));
    expect(await screen.findByText(/Salve o cadastro do vendedor/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Endereço' })).toBeDisabled();
    expect(screen.getByRole('tab', { name: 'Documentos' })).toBeDisabled();
    expect(screen.getByLabelText('Nome do vendedor')).toHaveFocus();

    await u.type(screen.getByLabelText('Nome do vendedor'), 'Joana Souza');
    await u.type(screen.getByLabelText('Limite de consignação'), '1.500,00');
    await u.type(screen.getByLabelText('Comissão (%)'), '12,5');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));

    expect(
      await screen.findByText(
        'Joana Souza foi cadastrado. Agora você pode preencher as outras abas.',
      ),
    ).toBeInTheDocument();
    expect(ultima(m, 'vendedores.criar')).toEqual({
      nome: 'Joana Souza',
      estrangeiro: false,
      limiteConsignacao: '1500.00',
      semLimite: false,
      comissaoPercentual: '12.50',
    });
    expect(screen.getByRole('tab', { name: 'Endereço' })).toBeEnabled();
  });

  it('o limite é obrigatório: sem valor e sem "Sem limite" o vendedor não é salvo', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Vendedores' }));
    await u.click(await screen.findByRole('button', { name: 'Novo vendedor' }));
    await u.type(await screen.findByLabelText('Nome do vendedor'), 'Joana Souza');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('Informe o limite em reais ou marque "Sem limite".'),
    ).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'vendedores.criar')).toBe(false);
  });

  it('"Sem limite" desabilita o valor e manda semLimite, sem limite em reais', async () => {
    const m = montar({
      'vendedores.criar': (p) => ({
        ok: true,
        dados: { ...JOANA, nome: (p.parametros as { nome: string }).nome, version: 0 },
      }),
    });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Vendedores' }));
    await u.click(await screen.findByRole('button', { name: 'Novo vendedor' }));
    await u.type(await screen.findByLabelText('Nome do vendedor'), 'Joana Souza');
    await u.type(screen.getByLabelText('Limite de consignação'), '300,00');
    await u.click(screen.getByLabelText('Sem limite'));
    expect(screen.getByLabelText('Limite de consignação')).toBeDisabled();
    expect(screen.getByLabelText('Limite de consignação')).toHaveValue('');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText(/foi cadastrado/);
    expect(ultima(m, 'vendedores.criar')).toEqual({
      nome: 'Joana Souza',
      estrangeiro: false,
      semLimite: true,
    });
  });

  it('o limite aparece também nas três moedas', async () => {
    const m = montar({
      'cotacao.hoje': () => ({
        ok: true,
        dados: {
          disponivel: true,
          cotacao: {
            dataCotacao: '2026-11-06',
            dolar: '5.50130000',
            guarani: '0.00089500',
            deDiaAnterior: false,
          },
        },
      }),
    });
    const u = await abrirFicha(m);
    expect(screen.getByLabelText('Limite de consignação')).toHaveValue('1500,00');
    await u.clear(screen.getByLabelText('Limite de consignação'));
    await u.type(screen.getByLabelText('Limite de consignação'), '500');
    expect(await screen.findByText(/US\$/)).toBeInTheDocument();
  });

  it('comissão fora de 0 a 100 e valor inválido erram no campo, sem chamar a API', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    const comissao = screen.getByLabelText('Comissão (%)');
    await u.clear(comissao);
    await u.type(comissao, '150');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('A comissão deve ficar entre 0 e 100.')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'vendedores.editar')).toBe(false);
  });

  it('edita mandando a versão lida; erro de campo do servidor aparece no campo', async () => {
    const m = montar({
      'vendedores.editar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Confira os campos.',
        campos: [
          {
            campo: 'cpfCnpj',
            mensagem: 'Confira o CPF ou o CNPJ: o número não parece estar certo.',
          },
        ],
      }),
    });
    const u = await abrirFicha(m);
    await u.type(screen.getByLabelText('CPF ou CNPJ'), '123');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('Confira o CPF ou o CNPJ: o número não parece estar certo.'),
    ).toBeInTheDocument();
    expect(ultima(m, 'vendedores.editar')).toMatchObject({
      id: ID_V,
      version: 3,
      cpfCnpj: '123',
      limiteConsignacao: '1500.00',
      pracaId: ID_PRACA,
    });
  });

  it('409 de versão: diz que outra pessoa alterou e oferece Atualizar', async () => {
    const m = montar({
      'vendedores.editar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CADASTRO_ALTERADO',
        mensagem:
          'Este cadastro foi alterado por outra pessoa. Atualize a tela e faça a alteração de novo.',
      }),
    });
    const u = await abrirFicha(m);
    await u.type(screen.getByLabelText('Naturalidade'), 'Foz');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText(/alterado por outra pessoa/)).toBeInTheDocument();
    const antes = m.chamadas.filter((c) => c.operacao === 'vendedores.ver').length;
    await u.click(screen.getByRole('button', { name: 'Atualizar' }));
    await waitFor(() =>
      expect(m.chamadas.filter((c) => c.operacao === 'vendedores.ver').length).toBeGreaterThan(
        antes,
      ),
    );
  });

  it('sair com alteração não salva pergunta antes; continuar editando mantém o que foi digitado', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.type(screen.getByLabelText('Naturalidade'), 'Foz');
    await u.click(screen.getByRole('button', { name: 'Voltar para a lista' }));
    expect(await screen.findByText('Há alterações que não foram salvas.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Continuar editando' }));
    expect(screen.getByLabelText('Naturalidade')).toHaveValue('Foz');
    await u.click(screen.getByRole('button', { name: 'Voltar para a lista' }));
    await u.click(screen.getByRole('button', { name: 'Sair sem salvar' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('sem alteração, voltar sai direto; o menu também pergunta quando há alteração', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.type(screen.getByLabelText('Naturalidade'), 'Foz');
    await u.click(screen.getByRole('button', { name: 'Clientes' }));
    expect(await screen.findByText('Há alterações que não foram salvas.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Continuar editando' }));
    await u.clear(screen.getByLabelText('Naturalidade'));
    await u.click(screen.getByRole('button', { name: 'Voltar para a lista' }));
    expect(screen.queryByText('Há alterações que não foram salvas.')).toBeNull();
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('desativar pede confirmação e chama inativar; depois oferece reativar', async () => {
    const m = montar({
      'vendedores.inativar': () => ({ ok: true, dados: { ...JOANA, ativo: false, version: 4 } }),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Desativar vendedor' }));
    expect(await screen.findByText('Desativar Joana Souza?')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'vendedores.inativar')).toBe(false);
    const janela = screen.getByRole('dialog');
    await u.click(within(janela).getByRole('button', { name: 'Desativar vendedor' }));
    expect(await screen.findByText('Joana Souza foi desativado.')).toBeInTheDocument();
    expect(ultima(m, 'vendedores.inativar')).toEqual({ id: ID_V });
    expect(screen.getByRole('button', { name: 'Reativar vendedor' })).toBeInTheDocument();
  });

  it('mostra o acesso ligado ao vendedor só como informação', async () => {
    const m = montar({
      'usuarios.listar': () =>
        pagina([
          {
            id: ID_REF,
            version: 0,
            nome: 'Joana',
            login: 'joana',
            perfil: 'VENDEDOR',
            vendedorId: ID_V,
            ativo: true,
          },
        ]),
    });
    await abrirFicha(m);
    expect(await screen.findByText(/Usuário joana\./)).toBeInTheDocument();
  });

  it('sem acesso ligado, diz onde criar', async () => {
    const m = montar();
    await abrirFicha(m);
    expect(await screen.findByText(/ainda não tem acesso/)).toBeInTheDocument();
  });
});

describe('Vendedores: outras abas', () => {
  it('Endereço busca a aba e grava só ela, com a versão lida (aba nova = 0)', async () => {
    const m = montar({
      'vendedores.aba.ver': () => ({
        ok: true,
        dados: { cidade: 'Foz', valorResidencia: '800.00', version: 0 },
      }),
      'vendedores.aba.gravar': () => ({ ok: true, dados: { cidade: 'Foz', version: 1 } }),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('tab', { name: 'Endereço' }));
    expect(await screen.findByLabelText('Cidade')).toHaveValue('Foz');
    expect(ultima(m, 'vendedores.aba.ver')).toEqual({ id: ID_V, aba: 'endereco' });
    expect(screen.getByLabelText('Valor da moradia')).toHaveValue('800,00');
    await u.type(screen.getByLabelText('Rua'), 'Rua das Flores');
    await u.click(
      within(screen.getByRole('tabpanel', { name: /Endereço/ })).getByRole('button', {
        name: 'Salvar',
      }),
    );
    expect(await screen.findByText('Os dados desta aba foram salvos.')).toBeInTheDocument();
    expect(ultima(m, 'vendedores.aba.gravar')).toEqual({
      id: ID_V,
      aba: 'endereco',
      logradouro: 'Rua das Flores',
      cidade: 'Foz',
      valorResidencia: '800.00',
      version: 0,
    });
  });

  it('a aba com alteração não salva fica marcada e continua com o que foi digitado ao trocar de aba', async () => {
    const m = montar({
      'vendedores.aba.ver': () => ({ ok: true, dados: { version: 0 } }),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('tab', { name: 'Trabalho' }));
    await u.type(await screen.findByLabelText('Empresa'), 'Loja do Centro');
    expect(screen.getByRole('tab', { name: /Trabalho/ })).toHaveTextContent('•');
    await u.click(screen.getByRole('tab', { name: 'Cadastro' }));
    await u.click(screen.getByRole('tab', { name: /Trabalho/ }));
    expect(screen.getByLabelText('Empresa')).toHaveValue('Loja do Centro');
  });

  it('Observações é um texto livre da própria aba', async () => {
    const m = montar({
      'vendedores.aba.ver': () => ({
        ok: true,
        dados: { texto: 'Prefere visitas à tarde.', version: 2 },
      }),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('tab', { name: 'Observações' }));
    expect(await screen.findByLabelText('Observações sobre o vendedor')).toHaveValue(
      'Prefere visitas à tarde.',
    );
  });

  it('Referências: lista, cria e exclui com confirmação', async () => {
    let itens = [
      { id: ID_REF, version: 1, nome: 'Maria Lima', parentesco: 'Vizinha', telefone: '111' },
    ];
    const m = montar({
      'vendedores.referencias.listar': () => ({ ok: true, dados: itens }),
      'vendedores.referencias.criar': (p) => {
        const novo = {
          id: ID_DOC,
          version: 0,
          ...(p.parametros as object),
        } as (typeof itens)[number];
        itens = [...itens, novo];
        return { ok: true, dados: novo };
      },
      'vendedores.referencias.excluir': () => {
        itens = [];
        return { ok: true, dados: null };
      },
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('tab', { name: 'Referências' }));
    expect(await screen.findByText('Maria Lima')).toBeInTheDocument();

    await u.click(screen.getByRole('button', { name: 'Nova referência' }));
    await u.type(screen.getByLabelText('Nome da pessoa'), 'Pedro Alves');
    await u.click(screen.getByRole('button', { name: 'Salvar referência' }));
    expect(await screen.findByText('A referência foi salva.')).toBeInTheDocument();
    expect(ultima(m, 'vendedores.referencias.criar')).toEqual({ id: ID_V, nome: 'Pedro Alves' });
    expect(await screen.findByText('Pedro Alves')).toBeInTheDocument();

    await u.click(screen.getAllByRole('button', { name: 'Excluir referência' })[0]!);
    expect(await screen.findByText('Excluir a referência Maria Lima?')).toBeInTheDocument();
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir referência' }),
    );
    expect(await screen.findByText('A referência foi excluída.')).toBeInTheDocument();
    expect(ultima(m, 'vendedores.referencias.excluir')).toEqual({ id: ID_V, referenciaId: ID_REF });
  });

  it('Referências: nome é obrigatório e fica no campo', async () => {
    const m = montar({ 'vendedores.referencias.listar': () => ({ ok: true, dados: [] }) });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('tab', { name: 'Referências' }));
    await u.click(await screen.findByRole('button', { name: 'Nova referência' }));
    await u.click(screen.getByRole('button', { name: 'Salvar referência' }));
    expect(await screen.findByText('Preencha este campo.')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'vendedores.referencias.criar')).toBe(false);
  });
});

describe('Vendedores: documentos', () => {
  const doc = {
    id: ID_DOC,
    version: 0,
    descricao: 'RG',
    nomeOriginal: 'rg.pdf',
    tipoConteudo: 'application/pdf',
    tamanhoBytes: 2048,
    enviadoEm: '2026-11-09T15:00:00Z',
  };

  async function abrirDocumentos(m: ReturnType<typeof montar>) {
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('tab', { name: 'Documentos' }));
    return u;
  }

  it('lista com nome, tamanho e data do Brasil', async () => {
    const m = montar({ 'vendedores.documentos.listar': () => ({ ok: true, dados: [doc] }) });
    await abrirDocumentos(m);
    const linha = await screen.findByRole('row', { name: /rg\.pdf/ });
    expect(within(linha).getByText('2 KB')).toBeInTheDocument();
    expect(within(linha).getByText('09/11/2026')).toBeInTheDocument();
  });

  it('recusa tipo errado e arquivo grande demais, com o texto do glossário e sem chamar a API', async () => {
    const m = montar({ 'vendedores.documentos.listar': () => ({ ok: true, dados: [] }) });
    const u = await abrirDocumentos(m);
    const campo = await screen.findByLabelText('Arquivo');
    fireEvent.change(campo, { target: { files: [new File(['x'], 'virus.exe')] } });
    await u.click(screen.getByRole('button', { name: 'Enviar documento' }));
    expect(
      await screen.findByText(/Este tipo de arquivo não pode ser enviado\. Envie uma foto/),
    ).toBeInTheDocument();

    const grande = new File(['x'], 'foto.png');
    Object.defineProperty(grande, 'size', { value: 10 * 1024 * 1024 + 1 });
    fireEvent.change(campo, { target: { files: [grande] } });
    await u.click(screen.getByRole('button', { name: 'Enviar documento' }));
    expect(await screen.findByText(/Este arquivo é grande demais/)).toBeInTheDocument();

    fireEvent.change(campo, { target: { files: [new File([], 'vazio.pdf')] } });
    await u.click(screen.getByRole('button', { name: 'Enviar documento' }));
    expect(await screen.findByText('Este arquivo está vazio. Escolha outro.')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'vendedores.documentos.enviar')).toBe(false);
  });

  it('exige escolher o arquivo', async () => {
    const m = montar({ 'vendedores.documentos.listar': () => ({ ok: true, dados: [] }) });
    const u = await abrirDocumentos(m);
    await u.click(await screen.findByRole('button', { name: 'Enviar documento' }));
    expect(await screen.findByText('Escolha o arquivo que será enviado.')).toBeInTheDocument();
  });

  it('envia o arquivo como bytes, nunca como texto', async () => {
    const m = montar({
      'vendedores.documentos.listar': () => ({ ok: true, dados: [] }),
      'vendedores.documentos.enviar': () => ({ ok: true, dados: doc }),
    });
    const u = await abrirDocumentos(m);
    await u.type(await screen.findByLabelText('O que é este documento'), 'RG');
    await u.upload(
      screen.getByLabelText('Arquivo'),
      new File([new Uint8Array([1, 2, 3])], 'rg.pdf'),
    );
    await u.click(screen.getByRole('button', { name: 'Enviar documento' }));
    expect(await screen.findByText('O documento foi enviado.')).toBeInTheDocument();
    const pedido = ultima(m, 'vendedores.documentos.enviar') as {
      id: string;
      descricao: string;
      arquivo: { nome: string; bytes: Uint8Array };
    };
    expect(pedido.id).toBe(ID_V);
    expect(pedido.descricao).toBe('RG');
    expect(pedido.arquivo.nome).toBe('rg.pdf');
    expect(pedido.arquivo.bytes).toBeInstanceOf(Uint8Array);
    expect([...pedido.arquivo.bytes]).toEqual([1, 2, 3]);
  });

  it('baixa entregando o arquivo recebido; excluir pede confirmação', async () => {
    const bytes = new Uint8Array([9, 9]);
    const m = montar({
      'vendedores.documentos.listar': () => ({ ok: true, dados: [doc] }),
      'vendedores.documentos.baixar': () => ({
        ok: true,
        dados: { nome: 'rg.pdf', tipo: 'application/pdf', bytes },
      }),
      'vendedores.documentos.excluir': () => ({ ok: true, dados: null }),
    });
    const u = await abrirDocumentos(m);
    await u.click(await screen.findByRole('button', { name: 'Baixar' }));
    await waitFor(() => expect(entregar).toHaveBeenCalledWith('rg.pdf', 'application/pdf', bytes));
    expect(ultima(m, 'vendedores.documentos.baixar')).toEqual({ id: ID_V, documentoId: ID_DOC });

    await u.click(screen.getByRole('button', { name: 'Excluir documento' }));
    expect(await screen.findByText('Excluir RG?')).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'vendedores.documentos.excluir')).toBe(false);
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Excluir documento' }),
    );
    expect(await screen.findByText('O documento foi excluído.')).toBeInTheDocument();
  });

  it('recusa do servidor (tipo pelo conteúdo) aparece com o texto dele', async () => {
    const m = montar({
      'vendedores.documentos.listar': () => ({ ok: true, dados: [] }),
      'vendedores.documentos.enviar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Este tipo de arquivo não pode ser enviado.',
      }),
    });
    const u = await abrirDocumentos(m);
    await u.upload(await screen.findByLabelText('Arquivo'), new File(['x'], 'rg.pdf'));
    await u.click(screen.getByRole('button', { name: 'Enviar documento' }));
    expect(
      await screen.findByText('Este tipo de arquivo não pode ser enviado.'),
    ).toBeInTheDocument();
  });
});
