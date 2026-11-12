import type { FotoPronta } from '../../shared/dados';
import type { TamanhoDeFoto } from '../../shared/operacoes';
import type { ComToken } from '../sync/ciclo';
import type { Resultado } from '../tipos';
import type { CacheDeFotos } from './cache-de-fotos';
import { sha256 as calcularSha256 } from './fila-de-fotos';
import type { TransporteDeFotos } from './transporte-de-fotos';

export interface OpcoesDoServicoDeFotos {
  cache: CacheDeFotos;
  transporte: TransporteDeFotos;
  comToken: ComToken;
  /** O último contato com o servidor deu certo? Só então vale tentar a rede (o tamanho grande é sob demanda). */
  comInternet: () => boolean;
}

/**
 * `fotos.ver` (tarefa 5.9): a foto vem do disco quando há cópia (sem rede, em poucos milissegundos). Sem cópia, só vai à
 * internet se houver internet; o que baixar fica guardado. Sem foto, a resposta é uma falha comum e a tela mostra
 * "sem foto": nada aqui trava a venda.
 */
export class ServicoDeFotos {
  constructor(private readonly o: OpcoesDoServicoDeFotos) {}

  async ver(hash: string, tamanho: TamanhoDeFoto): Promise<Resultado<FotoPronta>> {
    const doDisco = this.o.cache.ler(hash, tamanho);
    if (doDisco) return { ok: true, dados: comoEndereco(doDisco) };
    if (!this.o.comInternet()) return { ok: false, falha: { tipo: 'sem-conexao' } };
    const r = await this.o.comToken((token) => this.o.transporte.baixar(token, hash, tamanho));
    if (!r.ok) return r;
    if (r.dados.situacao !== 'baixada') return { ok: false, falha: { tipo: 'resposta-invalida' } };
    const { bytes, sha256 } = r.dados.foto;
    let confere: boolean;
    try {
      confere = this.o.cache.gravar(hash, tamanho, bytes, sha256) === 'gravada';
    } catch {
      // Disco cheio: mostra a foto mesmo assim (se o SHA-256 bate), só não guarda.
      confere = !sha256 || calcularSha256(bytes) === sha256.toLowerCase();
    }
    if (!confere) return { ok: false, falha: { tipo: 'resposta-invalida' } };
    return { ok: true, dados: comoEndereco(bytes) };
  }
}

function comoEndereco(bytes: Buffer): FotoPronta {
  return { dataUrl: `data:image/jpeg;base64,${bytes.toString('base64')}` };
}
