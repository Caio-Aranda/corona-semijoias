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
export type FalhaDaRede = 'QUEDA_ANTES' | 'CORTE_DEPOIS_DE_GRAVAR' | 'ERRO_502';

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
}

export interface OpcoesDoProxy {
  /** Endereço da API de verdade, por exemplo `http://127.0.0.1:8099`. */
  destino: string;
  semente: number;
  plano: PlanoDeRede;
  /** Porta local; omitida, o sistema escolhe uma livre. */
  porta?: number;
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

function escolher(
  plano: PlanoDeRede,
  numero: number,
  a: number,
  b: number,
): FalhaDaRede | undefined {
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

  const tratar = async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const numero = contador++;
    // Sempre os mesmos três sorteios por pedido.
    const a = sorteio();
    const b = sorteio();
    const c = sorteio();
    const atual = plano;
    const falha = escolher(atual, numero, a, b);
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
      const cabecalhos = { ...req.headers, host: alvo.host, connection: 'close' };
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
    },
    close: () =>
      new Promise<void>((ok) => {
        servidor.closeAllConnections();
        servidor.close(() => ok());
      }),
  };
}
