import { Aviso, Botao } from '@corona/ui';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_CONCILIACOES } from '../../textos-conciliacoes';
import { reais } from '../devolucoes/Apoio';
import { dataEHora } from '../sincronizacao/dados';

const T = TEXTOS_DE_CONCILIACOES;

interface Diferenca {
  tipo: string;
  referencia: string;
  descricao: string;
  esperado: string;
  encontrado: string;
  unidade: string;
}

interface Conferencia {
  chave: string;
  conferidos: number;
  divergencias: Diferenca[];
}

interface Resultado {
  geradaEm: string | null;
  tudoCerto: boolean;
  conferencias: Conferencia[];
}

const texto = (v: unknown): string =>
  typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '';

/** Lê a resposta do servidor; o que não tem o formato esperado é descartado. */
export function lerConferenciaDoDinheiro(bruto: unknown): Resultado {
  const p = (typeof bruto === 'object' && bruto !== null ? bruto : {}) as Record<string, unknown>;
  const conferencias: Conferencia[] = (Array.isArray(p.conferencias) ? p.conferencias : [])
    .filter(
      (c): c is Record<string, unknown> =>
        typeof c === 'object' && c !== null && typeof (c as { chave?: unknown }).chave === 'string',
    )
    .map((c) => ({
      chave: String(c.chave),
      conferidos: typeof c.conferidos === 'number' ? c.conferidos : 0,
      divergencias: (Array.isArray(c.divergencias) ? c.divergencias : [])
        .filter((d): d is Record<string, unknown> => typeof d === 'object' && d !== null)
        .map((d) => ({
          tipo: texto(d.tipo),
          referencia: texto(d.referencia),
          descricao: texto(d.descricao),
          esperado: texto(d.esperado),
          encontrado: texto(d.encontrado),
          unidade: texto(d.unidade),
        })),
    }));
  return {
    geradaEm: typeof p.geradaEm === 'string' ? p.geradaEm : null,
    tudoCerto: p.tudoCerto === true && conferencias.every((c) => c.divergencias.length === 0),
    conferencias,
  };
}

/** Reais com vírgula, peças e registros em número, porcentagem com vírgula: nunca Dólar nem Guarani (a conferência é em Real). */
export function valorDaDiferenca(valor: string, unidade: string): string {
  switch (unidade) {
    case 'REAIS':
      return reais(valor) || valor;
    case 'PECAS':
      return T.pecas(valor);
    case 'PERCENTUAL':
      return `${valor.replace('.', ',')}%`;
    default:
      return T.registros(valor);
  }
}

/** Conferência do dinheiro (tarefa 7.8; §13): só mostra o que não bate, nunca corrige nada. */
export function TelaConferenciaDoDinheiro({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'conciliacoes.conferir'), 'conciliacoes');
  const r = busca.dados ? lerConferenciaDoDinheiro(busca.dados) : null;
  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      <p className="gestao-texto">{T.aviso}</p>
      <Botao onClick={busca.recarregar} disabled={busca.carregando}>
        {busca.carregando ? T.conferindo : T.conferir}
      </Botao>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {r ? (
        <>
          <Aviso tipo={r.tudoCerto ? 'sucesso' : 'erro'}>
            {r.tudoCerto ? T.tudoCerto : T.temDiferenca}
          </Aviso>
          {r.geradaEm ? <p className="gestao-texto">{T.geradaEm(dataEHora(r.geradaEm))}</p> : null}
          {r.conferencias.map((c) => (
            <section key={c.chave} aria-labelledby={`conf-${c.chave}`}>
              <h2 id={`conf-${c.chave}`} className="gestao-subtitulo">
                {T.titulos[c.chave] ?? c.chave}
              </h2>
              <p className="gestao-texto">{T.explicacoes[c.chave] ?? ''}</p>
              <p className="gestao-texto">{T.conferidos(c.conferidos)}</p>
              {c.divergencias.length === 0 ? (
                <p className="gestao-texto">{T.semDiferenca}</p>
              ) : (
                <ul>
                  {c.divergencias.map((d, i) => (
                    <li key={`${d.referencia}-${i}`}>
                      <strong>
                        {T.referencias[d.tipo] ?? T.outraReferencia} {d.referencia.slice(0, 8)}
                      </strong>
                      : {d.descricao}{' '}
                      {T.diferenca(
                        valorDaDiferenca(d.esperado, d.unidade),
                        valorDaDiferenca(d.encontrado, d.unidade),
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ))}
        </>
      ) : null}
    </div>
  );
}
