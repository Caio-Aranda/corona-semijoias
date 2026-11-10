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
});
