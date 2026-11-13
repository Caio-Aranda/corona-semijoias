// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Cliente, Conferencia, LinhaDaMaleta, Maleta, Venda } from '../shared/dados';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';

const ANEL = '11111111-1111-4111-8111-111111111111';
const COLAR = '22222222-2222-4222-8222-222222222222';
const MALETA = '33333333-3333-4333-8333-333333333333';
const MARIA = '44444444-4444-4444-8444-444444444444';

const linha = (
  id: string,
  nome: string,
  codigo: string,
  saldo: number,
  preco: string,
): LinhaDaMaleta => ({
  produto: {
    id,
    nome,
    codigo,
    fotoHash: null,
    categoria: 'Anéis',
    codigoFornecedor: 'FAB-' + codigo,
  },
  enviada: saldo,
  vendida: 0,
  devolvida: 0,
  saldo,
  precoUnitario: preco,
  valorEmAberto: '0.00',
});

const LINHA_ANEL = linha(ANEL, 'Anel de prata', 'A-1', 3, '50.00');
const LINHA_COLAR = linha(COLAR, 'Colar dourado', 'C-1', 1, '120.00');

const maleta = (status = 'ENVIADA'): Maleta => ({
  id: MALETA,
  numero: 'S12-V7-1',
  semanaNumero: 12,
  tipo: 'NOVA',
  status,
  enviadaEm: '2026-11-10T12:00:00Z',
  pecasEmAberto: 4,
  valorEmAberto: '270.00',
  itens: [LINHA_ANEL, LINHA_COLAR],
});

const cliente: Cliente = {
  id: MARIA,
  nome: 'Maria Souza',
  cpf: null,
  dataNascimento: null,
  telefone: null,
  cidade: null,
  meu: true,
  whatsapp: null,
  observacoes: null,
  ativo: true,
};

const vendaGravada = (
  total = '100.00',
  pago = '100.00',
  status: 'CONCLUIDA' | 'ESTORNADA' = 'CONCLUIDA',
): Venda => ({
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  remessaId: MALETA,
  status,
  subtotal: total,
  desconto: '0.00',
  total,
  totalDolar: null,
  totalGuarani: null,
  cotacao: null,
  pago,
  aReceber: (Number(total) - Number(pago)).toFixed(2),
  vendidaEm: '2026-11-11T15:00:00Z',
  estornadaEm: status === 'ESTORNADA' ? '2026-11-11T16:00:00Z' : null,
  motivoDoEstorno: status === 'ESTORNADA' ? 'Cliente desistiu' : null,
  itens: [
    {
      produto: LINHA_ANEL.produto,
      quantidade: 2,
      precoUnitario: '50.00',
      desconto: '0.00',
      total: '100.00',
    },
  ],
  pagamentos: [],
});

/** A situação da maleta de mentira: enviada (para conferir); na venda, conferida (vender só depois de conferir). */
let statusPadrao = 'ENVIADA';

type Manipulador = (pedido: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

/** A ponte do app com a API de mentira: cada operação responde o que o teste combinou. */
function ponteDeTrabalho(
  manipuladores: Partial<Record<PedidoDeChamada['operacao'], Manipulador>> = {},
) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'maletas.listar': () => ({ ok: true, dados: [maleta(statusPadrao)] }),
    'cotacao.hoje': () => ({
      ok: true,
      dados: {
        disponivel: false,
        dataDeHoje: '2026-11-11',
        dataDaCotacao: null,
        dolar: null,
        guarani: null,
        deDiaAnterior: false,
      },
    }),
    'clientes.listar': () => ({ ok: true, dados: [cliente] }),
    'vendas.listar': () => ({ ok: true, dados: [vendaGravada()] }),
  };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Ana' } }),
    ),
    chamarApi: vi.fn((pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = manipuladores[pedido.operacao] ?? padrao[pedido.operacao];
      return Promise.resolve(m ? m(pedido) : { ok: false as const, motivo: 'erro' as const });
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
    aoMudarASincronizacao: () => () => undefined,
  };
  return { ponte, chamadas, daOperacao: (op: string) => chamadas.filter((c) => c.operacao === op) };
}

async function abrir(ponte: PonteDoCorona, botao: string) {
  const u = userEvent.setup();
  render(<App ponte={ponte} />);
  await u.click(await screen.findByRole('button', { name: botao }));
  return u;
}

describe('Minha maleta', () => {
  it('mostra as peças com saldo e preço, sem custo, e o botão Conferir maleta', async () => {
    const { ponte } = ponteDeTrabalho();
    await abrir(ponte, 'Minha maleta');
    expect(await screen.findByText('Anel de prata')).toBeInTheDocument();
    expect(screen.getByText('Colar dourado')).toBeInTheDocument();
    expect(screen.getByText('R$ 50,00')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Conferir maleta' })).toBeInTheDocument();
    expect(screen.queryByText(/custo/i)).not.toBeInTheDocument();
  });

  it('mostra Dólar e Guarani pela cotação de hoje, e diz isso na tela', async () => {
    const { ponte } = ponteDeTrabalho({
      'cotacao.hoje': () => ({
        ok: true,
        dados: {
          disponivel: true,
          dataDeHoje: '2026-11-11',
          dataDaCotacao: '2026-11-11',
          dolar: '5.00000000',
          guarani: '0.00070000',
          deDiaAnterior: false,
        },
      }),
    });
    await abrir(ponte, 'Minha maleta');
    expect(await screen.findByText('Anel de prata')).toBeInTheDocument();
    expect(
      screen.getByText('Cotação do Dólar e do Guarani de hoje, 11/11/2026.'),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/US\$ 10,00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Gs\. 71\.429/).length).toBeGreaterThan(0);
  });

  it('sem cotação, mostra só Real e avisa', async () => {
    const { ponte } = ponteDeTrabalho();
    await abrir(ponte, 'Minha maleta');
    expect(await screen.findByText('Anel de prata')).toBeInTheDocument();
    expect(screen.queryByText(/Cotação do Dólar e do Guarani de hoje/)).not.toBeInTheDocument();
    expect(screen.getByText(/Sem cotação do Dólar e do Guarani/)).toBeInTheDocument();
    expect(screen.queryByText(/US\$/)).not.toBeInTheDocument();
  });

  it('maleta já conferida não oferece conferir de novo', async () => {
    const { ponte } = ponteDeTrabalho({
      'maletas.listar': () => ({ ok: true, dados: [maleta('CONFERIDA')] }),
    });
    await abrir(ponte, 'Minha maleta');
    expect(await screen.findByText('Anel de prata')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Conferir maleta' })).not.toBeInTheDocument();
  });

  it('sem maleta explica em português', async () => {
    const { ponte } = ponteDeTrabalho({ 'maletas.listar': () => ({ ok: true, dados: [] }) });
    await abrir(ponte, 'Minha maleta');
    expect(await screen.findByText(/ainda não tem maleta com peças/)).toBeInTheDocument();
  });

  it('falta de internet vira texto simples e deixa tentar de novo', async () => {
    const { ponte } = ponteDeTrabalho({
      'maletas.listar': () => ({ ok: false, motivo: 'sem-conexao' }),
    });
    await abrir(ponte, 'Minha maleta');
    expect(
      await screen.findByText('Sem internet. Conecte-se e tente de novo.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();
  });
});

describe('Conferir a maleta', () => {
  it('cada leitura conta uma peça e a conferência vai com o id e as contagens', async () => {
    const feita: Conferencia = {
      id: 'x',
      remessaId: MALETA,
      conferidaEm: '2026-11-11T15:00:00Z',
      divergencias: 2,
      itens: [
        { produtoId: ANEL, produtoNome: 'Anel de prata', esperado: 3, contado: 2, diferenca: -1 },
        { produtoId: COLAR, produtoNome: 'Colar dourado', esperado: 1, contado: 0, diferenca: -1 },
      ],
    };
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'maletas.conferir': () => ({ ok: true, dados: feita }),
    });
    const u = await abrir(ponte, 'Minha maleta');
    await u.click(await screen.findByRole('button', { name: 'Conferir maleta' }));
    const campo = await screen.findByLabelText('Ler o código da peça');
    await u.type(campo, 'A-1{Enter}');
    await screen.findByText(/Anel de prata: 1 contada/);
    await u.type(campo, 'A-1{Enter}');
    await screen.findByText(/Anel de prata: 2 contada/);
    expect(daOperacao('maletas.peca')[0]).toMatchObject({ id: MALETA, codigo: 'A-1' });

    await u.click(screen.getByRole('button', { name: 'Concluir conferência' }));
    const janela = await screen.findByText('Concluir a conferência?');
    expect(janela).toBeInTheDocument();
    expect(screen.getByText('Você contou 2 de 4 peças.')).toBeInTheDocument();
    expect(screen.getByText(/Faltam 2 peça/)).toBeInTheDocument();
    const botoes = screen.getAllByRole('button', { name: 'Concluir conferência' });
    await u.click(botoes[botoes.length - 1]!);

    expect(await screen.findByText('Conferência concluída.')).toBeInTheDocument();
    expect(screen.getByText(/Há diferença em 2 peça/)).toBeInTheDocument();
    const enviado = daOperacao('maletas.conferir')[0];
    expect(enviado).toMatchObject({
      operacao: 'maletas.conferir',
      id: MALETA,
      itens: [{ produtoId: ANEL, contada: 2 }],
    });
    expect(enviado && 'conferenciaId' in enviado && enviado.conferenciaId).toMatch(
      /^[0-9a-f-]{36}$/,
    );
  });

  it('na conferência o painel mostra Esperado e Contadas, e escolher pelo nome conta uma peça como a leitura', async () => {
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'maletas.buscarPeca': () => ({ ok: true, dados: [LINHA_ANEL] }),
    });
    const u = await abrir(ponte, 'Minha maleta');
    await u.click(await screen.findByRole('button', { name: 'Conferir maleta' }));
    await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1{Enter}');
    const painel = await screen.findByRole('region', { name: 'Peça lida: Anel de prata' });
    expect(within(painel).getByText('Esperado: 3 · Contadas: 1')).toBeInTheDocument();
    expect(within(painel).getByText('COD Fábrica: FAB-A-1')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Procurar pelo nome' }));
    await u.type(screen.getByLabelText('Nome, referência ou parte do código'), 'anel');
    await u.click(await screen.findByRole('button', { name: 'Escolher Anel de prata' }));
    expect(await screen.findByText('Esperado: 3 · Contadas: 2')).toBeInTheDocument();
    expect(daOperacao('maletas.peca')).toHaveLength(1);
  });

  it('peça que não é da maleta mostra o aviso do servidor e não conta', async () => {
    const mensagem = 'Esta peça não consta na sua maleta.';
    const { ponte } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: false, motivo: 'erro', mensagem }),
    });
    const u = await abrir(ponte, 'Minha maleta');
    await u.click(await screen.findByRole('button', { name: 'Conferir maleta' }));
    await u.type(await screen.findByLabelText('Ler o código da peça'), 'XYZ{Enter}');
    expect(await screen.findByText(mensagem)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Concluir conferência' })).toBeDisabled();
  });

  it('se a conferência falhar, tenta de novo com o MESMO id', async () => {
    let tentativa = 0;
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'maletas.conferir': () => {
        tentativa += 1;
        return tentativa === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : {
              ok: true,
              dados: {
                id: 'x',
                remessaId: MALETA,
                conferidaEm: '2026-11-11T15:00:00Z',
                divergencias: 0,
                itens: [],
              },
            };
      },
    });
    const u = await abrir(ponte, 'Minha maleta');
    await u.click(await screen.findByRole('button', { name: 'Conferir maleta' }));
    await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1{Enter}');
    await screen.findByText(/Anel de prata: 1 contada/);
    for (let i = 0; i < 2; i += 1) {
      await u.click(screen.getByRole('button', { name: 'Concluir conferência' }));
      const botoes = await screen.findAllByRole('button', { name: 'Concluir conferência' });
      await u.click(botoes[botoes.length - 1]!);
      if (i === 0) await screen.findByText('Sem internet. Conecte-se e tente de novo.');
    }
    await screen.findByText('Conferência concluída.');
    const ids = daOperacao('maletas.conferir').map((c) =>
      'conferenciaId' in c ? c.conferenciaId : '',
    );
    expect(ids).toHaveLength(2);
    expect(ids[0]).toBe(ids[1]);
  });
});

async function lerEEscolherCliente(u: ReturnType<typeof userEvent.setup>) {
  await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1{Enter}');
  await screen.findByText(/Anel de prata: 1/);
  await u.click(await screen.findByRole('button', { name: 'Escolher: Maria Souza' }));
  await screen.findByText('Maria Souza');
}

describe('Vender', () => {
  beforeEach(() => {
    statusPadrao = 'CONFERIDA';
  });
  afterEach(() => {
    statusPadrao = 'ENVIADA';
  });

  it('maleta ainda não conferida não vende e diz para conferir antes', async () => {
    const { ponte } = ponteDeTrabalho({
      'maletas.listar': () => ({ ok: true, dados: [maleta('ENVIADA')] }),
    });
    await abrir(ponte, 'Vender');
    expect(
      await screen.findByText(
        'Esta maleta ainda não foi conferida. Confira todas as peças da maleta antes de vender.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Ler o código da peça')).not.toBeInTheDocument();
  });

  it('lê a peça, escolhe o cliente, paga e manda só peça, quantidade, desconto e pagamento', async () => {
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'vendas.vender': () => ({ ok: true, dados: vendaGravada('90.00', '90.00') }),
    });
    const u = await abrir(ponte, 'Vender');
    await lerEEscolherCliente(u);
    await u.type(screen.getByLabelText('Ler o código da peça'), 'A-1{Enter}');
    await screen.findByText(/Anel de prata: 2/);
    await u.type(screen.getByLabelText('Desconto (R$): Anel de prata'), '10,00');
    expect(screen.getByText(/Total:/, { selector: 'p' })).toHaveTextContent('R$ 90,00');
    await u.click(screen.getByLabelText('Transferência'));
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    const confirmar = await screen.findAllByRole('button', { name: 'Concluir venda' });
    await u.click(confirmar[confirmar.length - 1]!);

    expect(await screen.findByText('Venda registrada.')).toBeInTheDocument();
    const enviada = daOperacao('vendas.vender')[0];
    expect(enviada).toBeDefined();
    if (!enviada || enviada.operacao !== 'vendas.vender') throw new Error('sem venda');
    expect(enviada.venda).toMatchObject({
      remessaId: MALETA,
      clienteId: MARIA,
      itens: [{ produtoId: ANEL, quantidade: 2, desconto: '10.00' }],
      pagamentos: [{ valor: '90.00', forma: 'PIX' }],
      totalConferido: '90.00',
    });
    // Nada de preço nem de vendedor no que sai da tela.
    const texto = JSON.stringify(enviada);
    expect(texto).not.toMatch(/preco|vendedor|custo/i);
  });

  it('o painel da peça lida mostra quantas há na maleta, o COD Fábrica e a categoria, sem custo', async () => {
    const { ponte } = ponteDeTrabalho({ 'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }) });
    const u = await abrir(ponte, 'Vender');
    await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1{Enter}');
    const painel = await screen.findByRole('region', { name: 'Peça lida: Anel de prata' });
    expect(within(painel).getByText('Na maleta: 3')).toBeInTheDocument();
    expect(within(painel).getByText('COD Fábrica: FAB-A-1')).toBeInTheDocument();
    expect(within(painel).getByText('Categoria: Anéis')).toBeInTheDocument();
    expect(painel.textContent ?? '').not.toMatch(/custo|estoque da loja/i);
  });

  it('"Procurar pelo nome": acha só o que o servidor mandou e escolher age como ler o código', async () => {
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.buscarPeca': () => ({ ok: true, dados: [LINHA_ANEL] }),
    });
    const u = await abrir(ponte, 'Vender');
    await screen.findByLabelText('Ler o código da peça');
    await u.click(screen.getByRole('button', { name: 'Procurar pelo nome' }));
    expect(screen.getByText('A etiqueta não lê? Procure pelo nome.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Nome, referência ou parte do código'), 'anel');
    expect(await screen.findByText('Na maleta: 3')).toBeInTheDocument();
    expect(screen.getByText('Código A-1')).toBeInTheDocument();
    expect(screen.getByText('Sem foto')).toBeInTheDocument();
    expect(daOperacao('maletas.buscarPeca')[0]).toMatchObject({ id: MALETA, texto: 'anel' });
    await u.click(screen.getByRole('button', { name: 'Escolher Anel de prata' }));
    // A peça entra na venda como se o código tivesse sido lido (e a busca fecha).
    expect(await screen.findByText(/Anel de prata: 1/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Procurar pelo nome' })).toBeInTheDocument();
    expect(daOperacao('maletas.peca')).toHaveLength(0);
  });

  it('busca sem resultado diz que não há peça da maleta com esse nome', async () => {
    const { ponte } = ponteDeTrabalho({ 'maletas.buscarPeca': () => ({ ok: true, dados: [] }) });
    const u = await abrir(ponte, 'Vender');
    await screen.findByLabelText('Ler o código da peça');
    await u.click(screen.getByRole('button', { name: 'Procurar pelo nome' }));
    await u.type(screen.getByLabelText('Nome, referência ou parte do código'), 'zzz');
    expect(await screen.findByText(/Nenhuma peça da sua maleta com esse nome/)).toBeInTheDocument();
  });

  it('recusa mais peças do que há na maleta, com a frase da loja', async () => {
    const { ponte } = ponteDeTrabalho({ 'maletas.peca': () => ({ ok: true, dados: LINHA_COLAR }) });
    const u = await abrir(ponte, 'Vender');
    const campo = await screen.findByLabelText('Ler o código da peça');
    await u.type(campo, 'C-1{Enter}');
    await screen.findByText(/Colar dourado: 1/);
    await u.type(campo, 'C-1{Enter}');
    expect(await screen.findByText(/Esta peça acabou na sua maleta/)).toBeInTheDocument();
  });

  it('pede o cliente e a peça antes de concluir', async () => {
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
    });
    const u = await abrir(ponte, 'Vender');
    await screen.findByLabelText('Ler o código da peça');
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(await screen.findByText('Leia o código de pelo menos uma peça.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Ler o código da peça'), 'A-1{Enter}');
    await screen.findByText(/Anel de prata: 1/);
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(await screen.findByText('Escolha o cliente da venda.')).toBeInTheDocument();
    expect(daOperacao('vendas.vender')).toHaveLength(0);
  });

  it('desconto fora do formato e valor pago a mais são avisados antes de enviar', async () => {
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
    });
    const u = await abrir(ponte, 'Vender');
    await lerEEscolherCliente(u);
    await u.type(screen.getByLabelText('Desconto (R$): Anel de prata'), 'abc');
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(
      await screen.findByText('Digite o desconto em reais, por exemplo 5,00.'),
    ).toBeInTheDocument();
    await u.clear(screen.getByLabelText('Desconto (R$): Anel de prata'));
    await u.type(screen.getByLabelText('Valor pago agora (R$)'), '80,00');
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(
      await screen.findByText('O valor pago passa do total da venda. Confira o valor.'),
    ).toBeInTheDocument();
    expect(daOperacao('vendas.vender')).toHaveLength(0);
  });

  it('venda a prazo não manda pagamento e mostra o que fica para receber', async () => {
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'vendas.vender': () => ({ ok: true, dados: vendaGravada('50.00', '0.00') }),
    });
    const u = await abrir(ponte, 'Vender');
    await lerEEscolherCliente(u);
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    const confirmar = await screen.findAllByRole('button', { name: 'Concluir venda' });
    await u.click(confirmar[confirmar.length - 1]!);
    await screen.findByText('Venda registrada.');
    const enviada = daOperacao('vendas.vender')[0];
    if (!enviada || enviada.operacao !== 'vendas.vender') throw new Error('sem venda');
    expect(enviada.venda.pagamentos).toEqual([]);
  });

  it('se a venda falhar, tenta de novo com os MESMOS ids (a venda nunca duplica)', async () => {
    let tentativa = 0;
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'vendas.vender': () => {
        tentativa += 1;
        return tentativa === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: vendaGravada('50.00', '50.00') };
      },
    });
    const u = await abrir(ponte, 'Vender');
    await lerEEscolherCliente(u);
    for (let i = 0; i < 2; i += 1) {
      await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
      const botoes = await screen.findAllByRole('button', { name: 'Concluir venda' });
      await u.click(botoes[botoes.length - 1]!);
      if (i === 0) await screen.findByText('Sem internet. Conecte-se e tente de novo.');
    }
    await screen.findByText('Venda registrada.');
    const vendas = daOperacao('vendas.vender').map((c) =>
      c.operacao === 'vendas.vender' ? c.venda : null,
    );
    expect(vendas).toHaveLength(2);
    expect(vendas[0]?.id).toBe(vendas[1]?.id);
    expect(vendas[0]?.pagamentos[0]?.id).toBe(vendas[1]?.pagamentos[0]?.id);
  });

  it('mostra a recusa do servidor (por exemplo, desconto acima do limite) sem montar texto próprio', async () => {
    const mensagem = 'O desconto passa do limite que a dona definiu (10% do valor).';
    const { ponte } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'vendas.vender': () => ({ ok: false, motivo: 'erro', mensagem }),
    });
    const u = await abrir(ponte, 'Vender');
    await lerEEscolherCliente(u);
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    const botoes = await screen.findAllByRole('button', { name: 'Concluir venda' });
    await u.click(botoes[botoes.length - 1]!);
    expect(await screen.findByText(mensagem)).toBeInTheDocument();
  });

  it('cadastra o cliente na hora e já o escolhe', async () => {
    const novo: Cliente = {
      ...cliente,
      id: '55555555-5555-4555-8555-555555555555',
      nome: 'Joana Lima',
    };
    const { ponte, daOperacao } = ponteDeTrabalho({
      'clientes.listar': () => ({ ok: true, dados: [] }),
      'clientes.cadastrar': () => ({ ok: true, dados: novo }),
    });
    const u = await abrir(ponte, 'Vender');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar cliente novo' }));
    await u.click(screen.getByRole('button', { name: 'Salvar cliente' }));
    expect(await screen.findByText('Escreva o nome do cliente.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Nome'), 'Joana Lima');
    await u.type(screen.getByLabelText('Telefone'), '11 98888-7777');
    await u.click(screen.getByRole('button', { name: 'Salvar cliente' }));
    expect(await screen.findByText('Joana Lima')).toBeInTheDocument();
    const pedido = daOperacao('clientes.cadastrar')[0];
    if (!pedido || pedido.operacao !== 'clientes.cadastrar') throw new Error('sem cadastro');
    expect(pedido.cliente).toMatchObject({ nome: 'Joana Lima', telefone: '11 98888-7777' });
    expect(pedido.cliente.id).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('sem maleta avisa em vez de mostrar a tela de venda', async () => {
    const { ponte } = ponteDeTrabalho({ 'maletas.listar': () => ({ ok: true, dados: [] }) });
    await abrir(ponte, 'Vender');
    expect(await screen.findByText(/ainda não tem maleta para vender/)).toBeInTheDocument();
  });

  it('mostra Dólar e Guarani quando há cotação', async () => {
    const { ponte } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'cotacao.hoje': () => ({
        ok: true,
        dados: {
          disponivel: true,
          dataDeHoje: '2026-11-11',
          dataDaCotacao: '2026-11-11',
          dolar: '5.00000000',
          guarani: '0.00070000',
          deDiaAnterior: false,
        },
      }),
    });
    const u = await abrir(ponte, 'Vender');
    await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1{Enter}');
    await screen.findByText(/Anel de prata: 1/);
    expect((await screen.findAllByText(/US\$ 10,00/)).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Gs\. 71\.429/).length).toBeGreaterThan(0);
  });
});

describe('Minhas vendas e estorno', () => {
  beforeEach(() => {
    statusPadrao = 'CONFERIDA';
  });
  afterEach(() => {
    statusPadrao = 'ENVIADA';
  });

  async function abrirVendas(ponte: PonteDoCorona) {
    const u = await abrir(ponte, 'Vender');
    await screen.findByLabelText('Ler o código da peça');
    return u;
  }

  it('estorna a venda com o motivo e um id próprio; o reenvio usa o mesmo id', async () => {
    let tentativa = 0;
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'vendas.vender': () => ({ ok: true, dados: vendaGravada() }),
      'vendas.estornar': () => {
        tentativa += 1;
        return tentativa === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: vendaGravada('100.00', '0.00', 'ESTORNADA') };
      },
    });
    const u = await abrirVendas(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    const c = await screen.findAllByRole('button', { name: 'Concluir venda' });
    await u.click(c[c.length - 1]!);
    await u.click(await screen.findByRole('button', { name: 'Ver minhas vendas' }));
    await u.click(await screen.findByRole('button', { name: 'Ver: Maria Souza' }));

    for (let i = 0; i < 2; i += 1) {
      await u.click(await screen.findByRole('button', { name: 'Estornar venda' }));
      await u.type(await screen.findByLabelText('Motivo do estorno'), 'Cliente desistiu');
      const botoes = screen.getAllByRole('button', { name: 'Estornar venda' });
      await u.click(botoes[botoes.length - 1]!);
      if (i === 0) await screen.findByText('Sem internet. Conecte-se e tente de novo.');
    }
    expect(await screen.findByText('Venda estornada.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Estornar venda' })).not.toBeInTheDocument();
    const pedidos = daOperacao('vendas.estornar').map((p) =>
      p.operacao === 'vendas.estornar' ? p : null,
    );
    expect(pedidos[0]?.motivo).toBe('Cliente desistiu');
    expect(pedidos[0]?.estornoId).toBe(pedidos[1]?.estornoId);
  });

  it('o estorno exige o motivo e mostra a recusa do servidor (fora do dia)', async () => {
    const mensagem = 'Só dá para estornar a venda no mesmo dia. Peça à dona para estornar.';
    const { ponte, daOperacao } = ponteDeTrabalho({
      'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
      'vendas.vender': () => ({ ok: true, dados: vendaGravada() }),
      'vendas.estornar': () => ({ ok: false, motivo: 'erro', mensagem }),
    });
    const u = await abrirVendas(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    const c = await screen.findAllByRole('button', { name: 'Concluir venda' });
    await u.click(c[c.length - 1]!);
    await u.click(await screen.findByRole('button', { name: 'Ver minhas vendas' }));
    await u.click(await screen.findByRole('button', { name: 'Ver: Maria Souza' }));
    await u.click(await screen.findByRole('button', { name: 'Estornar venda' }));
    const botoes = screen.getAllByRole('button', { name: 'Estornar venda' });
    await u.click(botoes[botoes.length - 1]!);
    expect(await screen.findByText('Diga o motivo do estorno.')).toBeInTheDocument();
    expect(daOperacao('vendas.estornar')).toHaveLength(0);
    await u.type(screen.getByLabelText('Motivo do estorno'), 'Engano');
    await u.click(screen.getAllByRole('button', { name: 'Estornar venda' }).at(-1)!);
    expect(await screen.findByText(mensagem)).toBeInTheDocument();
    await waitFor(() =>
      expect(within(document.body).queryByText('Estornar esta venda?')).not.toBeInTheDocument(),
    );
  });
});
