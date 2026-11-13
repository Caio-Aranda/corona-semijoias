import { Aviso, Botao } from '@corona/ui';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from '../../textos-relatorios-devolucao';
import { dataEHora } from '../sincronizacao/dados';

const T = TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO.conferencia;

interface Divergencia {
  referencia: string;
  descricao: string;
}

interface Conferencia {
  chave: string;
  conferidos: number;
  divergencias: Divergencia[];
}

interface Resultado {
  geradaEm: string | null;
  tudoCerto: boolean;
  conferencias: Conferencia[];
}

/** Lê a resposta do servidor; o que não tem o formato esperado é descartado. */
export function lerConferencia(bruto: unknown): Resultado {
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
          referencia: typeof d.referencia === 'string' ? d.referencia : '',
          descricao: typeof d.descricao === 'string' ? d.descricao : '',
        })),
    }));
  return {
    geradaEm: typeof p.geradaEm === 'string' ? p.geradaEm : null,
    tudoCerto: p.tudoCerto === true && conferencias.every((c) => c.divergencias.length === 0),
    conferencias,
  };
}

/** Conferência das devoluções e trocas (tarefa 6.10; §13): deve vir sempre vazia. */
export function TelaConferencia({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'devolucoes.conferencia'), 'conferencia');
  const r = busca.dados ? lerConferencia(busca.dados) : null;
  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
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
                      <strong>{d.referencia}</strong>: {d.descricao}
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
