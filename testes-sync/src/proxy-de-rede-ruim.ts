import {
  createServer,
  request,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from 'node:http';
import type { AddressInfo } from 'node:net';
import { criarSorteio } from './sorteio';

/**
 * Proxy HTTP local que imita a rede ruim do vendedor (ADR-0013, itens 19 e 20): atrasa, derruba a conexão, devolve 502,
 * limita a banda e, principalmente, corta **depois de o servidor gravar e antes de o app receber a resposta**, o caso
 * que o reenvio com o mesmo `operacao_id` precisa resolver sem duplicar nada.
 *
 * Tudo é decidido por um sorteio com semente (ou por um roteiro fixo): o teste que falhar roda de novo com a mesma
 * semente e acontece a mesma coisa. Cada pedido sorteia sempre os mesmos números, para a sequência não "desencaixar".
 */
export type FalhaDaRede =
  | 'QUEDA_ANTES'
  | 'CORTE_DEPOIS_DE_GRAVAR'
  | 'ERRO_502'
  /** O servidor respondeu, metade da resposta chega e a conexão cai (queda no meio do download de uma foto). */
  | 'CORTE_NO_MEIO_DA_RESPOSTA';

/** Um roteiro só para os pedidos de um caminho: "o 1º push corta depois de gravar", sem contar saúde, pull e fotos. */
export interface RoteiroPorCaminho {
  /** Começo do caminho (sem a busca, `?...`) ou expressão regular. */
  caminho: string | RegExp;
  /** Falhas na ordem dos pedidos deste caminho (`undefined` = passa sem falha). Depois do fim, vale o resto do plano. */
  roteiro: ReadonlyArray<FalhaDaRede | undefined>;
}

export interface PlanoDeRede {
  /** Falhas possíveis e o peso de cada uma. Vazio: nenhuma falha sorteada. */
  sorteio: ReadonlyArray<{ falha: FalhaDaRede; peso: number }>;
  /** Chance (0 a 1) de um pedido falhar quando há falhas no sorteio. Padrão 1. */
  chanceDeFalha?: number;
  /** Falhas na ordem dos pedidos (`undefined` = sem falha). Vale antes do sorteio, enquanto durar. */
  roteiro?: ReadonlyArray<FalhaDaRede | undefined>;
  /** Atraso sorteado entre o mínimo e o máximo antes de repassar, em milissegundos. */
  atrasoMs?: { minimo: number; maximo: number };
  /** Banda: o pedido e a resposta demoram `bytes / bytesPorSegundo`. */
  bytesPorSegundo?: number;
  /** Roteiros por caminho; valem antes do `roteiro` geral e do sorteio. Cada plano novo começa a contagem do zero. */
  porCaminho?: ReadonlyArray<RoteiroPorCaminho>;
  /**
   * Cabeçalhos trocados no caminho até a API. Serve para imitar "a dona subiu a versão mínima com o app aberto" sem uma
   * segunda API: o pedido chega à API com uma versão abaixo da mínima (tarefa 5.13).
   */
  cabecalhos?: Readonly<Record<string, string>>;
}

/** Rede boa: nada falha. */
export const PLANO_BOM: PlanoDeRede = Object.freeze({ sorteio: [] });
/** Sem internet: toda conexão cai antes de chegar ao servidor (nem a saúde responde). */
export const PLANO_SEM_INTERNET: PlanoDeRede = Object.freeze({
  sorteio: [{ falha: 'QUEDA_ANTES' as const, peso: 1 }],
});

/** O que o servidor respondeu, para a varredura de marcadores proibidos. */
export interface RespostaVista {
  metodo: string;
  caminho: string;
  status: number;
  tipo: string | undefined;
  corpo: Buffer;
}

export interface OpcoesDoProxy {
  /** Endereço da API de verdade, por exemplo `http://127.0.0.1:8099`. */
  destino: string;
  semente: number;
  plano: PlanoDeRede;
  /** Porta local; omitida, o sistema escolhe uma livre. */
  porta?: number;
  /** Recebe toda resposta que o servidor deu (mesmo as que a rede cortou depois). */
  aoResponder?: (r: RespostaVista) => void;
}

export interface RegistroDoPedido {
  numero: number;
  metodo: string;
  caminho: string;
  falha?: FalhaDaRede;
  /** O servidor recebeu e respondeu (mesmo que o app não tenha visto a resposta). */
  servidorRespondeu: boolean;
  statusDoServidor?: number;
}

export interface ProxyDeRedeRuim {
  porta: number;
  semente: number;
  /** Todos os pedidos que passaram, na ordem. */
  historico(): readonly RegistroDoPedido[];
  /** Troca o plano daqui para a frente (o sorteio continua de onde estava). */
  trocarPlano(plano: PlanoDeRede): void;
  close(): Promise<void>;
}

const esperar = (ms: number) => new Promise<void>((ok) => setTimeout(ok, ms));

function lerCorpo(req: IncomingMessage): Promise<Buffer> {
  return new Promise((ok, erro) => {
    const partes: Buffer[] = [];
    req.on('data', (c: Buffer) => partes.push(c));
    req.on('end', () => ok(Buffer.concat(partes)));
    req.on('error', erro);
  });
}

function casa(regra: RoteiroPorCaminho, caminho: string): boolean {
  const semBusca = caminho.split('?')[0] ?? caminho;
  return typeof regra.caminho === 'string'
    ? semBusca.startsWith(regra.caminho)
    : regra.caminho.test(semBusca);
}

function escolher(
  plano: PlanoDeRede,
  contagens: Map<RoteiroPorCaminho, number>,
  caminho: string,
  numero: number,
  a: number,
  b: number,
): FalhaDaRede | undefined {
  for (const regra of plano.porCaminho ?? []) {
    if (!casa(regra, caminho)) continue;
    const n = contagens.get(regra) ?? 0;
    contagens.set(regra, n + 1);
    if (n < regra.roteiro.length) return regra.roteiro[n];
  }
  if (plano.roteiro && numero < plano.roteiro.length) return plano.roteiro[numero];
  const total = plano.sorteio.reduce((s, x) => s + x.peso, 0);
  if (total <= 0 || a >= (plano.chanceDeFalha ?? 1)) return undefined;
  let alvo = b * total;
  for (const x of plano.sorteio) {
    if (alvo < x.peso) return x.falha;
    alvo -= x.peso;
  }
  return plano.sorteio[plano.sorteio.length - 1]?.falha;
}

export async function iniciarProxyDeRedeRuim(o: OpcoesDoProxy): Promise<ProxyDeRedeRuim> {
  const alvo = new URL(o.destino);
  const sorteio = criarSorteio(o.semente);
  const historico: RegistroDoPedido[] = [];
  let plano = o.plano;
  let contador = 0;
  let contagens = new Map<RoteiroPorCaminho, number>();

  const tratar = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const numero = contador++;
    // Sempre os mesmos três sorteios por pedido.
    const a = sorteio();
    const b = sorteio();
    const c = sorteio();
    const atual = plano;
    const falha = escolher(atual, contagens, req.url ?? '/', numero, a, b);
    const registro: RegistroDoPedido = {
      numero,
      metodo: req.method ?? 'GET',
      caminho: req.url ?? '/',
      servidorRespondeu: false,
      ...(falha ? { falha } : {}),
    };
    historico.push(registro);

    const corpo = await lerCorpo(req);
    const atraso = atual.atrasoMs
      ? atual.atrasoMs.minimo + c * (atual.atrasoMs.maximo - atual.atrasoMs.minimo)
      : 0;
    const banda = (bytes: number) =>
      atual.bytesPorSegundo ? (bytes / atual.bytesPorSegundo) * 1000 : 0;
    await esperar(atraso + banda(corpo.length));

    if (falha === 'QUEDA_ANTES') {
      req.socket.destroy();
      return;
    }
    if (falha === 'ERRO_502') {
      res.writeHead(502, { 'content-type': 'text/plain' });
      res.end('Bad Gateway (simulado)');
      return;
    }

    const resposta = await new Promise<{
      status: number;
      cabecalhos: Record<string, string | string[] | undefined>;
      corpo: Buffer;
    }>((ok, erro) => {
      const cabecalhos = {
        ...req.headers,
        ...(atual.cabecalhos ?? {}),
        host: alvo.host,
        connection: 'close',
      };
      const saida = request(
        {
          host: alvo.hostname,
          port: alvo.port,
          method: req.method,
          path: req.url,
          headers: cabecalhos,
          agent: false,
        },
        (r) => {
          const partes: Buffer[] = [];
          r.on('data', (x: Buffer) => partes.push(x));
          r.on('end', () =>
            ok({
              status: r.statusCode ?? 502,
              cabecalhos: r.headers,
              corpo: Buffer.concat(partes),
            }),
          );
          r.on('error', erro);
        },
      );
      saida.on('error', erro);
      saida.end(corpo);
    }).catch(() => undefined);

    if (!resposta) {
      res.writeHead(502, { 'content-type': 'text/plain' });
      res.end('Servidor fora do ar');
      return;
    }
    registro.servidorRespondeu = true;
    registro.statusDoServidor = resposta.status;
    const tipo = resposta.cabecalhos['content-type'];
    o.aoResponder?.({
      metodo: registro.metodo,
      caminho: registro.caminho,
      status: resposta.status,
      tipo: Array.isArray(tipo) ? tipo[0] : tipo,
      corpo: resposta.corpo,
    });

    if (falha === 'CORTE_DEPOIS_DE_GRAVAR') {
      req.socket.destroy();
      return;
    }
    await esperar(banda(resposta.corpo.length));
    const limpos = { ...resposta.cabecalhos };
    delete limpos['transfer-encoding'];
    delete limpos['connection'];
    res.writeHead(resposta.status, {
      ...limpos,
      'content-length': String(resposta.corpo.length),
    } as Record<string, string>);
    if (falha === 'CORTE_NO_MEIO_DA_RESPOSTA') {
      // Metade chega; o resto nunca. O app vê a conexão cair no meio da leitura.
      res.write(resposta.corpo.subarray(0, Math.floor(resposta.corpo.length / 2)), () =>
        setTimeout(() => req.socket.destroy(), 20),
      );
      return;
    }
    res.end(resposta.corpo);
  };

  const servidor: Server = createServer((req, res) => {
    tratar(req, res).catch(() => req.socket.destroy());
  });
  await new Promise<void>((ok) => servidor.listen(o.porta ?? 0, '127.0.0.1', ok));

  return {
    porta: (servidor.address() as AddressInfo).port,
    semente: o.semente,
    historico: () => historico,
    trocarPlano: (novo) => {
      plano = novo;
      contagens = new Map();
    },
    close: () =>
      new Promise<void>((ok) => {
        servidor.closeAllConnections();
        servidor.close(() => ok());
      }),
  };
}
