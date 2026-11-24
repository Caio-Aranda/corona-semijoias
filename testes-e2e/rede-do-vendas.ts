import { createServer, request, type IncomingMessage, type ServerResponse } from 'node:http';
import type { Socket } from 'node:net';
import { PORTA_DA_API, PORTA_DA_REDE_DO_VENDAS } from './ambiente';

/**
 * A "internet" do Corona Vendas nos testes: repassa cada pedido para a API de teste e, quando cortada, derruba a conexão
 * (o app vê o mesmo que veria sem internet). Um dedo no interruptor basta: `cortar()` e `religar()`.
 */
export class RedeDoVendas {
  private cortada = false;
  private readonly conexoes = new Set<Socket>();
  private readonly servidor = createServer((entrada, saida) => this.repassar(entrada, saida));

  private constructor() {
    this.servidor.on('connection', (s) => {
      this.conexoes.add(s);
      s.on('close', () => this.conexoes.delete(s));
    });
  }

  static ligar(): Promise<RedeDoVendas> {
    const rede = new RedeDoVendas();
    return new Promise((ok, falhou) => {
      rede.servidor.once('error', falhou);
      rede.servidor.listen(PORTA_DA_REDE_DO_VENDAS, '127.0.0.1', () => ok(rede));
    });
  }

  private repassar(entrada: IncomingMessage, saida: ServerResponse): void {
    if (this.cortada) {
      entrada.socket.destroy();
      return;
    }
    const para = request(
      {
        host: '127.0.0.1',
        port: PORTA_DA_API,
        path: entrada.url,
        method: entrada.method,
        headers: entrada.headers,
      },
      (resposta) => {
        saida.writeHead(resposta.statusCode ?? 502, resposta.headers);
        resposta.pipe(saida);
      },
    );
    para.on('error', () => entrada.socket.destroy());
    entrada.pipe(para);
  }

  cortar(): void {
    this.cortada = true;
    for (const s of this.conexoes) s.destroy();
  }

  religar(): void {
    this.cortada = false;
  }

  desligar(): Promise<void> {
    this.cortar();
    return new Promise((ok) => this.servidor.close(() => ok()));
  }
}
