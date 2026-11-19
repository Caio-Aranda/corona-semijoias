import { describe, expect, it } from 'vitest';
import { criarApiDoGestao } from './api';

function falso(respostas: { status: number; corpo: unknown }[] | { erro: Error }): {
  pedidos: Request[];
  fetch: typeof fetch;
} {
  const pedidos: Request[] = [];
  let i = 0;
  const fetchFalso = (pedido: Request | string | URL): Promise<Response> => {
    pedidos.push(pedido as Request);
    if ('erro' in respostas) return Promise.reject(respostas.erro);
    const r = respostas[Math.min(i++, respostas.length - 1)] ?? { status: 500, corpo: {} };
    return Promise.resolve(
      new Response(JSON.stringify(r.corpo), {
        status: r.status,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  };
  return { pedidos, fetch: fetchFalso };
}

const base = { baseUrl: 'http://api.teste', versaoDoApp: '1.0.0' };

describe('API do Corona Gestão (processo principal)', () => {
  it('entrar manda os cabeçalhos de tipo e versão e devolve as credenciais', async () => {
    const { pedidos, fetch } = falso([
      {
        status: 200,
        corpo: {
          tokenDeAcesso: 'a',
          tokenDeRenovacao: 'r',
          validadeEmSegundos: 900,
          trocaDeSenhaObrigatoria: false,
        },
      },
    ]);
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.entrar('maria', 'senha');
    expect(r).toEqual({
      ok: true,
      dados: {
        tokenDeAcesso: 'a',
        tokenDeRenovacao: 'r',
        validadeEmSegundos: 900,
        trocaDeSenhaObrigatoria: false,
      },
    });
    expect(pedidos[0]?.headers.get('X-App-Tipo')).toBe('gestao');
    expect(pedidos[0]?.headers.get('X-App-Versao')).toBe('1.0.0');
    expect(pedidos[0]?.headers.get('Authorization')).toBeNull();
    expect(await pedidos[0]?.clone().json()).toEqual({ login: 'maria', senha: 'senha' });
  });

  it('chamadas com sessão levam o token no cabeçalho Authorization', async () => {
    const { pedidos, fetch } = falso([{ status: 200, corpo: { nome: 'Maria', login: 'maria' } }]);
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.eu('token-de-acesso');
    expect(pedidos[0]?.headers.get('Authorization')).toBe('Bearer token-de-acesso');
    expect(r).toEqual({ ok: true, dados: { nome: 'Maria' } });
  });

  it('erro da API: guarda a mensagem do servidor, que é o que a tela mostra', async () => {
    const { fetch } = falso([
      {
        status: 401,
        corpo: {
          codigo: 'LOGIN_INVALIDO',
          detail: 'Não foi possível entrar. Confira o que digitou.',
        },
      },
    ]);
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.entrar('maria', 'errada');
    expect(r).toEqual({
      ok: false,
      falha: {
        tipo: 'recusada',
        status: 401,
        codigo: 'LOGIN_INVALIDO',
        mensagem: 'Não foi possível entrar. Confira o que digitou.',
      },
    });
  });

  it('aplicativo desatualizado (426) vira uma falha própria com a mensagem da API', async () => {
    const { fetch } = falso([
      {
        status: 426,
        corpo: { codigo: 'APP_DESATUALIZADO', detail: 'Seu aplicativo está desatualizado.' },
      },
    ]);
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.entrar('maria', 'senha');
    expect(r).toEqual({
      ok: false,
      falha: { tipo: 'app-desatualizado', mensagem: 'Seu aplicativo está desatualizado.' },
    });
  });

  it('sem internet: falha de conexão, sem mensagem inventada', async () => {
    const { fetch } = falso({ erro: new TypeError('fetch failed') });
    const api = criarApiDoGestao({ ...base, fetch });
    expect(await api.entrar('maria', 'senha')).toEqual({
      ok: false,
      falha: { tipo: 'sem-conexao' },
    });
  });

  it('resposta de login sem token de acesso não é aceita', async () => {
    const { fetch } = falso([{ status: 200, corpo: {} }]);
    const api = criarApiDoGestao({ ...base, fetch });
    expect(await api.entrar('maria', 'senha')).toEqual({
      ok: false,
      falha: { tipo: 'resposta-invalida' },
    });
  });
});

describe('API do Corona Gestão: vendedores (processo principal)', () => {
  const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
  const DOC = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';

  function fetchQueGuarda(resposta: () => Response) {
    const pedidos: Request[] = [];
    const fetchFalso = (pedido: Request | string | URL): Promise<Response> => {
      pedidos.push(pedido as Request);
      return Promise.resolve(resposta());
    };
    return { pedidos, fetch: fetchFalso };
  }

  it('o nome do arquivo vem do cabeçalho, com ou sem acento', async () => {
    const { nomeDoArquivoNoCabecalho } = await import('./api');
    expect(
      nomeDoArquivoNoCabecalho(`attachment; filename*=UTF-8''comprovante%20de%20moradia.pdf`),
    ).toBe('comprovante de moradia.pdf');
    expect(nomeDoArquivoNoCabecalho('attachment; filename="rg.png"')).toBe('rg.png');
    expect(nomeDoArquivoNoCabecalho(null)).toBe('documento');
  });

  it('baixar devolve nome, tipo e bytes do arquivo, com o token', async () => {
    const { pedidos, fetch } = fetchQueGuarda(
      () =>
        new Response(new Uint8Array([1, 2, 3]), {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'Content-Disposition': `attachment; filename*=UTF-8''rg.pdf`,
          },
        }),
    );
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.executar('tk', {
      operacao: 'vendedores.documentos.baixar',
      id: ID,
      documentoId: DOC,
    });
    expect(r.ok).toBe(true);
    if (r.ok) {
      const d = r.dados as { nome: string; tipo: string; bytes: Uint8Array };
      expect(d.nome).toBe('rg.pdf');
      expect(d.tipo).toBe('application/pdf');
      expect([...d.bytes]).toEqual([1, 2, 3]);
    }
    expect(pedidos[0]?.url).toBe(
      `http://api.teste/api/dona/vendedores/${ID}/documentos/${DOC}/arquivo`,
    );
    expect(pedidos[0]?.headers.get('Authorization')).toBe('Bearer tk');
  });

  it('baixar com recusa devolve o texto do servidor', async () => {
    const { fetch } = fetchQueGuarda(
      () =>
        new Response(JSON.stringify({ detail: 'Documento não encontrado.' }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.executar('tk', {
      operacao: 'vendedores.documentos.baixar',
      id: ID,
      documentoId: DOC,
    });
    expect(r).toMatchObject({ ok: false, falha: { tipo: 'recusada', status: 404 } });
  });

  it('enviar manda o arquivo em formulário (multipart), não em texto codificado', async () => {
    const { pedidos, fetch } = fetchQueGuarda(
      () =>
        new Response(JSON.stringify({ id: DOC }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
    );
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.executar('tk', {
      operacao: 'vendedores.documentos.enviar',
      id: ID,
      descricao: 'RG',
      arquivo: { nome: 'rg.pdf', bytes: new Uint8Array([1, 2, 3]) },
    });
    expect(r.ok).toBe(true);
    const pedido = pedidos[0]!;
    expect(pedido.method).toBe('POST');
    expect(pedido.url).toContain(`/api/dona/vendedores/${ID}/documentos`);
    expect(pedido.url).toContain('descricao=RG');
    expect(pedido.headers.get('Content-Type')).toMatch(/^multipart\/form-data/);
    const form = await pedido.clone().formData();
    const arquivo = form.get('arquivo') as File;
    expect(arquivo.name).toBe('rg.pdf');
    expect(arquivo.type).toBe('application/pdf');
    expect(arquivo.size).toBe(3);
  });

  it('editar vendedor manda a versão lida no corpo; abas vão para a rota da aba', async () => {
    const { pedidos, fetch } = fetchQueGuarda(
      () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const api = criarApiDoGestao({ ...base, fetch });
    await api.executar('tk', {
      operacao: 'vendedores.editar',
      id: ID,
      corpo: { nome: 'Joana', limiteConsignacao: '1500.00', version: 3 },
    });
    expect(pedidos[0]?.method).toBe('PUT');
    expect(pedidos[0]?.url).toBe(`http://api.teste/api/dona/vendedores/${ID}`);
    expect(await pedidos[0]?.clone().json()).toEqual({
      nome: 'Joana',
      limiteConsignacao: '1500.00',
      version: 3,
    });
    await api.executar('tk', {
      operacao: 'vendedores.aba.gravar',
      id: ID,
      aba: 'conjuge',
      corpo: { nome: 'Pedro', version: 0 },
    });
    expect(pedidos[1]?.method).toBe('PUT');
    expect(pedidos[1]?.url).toBe(`http://api.teste/api/dona/vendedores/${ID}/conjuge`);
  });

  it('produtos: foto em formulário, importação com várias fotos, códigos com a versão', async () => {
    const { pedidos, fetch } = fetchQueGuarda(
      () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    const api = criarApiDoGestao({ ...base, fetch });
    await api.executar('tk', {
      operacao: 'produtos.fotos.enviar',
      id: ID,
      arquivo: { nome: 'a.PNG', bytes: new Uint8Array([1, 2]) },
    });
    const foto = (await pedidos[0]!.clone().formData()).get('arquivo') as File;
    expect(pedidos[0]?.url).toBe(`http://api.teste/api/dona/produtos/${ID}/fotos`);
    expect(foto.type).toBe('image/png');
    await api.executar('tk', {
      operacao: 'produtos.importacao.confirmar',
      arquivos: [
        { nome: '1.jpg', bytes: new Uint8Array([1]) },
        { nome: '2.webp', bytes: new Uint8Array([2]) },
      ],
    });
    expect((await pedidos[1]!.clone().formData()).getAll('arquivos')).toHaveLength(2);
    await api.executar('tk', {
      operacao: 'produtos.codigos.gravar',
      id: ID,
      version: 4,
      codigos: [{ codigo: '0012', tipo: 'LOJA', principal: true }],
    });
    expect(pedidos[2]?.method).toBe('PUT');
    expect(await pedidos[2]?.clone().json()).toEqual({
      version: 4,
      codigos: [{ codigo: '0012', tipo: 'LOJA', principal: true }],
    });
  });

  it('produtos: a imagem volta como bytes', async () => {
    const { fetch } = fetchQueGuarda(
      () =>
        new Response(new Uint8Array([9, 8]), {
          status: 200,
          headers: { 'Content-Type': 'image/jpeg' },
        }),
    );
    const api = criarApiDoGestao({ ...base, fetch });
    const r = await api.executar('tk', {
      operacao: 'produtos.fotos.imagem',
      hash: 'a'.repeat(64),
      tamanho: 'miniatura',
    });
    expect(r).toMatchObject({ ok: true, dados: { tipo: 'image/jpeg' } });
    expect((r as { dados: { bytes: Uint8Array } }).dados.bytes).toEqual(new Uint8Array([9, 8]));
  });
});

describe('API do Corona Gestão: rotas certas no cliente gerado', () => {
  const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
  const PEDIDO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';

  /** Devolve o caminho que a operação chamou (o cliente gerado renumera as funções quando a API cresce). */
  async function caminhoDe(pedido: Parameters<ReturnType<typeof criarApiDoGestao>['executar']>[1]) {
    const urls: string[] = [];
    const fetchFalso = (p: Request | string | URL): Promise<Response> => {
      const r = p as Request;
      urls.push(`${r.method} ${new URL(r.url).pathname}`);
      return Promise.resolve(
        new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
      );
    };
    const api = criarApiDoGestao({ ...base, fetch: fetchFalso });
    await api.executar('tk', pedido);
    return urls[0];
  }

  it('cada lista de cadastro e os avisos chamam a rota deles', async () => {
    const lista = { pagina: 0, tamanho: 20 };
    for (const cadastro of [
      'fornecedores',
      'categorias',
      'pracas',
      'semanas',
      'restricoes',
      'clientes',
    ]) {
      expect(await caminhoDe({ cadastro, acao: 'listar', lista } as never)).toBe(
        `GET /api/dona/${cadastro}`,
      );
    }
    expect(await caminhoDe({ operacao: 'avisos.listar' })).toBe('GET /api/dona/avisos');
    expect(await caminhoDe({ operacao: 'conciliacoes.conferir' })).toBe(
      'GET /api/dona/conciliacoes',
    );
    expect(await caminhoDe({ operacao: 'usuarios.listar', lista } as never)).toBe(
      'GET /api/dona/usuarios',
    );
  });

  it('compras: listar, ver, receber e desfazer', async () => {
    expect(
      await caminhoDe({ operacao: 'compras.listar', lista: { pagina: 0, tamanho: 20 } } as never),
    ).toBe('GET /api/dona/compras');
    expect(await caminhoDe({ operacao: 'compras.ver', id: ID } as never)).toBe(
      `GET /api/dona/compras/${ID}`,
    );
    expect(
      await caminhoDe({
        operacao: 'compras.receber',
        corpo: { id: ID, fornecedorId: ID, dataCompra: '2026-11-10', itens: [] },
      } as never),
    ).toBe('POST /api/dona/compras');
    expect(
      await caminhoDe({
        operacao: 'compras.desfazer',
        id: ID,
        pedidoId: PEDIDO,
        motivo: 'Peças erradas',
      } as never),
    ).toBe(`POST /api/dona/compras/${ID}/desfazer`);
  });

  it('acertos, pendências e comissão: cada operação chama a rota certa', async () => {
    const PROD = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
    const pedido = { id: PEDIDO, faltas: [], defeitos: [], cobrancas: [], pendenciasAntigas: [] };
    const casos: [Record<string, unknown>, string][] = [
      [{ operacao: 'acertos.listar', situacao: 'EM_ACERTO' }, 'GET /api/dona/acertos'],
      [{ operacao: 'acertos.iniciar', id: ID, vendedorId: PROD }, 'POST /api/dona/acertos'],
      [{ operacao: 'acertos.ver', id: ID }, `GET /api/dona/acertos/${ID}`],
      [
        {
          operacao: 'acertos.ler',
          id: ID,
          leituraId: PEDIDO,
          produtoId: PROD,
          estadoPeca: 'BOA',
          quantidade: -1,
        },
        `POST /api/dona/acertos/${ID}/leituras`,
      ],
      [
        { operacao: 'acertos.desistir', id: ID, comandoId: PEDIDO, motivo: 'Desistiu' },
        `POST /api/dona/acertos/${ID}/desistir`,
      ],
      [{ operacao: 'acertos.previa', id: ID }, `GET /api/dona/acertos/${ID}/fechamento`],
      [
        { operacao: 'acertos.previaComDecisoes', id: ID, pedido },
        `POST /api/dona/acertos/${ID}/fechamento/previa`,
      ],
      [
        { operacao: 'acertos.fechar', id: ID, pedido: { ...pedido, liquidoMostrado: '10.00' } },
        `POST /api/dona/acertos/${ID}/fechar`,
      ],
      [{ operacao: 'pendencias.listar', status: 'ABERTA' }, 'GET /api/dona/pendencias-acerto'],
      [
        {
          operacao: 'pendencias.resolver',
          id: ID,
          comandoId: PEDIDO,
          acao: 'ENCONTRADA',
          motivo: 'Apareceu',
        },
        `POST /api/dona/pendencias-acerto/${ID}/resolver`,
      ],
      [{ operacao: 'comissao.ler' }, 'GET /api/dona/configuracoes/comissao'],
      [
        {
          operacao: 'comissao.alterar',
          metaGuarani: 700000,
          percentualAbaixoDaMeta: 30,
          percentualNaMeta: 40,
        },
        'PUT /api/dona/configuracoes/comissao',
      ],
    ];
    for (const [pedidoDaTela, esperado] of casos) {
      expect(await caminhoDe(pedidoDaTela as never)).toBe(esperado);
    }
  });

  it('acertos: o corpo do fechar leva o id do fechamento e o líquido mostrado, e nada calculado', async () => {
    const corpos: unknown[] = [];
    const fetchFalso = async (p: Request | string | URL): Promise<Response> => {
      const r = p as Request;
      corpos.push(await r.clone().json());
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const api = criarApiDoGestao({ ...base, fetch: fetchFalso });
    await api.executar('tk', {
      operacao: 'acertos.fechar',
      id: ID,
      pedido: {
        id: PEDIDO,
        faltas: [{ produtoId: ID, decisao: 'COBRAR_NO_ACERTO' }],
        liquidoMostrado: '88.00',
      },
    } as never);
    expect(corpos[0]).toMatchObject({ id: PEDIDO, liquidoMostrado: '88.00' });
    expect(Object.keys(corpos[0] as object).sort()).toEqual(['faltas', 'id', 'liquidoMostrado']);
  });

  it('financeiro: cada operação chama a rota da dona e manda só o que a tela decidiu', async () => {
    const BAIXA = '5f6e28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';
    const casos: [Record<string, unknown>, string][] = [
      [{ operacao: 'caixa.extrato', de: '2026-11-01' }, 'GET /api/dona/caixa/lancamentos'],
      [{ operacao: 'caixa.saldo' }, 'GET /api/dona/caixa/saldo'],
      [
        {
          operacao: 'caixa.lancar',
          corpo: {
            id: ID,
            tipo: 'SAIDA',
            forma: 'PIX',
            categoriaId: ID,
            data: '2026-11-10',
            descricao: 'Luz',
            valor: '10.00',
          },
        },
        'POST /api/dona/caixa/lancamentos',
      ],
      [
        { operacao: 'caixa.estornar', id: ID, corpo: { id: PEDIDO, motivo: 'Errei' } },
        `POST /api/dona/caixa/lancamentos/${ID}/estorno`,
      ],
      [{ operacao: 'caixa.categorias' }, 'GET /api/dona/caixa/categorias'],
      [
        { operacao: 'caixa.criarCategoria', corpo: { id: ID, nome: 'Luz', tipo: 'SAIDA' } },
        'POST /api/dona/caixa/categorias',
      ],
      [
        { operacao: 'caixa.renomearCategoria', id: ID, corpo: { nome: 'Água' } },
        `PUT /api/dona/caixa/categorias/${ID}`,
      ],
      [
        { operacao: 'caixa.inativarCategoria', id: ID },
        `POST /api/dona/caixa/categorias/${ID}/inativar`,
      ],
      [
        { operacao: 'caixa.reativarCategoria', id: ID },
        `POST /api/dona/caixa/categorias/${ID}/reativar`,
      ],
      [
        { operacao: 'contasAPagar.listar', lista: { situacao: 'ABERTA' } },
        'GET /api/dona/contas-a-pagar',
      ],
      [{ operacao: 'contasAPagar.ver', id: ID }, `GET /api/dona/contas-a-pagar/${ID}`],
      [
        {
          operacao: 'contasAPagar.pagar',
          id: ID,
          corpo: { id: PEDIDO, forma: 'PIX', valor: '5.00' },
        },
        `POST /api/dona/contas-a-pagar/${ID}/pagamentos`,
      ],
      [
        { operacao: 'contasAPagar.estornar', id: BAIXA, corpo: { id: PEDIDO, motivo: 'Errei' } },
        `POST /api/dona/contas-a-pagar/pagamentos/${BAIXA}/estorno`,
      ],
      [
        { operacao: 'contasAReceber.listar', lista: { nivel: 'VENDEDOR' } },
        'GET /api/dona/contas-receber',
      ],
      [{ operacao: 'recebimentos.listar', lista: {} }, 'GET /api/dona/recebimentos-vendedor'],
      [
        {
          operacao: 'recebimentos.receber',
          corpo: { id: ID, vendedorId: ID, forma: 'DINHEIRO', valor: '5.00' },
        },
        'POST /api/dona/recebimentos-vendedor',
      ],
      [
        { operacao: 'recebimentos.estornar', id: ID, corpo: { id: PEDIDO, motivo: 'Errei' } },
        `POST /api/dona/recebimentos-vendedor/${ID}/estorno`,
      ],
      [
        { operacao: 'semanas.fechar', id: ID, corpo: { id: PEDIDO } },
        `POST /api/dona/semanas/${ID}/fechar`,
      ],
    ];
    for (const [pedidoDaTela, esperado] of casos) {
      expect(await caminhoDe(pedidoDaTela as never)).toBe(esperado);
    }
  });

  it('maletas: criar, colocar e tirar peça, enviar, excluir, ver, listar e em aberto', async () => {
    const PROD = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
    const casos: [Record<string, unknown>, string][] = [
      [
        {
          operacao: 'maletas.criar',
          corpo: { id: ID, vendedorId: ID, semanaId: ID, tipo: 'NOVA' },
        },
        'POST /api/dona/remessas',
      ],
      [{ operacao: 'maletas.ver', id: ID }, `GET /api/dona/remessas/${ID}`],
      [
        { operacao: 'maletas.listar', lista: { status: 'RASCUNHO', pagina: 0, tamanho: 50 } },
        'GET /api/dona/remessas',
      ],
      [
        { operacao: 'maletas.colocarPeca', id: ID, produtoId: PROD, quantidade: 2, version: 1 },
        `PUT /api/dona/remessas/${ID}/itens/${PROD}`,
      ],
      [
        { operacao: 'maletas.tirarPeca', id: ID, produtoId: PROD, version: 2 },
        `DELETE /api/dona/remessas/${ID}/itens/${PROD}`,
      ],
      [{ operacao: 'maletas.excluir', id: ID, version: 2 }, `DELETE /api/dona/remessas/${ID}`],
      [{ operacao: 'maletas.enviar', id: ID }, `POST /api/dona/remessas/${ID}/enviar`],
      [{ operacao: 'maletas.emAberto', vendedorId: ID }, 'GET /api/dona/remessas/em-aberto'],
      [{ operacao: 'maletas.saldo', id: ID }, `GET /api/dona/remessas/${ID}/maleta`],
      [{ operacao: 'maletas.conferencia', id: ID }, `GET /api/dona/remessas/${ID}/conferencia`],
      [{ operacao: 'maletas.nota', id: ID }, `GET /api/dona/remessas/${ID}/nota`],
      [
        {
          operacao: 'maletas.devolver',
          id: ID,
          itens: [{ id: ID, produtoId: PROD, quantidade: 1 }],
        },
        `POST /api/dona/remessas/${ID}/devolucoes`,
      ],
      [
        {
          operacao: 'maletas.desfazerDevolucao',
          id: ID,
          devolucaoId: ID,
          estornoId: PROD,
          motivo: 'Engano',
        },
        `POST /api/dona/remessas/${ID}/devolucoes/${ID}/estorno`,
      ],
      [
        { operacao: 'maletas.cancelar', id: ID, motivo: 'Vendedor desistiu' },
        `POST /api/dona/remessas/${ID}/cancelar`,
      ],
      [{ operacao: 'telas.ler' }, 'GET /api/configuracoes/telas'],
      [{ operacao: 'fechamento.iniciar', id: ID }, `POST /api/maletas/${ID}/fechamento`],
      [{ operacao: 'fechamento.ler', id: ID }, `GET /api/maletas/${ID}/fechamento`],
      [
        {
          operacao: 'fechamento.leituras',
          id: ID,
          leituras: [{ id: PEDIDO, produtoId: PROD, quantidade: 1 }],
        },
        `POST /api/maletas/${ID}/fechamento/leituras`,
      ],
      [{ operacao: 'fechamento.concluir', id: ID }, `POST /api/maletas/${ID}/fechamento/concluir`],
      [
        { operacao: 'consignado.semanas', id: ID },
        `GET /api/consignado/vendedores/${ID}/semanas`,
      ],
      [{ operacao: 'consignado.semana', id: ID }, `GET /api/consignado/semanas/${ID}`],
      [{ operacao: 'consignado.notas', filtros: {} }, 'GET /api/consignado/notas'],
      [{ operacao: 'consignado.nota', id: ID }, `GET /api/consignado/notas/${ID}`],
      [
        { operacao: 'consignado.cliente', id: ID },
        `GET /api/consignado/clientes/${ID}/situacao`,
      ],
      [
        { operacao: 'consignado.fotoDoCliente', id: ID, tipo: 'documento-frente' },
        `GET /api/clientes/${ID}/fotos/documento-frente`,
      ],
      [{ operacao: 'consignado.alertas', soNaoVistos: true }, 'GET /api/dona/consignado/alertas'],
      [
        { operacao: 'consignado.alertaVisto', id: ID },
        `POST /api/dona/consignado/alertas/${ID}/visto`,
      ],
      [
        { operacao: 'relatorios.cadastros', relatorio: 'maleta-fechamento', consulta: { remessaId: ID } },
        'GET /api/dona/relatorios/maleta/fechamento',
      ],
      [
        { operacao: 'sincronizacao.aparelhos', vendedorId: ID },
        `GET /api/dona/vendedores/${ID}/aparelhos`,
      ],
      [
        { operacao: 'sincronizacao.liberarAparelho', id: ID },
        `POST /api/dona/aparelhos/${ID}/liberar`,
      ],
      [
        { operacao: 'sincronizacao.bloquearAparelho', id: ID },
        `POST /api/dona/aparelhos/${ID}/bloquear`,
      ],
      [{ operacao: 'sincronizacao.envios' }, 'GET /api/dona/envios-com-problema'],
      [
        { operacao: 'sincronizacao.tentarDeNovo', id: ID },
        `POST /api/dona/envios-com-problema/${ID}/tentar-de-novo`,
      ],
      [
        { operacao: 'sincronizacao.descartarEnvio', id: ID, motivo: 'Duplicado' },
        `POST /api/dona/envios-com-problema/${ID}/descartar`,
      ],
      [{ operacao: 'sincronizacao.quarentena' }, 'GET /api/dona/quarentena'],
      [
        { operacao: 'sincronizacao.aprovarQuarentena', id: ID },
        `POST /api/dona/quarentena/${ID}/aprovar`,
      ],
      [
        { operacao: 'sincronizacao.descartarQuarentena', id: ID, motivo: 'Duvidosa' },
        `POST /api/dona/quarentena/${ID}/descartar`,
      ],
      [
        { operacao: 'sincronizacao.valoresSubstituidos' },
        'GET /api/dona/clientes/valores-substituidos',
      ],
    ];
    for (const [pedido, esperado] of casos) {
      expect(await caminhoDe(pedido as never)).toBe(esperado);
    }
  });

  it('devoluções, trocas, créditos e garantia: cada operação chama a rota dela', async () => {
    const OUTRO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
    const casos: [Record<string, unknown>, string][] = [
      [{ operacao: 'devolucoes.autorizacoes' }, 'GET /api/dona/autorizacoes'],
      [
        { operacao: 'devolucoes.aprovarAutorizacao', id: ID },
        `POST /api/dona/autorizacoes/${ID}/aprovar`,
      ],
      [
        { operacao: 'devolucoes.negarAutorizacao', id: ID, motivo: 'Já usou a peça' },
        `POST /api/dona/autorizacoes/${ID}/negar`,
      ],
      [{ operacao: 'devolucoes.listar' }, 'GET /api/dona/devolucoes'],
      [{ operacao: 'devolucoes.manter', id: ID }, `POST /api/dona/devolucoes/${ID}/manter`],
      [
        { operacao: 'devolucoes.naoAceitar', id: ID, motivo: 'Peça usada' },
        `POST /api/dona/devolucoes/${ID}/nao-aceitar`,
      ],
      [
        { operacao: 'devolucoes.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'Engano' },
        `POST /api/dona/devolucoes/${ID}/cancelar`,
      ],
      [
        { operacao: 'devolucoes.vendasComAPeca', codigo: 'AB-1' },
        'GET /api/dona/devolucoes/vendas-com-a-peca',
      ],
      [
        {
          operacao: 'devolucoes.registrar',
          corpo: {
            id: ID,
            vendaId: OUTRO,
            motivo: 'DEFEITO',
            formaReembolso: 'PIX',
            itens: [{ produtoId: ID, quantidade: 1, estado: 'DEFEITO' }],
          },
        },
        'POST /api/dona/devolucoes',
      ],
      [{ operacao: 'trocas.listar' }, 'GET /api/dona/trocas'],
      [
        { operacao: 'trocas.cancelar', id: ID, cancelamentoId: OUTRO, motivo: 'Engano' },
        `POST /api/dona/trocas/${ID}/cancelar`,
      ],
      [{ operacao: 'creditos.listar' }, 'GET /api/dona/creditos'],
      [
        { operacao: 'creditos.transferir', id: ID, novoId: OUTRO, vendedorId: ID },
        `POST /api/dona/creditos/${ID}/transferir`,
      ],
      [{ operacao: 'garantia.ler' }, 'GET /api/dona/configuracoes/garantia'],
      [
        {
          operacao: 'garantia.alterar',
          prazoArrependimentoDias: 7,
          prazoDefeitoDias: 30,
          limiteReembolsoSemanal: null,
          valorAprovacaoReembolso: '500.00',
          percentualAlertaDevolucao: 20,
        },
        'PUT /api/dona/configuracoes/garantia',
      ],
    ];
    for (const [pedido, esperado] of casos) {
      expect(await caminhoDe(pedido as never)).toBe(esperado);
    }
  });

  it('as listas de devolução levam o filtro e o corpo vai como a tela mandou', async () => {
    const OUTRO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
    const vistos: { url: string; corpo: string }[] = [];
    const fetchFalso = async (p: Request | string | URL): Promise<Response> => {
      const r = p as Request;
      vistos.push({
        url: `${r.method} ${new URL(r.url).pathname}${new URL(r.url).search}`,
        corpo: await r.text(),
      });
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    const api = criarApiDoGestao({ ...base, fetch: fetchFalso });
    await api.executar('tk', {
      operacao: 'devolucoes.listar',
      situacao: 'MANTIDA',
      vendedorId: ID,
    });
    await api.executar('tk', {
      operacao: 'creditos.listar',
      situacao: 'UTILIZADO',
      clienteId: OUTRO,
    });
    await api.executar('tk', {
      operacao: 'creditos.transferir',
      id: ID,
      novoId: OUTRO,
      vendedorId: ID,
    });
    await api.executar('tk', {
      operacao: 'garantia.alterar',
      prazoArrependimentoDias: 7,
      prazoDefeitoDias: 30,
      limiteReembolsoSemanal: null,
      valorAprovacaoReembolso: '500.00',
      percentualAlertaDevolucao: 20,
    });
    expect(vistos[0]?.url).toBe(`GET /api/dona/devolucoes?situacao=MANTIDA&vendedorId=${ID}`);
    expect(vistos[1]?.url).toBe(`GET /api/dona/creditos?situacao=UTILIZADO&clienteId=${OUTRO}`);
    expect(JSON.parse(vistos[2]?.corpo ?? '{}')).toEqual({ id: OUTRO, vendedorId: ID });
    expect(JSON.parse(vistos[3]?.corpo ?? '{}')).toEqual({
      prazoArrependimentoDias: 7,
      prazoDefeitoDias: 30,
      valorAprovacaoReembolso: '500.00',
      percentualAlertaDevolucao: 20,
    });
  });

  it('estoque mínimo vai por PATCH, só com versão e valor', async () => {
    expect(
      await caminhoDe({
        operacao: 'produtos.estoqueMinimo',
        id: ID,
        corpo: { version: 1, estoqueMinimo: 4 },
      } as never),
    ).toBe(`PATCH /api/dona/produtos/${ID}/estoque-minimo`);
  });

  it('compra e leituras dizem se o servidor criou (201) ou já tinha registrado (200)', async () => {
    async function resposta(status: number, pedido: object) {
      const fetchFalso = (): Promise<Response> =>
        Promise.resolve(
          new Response('{}', { status, headers: { 'Content-Type': 'application/json' } }),
        );
      return criarApiDoGestao({ ...base, fetch: fetchFalso }).executar('tk', pedido as never);
    }
    const receber = { operacao: 'compras.receber', corpo: {} };
    const ler = { operacao: 'contagens.ler', id: ID, corpo: {} };
    const corrigir = { operacao: 'contagens.corrigir', id: ID, corpo: {} };
    for (const pedido of [receber, ler, corrigir]) {
      expect(await resposta(201, pedido)).toMatchObject({ ok: true, criado: true });
      expect(await resposta(200, pedido)).toMatchObject({ ok: true, criado: false });
    }
    // As demais chamadas não carregam essa informação.
    const outra = await resposta(200, { operacao: 'compras.ver', id: ID });
    expect(outra).toEqual({ ok: true, dados: {} });
  });

  it('estoque e contagens chamam as rotas certas', async () => {
    const c = (operacao: string, extra: object = {}) => caminhoDe({ operacao, ...extra } as never);
    const consulta = { pagina: 0, tamanho: 20 };
    expect(await c('estoque.ficha', { id: ID, consulta })).toBe(
      `GET /api/dona/estoque/produtos/${ID}`,
    );
    expect(await c('estoque.lancar', { corpo: {} })).toBe('POST /api/dona/estoque/movimentacoes');
    expect(await c('estoque.ajustar', { corpo: {} })).toBe('POST /api/dona/estoque/ajustes');
    expect(await c('estoque.desfazer', { id: ID, corpo: {} })).toBe(
      `POST /api/dona/estoque/movimentacoes/${ID}/estorno`,
    );
    expect(await c('estoque.lancamentos', { consulta })).toBe(
      'GET /api/dona/estoque/movimentacoes',
    );
    expect(await c('estoque.acabando', { consulta })).toBe(
      'GET /api/dona/estoque/abaixo-do-minimo',
    );
    expect(await c('contagens.listar', { consulta })).toBe('GET /api/dona/inventarios');
    expect(await c('contagens.comecar', { corpo: {} })).toBe('POST /api/dona/inventarios');
    expect(await c('contagens.ver', { id: ID })).toBe(`GET /api/dona/inventarios/${ID}`);
    expect(await c('contagens.itens', { id: ID, consulta })).toBe(
      `GET /api/dona/inventarios/${ID}/itens`,
    );
    expect(await c('contagens.ler', { id: ID, corpo: {} })).toBe(
      `POST /api/dona/inventarios/${ID}/leituras`,
    );
    expect(await c('contagens.corrigir', { id: ID, corpo: {} })).toBe(
      `POST /api/dona/inventarios/${ID}/contagens`,
    );
    expect(await c('contagens.fechar', { id: ID, corpo: {} })).toBe(
      `POST /api/dona/inventarios/${ID}/fechar`,
    );
    expect(await c('contagens.cancelar', { id: ID, corpo: {} })).toBe(
      `POST /api/dona/inventarios/${ID}/cancelar`,
    );
  });

  it('cada relatório de estoque e de compras chama a rota dele', async () => {
    const c = (relatorio: string, extra: object = {}) =>
      caminhoDe({
        operacao: 'relatorios.cadastros',
        relatorio,
        consulta: { pagina: 0, tamanho: 20, ...extra },
      } as never);
    const base = 'GET /api/dona/relatorios';
    expect(await c('estoque-posicao')).toBe(`${base}/estoque/posicao-atual`);
    expect(await c('estoque-movimentos')).toBe(`${base}/estoque/movimentacoes`);
    expect(await c('estoque-minimo')).toBe(`${base}/estoque/abaixo-do-minimo`);
    expect(await c('estoque-parados')).toBe(`${base}/estoque/parados`);
    expect(await c('estoque-contagens')).toBe(`${base}/estoque/inventarios`);
    expect(await c('estoque-conferencia')).toBe(`${base}/estoque/conciliacao`);
    expect(await c('compras-por-compra')).toBe(`${base}/compras/por-compra`);
    expect(await c('compras-por-fornecedor')).toBe(`${base}/compras/por-fornecedor`);
    expect(await c('compras-custos')).toBe(`${base}/compras/historico-de-custo`);
    expect(await c('compras-pecas', { compraId: ID })).toBe(
      `${base}/compras/por-compra/${ID}/itens`,
    );
  });
});
