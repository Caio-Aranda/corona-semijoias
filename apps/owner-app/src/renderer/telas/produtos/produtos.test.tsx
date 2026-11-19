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
import { esquecerImagens } from '../../componentes/ImagemDoProduto';

const ID_P = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_OUTRO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const ID_CAT = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_FORN = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_FOTO = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const HASH = 'a'.repeat(64);

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const ANEL = {
  id: ID_P,
  version: 3,
  nome: 'Anel Solitário',
  referencia: 'R-10',
  categoriaId: ID_CAT,
  precoVenda: 70,
  precoCusto: 10,
  estoqueMinimo: 2,
  estoqueAtual: 0,
  ativo: true,
  codigos: [{ id: ID_FOTO, codigo: '0012', tipo: 'LOJA', principal: true, ativo: true }],
  fotoPrincipal: { hash: HASH },
};
const BRINCO = {
  ...ANEL,
  id: ID_OUTRO,
  nome: 'Brinco de Argola',
  codigos: [{ id: ID_OUTRO, codigo: 'B-7', tipo: 'FORNECEDOR', principal: true, ativo: true }],
  fotoPrincipal: null,
};

const naoAchou: ResultadoDeChamada = {
  ok: false,
  motivo: 'erro',
  status: 404,
  mensagem: 'Não achou.',
};

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'categorias.listar': () => pagina([{ id: ID_CAT, version: 0, nome: 'Anéis' }]),
    'fornecedores.listar': () => pagina([{ id: ID_FORN, version: 0, razaoSocial: 'Ouro Ltda' }]),
    'produtos.listar': () => pagina([ANEL, BRINCO]),
    'produtos.ver': () => ({ ok: true, dados: ANEL }),
    'produtos.porCodigo': () => naoAchou,
    'produtos.fotos.imagem': () => ({
      ok: true,
      dados: { nome: 'x.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
    }),
    'produtos.fotos.listar': () => ({
      ok: true,
      dados: [{ id: ID_FOTO, hash: HASH, principal: true, ordem: 0 }],
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
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

const ultima = (m: ReturnType<typeof montar>, op: string) =>
  m.chamadas.filter((c) => c.operacao === op).at(-1)?.parametros;
const quantas = (m: ReturnType<typeof montar>, op: string) =>
  m.chamadas.filter((c) => c.operacao === op).length;

async function abrirLista(m: ReturnType<typeof montar>) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Produtos' }));
  await screen.findByText('Anel Solitário');
  return u;
}

async function abrirFicha(m: ReturnType<typeof montar>) {
  const u = await abrirLista(m);
  const linha = screen.getByRole('row', { name: /Anel Solitário/ });
  await u.click(within(linha).getByRole('button', { name: 'Abrir cadastro' }));
  await screen.findByLabelText('Nome');
  await waitFor(() => expect(screen.getByLabelText('Nome')).toHaveValue('Anel Solitário'));
  return u;
}

async function abrirCadastroNovo(m: ReturnType<typeof montar>) {
  const u = await abrirLista(m);
  await u.click(screen.getByRole('button', { name: 'Cadastrar produto' }));
  await screen.findByLabelText('Código');
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  esquecerImagens();
  URL.createObjectURL = vi.fn(() => 'blob:foto');
  URL.revokeObjectURL = vi.fn();
});

describe('Produtos: lista', () => {
  it('mostra a foto em miniatura e, sem foto, o marcador neutro', async () => {
    const m = montar();
    await abrirLista(m);
    const comFoto = screen.getByRole('row', { name: /Anel Solitário/ });
    expect(
      await within(comFoto).findByRole('img', { name: 'Foto de Anel Solitário' }),
    ).toBeInTheDocument();
    expect(ultima(m, 'produtos.fotos.imagem')).toEqual({ hash: HASH, tamanho: 'miniatura' });
    const semFoto = screen.getByRole('row', { name: /Brinco de Argola/ });
    expect(within(semFoto).getByRole('img', { name: 'Sem foto' })).toBeInTheDocument();
    expect(within(semFoto).getByText('B-7')).toBeInTheDocument();
    expect(within(semFoto).getByText('R$ 70,00')).toBeInTheDocument();
  });

  it('foto que não carrega nunca trava a lista', async () => {
    const m = montar({ 'produtos.fotos.imagem': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrirLista(m);
    const linha = screen.getByRole('row', { name: /Anel Solitário/ });
    await waitFor(() =>
      expect(within(linha).getByRole('img', { name: 'Sem foto' })).toBeInTheDocument(),
    );
    expect(within(linha).getByRole('button', { name: 'Abrir cadastro' })).toBeEnabled();
  });

  it('busca, filtros e situação vão à API', async () => {
    const m = montar();
    const u = await abrirLista(m);
    await u.type(screen.getByLabelText('Buscar por nome, código ou referência'), 'anel');
    await waitFor(() =>
      expect(ultima(m, 'produtos.listar')).toMatchObject({ busca: 'anel', pagina: 0 }),
    );
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await waitFor(() =>
      expect(ultima(m, 'produtos.listar')).toMatchObject({ categoriaId: ID_CAT }),
    );
    await u.selectOptions(screen.getByLabelText('Fornecedor'), 'Ouro Ltda');
    await waitFor(() =>
      expect(ultima(m, 'produtos.listar')).toMatchObject({ fornecedorId: ID_FORN }),
    );
    await u.selectOptions(screen.getByLabelText('Situação'), 'Inativos');
    await waitFor(() => expect(ultima(m, 'produtos.listar')).toMatchObject({ ativo: false }));
  });
});

describe('Produtos: cadastro e códigos', () => {
  it('o cursor já está em Código e o leitor (teclas rápidas + Enter) não envia o formulário', async () => {
    const m = montar();
    const u = await abrirCadastroNovo(m);
    const codigo = screen.getByLabelText('Código');
    expect(codigo).toHaveFocus();
    await u.keyboard('7891234567895{Enter}');
    expect(screen.getByLabelText('Código')).toHaveValue('7891234567895');
    expect(await screen.findByText('Código lido pelo leitor.')).toBeInTheDocument();
    expect(screen.getByLabelText('Tipo do código')).toHaveValue('EAN13');
    expect(quantas(m, 'produtos.criar')).toBe(0);
    await waitFor(() =>
      expect(ultima(m, 'produtos.porCodigo')).toEqual({ codigo: '7891234567895' }),
    );
  });

  it('digitação lenta não é leitor', async () => {
    const m = montar();
    const u = userEvent.setup({ delay: 120 });
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Produtos' }));
    await u.click(await screen.findByRole('button', { name: 'Cadastrar produto' }));
    await u.type(await screen.findByLabelText('Código'), '0012{Enter}');
    await waitFor(() => expect(quantas(m, 'produtos.porCodigo')).toBeGreaterThan(0));
    expect(screen.queryByText('Código lido pelo leitor.')).toBeNull();
  });

  it('código que já existe avisa na hora, com o nome do produto, e deixa abrir esse produto', async () => {
    const m = montar({ 'produtos.porCodigo': () => ({ ok: true, dados: ANEL }) });
    const u = await abrirCadastroNovo(m);
    await u.type(screen.getByLabelText('Código'), '0012{Enter}');
    expect(await screen.findByText(/Já existe: Anel Solitário\./)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Abrir esse produto' }));
    expect(await screen.findByDisplayValue('Anel Solitário')).toBeInTheDocument();
  });

  it('código do fornecedor repetido avisa e a dona decide', async () => {
    const m = montar({ 'produtos.porCodigo': () => ({ ok: true, dados: BRINCO }) });
    const u = await abrirCadastroNovo(m);
    await u.selectOptions(screen.getByLabelText('Tipo do código'), 'Código do fornecedor');
    await u.type(screen.getByLabelText('Código'), 'B-7{Enter}');
    expect(
      await screen.findByText(/Já existe: Brinco de Argola\. Esse código do fornecedor/),
    ).toBeInTheDocument();
  });

  it('EAN-13 de 13 números com dígito errado e leitura cortada têm aviso', async () => {
    const m = montar();
    const u = await abrirCadastroNovo(m);
    await u.selectOptions(screen.getByLabelText('Tipo do código'), 'Código de barras');
    await u.type(screen.getByLabelText('Código'), '7891234567890');
    expect(
      screen.getByText(
        'O código de barras 7891234567890 não confere. Leia o código de novo ou confira os números.',
      ),
    ).toBeInTheDocument();
    await u.clear(screen.getByLabelText('Código'));
    await u.type(screen.getByLabelText('Código'), '78912345');
    expect(
      screen.getByText('Este código de barras parece incompleto. Leia o código de novo.'),
    ).toBeInTheDocument();
  });

  it('código da loja não tem dígito conferido e guarda os zeros à esquerda', async () => {
    const m = montar({
      'produtos.criar': (p) => ({ ok: true, dados: { ...ANEL, ...p.parametros } }),
    });
    const u = await abrirCadastroNovo(m);
    await u.type(screen.getByLabelText('Código'), '000123');
    await u.type(screen.getByLabelText('Nome'), 'Colar');
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await u.type(screen.getByLabelText('Preço de venda'), '70,00');
    await u.type(screen.getByLabelText('Preço de custo'), '10');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Colar foi cadastrado.');
    expect(ultima(m, 'produtos.criar')).toMatchObject({
      nome: 'Colar',
      categoriaId: ID_CAT,
      precoVenda: '70.00',
      precoCusto: '10.00',
      codigos: [{ codigo: '000123', tipo: 'LOJA', principal: true, ativo: true }],
    });
    expect((ultima(m, 'produtos.criar') as { id: string }).id).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('salvar sem preencher mostra o que falta e não chama a API', async () => {
    const m = montar();
    const u = await abrirCadastroNovo(m);
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('Informe pelo menos um código para a peça.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Escolha a categoria do produto.')).toBeInTheDocument();
    expect(quantas(m, 'produtos.criar')).toBe(0);
  });

  it('gerar código da loja usa o número que o servidor entrega', async () => {
    const m = montar({ 'produtos.codigoProximo': () => ({ ok: true, dados: { codigo: '4821' } }) });
    const u = await abrirCadastroNovo(m);
    await u.click(screen.getByRole('button', { name: 'Gerar código da loja' }));
    expect(await screen.findByDisplayValue('4821')).toBeInTheDocument();
    expect(screen.getByText(/Código da loja 4821 gerado/)).toBeInTheDocument();
  });

  it('vários códigos por peça, um só principal', async () => {
    const m = montar();
    const u = await abrirCadastroNovo(m);
    await u.click(screen.getByRole('button', { name: 'Adicionar outro código' }));
    const campos = screen.getAllByLabelText('Código');
    await u.type(campos[0]!, '1');
    await u.type(campos[1]!, '2');
    const radios = screen.getAllByRole('radio');
    expect(radios[0]).toBeChecked();
    await u.click(radios[1]!);
    expect(radios[0]).not.toBeChecked();
    expect(radios[1]).toBeChecked();
  });
});

describe('Produtos: ficha', () => {
  it('mostra o custo com as três moedas e salva dados e códigos com a versão lida', async () => {
    const m = montar({
      'produtos.editar': (p) => ({
        ok: true,
        dados: { ...ANEL, version: 4, nome: String((p.parametros as { nome: string }).nome) },
      }),
      'produtos.codigos.gravar': () => ({
        ok: true,
        dados: { ...ANEL, version: 5, nome: 'Anel Novo' },
      }),
    });
    const u = await abrirFicha(m);
    expect(screen.getByLabelText('Preço de custo')).toHaveValue('10,00');
    expect(
      screen.getByText('O custo só aparece neste aplicativo, nunca para o representante.'),
    ).toBeInTheDocument();
    await u.clear(screen.getByLabelText('Nome'));
    await u.type(screen.getByLabelText('Nome'), 'Anel Novo');
    await u.clear(screen.getByLabelText('Código'));
    await u.type(screen.getByLabelText('Código'), '0099');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await screen.findByText('Os dados de Anel Novo foram salvos.');
    expect(ultima(m, 'produtos.editar')).toMatchObject({
      id: ID_P,
      nome: 'Anel Novo',
      version: 3,
      precoCusto: '10.00',
    });
    expect(ultima(m, 'produtos.codigos.gravar')).toMatchObject({
      id: ID_P,
      version: 4,
      codigos: [{ id: ID_FOTO, codigo: '0099', tipo: 'LOJA', principal: true }],
    });
  });

  it('409 de versão: texto do servidor e Atualizar relê o produto', async () => {
    const m = montar({
      'produtos.editar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CADASTRO_ALTERADO',
        mensagem: 'Este cadastro foi alterado por outra pessoa. Atualize a tela.',
      }),
    });
    const u = await abrirFicha(m);
    await u.type(screen.getByLabelText('Referência'), 'x');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(
      await screen.findByText('Este cadastro foi alterado por outra pessoa. Atualize a tela.'),
    ).toBeInTheDocument();
    const antes = quantas(m, 'produtos.ver');
    await u.click(screen.getByRole('button', { name: 'Atualizar' }));
    await waitFor(() => expect(quantas(m, 'produtos.ver')).toBeGreaterThan(antes));
  });

  it('desativar pede confirmação (foco em Voltar) e só então chama a API', async () => {
    const m = montar({
      'produtos.inativar': () => ({ ok: true, dados: { ...ANEL, ativo: false } }),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Inativar' }));
    const janela = await screen.findByRole('dialog');
    expect(within(janela).getByRole('button', { name: 'Voltar' })).toHaveFocus();
    expect(quantas(m, 'produtos.inativar')).toBe(0);
    await u.click(within(janela).getByRole('button', { name: 'Inativar' }));
    await screen.findByText('Anel Solitário foi inativado.');
    expect(ultima(m, 'produtos.inativar')).toEqual({ id: ID_P });
    expect(screen.getByRole('button', { name: 'Reativar' })).toBeInTheDocument();
  });
});

describe('Produtos: fotos', () => {
  const arquivo = (nome: string, tipo: string, tamanho = 4) =>
    new File([new Uint8Array(tamanho)], nome, { type: tipo });

  it('arquivo que não é foto é recusado antes de enviar', async () => {
    const m = montar();
    await abrirFicha(m);
    fireEvent.change(screen.getByTestId('escolher-foto'), {
      target: { files: [arquivo('a.pdf', 'application/pdf')] },
    });
    expect(await screen.findByText(/Esta não é uma foto que o sistema aceite/)).toBeInTheDocument();
    expect(quantas(m, 'produtos.fotos.enviar')).toBe(0);
  });

  it('foto escolhida vai como bytes e a ficha relê o produto', async () => {
    const m = montar({ 'produtos.fotos.enviar': () => ({ ok: true, dados: {} }) });
    await abrirFicha(m);
    const lidas = quantas(m, 'produtos.ver');
    fireEvent.change(screen.getByTestId('escolher-foto'), {
      target: { files: [arquivo('a.png', 'image/png')] },
    });
    await screen.findByText('Foto trocada.');
    const p = ultima(m, 'produtos.fotos.enviar') as {
      id: string;
      arquivo: { nome: string; bytes: Uint8Array };
    };
    expect(p.id).toBe(ID_P);
    expect(p.arquivo.nome).toBe('a.png');
    expect(p.arquivo.bytes).toBeInstanceOf(Uint8Array);
    await waitFor(() => expect(quantas(m, 'produtos.ver')).toBeGreaterThan(lidas));
  });

  it('colar (Ctrl+V) e arrastar também enviam', async () => {
    const m = montar({ 'produtos.fotos.enviar': () => ({ ok: true, dados: {} }) });
    await abrirFicha(m);
    const colada = arquivo('image', 'image/png');
    const evento = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(evento, 'clipboardData', { value: { files: [colada] } });
    window.dispatchEvent(evento);
    // Espera mais que o padrão: com a máquina cheia (todos os arquivos em paralelo) a ficha demora a abrir.
    await waitFor(() => expect(quantas(m, 'produtos.fotos.enviar')).toBe(1), { timeout: 5000 });
    expect((ultima(m, 'produtos.fotos.enviar') as { arquivo: { nome: string } }).arquivo.nome).toBe(
      'image.png',
    );
    await screen.findByText('Foto trocada.', {}, { timeout: 5000 });
    fireEvent.drop(screen.getByRole('group', { name: 'Área para soltar ou colar a foto' }), {
      dataTransfer: { files: [arquivo('b.jpg', 'image/jpeg')] },
    });
    await waitFor(() => expect(quantas(m, 'produtos.fotos.enviar')).toBe(2), { timeout: 5000 });
  });

  it('remover foto pede confirmação', async () => {
    const m = montar({ 'produtos.fotos.excluir': () => ({ ok: true, dados: null }) });
    const u = await abrirFicha(m);
    await u.click(await screen.findByRole('button', { name: 'Remover foto' }));
    const janela = await screen.findByRole('dialog');
    expect(quantas(m, 'produtos.fotos.excluir')).toBe(0);
    await u.click(within(janela).getByRole('button', { name: 'Remover foto' }));
    await screen.findByText('A foto foi removida.');
    expect(ultima(m, 'produtos.fotos.excluir')).toEqual({ id: ID_P, fotoId: ID_FOTO });
  });

  it('uma foto só por peça: com foto o botão é Trocar foto, sem principal nem limite', async () => {
    const m = montar();
    await abrirFicha(m);
    expect(await screen.findByRole('button', { name: 'Trocar foto' })).toBeEnabled();
    expect(screen.queryByText(/foto principal/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/5 fotos/)).not.toBeInTheDocument();
  });

  it('peça sem foto: Escolher foto e, depois de enviar, a mensagem de foto enviada', async () => {
    const m = montar({
      'produtos.fotos.listar': () => ({ ok: true, dados: [] }),
      'produtos.fotos.enviar': () => ({ ok: true, dados: {} }),
    });
    await abrirFicha(m);
    expect(await screen.findByRole('button', { name: 'Escolher foto' })).toBeEnabled();
    fireEvent.change(screen.getByTestId('escolher-foto'), {
      target: { files: [arquivo('a.png', 'image/png')] },
    });
    await screen.findByText('Foto enviada.');
  });
});

describe('Produtos: importação por planilha', () => {
  const CSV =
    'código;nome;categoria;custo;preço\n' +
    '0012;Anel Solitário;Anéis;12,00;80,00\n' +
    '0200;Anel Novo;Anéis;5;30\n' +
    '0300;Colar;Colares;1;2\n';

  async function escolher(m: ReturnType<typeof montar>, csv = CSV) {
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Importar planilha' }));
    fireEvent.change(screen.getByTestId('escolher-planilha'), {
      target: { files: [new File([csv], 'produtos.csv', { type: 'text/csv' })] },
    });
    return u;
  }

  it('prévia linha a linha sem gravar nada; depois confirma e mostra o relatório com o motivo', async () => {
    const m = montar({
      'produtos.porCodigo': (p) =>
        (p.parametros as { codigo: string }).codigo === '0012'
          ? { ok: true, dados: ANEL }
          : naoAchou,
      'produtos.criar': () => ({ ok: true, dados: ANEL }),
      'produtos.editar': () => ({ ok: true, dados: ANEL }),
    });
    const u = await escolher(m);
    expect(await screen.findByText('Prévia: nada foi gravado ainda.')).toBeInTheDocument();
    expect(
      screen.getByText('1 para cadastrar, 1 para atualizar, 1 com problema.'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('A categoria "Colares" não existe. Cadastre a categoria antes.'),
    ).toBeInTheDocument();
    expect(quantas(m, 'produtos.criar') + quantas(m, 'produtos.editar')).toBe(0);

    await u.click(screen.getByRole('button', { name: 'Importar produtos' }));
    expect(await screen.findByText('Importação concluída.')).toBeInTheDocument();
    expect(screen.getByText('1 cadastrados, 1 atualizados, 1 não importados.')).toBeInTheDocument();
    expect(ultima(m, 'produtos.criar')).toMatchObject({
      nome: 'Anel Novo',
      precoCusto: '5.00',
      precoVenda: '30.00',
      codigos: [{ codigo: '0200', tipo: 'LOJA', principal: true }],
    });
    expect(ultima(m, 'produtos.editar')).toMatchObject({
      id: ID_P,
      version: 3,
      precoVenda: '80.00',
      precoCusto: '12.00',
    });
  });

  it('o resultado de cada linha é guardado no servidor (relatório de importações)', async () => {
    const m = montar({
      'produtos.porCodigo': (p) =>
        (p.parametros as { codigo: string }).codigo === '0012'
          ? { ok: true, dados: ANEL }
          : naoAchou,
      'produtos.criar': () => ({ ok: true, dados: ANEL }),
      'produtos.editar': () => ({ ok: true, dados: ANEL }),
      'importacoes.registrar': () => ({ ok: true, dados: null }),
    });
    const u = await escolher(m);
    await u.click(await screen.findByRole('button', { name: 'Importar produtos' }));
    await screen.findByText('Importação concluída.');
    expect(quantas(m, 'importacoes.registrar')).toBe(1);
    const lote = ultima(m, 'importacoes.registrar') as {
      importacaoId: string;
      importacao: {
        tipo: string;
        arquivoNome: string;
        linhas: { referencia: string; resultado: string; motivo?: string }[];
      };
    };
    expect(lote.importacaoId).toMatch(/^[0-9a-f-]{36}$/);
    expect(lote.importacao).toMatchObject({ tipo: 'PLANILHA', arquivoNome: 'produtos.csv' });
    expect(lote.importacao.linhas.map((l) => l.resultado)).toEqual([
      'ATUALIZADO',
      'CRIADO',
      'RECUSADO',
    ]);
    expect(lote.importacao.linhas[2]?.motivo).toContain('Colares');
    expect(screen.queryByText(/não pôde ser guardado/)).toBeNull();
  });

  it('se o servidor não guardar o resultado, avisa sem desfazer a importação', async () => {
    const m = montar({
      'produtos.criar': () => ({ ok: true, dados: ANEL }),
      'importacoes.registrar': () => ({ ok: false, motivo: 'sem-conexao' }),
    });
    const u = await escolher(m, 'código;nome;categoria;custo;preço\n0200;Anel Novo;Anéis;5;30\n');
    await u.click(await screen.findByRole('button', { name: 'Importar produtos' }));
    expect(await screen.findByText(/não pôde ser guardado/)).toBeInTheDocument();
    expect(screen.getByText('1 cadastrados, 0 atualizados, 0 não importados.')).toBeInTheDocument();
  });

  it('recusa do servidor vira motivo da linha no relatório', async () => {
    const m = montar({
      'produtos.criar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'O código 0200 já é usado pelo produto Outro.',
      }),
    });
    const u = await escolher(m, 'código;nome;categoria;custo;preço\n0200;Anel Novo;Anéis;5;30\n');
    await u.click(await screen.findByRole('button', { name: 'Importar produtos' }));
    expect(
      await screen.findByText('0 cadastrados, 0 atualizados, 1 não importados.'),
    ).toBeInTheDocument();
    expect(screen.getByText('O código 0200 já é usado pelo produto Outro.')).toBeInTheDocument();
  });

  it('planilha sem as colunas pede os títulos certos', async () => {
    const m = montar();
    await escolher(m, 'a;b\n1;2\n');
    expect(
      await screen.findByText(/Faltam colunas na primeira linha da planilha/),
    ).toBeInTheDocument();
  });

  it('arquivo que não é planilha é recusado', async () => {
    const m = montar();
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Importar planilha' }));
    fireEvent.change(screen.getByTestId('escolher-planilha'), {
      target: { files: [new File(['x'], 'a.pdf', { type: 'application/pdf' })] },
    });
    expect(await screen.findByText('Escolha uma planilha .xlsx ou .csv.')).toBeInTheDocument();
  });
});

describe('Produtos: importação de fotos em massa', () => {
  const foto = (nome: string) => new File([new Uint8Array([1, 2])], nome, { type: 'image/jpeg' });

  it('prévia só com os nomes; confirma em lotes de 10 e mostra o relatório', async () => {
    const nomes = Array.from({ length: 12 }, (_, i) => `${100 + i}.jpg`);
    const m = montar({
      'produtos.importacao.previa': (p) => {
        const lista = (p.parametros as { nomes: string[] }).nomes;
        return {
          ok: true,
          dados: {
            associadas: lista.slice(0, 12).map((n) => ({ nome: n, produtoNome: `Produto ${n}` })),
            semProduto: [{ nome: 'zzz.jpg', mensagem: 'Nenhuma peça tem o código zzz.' }],
            ambiguas: [],
            produtosSemFoto: { total: 3, itens: [] },
          },
        };
      },
      'produtos.importacao.confirmar': (p) => ({
        ok: true,
        dados: {
          resultados: (p.parametros as { arquivos: { nome: string }[] }).arquivos.map((a) => ({
            nome: a.nome,
            situacao: 'GRAVADA',
            mensagem: `Foto gravada na peça ${a.nome}.`,
          })),
        },
      }),
    });
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Importar fotos' }));
    fireEvent.change(screen.getByTestId('escolher-fotos'), {
      target: { files: [...nomes.map(foto), foto('zzz.jpg')] },
    });
    expect(await screen.findByText('Prévia: nenhuma foto foi enviada ainda.')).toBeInTheDocument();
    expect(screen.getByText(/12 fotos acharam o produto, 1 não acharam/)).toBeInTheDocument();
    expect((ultima(m, 'produtos.importacao.previa') as { nomes: string[] }).nomes).toContain(
      'zzz.jpg',
    );
    expect(quantas(m, 'produtos.importacao.confirmar')).toBe(0);

    await u.click(screen.getByRole('button', { name: 'Enviar as fotos' }));
    expect(await screen.findByText('Envio concluído.')).toBeInTheDocument();
    const lotes = m.chamadas.filter((c) => c.operacao === 'produtos.importacao.confirmar');
    expect(lotes.map((l) => (l.parametros as { arquivos: unknown[] }).arquivos.length)).toEqual([
      10, 2,
    ]);
    expect(
      screen.getByText('12 gravadas, 0 já estavam no produto, 1 não gravadas.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Nenhuma peça tem o código zzz.')).toBeInTheDocument();
  });

  it('cada lote de fotos leva o mesmo número da importação e as fotos sem peça também são guardadas', async () => {
    const m = montar({
      'produtos.importacao.previa': () => ({
        ok: true,
        dados: {
          associadas: [{ nome: 'a.jpg', produtoNome: 'A' }],
          semProduto: [{ nome: 'zzz.jpg', mensagem: 'Nenhuma peça tem o código zzz.' }],
          ambiguas: [],
          produtosSemFoto: { total: 0, itens: [] },
        },
      }),
      'produtos.importacao.confirmar': () => ({
        ok: true,
        dados: { resultados: [{ nome: 'a.jpg', situacao: 'GRAVADA', mensagem: 'ok' }] },
      }),
      'importacoes.registrar': () => ({ ok: true, dados: null }),
    });
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Importar fotos' }));
    fireEvent.change(screen.getByTestId('escolher-fotos'), {
      target: { files: [foto('a.jpg'), foto('zzz.jpg')] },
    });
    await u.click(await screen.findByRole('button', { name: 'Enviar as fotos' }));
    await screen.findByText('Envio concluído.');
    const enviado = ultima(m, 'produtos.importacao.confirmar') as { importacaoId: string };
    const guardado = ultima(m, 'importacoes.registrar') as {
      importacaoId: string;
      importacao: { tipo: string; linhas: { referencia: string; resultado: string }[] };
    };
    expect(enviado.importacaoId).toMatch(/^[0-9a-f-]{36}$/);
    expect(guardado.importacaoId).toBe(enviado.importacaoId);
    expect(guardado.importacao.tipo).toBe('FOTOS');
    expect(guardado.importacao.linhas).toEqual([
      { referencia: 'zzz.jpg', resultado: 'RECUSADO', motivo: 'Nenhuma peça tem o código zzz.' },
    ]);
  });

  it('nomes repetidos não são enviados', async () => {
    const m = montar({
      'produtos.importacao.previa': () => ({
        ok: true,
        dados: {
          associadas: [
            { nome: 'a.jpg', produtoNome: 'A' },
            { nome: 'a.png', produtoNome: 'A' },
            { nome: 'b.jpg', produtoNome: 'B' },
          ],
          semProduto: [],
          ambiguas: [
            {
              codigo: 'a',
              nomes: ['a.jpg', 'a.png'],
              mensagem: 'Há mais de um arquivo para o código a.',
            },
          ],
          produtosSemFoto: { total: 0, itens: [] },
        },
      }),
      'produtos.importacao.confirmar': (p) => ({
        ok: true,
        dados: {
          resultados: (p.parametros as { arquivos: { nome: string }[] }).arquivos.map((a) => ({
            nome: a.nome,
            situacao: 'GRAVADA',
            mensagem: 'ok',
          })),
        },
      }),
    });
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Importar fotos' }));
    fireEvent.change(screen.getByTestId('escolher-fotos'), {
      target: { files: [foto('a.jpg'), foto('a.png'), foto('b.jpg')] },
    });
    await u.click(await screen.findByRole('button', { name: 'Enviar as fotos' }));
    await screen.findByText('Envio concluído.');
    const enviados = (
      ultima(m, 'produtos.importacao.confirmar') as { arquivos: { nome: string }[] }
    ).arquivos;
    expect(enviados.map((a) => a.nome)).toEqual(['b.jpg']);
  });

  it('arquivos que não são fotos aceitas não chegam à prévia', async () => {
    const m = montar();
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Importar fotos' }));
    fireEvent.change(screen.getByTestId('escolher-fotos'), {
      target: { files: [new File(['x'], 'a.pdf', { type: 'application/pdf' })] },
    });
    expect(
      await screen.findByText(/Nenhum dos arquivos escolhidos é uma foto aceita/),
    ).toBeInTheDocument();
    expect(quantas(m, 'produtos.importacao.previa')).toBe(0);
  });
});

describe('Produtos: consulta', () => {
  it('lê o código e mostra preço, foto e saldo; o campo volta a ficar pronto', async () => {
    const m = montar({ 'produtos.porCodigo': () => ({ ok: true, dados: ANEL }) });
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Consultar produto' }));
    await u.type(await screen.findByLabelText('Código da peça'), '0012{Enter}');
    expect(await screen.findByRole('heading', { name: 'Anel Solitário' })).toBeInTheDocument();
    expect(screen.getByText(/Preço:/)).toHaveTextContent('R$ 70,00');
    // Zero na loja e nenhuma em maleta: "Acabou".
    expect(screen.getByText(/Saldo no estoque:/)).toHaveTextContent('Acabou');
    await waitFor(() => expect(screen.getByLabelText('Código da peça')).toHaveFocus());
    expect(screen.getByLabelText('Código da peça')).toHaveValue('');
  });

  it('zero na loja com a peça em maleta: "0 na loja" e quem está com ela, sem dizer que acabou', async () => {
    const m = montar({
      'produtos.porCodigo': () => ({
        ok: true,
        dados: {
          ...ANEL,
          emMaletas: 2,
          vendedoresEmMaletas: [
            { nome: 'Maria', quantidade: 1 },
            { nome: 'João', quantidade: 1 },
          ],
        },
      }),
    });
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Consultar produto' }));
    await u.type(await screen.findByLabelText('Código da peça'), '0012{Enter}');
    expect(await screen.findByRole('heading', { name: 'Anel Solitário' })).toBeInTheDocument();
    const saldo = screen.getByText(/Saldo no estoque:/);
    expect(saldo).toHaveTextContent('0 na loja');
    expect(saldo).toHaveTextContent('Está em maleta: 2 (Maria, João)');
    expect(saldo).not.toHaveTextContent('Acabou');
  });

  it('código que não existe tem texto próprio, sem travar', async () => {
    const m = montar();
    const u = await abrirLista(m);
    await u.click(screen.getByRole('tab', { name: 'Consultar produto' }));
    await u.type(await screen.findByLabelText('Código da peça'), '999{Enter}');
    expect(
      await screen.findByText(
        'Nenhum produto tem o código 999. Confira o número ou cadastre o produto.',
      ),
    ).toBeInTheDocument();
  });
});
