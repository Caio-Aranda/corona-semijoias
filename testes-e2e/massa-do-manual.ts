import { expect } from '@playwright/test';
import { REPRESENTANTE } from './ambiente';
import { chamarApi, novoId } from './apoio';
import {
  abrirOVendas,
  comecarASemana,
  entrarComoRepresentante,
  indicadorDeEnvio,
  lerCodigo,
} from './apoio-do-vendas';
import { gravarCotacaoDeHoje, tokenDaDonaPronta } from './massa-do-acerto';
import { RedeDoVendas } from './rede-do-vendas';

/**
 * Massa do manual e do vídeo da dona (zz-manual-da-dona.test.ts): tudo FICTÍCIO, no banco descartável do e2e.
 * Nada daqui toca a produção nem usa dado real de cliente.
 *
 * Contas (à mão):
 *  - Peças: Pendientes Gota R$ 89,90 · Collar Luna R$ 150,00 · Pulsera Sol R$ 60,00 · Anillo Estrella R$ 45,00 (sem estoque).
 *  - Maleta da Ana (semana 47): 10 colares + 8 pulseiras. A cliente Rosa leva 8 colares: DX = R$ 1.200,00 e, sem devolver
 *    nada, VD = R$ 1.200,00 = Gs 1.200.000 (cotação de 1 Real = 1.000 Guarani), acima da meta de Gs 700.000 → comissão 40%.
 *    A cliente Lidia leva 1 pulseira e o pedido fica em aberto (a peça fica com a cliente).
 *  - Na loja sobram, na maleta: 2 colares e 7 pulseiras - 1 com a Lidia = 6 pulseiras; a dona lê 2 + 5... veja o teste.
 */
export const PECAS_DO_MANUAL = [
  {
    nome: 'Pendientes Gota Ejemplo',
    codigo: 'EJ-001',
    preco: '89.90',
    custo: '20.00',
    estoque: 20,
  },
  { nome: 'Collar Luna Ejemplo', codigo: 'EJ-002', preco: '150.00', custo: '40.00', estoque: 20 },
  { nome: 'Pulsera Sol Ejemplo', codigo: 'EJ-003', preco: '60.00', custo: '15.00', estoque: 8 },
  { nome: 'Anillo Estrella Ejemplo', codigo: 'EJ-004', preco: '45.00', custo: '12.00', estoque: 0 },
] as const;

export const PESSOAS_DO_MANUAL = {
  dona: { login: 'lucia.ejemplo', nome: 'Lucía Ejemplo' },
  representanteDaMaleta: { nome: 'Ana Ejemplo', login: 'ana.ejemplo' },
  clienteRosa: 'Rosa Ejemplo',
  clienteLidia: 'Lidia Ejemplo',
  proveedor: 'Proveedor Ejemplo S.A.',
  semanaAnterior: { numero: 47, inicio: '2026-11-16', fim: '2026-11-22' },
  semanaNova: { numero: 48, inicio: '2026-11-23', fim: '2026-11-29' },
};

export interface MassaDoManual {
  /** A usuária "dona" do vídeo, com a senha temporária (a primeira entrada é gravada). */
  senhaTemporariaDaDona: string;
  maletaDaAnaId: string;
}

/** Cria a massa pela API e, com o Corona Vendas, a nota acertada da Rosa e a nota em aberto da Lidia. */
export async function criarMassaDoManual(): Promise<MassaDoManual> {
  const token = await tokenDaDonaPronta();
  gravarCotacaoDeHoje();
  await chamarApi('PUT', '/api/dona/configuracoes/comissao', token, {
    metaGuarani: 700_000,
    percentualAbaixoDaMeta: 30,
    percentualNaMeta: 40,
  });

  // A usuária do vídeo: administradora de verdade, com senha temporária (a dona dos testes já trocou a dela).
  const lucia = await chamarApi<{ senhaTemporaria: string }>('POST', '/api/dona/usuarios', token, {
    login: PESSOAS_DO_MANUAL.dona.login,
    nome: PESSOAS_DO_MANUAL.dona.nome,
    perfil: 'ADMIN',
  });

  const categoria = await chamarApi<{ id: string }>('POST', '/api/dona/categorias', token, {
    id: novoId(),
    nome: 'Bijouterie de ejemplo',
  });
  await chamarApi('POST', '/api/dona/fornecedores', token, {
    id: novoId(),
    razaoSocial: PESSOAS_DO_MANUAL.proveedor,
    contatoNome: 'Contacto de ejemplo',
  });
  const ids: Record<string, string> = {};
  for (const p of PECAS_DO_MANUAL) {
    const produto = await chamarApi<{ id: string }>('POST', '/api/dona/produtos', token, {
      id: novoId(),
      nome: p.nome,
      categoriaId: categoria.id,
      precoVenda: p.preco,
      precoCusto: p.custo,
      codigos: [{ codigo: p.codigo, tipo: 'LOJA', principal: true }],
    });
    ids[p.codigo] = produto.id;
    if (p.estoque > 0) {
      await chamarApi('POST', '/api/dona/estoque/movimentacoes', token, {
        id: novoId(),
        produtoId: produto.id,
        tipo: 'ENTRADA',
        quantidade: p.estoque,
        motivo: 'Entrada de ejemplo',
      });
    }
  }

  // Ana: representante já com maleta na rua (semana anterior), usada no cierre e nas notas.
  const ana = await chamarApi<{ id: string }>('POST', '/api/dona/vendedores', token, {
    nome: PESSOAS_DO_MANUAL.representanteDaMaleta.nome,
    semLimite: true,
  });
  const usuario = await chamarApi<{ senhaTemporaria: string }>(
    'POST',
    '/api/dona/usuarios',
    token,
    {
      login: PESSOAS_DO_MANUAL.representanteDaMaleta.login,
      nome: PESSOAS_DO_MANUAL.representanteDaMaleta.nome,
      perfil: 'VENDEDOR',
      vendedorId: ana.id,
    },
  );
  for (const nome of [PESSOAS_DO_MANUAL.clienteRosa, PESSOAS_DO_MANUAL.clienteLidia]) {
    await chamarApi('POST', '/api/dona/clientes', token, { nome, vendedorResponsavelId: ana.id });
  }
  const semana = PESSOAS_DO_MANUAL.semanaAnterior;
  const semanaId = novoId();
  await chamarApi('POST', '/api/dona/semanas', token, { id: semanaId, ...semana });
  const maletaId = novoId();
  let r = await chamarApi<{ version: number }>('POST', '/api/dona/remessas', token, {
    id: maletaId,
    vendedorId: ana.id,
    semanaId,
    tipo: 'NOVA',
  });
  for (const [codigo, quantidade] of [
    ['EJ-002', 10],
    ['EJ-003', 8],
  ] as const) {
    r = await chamarApi<{ version: number }>(
      'PUT',
      `/api/dona/remessas/${maletaId}/itens/${ids[codigo]}`,
      token,
      { quantidade, version: r.version },
    );
  }
  await chamarApi('POST', `/api/dona/remessas/${maletaId}/enviar`, token, {});

  // Corona Vendas (em português, fora do vídeo): a Ana faz a nota da Rosa e a acerta, e deixa a nota da Lidia em aberto.
  const rede = await RedeDoVendas.ligar();
  const vendas = await abrirOVendas('pt');
  try {
    const { janela } = vendas;
    await entrarComoRepresentante(
      janela,
      PESSOAS_DO_MANUAL.representanteDaMaleta.login,
      usuario.senhaTemporaria,
      REPRESENTANTE.senhaNova,
    );
    await comecarASemana(janela, semana.numero);

    const pedido = async (cliente: string, codigo: string, quantidade: number) => {
      await janela.getByRole('button', { name: 'Novo Pedido' }).click();
      await janela
        .getByText(cliente, { exact: true })
        .locator('xpath=ancestor::*[.//button[normalize-space()="Escolher"]][1]')
        .getByRole('button', { name: 'Escolher' })
        .click();
      await janela.getByLabel('Maleta', { exact: true }).selectOption({ index: 1 });
      for (let i = 0; i < quantidade; i++) await lerCodigo(janela, 'Ler o código da peça', codigo);
      await janela.getByRole('button', { name: 'Finalizar pedido' }).click();
      await janela.getByRole('dialog').getByRole('button', { name: 'Finalizar pedido' }).click();
      await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 30_000 });
      await janela.getByRole('button', { name: 'Voltar' }).first().click();
    };
    await pedido(PESSOAS_DO_MANUAL.clienteRosa, 'EJ-002', 8);
    await pedido(PESSOAS_DO_MANUAL.clienteLidia, 'EJ-003', 1);

    // Acerto da Rosa: não devolveu nada; paga o líquido (R$ 1.200,00 - 40% = R$ 720,00).
    await janela.getByRole('button', { name: 'Cobrança', exact: true }).click();
    await janela
      .getByRole('row')
      .filter({ hasText: PESSOAS_DO_MANUAL.clienteRosa })
      .getByRole('button', { name: 'Escolher' })
      .click();
    await janela.getByRole('button', { name: 'Concluir devolução' }).first().click();
    await janela.getByRole('dialog').getByRole('button', { name: 'Concluir devolução' }).click();
    await janela.getByRole('tab', { name: 'Forma de Pagamento' }).click();
    await janela.getByLabel('Dinheiro', { exact: true }).fill('720,00');
    await janela.getByRole('button', { name: 'Salvar acerto' }).click();
    await janela.getByRole('dialog').getByRole('button', { name: 'Salvar acerto' }).click();
    await expect(janela.getByText('Acerto salvo.')).toBeVisible();
    await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 30_000 });
  } finally {
    await vendas.app.close();
    await rede.desligar();
  }
  return { senhaTemporariaDaDona: lucia.senhaTemporaria, maletaDaAnaId: maletaId };
}
