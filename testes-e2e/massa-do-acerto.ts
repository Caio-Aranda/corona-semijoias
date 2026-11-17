import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DONA } from './ambiente';
import { chamarApi, novoId } from './apoio';

const aqui = dirname(fileURLToPath(import.meta.url));

/**
 * Massa do cenário do acerto (feita pela API, como a loja faria): uma cotação, a comissão, um vendedor com maleta de
 * duas peças já conferida por ele e uma venda à vista. Nada daqui é a tela; a tela é o que o teste confere.
 *
 * Contas da massa (feitas à mão; o teste só confere o que o servidor devolve):
 *  - Colar Aurora R$ 100,00, 5 na maleta; Pulseira Lua R$ 60,00, 4 na maleta.
 *  - Venda: 2 Colares = R$ 200,00 (o cliente pagou em dinheiro ao vendedor).
 *  - Cotação do dia (reais por moeda): Dólar R$ 5,00 e Guarani R$ 0,001, ou seja, 1 Real = 1.000 Guarani.
 *  - Vendido para a meta: R$ 200,00 / 0,001 = 200.000 Guarani. Meta 700.000, então faltam 500.000 Guarani = R$ 500,00.
 *  - Abaixo da meta a comissão é 30%: 30% de R$ 200,00 = R$ 60,00.
 */
export const MASSA = {
  nomeDoVendedor: 'Vendedora do Acerto',
  colar: { nome: 'Colar Aurora', preco: '100.00', naMaleta: 5, vendidas: 2 },
  pulseira: { nome: 'Pulseira Lua', preco: '60.00', naMaleta: 4 },
  metaGuarani: 700_000,
  percentualAbaixoDaMeta: 30,
  percentualNaMeta: 40,
};

export interface MassaDoAcerto {
  vendedorId: string;
  colar: { id: string; codigo: string };
  pulseira: { id: string; codigo: string };
}

/** A API só busca cotação na internet; no banco descartável do e2e gravamos uma de hoje (Brasília), com valores fixos. */
export function gravarCotacaoDeHoje(): void {
  const sql = `INSERT INTO corona.cotacoes (id, data_cotacao, usd_compra, usd_venda, usd_paridade_compra, usd_paridade_venda,
    pyg_compra, pyg_venda, pyg_paridade_compra, pyg_paridade_venda, buscada_em, fonte)
  VALUES (uuidv7(), (now() AT TIME ZONE 'America/Sao_Paulo')::date, 5.00, 5.00, 1, 1, 0.001, 0.001, 1, 1, now(), 'teste')
  ON CONFLICT ON CONSTRAINT cotacoes_data_unica DO NOTHING;`;
  const r = spawnSync(
    'docker',
    [
      'compose',
      '-f',
      join(aqui, 'docker-compose.yml'),
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'e2e_admin',
      '-d',
      'corona_e2e',
      '-v',
      'ON_ERROR_STOP=1',
      '-q',
    ],
    { input: sql, encoding: 'utf8', shell: process.platform === 'win32' },
  );
  if (r.status !== 0) throw new Error(`Não gravou a cotação do teste: ${r.stderr}`);
}

interface Entrada {
  tokenDeAcesso: string;
}

/** Entra como a dona; na primeira vez da rodada troca a senha temporária (assim o cenário também roda sozinho). */
export async function tokenDaDonaPronta(): Promise<string> {
  try {
    return (
      await chamarApi<Entrada>('POST', '/api/auth/login', null, {
        login: DONA.login,
        senha: DONA.senhaNova,
      })
    ).tokenDeAcesso;
  } catch {
    const restrito = await chamarApi<Entrada>('POST', '/api/auth/login', null, {
      login: DONA.login,
      senha: DONA.senhaTemporaria,
    });
    const nova = await chamarApi<Entrada>(
      'POST',
      '/api/auth/trocar-senha',
      restrito.tokenDeAcesso,
      { senhaNova: DONA.senhaNova },
    );
    return nova.tokenDeAcesso;
  }
}

/** Cria tudo: comissão, vendedor, peças, maleta enviada e conferida, e a venda de 2 colares. */
export async function criarMassaDoAcerto(token: string): Promise<MassaDoAcerto> {
  gravarCotacaoDeHoje();
  await chamarApi('PUT', '/api/dona/configuracoes/comissao', token, {
    metaGuarani: MASSA.metaGuarani,
    percentualAbaixoDaMeta: MASSA.percentualAbaixoDaMeta,
    percentualNaMeta: MASSA.percentualNaMeta,
  });

  const categoria = await chamarApi<{ id: string }>('POST', '/api/dona/categorias', token, {
    id: novoId(),
    nome: 'Categoria do Acerto',
  });
  const peca = async (nome: string, preco: string, codigo: string) => {
    const p = await chamarApi<{ id: string }>('POST', '/api/dona/produtos', token, {
      id: novoId(),
      nome,
      categoriaId: categoria.id,
      precoVenda: preco,
      precoCusto: '10.00',
      codigos: [{ codigo, tipo: 'LOJA', principal: true }],
    });
    await chamarApi('POST', '/api/dona/estoque/movimentacoes', token, {
      id: novoId(),
      produtoId: p.id,
      tipo: 'ENTRADA',
      quantidade: 10,
      motivo: 'Entrada do teste',
    });
    return { id: p.id, codigo };
  };
  const colar = await peca(MASSA.colar.nome, MASSA.colar.preco, 'AC-COLAR');
  const pulseira = await peca(MASSA.pulseira.nome, MASSA.pulseira.preco, 'AC-PULSEIRA');

  const vendedor = await chamarApi<{ id: string }>('POST', '/api/dona/vendedores', token, {
    nome: MASSA.nomeDoVendedor,
    semLimite: true,
  });
  const login = 'vendedora.acerto';
  const usuario = await chamarApi<{ senhaTemporaria: string }>(
    'POST',
    '/api/dona/usuarios',
    token,
    { login, nome: MASSA.nomeDoVendedor, perfil: 'VENDEDOR', vendedorId: vendedor.id },
  );

  // O primeiro computador do vendedor nasce liberado; a troca de senha obrigatória vem antes de tudo.
  const entrada = await chamarApi<Entrada>(
    'POST',
    '/api/auth/login',
    null,
    { login, senha: usuario.senhaTemporaria, dispositivoId: novoId() },
    'vendas',
  );
  const logado = await chamarApi<Entrada>(
    'POST',
    '/api/auth/trocar-senha',
    entrada.tokenDeAcesso,
    { senhaNova: 'Vendedora12345abc' },
    'vendas',
  );
  const doVendedor = logado.tokenDeAcesso;

  // Maleta: monta, envia, e o vendedor confere o que recebeu.
  const semanaId = novoId();
  const inicio = new Date(Date.UTC(2700, 0, 4));
  await chamarApi('POST', '/api/dona/semanas', token, {
    id: semanaId,
    numero: 2700,
    inicio: inicio.toISOString().slice(0, 10),
    fim: new Date(inicio.getTime() + 6 * 86_400_000).toISOString().slice(0, 10),
  });
  const maletaId = novoId();
  let r = await chamarApi<{ version: number }>('POST', '/api/dona/remessas', token, {
    id: maletaId,
    vendedorId: vendedor.id,
    semanaId,
    tipo: 'NOVA',
  });
  for (const [produtoId, quantidade] of [
    [colar.id, MASSA.colar.naMaleta],
    [pulseira.id, MASSA.pulseira.naMaleta],
  ] as const) {
    r = await chamarApi<{ version: number }>(
      'PUT',
      `/api/dona/remessas/${maletaId}/itens/${produtoId}`,
      token,
      { quantidade, version: r.version },
    );
  }
  await chamarApi('POST', `/api/dona/remessas/${maletaId}/enviar`, token, {});
  await chamarApi(
    'POST',
    `/api/vendedor/maletas/${maletaId}/conferencia`,
    doVendedor,
    {
      id: novoId(),
      itens: [
        { produtoId: colar.id, contada: MASSA.colar.naMaleta },
        { produtoId: pulseira.id, contada: MASSA.pulseira.naMaleta },
      ],
    },
    'vendas',
  );

  // Venda de 2 colares, paga à vista em dinheiro.
  const cliente = await chamarApi<{ id: string }>('POST', '/api/dona/clientes', token, {
    nome: 'Cliente do Acerto',
    vendedorResponsavelId: vendedor.id,
  });
  await chamarApi(
    'POST',
    '/api/vendedor/vendas',
    doVendedor,
    {
      id: novoId(),
      remessaId: maletaId,
      clienteId: cliente.id,
      itens: [{ produtoId: colar.id, quantidade: MASSA.colar.vendidas }],
      pagamentos: [{ id: novoId(), valor: '200.00', forma: 'DINHEIRO' }],
    },
    'vendas',
  );

  return { vendedorId: vendedor.id, colar, pulseira };
}
