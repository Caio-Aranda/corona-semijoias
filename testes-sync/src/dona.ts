import { randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';
import { DONA, URL_DA_API } from '../ambiente/ambiente';

/**
 * A dona pela API de verdade, sem proxy (ela está "no escritório", com internet boa). Só prepara e mexe nos dados do
 * jeito que o Corona Gestão faz; nunca escreve no banco por fora. O `testes-sync` não vai para pacote nenhum, então
 * falar com as rotas da dona aqui não fere o ADR-0003.
 */
export class ErroDaDona extends Error {
  constructor(
    readonly status: number,
    readonly corpo: string,
    caminho: string,
  ) {
    super(`A dona pediu ${caminho} e a API respondeu ${status}: ${corpo.slice(0, 500)}`);
  }
}

export interface VendedorDeTeste {
  vendedorId: string;
  usuarioId: string;
  login: string;
  senhaTemporaria: string;
  nome: string;
}

export interface AparelhoNaDona {
  id: string;
  situacao: 'ATIVO' | 'AGUARDANDO_LIBERACAO' | 'SUBSTITUIDO' | 'BLOQUEADO';
  aguardandoEnvio?: number;
}

/**
 * O número da primeira semana deste arquivo de teste: um por segundo de relógio, para dois arquivos da mesma rodada não
 * começarem no mesmo número.
 */
export function semanaInicial(agoraMs: number): number {
  return Math.floor(agoraMs / 1000) % 150_000;
}

let semanaSeguinte = 0;
const SEMANA_INICIAL = semanaInicial(Date.now());

/** Um PNG pequeno e válido, de uma cor só, diferente para cada `semente` (a foto muda, o hash muda). */
export function pngDeTeste(semente: number, lado = 24): Buffer {
  const crc = (dados: Buffer): number => {
    let c = ~0;
    for (const byte of dados) {
      c ^= byte;
      for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return ~c >>> 0;
  };
  const bloco = (tipo: string, dados: Buffer): Buffer => {
    const tamanho = Buffer.alloc(4);
    tamanho.writeUInt32BE(dados.length);
    const corpo = Buffer.concat([Buffer.from(tipo, 'ascii'), dados]);
    const soma = Buffer.alloc(4);
    soma.writeUInt32BE(crc(corpo));
    return Buffer.concat([tamanho, corpo, soma]);
  };
  const cabecalho = Buffer.alloc(13);
  cabecalho.writeUInt32BE(lado, 0);
  cabecalho.writeUInt32BE(lado, 4);
  cabecalho[8] = 8; // 8 bits
  cabecalho[9] = 2; // RGB
  const cor = [semente & 0xff, (semente >>> 8) & 0xff, (semente >>> 16) & 0xff];
  const linhas: number[] = [];
  for (let y = 0; y < lado; y++) {
    linhas.push(0);
    for (let x = 0; x < lado; x++) linhas.push(cor[0]!, (cor[1]! + x) & 0xff, (cor[2]! + y) & 0xff);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    bloco('IHDR', cabecalho),
    bloco('IDAT', deflateSync(Buffer.from(linhas))),
    bloco('IEND', Buffer.alloc(0)),
  ]);
}

export class Dona {
  private constructor(private readonly token: string) {}

  /** Entra como a dona (troca a senha temporária na primeira vez da rodada). */
  static async entrar(): Promise<Dona> {
    const tentar = (senha: string) =>
      fetch(`${URL_DA_API}/api/auth/login`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-App-Tipo': 'gestao',
          'X-App-Versao': '1.0.0',
        },
        body: JSON.stringify({ login: DONA.login, senha }),
      });
    let r = await tentar(DONA.senhaNova);
    if (r.status === 401) {
      r = await tentar(DONA.senhaTemporaria);
      if (!r.ok) throw new ErroDaDona(r.status, await r.text(), '/api/auth/login');
      const restrito = ((await r.json()) as { tokenDeAcesso: string }).tokenDeAcesso;
      const troca = await fetch(`${URL_DA_API}/api/auth/trocar-senha`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-App-Tipo': 'gestao',
          'X-App-Versao': '1.0.0',
          Authorization: `Bearer ${restrito}`,
        },
        body: JSON.stringify({ senhaNova: DONA.senhaNova }),
      });
      if (!troca.ok)
        throw new ErroDaDona(troca.status, await troca.text(), '/api/auth/trocar-senha');
      return new Dona(((await troca.json()) as { tokenDeAcesso: string }).tokenDeAcesso);
    }
    if (!r.ok) throw new ErroDaDona(r.status, await r.text(), '/api/auth/login');
    return new Dona(((await r.json()) as { tokenDeAcesso: string }).tokenDeAcesso);
  }

  async chamar<T = unknown>(metodo: string, caminho: string, corpo?: unknown): Promise<T> {
    const r = await fetch(`${URL_DA_API}${caminho}`, {
      method: metodo,
      headers: {
        'X-App-Tipo': 'gestao',
        'X-App-Versao': '1.0.0',
        Authorization: `Bearer ${this.token}`,
        ...(corpo === undefined || corpo instanceof FormData
          ? {}
          : { 'Content-Type': 'application/json' }),
      },
      ...(corpo === undefined
        ? {}
        : { body: corpo instanceof FormData ? corpo : JSON.stringify(corpo) }),
    });
    const texto = await r.text();
    if (!r.ok) throw new ErroDaDona(r.status, texto, `${metodo} ${caminho}`);
    return (texto ? JSON.parse(texto) : undefined) as T;
  }

  async categoria(nome: string): Promise<string> {
    const id = randomUUID();
    await this.chamar('POST', '/api/dona/categorias', { id, nome });
    return id;
  }

  /**
   * Semana nova, sem sobrepor nenhuma outra (o banco recusa semanas sobrepostas): semanas bem no futuro, uma por
   * segundo de relógio desde o início deste arquivo de teste mais um contador; se colidir com a de outro arquivo da
   * mesma rodada, pula para a frente e tenta de novo.
   */
  async semana(): Promise<string> {
    for (let tentativa = 0; ; tentativa++) {
      const id = randomUUID();
      const n = SEMANA_INICIAL + semanaSeguinte++;
      const inicio = new Date(Date.UTC(2600, 0, 4) + n * 7 * 86_400_000);
      const fim = new Date(inicio.getTime() + 6 * 86_400_000);
      try {
        await this.chamar('POST', '/api/dona/semanas', {
          id,
          numero: n,
          inicio: inicio.toISOString().slice(0, 10),
          fim: fim.toISOString().slice(0, 10),
        });
        return id;
      } catch (erro) {
        if (tentativa >= 20) throw erro;
        semanaSeguinte += 1 + Math.floor(Math.random() * 1000);
      }
    }
  }

  /** Vendedor sem limite de consignação, com o usuário dele (senha temporária). */
  async vendedor(nome: string): Promise<VendedorDeTeste> {
    const v = await this.chamar<{ id: string }>('POST', '/api/dona/vendedores', {
      nome,
      semLimite: true,
    });
    const login = `v${randomUUID().replaceAll('-', '').slice(0, 12)}`;
    const u = await this.chamar<{ id: string; senhaTemporaria: string }>(
      'POST',
      '/api/dona/usuarios',
      {
        login,
        nome,
        perfil: 'VENDEDOR',
        vendedorId: v.id,
      },
    );
    return { vendedorId: v.id, usuarioId: u.id, login, senhaTemporaria: u.senhaTemporaria, nome };
  }

  /** Peça com código da loja, preço, estoque na loja e (se pedido) uma foto. */
  async peca(o: {
    categoriaId: string;
    nome: string;
    preco: string;
    estoque: number;
    foto?: number;
  }): Promise<{ id: string; codigo: string }> {
    const id = randomUUID();
    const codigo = `S-${id.slice(0, 13)}`;
    await this.chamar('POST', '/api/dona/produtos', {
      id,
      nome: o.nome,
      categoriaId: o.categoriaId,
      precoCusto: '1.00',
      precoVenda: o.preco,
      codigos: [{ codigo, tipo: 'LOJA', principal: true }],
    });
    if (o.estoque > 0) {
      await this.chamar('POST', '/api/dona/estoque/movimentacoes', {
        id: randomUUID(),
        produtoId: id,
        tipo: 'ENTRADA',
        quantidade: o.estoque,
        motivo: 'Entrada do teste',
      });
    }
    if (o.foto !== undefined) await this.foto(id, o.foto);
    return { id, codigo };
  }

  /** Manda uma foto nova para a peça (vira a principal se a peça não tinha). Devolve o hash. */
  async foto(produtoId: string, semente: number): Promise<{ id: string; hash: string }> {
    const formulario = new FormData();
    formulario.append(
      'arquivo',
      new Blob([new Uint8Array(pngDeTeste(semente))], { type: 'image/png' }),
      `foto-${semente}.png`,
    );
    return this.chamar<{ id: string; hash: string }>(
      'POST',
      `/api/dona/produtos/${produtoId}/fotos`,
      formulario,
    );
  }

  /** "Trocar a foto": uma foto só por peça, a nova substitui a anterior (a antiga vira lápide). Devolve o hash novo. */
  async trocarFoto(produtoId: string, semente: number): Promise<string> {
    return (await this.foto(produtoId, semente)).hash;
  }

  async produto(id: string): Promise<Record<string, unknown> & { version: number }> {
    return this.chamar('GET', `/api/dona/produtos/${id}`);
  }

  /** Muda o preço de venda da peça (o resto do cadastro igual). */
  async mudarPreco(produtoId: string, preco: string): Promise<void> {
    const p = await this.produto(produtoId);
    await this.chamar('PUT', `/api/dona/produtos/${produtoId}`, {
      id: produtoId,
      nome: p['nome'],
      categoriaId: p['categoriaId'],
      precoCusto: p['precoCusto'],
      precoVenda: preco,
      ...(p['referencia'] ? { referencia: p['referencia'] } : {}),
      version: p.version,
    });
  }

  /**
   * Põe um código secundário na peça ou, se ela já tem um posto aqui, tira (o código sai com lápide). Mexe na tabela
   * `produto_codigos` que vai para o computador do vendedor.
   */
  async alternarCodigoSecundario(produtoId: string): Promise<void> {
    const p = await this.produto(produtoId);
    const codigos = (p['codigos'] ?? []) as Array<{
      id: string;
      codigo: string;
      tipo: string;
      principal: boolean;
      ativo?: boolean;
      fornecedorId?: string;
    }>;
    const meu = codigos.find((c) => c.codigo.startsWith('X-'));
    const lista = meu
      ? codigos.filter((c) => c !== meu)
      : [...codigos, { codigo: `X-${randomUUID().slice(0, 12)}`, tipo: 'LOJA', principal: false }];
    await this.chamar('PUT', `/api/dona/produtos/${produtoId}/codigos`, {
      codigos: lista,
      version: p.version,
    });
  }

  /** Maleta montada e enviada ao vendedor: produto → quantidade. */
  async maleta(vendedorId: string, pecas: Record<string, number>): Promise<string> {
    const id = randomUUID();
    const semanaId = await this.semana();
    let r = await this.chamar<{ version: number }>('POST', '/api/dona/remessas', {
      id,
      vendedorId,
      semanaId,
      tipo: 'NOVA',
    });
    for (const [produtoId, quantidade] of Object.entries(pecas)) {
      r = await this.chamar<{ version: number }>(
        'PUT',
        `/api/dona/remessas/${id}/itens/${produtoId}`,
        {
          quantidade,
          version: r.version,
        },
      );
    }
    await this.chamar('POST', `/api/dona/remessas/${id}/enviar`);
    return id;
  }

  async aparelhos(vendedorId: string): Promise<AparelhoNaDona[]> {
    return this.chamar('GET', `/api/dona/vendedores/${vendedorId}/aparelhos`);
  }

  /** "Trocar computador": libera o que espera a dona. */
  async liberar(aparelhoId: string): Promise<void> {
    await this.chamar('POST', `/api/dona/aparelhos/${aparelhoId}/liberar`);
  }

  async bloquear(aparelhoId: string): Promise<void> {
    await this.chamar('POST', `/api/dona/aparelhos/${aparelhoId}/bloquear`, {
      motivo: 'Computador perdido (teste)',
    });
  }

  async quarentena(): Promise<
    Array<{ id: string; tipo: string; vendedorId: string; dados: unknown }>
  > {
    return this.chamar('GET', '/api/dona/quarentena');
  }

  async aprovarQuarentena(id: string): Promise<unknown> {
    return this.chamar('POST', `/api/dona/quarentena/${id}/aprovar`);
  }

  async descartarQuarentena(id: string): Promise<void> {
    await this.chamar('POST', `/api/dona/quarentena/${id}/descartar`, {
      motivo: 'Venda não aconteceu (teste)',
    });
  }

  async enviosComProblema(): Promise<
    Array<{
      id: string;
      tipo: string;
      codigo?: string;
      resultado?: string;
      vendedorId: string;
      dados: unknown;
    }>
  > {
    return this.chamar('GET', '/api/dona/envios-com-problema');
  }

  async conciliacao(): Promise<{
    divergencias: unknown[];
    diferencasComMaletas: unknown[];
    produtosConferidos: number;
  }> {
    return this.chamar('GET', '/api/dona/estoque/conciliacao');
  }
}
