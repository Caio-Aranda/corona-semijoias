import { REPRESENTANTE } from './ambiente';
import { chamarApi, novoId } from './apoio';
import { gravarCotacaoDeHoje, tokenDaDonaPronta } from './massa-do-acerto';

/**
 * Massa dos cenários do Corona Vendas (feita pela API da dona, como a loja faria): cotação do dia, uma representante com
 * login e senha temporária, duas peças, uma cliente e uma maleta já enviada. A tela é o que o teste confere.
 *
 * Contas (à mão): Colar Vendas R$ 100,00 e Pulseira Vendas R$ 60,00, 5 de cada na maleta.
 */
export const PECAS = {
  colar: { nome: 'Colar Vendas', preco: '100.00', quantidade: 5 },
  pulseira: { nome: 'Pulseira Vendas', preco: '60.00', quantidade: 5 },
};

let sequencia = 0;

export interface MassaDoVendas {
  token: string;
  vendedorId: string;
  login: string;
  senhaTemporaria: string;
  nomeDaRepresentante: string;
  nomeDaCliente: string;
  clienteId: string;
  maletaId: string;
  /** O código de cada peça (o que o leitor digita). */
  codigoDoColar: string;
  codigoDaPulseira: string;
  nomeDoColar: string;
  nomeDaPulseira: string;
  colarId: string;
  pulseiraId: string;
}

/** Cada cenário cria a sua representante, para nunca depender do que outro deixou. */
export async function criarMassaDoVendas(rotulo: string): Promise<MassaDoVendas> {
  const token = await tokenDaDonaPronta();
  gravarCotacaoDeHoje();
  const n = `${Date.now().toString(36)}${sequencia++}`;

  const categoria = await chamarApi<{ id: string }>('POST', '/api/dona/categorias', token, {
    id: novoId(),
    nome: `Categoria ${rotulo} ${n}`,
  });
  const peca = async (nome: string, preco: string, codigo: string) => {
    const p = await chamarApi<{ id: string }>('POST', '/api/dona/produtos', token, {
      id: novoId(),
      nome: `${nome} ${n}`,
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
    return { id: p.id, nome: `${nome} ${n}`, codigo };
  };
  const colar = await peca(PECAS.colar.nome, PECAS.colar.preco, `VC-${n}`);
  const pulseira = await peca(PECAS.pulseira.nome, PECAS.pulseira.preco, `VP-${n}`);

  const nomeDaRepresentante = `Representante ${rotulo} ${n}`;
  const vendedor = await chamarApi<{ id: string }>('POST', '/api/dona/vendedores', token, {
    nome: nomeDaRepresentante,
    semLimite: true,
  });
  const login = `rep.${n}`;
  const usuario = await chamarApi<{ senhaTemporaria: string }>(
    'POST',
    '/api/dona/usuarios',
    token,
    { login, nome: nomeDaRepresentante, perfil: 'VENDEDOR', vendedorId: vendedor.id },
  );

  const nomeDaCliente = `Cliente ${rotulo} ${n}`;
  const cliente = await chamarApi<{ id: string }>('POST', '/api/dona/clientes', token, {
    nome: nomeDaCliente,
    vendedorResponsavelId: vendedor.id,
  });

  // Semana da dona (a de calendário da maleta): uma por cenário, num ano distante para nunca sobrepor outra.
  const numero = 3000 + (Number.parseInt(n.slice(-4), 36) % 90000);
  const inicio = new Date(Date.UTC(2800, 0, 4) + numero * 7 * 86_400_000);
  const semanaId = novoId();
  await chamarApi('POST', '/api/dona/semanas', token, {
    id: semanaId,
    numero,
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
    [colar.id, PECAS.colar.quantidade],
    [pulseira.id, PECAS.pulseira.quantidade],
  ] as const) {
    r = await chamarApi<{ version: number }>(
      'PUT',
      `/api/dona/remessas/${maletaId}/itens/${produtoId}`,
      token,
      { quantidade, version: r.version },
    );
  }
  await chamarApi('POST', `/api/dona/remessas/${maletaId}/enviar`, token, {});

  return {
    token,
    vendedorId: vendedor.id,
    login,
    senhaTemporaria: usuario.senhaTemporaria,
    nomeDaRepresentante,
    nomeDaCliente,
    clienteId: cliente.id,
    maletaId,
    codigoDoColar: colar.codigo,
    codigoDaPulseira: pulseira.codigo,
    nomeDoColar: colar.nome,
    nomeDaPulseira: pulseira.nome,
    colarId: colar.id,
    pulseiraId: pulseira.id,
  };
}

/** Faz o login de "outro computador" da representante (já com a senha trocada), para o computador do teste ser o segundo. */
export async function ocuparOPrimeiroComputador(m: MassaDoVendas): Promise<void> {
  const entrada = await chamarApi<{ tokenDeAcesso: string }>(
    'POST',
    '/api/auth/login',
    null,
    { login: m.login, senha: m.senhaTemporaria, dispositivoId: novoId() },
    'vendas',
  );
  await chamarApi(
    'POST',
    '/api/auth/trocar-senha',
    entrada.tokenDeAcesso,
    { senhaNova: REPRESENTANTE.senhaNova },
    'vendas',
  );
}

export interface NotaNaDona {
  id: string;
  numero: string;
  situacao: string;
  cliente: string;
  itensDx: number;
  valorDx: string;
}

/** As notas da representante, como a dona as vê no servidor (é aqui que se confere que o que foi feito na tela chegou). */
export function notasNaDona(m: MassaDoVendas): Promise<NotaNaDona[]> {
  return chamarApi<NotaNaDona[]>(
    'GET',
    `/api/consignado/notas?vendedorId=${m.vendedorId}`,
    m.token,
  );
}

export function situacaoDoClienteNaDona(m: MassaDoVendas): Promise<{ pendencia: string }> {
  return chamarApi<{ pendencia: string }>(
    'GET',
    `/api/consignado/clientes/${m.clienteId}/situacao`,
    m.token,
  );
}
