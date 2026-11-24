import { Aviso, Botao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_CONCILIACOES } from '../../textos-conciliacoes';
import { TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from '../../textos-relatorios-devolucao';
import { dataEHora } from '../sincronizacao/dados';
import { valorDaDiferenca } from './TelaConferenciaDoDinheiro';

const T = TEXTOS_DE_CONCILIACOES;
const D = T.diaria;

export interface RodadaDaTela {
  id: string;
  iniciadaEm: string;
  origem: string;
  pedidaPor: string;
  situacao: string;
  totalDiferencas: number;
  chavesComFalha: string[];
}

interface DiferencaDaRodada {
  item: string;
  tipo: string;
  referencia: string;
  descricao: string;
  esperado: string;
  encontrado: string;
  unidade: string;
}

interface GrupoDaRodada {
  chave: string;
  diferencas: DiferencaDaRodada[];
}

const texto = (v: unknown): string =>
  typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '';
const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};

/** Lê uma rodada do servidor; o que não tem o formato esperado vira vazio, sem derrubar a tela. */
export function lerRodada(bruto: unknown): RodadaDaTela | null {
  const r = objeto(bruto);
  if (typeof r.id !== 'string' || typeof r.iniciadaEm !== 'string') return null;
  return {
    id: r.id,
    iniciadaEm: r.iniciadaEm,
    origem: texto(r.origem),
    pedidaPor: texto(r.pedidaPor),
    situacao: texto(r.situacao),
    totalDiferencas: typeof r.totalDiferencas === 'number' ? r.totalDiferencas : 0,
    chavesComFalha: (Array.isArray(r.chavesComFalha) ? r.chavesComFalha : []).map(texto),
  };
}

export function lerHistorico(bruto: unknown): RodadaDaTela[] {
  const itens = objeto(bruto).itens;
  return (Array.isArray(itens) ? itens : []).flatMap((i) => {
    const r = lerRodada(i);
    return r ? [r] : [];
  });
}

export function lerDetalhe(
  bruto: unknown,
): { rodada: RodadaDaTela; grupos: GrupoDaRodada[] } | null {
  const p = objeto(bruto);
  const rodada = lerRodada(p.rodada);
  if (!rodada) return null;
  const grupos = (Array.isArray(p.grupos) ? p.grupos : []).map((g) => {
    const o = objeto(g);
    return {
      chave: texto(o.chave),
      diferencas: (Array.isArray(o.diferencas) ? o.diferencas : []).map((d) => {
        const x = objeto(d);
        return {
          item: texto(x.item),
          tipo: texto(x.tipo),
          referencia: texto(x.referencia),
          descricao: texto(x.descricao),
          esperado: texto(x.esperado),
          encontrado: texto(x.encontrado),
          unidade: texto(x.unidade),
        };
      }),
    };
  });
  return { rodada, grupos };
}

/** O nome de uma conferência na tela (as da Fase 7, o estoque e a devolução e troca). */
function tituloDaChave(chave: string): string {
  return T.titulos[chave] ?? T.titulosDaConferencia[chave] ?? chave;
}

/** A situação da rodada em palavras da loja. */
export function situacaoEmPalavras(r: RodadaDaTela): string {
  if (r.situacao === 'FALHOU') return D.naoTerminou(r.chavesComFalha.map(tituloDaChave).join(', '));
  if (r.situacao === 'COM_DIFERENCA') return D.achouDiferencas(r.totalDiferencas);
  return D.tudoCerto;
}

function quemFez(r: RodadaDaTela): string {
  return r.origem === 'PEDIDA' ? D.pedidaPor(r.pedidaPor) : D.automatica;
}

function Detalhe({
  ponte,
  id,
  aoFechar,
}: {
  ponte: PonteDoCorona;
  id: string;
  aoFechar: () => void;
}) {
  const busca = useBusca(() => chamar(ponte, 'conferencias.ver', { id }), `conferencia-${id}`);
  const d = busca.dados ? lerDetalhe(busca.dados) : null;
  return (
    <section className="gestao-secao" aria-label={D.titulo}>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {!d && !busca.erro ? <p className="gestao-texto">{D.carregando}</p> : null}
      {d ? (
        <>
          <h2 className="gestao-subtitulo">{D.detalhe(dataEHora(d.rodada.iniciadaEm))}</h2>
          <Aviso tipo={d.rodada.situacao === 'SEM_DIFERENCA' ? 'sucesso' : 'erro'}>
            {situacaoEmPalavras(d.rodada)}
          </Aviso>
          {d.rodada.situacao === 'FALHOU' ? (
            <p className="gestao-texto">{D.naoTerminouAjuda}</p>
          ) : null}
          {d.grupos.map((g) => (
            <div key={g.chave}>
              <h3 className="gestao-subtitulo">{tituloDaChave(g.chave)}</h3>
              <ul>
                {g.diferencas.map((x, i) => (
                  <li key={`${x.referencia}-${i}`}>
                    {x.item ? (
                      <strong>
                        {TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO.conferencia.titulos[x.item] ?? x.item}
                        :{' '}
                      </strong>
                    ) : x.tipo ? (
                      <strong>
                        {T.referencias[x.tipo] ?? T.outraReferencia} {x.referencia.slice(0, 8)}
                        :{' '}
                      </strong>
                    ) : null}
                    {x.descricao}{' '}
                    {x.esperado !== '' && x.encontrado !== ''
                      ? T.diferenca(
                          valorDaDiferenca(x.esperado, x.unidade),
                          valorDaDiferenca(x.encontrado, x.unidade),
                        )
                      : null}
                  </li>
                ))}
              </ul>
            </div>
          ))}
          {d.grupos.length === 0 && d.rodada.situacao === 'SEM_DIFERENCA' ? (
            <p className="gestao-texto">{D.semDiferenca}</p>
          ) : null}
          <Botao onClick={aoFechar}>{D.fechar}</Botao>
        </>
      ) : null}
    </section>
  );
}

/** A conferência de hoje (tarefa 8.4): a última rodada, "Conferir agora" e o histórico. Só mostra; nada é corrigido. */
export function TelaConferenciaDiaria({ ponte }: { ponte: PonteDoCorona }) {
  const historico = useBusca(() => chamar(ponte, 'conferencias.listar'), 'conferencias');
  const [aberta, setAberta] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  // O mesmo id vale até a conferência dar certo: se a internet cair no meio, tocar de novo não faz uma segunda rodada.
  const comandoId = useRef(crypto.randomUUID());
  const trava = useRef(false);

  const rodadas = historico.dados ? lerHistorico(historico.dados) : null;
  const ultima = rodadas?.[0] ?? null;

  async function conferirAgora() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'conferencias.conferirAgora', { id: comandoId.current });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      historico.recarregar();
      return;
    }
    const feita = lerDetalhe(r.dados);
    comandoId.current = crypto.randomUUID();
    if (feita) setAberta(feita.rodada.id);
    historico.recarregar();
  }

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{D.titulo}</h1>
      <p className="gestao-texto">{D.ajuda}</p>
      <p className="gestao-texto">{D.aviso}</p>
      <Botao onClick={() => void conferirAgora()} disabled={ocupado}>
        {ocupado ? D.conferindo : D.conferirAgora}
      </Botao>
      {erro ? <AvisoDeErro erro={erro} aoTentarDeNovo={() => void conferirAgora()} /> : null}
      {historico.erro && rodadas === null ? (
        <AvisoDeErro erro={historico.erro} aoTentarDeNovo={historico.recarregar} />
      ) : null}
      {rodadas !== null && ultima === null ? <p className="gestao-texto">{D.nenhuma}</p> : null}
      {ultima ? (
        <section aria-labelledby="conferencia-ultima">
          <h2 id="conferencia-ultima" className="gestao-subtitulo">
            {D.ultima}
          </h2>
          <Aviso tipo={ultima.situacao === 'SEM_DIFERENCA' ? 'sucesso' : 'erro'}>
            {situacaoEmPalavras(ultima)}
          </Aviso>
          <p className="gestao-texto">
            {D.quando(dataEHora(ultima.iniciadaEm))}. {quemFez(ultima)}.
          </p>
          {ultima.situacao === 'FALHOU' ? (
            <p className="gestao-texto">{D.naoTerminouAjuda}</p>
          ) : null}
        </section>
      ) : null}
      {aberta ? <Detalhe ponte={ponte} id={aberta} aoFechar={() => setAberta(null)} /> : null}
      {rodadas && rodadas.length > 0 ? (
        <section aria-labelledby="conferencia-anteriores">
          <h2 id="conferencia-anteriores" className="gestao-subtitulo">
            {D.anteriores}
          </h2>
          <ul>
            {rodadas.map((r) => (
              <li key={r.id}>
                {dataEHora(r.iniciadaEm)}: {situacaoEmPalavras(r)}{' '}
                <Botao
                  aria-label={D.verDaRodada(dataEHora(r.iniciadaEm))}
                  onClick={() => setAberta(r.id)}
                >
                  {D.ver}
                </Botao>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
