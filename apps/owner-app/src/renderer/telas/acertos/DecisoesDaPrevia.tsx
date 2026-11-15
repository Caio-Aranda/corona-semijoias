import { Campo } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { PecaComFoto } from './BlocosDaPrevia';
import type { DecisaoDeCobranca, Decisoes, PreviaDoFechamento } from './previa';
import { ValorDoAcerto, realDoAcerto } from './Valor';

const T = TEXTOS_DE_ACERTOS.previa;

const OPCOES_DE_COBRANCA = (['COBRAR_NO_ACERTO', 'COBRAR_DEPOIS', 'LOJA_ASSUME'] as const).map(
  (v) => ({ valor: v, texto: T.opcoesDeCobranca[v] ?? v }),
);

export interface DecisoesDaPreviaProps {
  ponte: PonteDoCorona;
  previa: PreviaDoFechamento;
  decisoes: Decisoes;
  /** `digitando`: a mudança veio de texto digitado (o sistema espera a pessoa parar de digitar). */
  aoMudar: (novas: Decisoes, digitando?: boolean) => void;
  nomeDaPeca: (id: string) => string;
}

/** "Loja assume" pede o motivo: o campo aparece e o sistema só refaz os números quando ele está escrito. */
function MotivoDaLoja({
  rotulo,
  valor,
  aoMudar,
}: {
  rotulo: string;
  valor: string;
  aoMudar: (v: string) => void;
}) {
  return (
    <Campo
      rotulo={rotulo}
      value={valor}
      maxLength={400}
      autoComplete="off"
      onChange={(e) => aoMudar(e.target.value)}
      {...(valor.trim() === '' ? { erro: T.faltas.motivoAviso } : {})}
    />
  );
}

/** Quantas peças com defeito a dona aprova; guarda o que ela digita e só manda número válido. */
function DefeitoDaPrevia({
  rotulo,
  contados,
  aprovadas,
  aoMudar,
}: {
  rotulo: string;
  contados: number;
  aprovadas: number;
  aoMudar: (n: number) => void;
}) {
  const [digitado, setDigitado] = useState(String(aprovadas));
  const numero = Number(digitado);
  const valido = digitado !== '' && Number.isInteger(numero) && numero >= 0 && numero <= contados;
  return (
    <Campo
      rotulo={rotulo}
      inputMode="numeric"
      value={digitado}
      autoComplete="off"
      ajuda={T.defeitos.contadas(contados)}
      onChange={(e) => {
        const texto = e.target.value.replace(/\D/g, '');
        setDigitado(texto);
        const n = Number(texto);
        if (texto !== '' && Number.isInteger(n) && n >= 0 && n <= contados) aoMudar(n);
      }}
      {...(valido ? {} : { erro: T.defeitos.aviso(contados) })}
    />
  );
}

/** Faltas, defeitos, o que não foi aceito e as pendências antigas: cada decisão refaz a prévia no sistema. */
export function DecisoesDaPrevia({
  ponte,
  previa,
  decisoes,
  aoMudar,
  nomeDaPeca,
}: DecisoesDaPreviaProps) {
  const trocar = (parcial: Partial<Decisoes>, digitando = false) =>
    aoMudar({ ...decisoes, ...parcial }, digitando);
  return (
    <>
      {decisoes.faltas.length > 0 ? (
        <section className="gestao-grupo" aria-labelledby="previa-faltas">
          <h3 id="previa-faltas" className="gestao-subtitulo">
            {T.faltas.titulo}
          </h3>
          <p className="gestao-texto">{T.faltas.ajuda}</p>
          {decisoes.faltas.map((f, indice) => {
            const linhas = previa.faltas.filter((x) => x.produto.id === f.produto.id);
            return (
              <div key={f.produto.id} className="gestao-grupo">
                <PecaComFoto ponte={ponte} produto={f.produto} />
                {linhas.length === 0 ? (
                  <p className="gestao-texto">{T.faltas.semFalta}</p>
                ) : (
                  linhas.map((l, i) => (
                    <p key={i} className="gestao-texto">
                      {T.faltas.linha(
                        l.quantidade,
                        realDoAcerto(l.valor),
                        T.faltas.situacoes[l.status] ?? '',
                      )}
                    </p>
                  ))
                )}
                <CampoDeEscolha
                  id={`falta-${f.produto.id}`}
                  rotulo={T.faltas.decisao(f.produto.nome)}
                  valor={f.decisao}
                  opcoes={OPCOES_DE_COBRANCA}
                  aoMudar={(v) =>
                    trocar({
                      faltas: decisoes.faltas.map((x, k) =>
                        k === indice ? { ...x, decisao: v as DecisaoDeCobranca } : x,
                      ),
                    })
                  }
                />
                {f.decisao === 'LOJA_ASSUME' ? (
                  <MotivoDaLoja
                    rotulo={T.faltas.motivo(f.produto.nome)}
                    valor={f.motivo}
                    aoMudar={(motivo) =>
                      trocar(
                        {
                          faltas: decisoes.faltas.map((x, k) =>
                            k === indice ? { ...x, motivo } : x,
                          ),
                        },
                        true,
                      )
                    }
                  />
                ) : null}
              </div>
            );
          })}
        </section>
      ) : null}
      {decisoes.defeitos.length > 0 ? (
        <section className="gestao-grupo" aria-labelledby="previa-defeitos">
          <h3 id="previa-defeitos" className="gestao-subtitulo">
            {T.defeitos.titulo}
          </h3>
          <p className="gestao-texto">{T.defeitos.ajuda}</p>
          {decisoes.defeitos.map((d, indice) => (
            <div key={d.produto.id} className="gestao-grupo">
              <PecaComFoto ponte={ponte} produto={d.produto} />
              <DefeitoDaPrevia
                rotulo={T.defeitos.aprovar(d.produto.nome)}
                contados={d.contados}
                aprovadas={d.aprovadas}
                aoMudar={(n) =>
                  trocar(
                    {
                      defeitos: decisoes.defeitos.map((x, k) =>
                        k === indice ? { ...x, aprovadas: n } : x,
                      ),
                    },
                    true,
                  )
                }
              />
            </div>
          ))}
        </section>
      ) : null}
      {previa.naoAceitas.length > 0 ? (
        <section className="gestao-grupo" aria-labelledby="previa-nao-aceitas">
          <h3 id="previa-nao-aceitas" className="gestao-subtitulo">
            {T.naoAceitas.titulo}
          </h3>
          <p className="gestao-texto">{T.naoAceitas.ajuda}</p>
          {previa.naoAceitas.map((n) => {
            const indice = decisoes.cobrancas.findIndex((c) => c.origemId === n.origemId);
            const atual = decisoes.cobrancas[indice];
            if (!atual) return null;
            const valor = realDoAcerto(n.valor);
            return (
              <div key={n.origemId} className="gestao-grupo">
                <p className="gestao-texto">
                  <ValorDoAcerto valor={n.valor} />
                </p>
                <CampoDeEscolha
                  id={`cobranca-${n.origemId}`}
                  rotulo={T.naoAceitas.decisao(n.tipo, valor)}
                  valor={atual.decisao}
                  opcoes={OPCOES_DE_COBRANCA}
                  aoMudar={(v) =>
                    trocar({
                      cobrancas: decisoes.cobrancas.map((x, k) =>
                        k === indice ? { ...x, decisao: v as DecisaoDeCobranca } : x,
                      ),
                    })
                  }
                />
                {atual.decisao === 'LOJA_ASSUME' ? (
                  <MotivoDaLoja
                    rotulo={T.naoAceitas.motivo(valor)}
                    valor={atual.motivo}
                    aoMudar={(motivo) =>
                      trocar(
                        {
                          cobrancas: decisoes.cobrancas.map((x, k) =>
                            k === indice ? { ...x, motivo } : x,
                          ),
                        },
                        true,
                      )
                    }
                  />
                ) : null}
              </div>
            );
          })}
        </section>
      ) : null}
      {previa.pendenciasAntigas.length > 0 ? (
        <section className="gestao-grupo" aria-labelledby="previa-antigas">
          <h3 id="previa-antigas" className="gestao-subtitulo">
            {T.antigas.titulo}
          </h3>
          <p className="gestao-texto">{T.antigas.ajuda}</p>
          {previa.pendenciasAntigas.map((a) => {
            const indice = decisoes.pendenciasAntigas.findIndex((x) => x.pendenciaId === a.id);
            const atual = decisoes.pendenciasAntigas[indice];
            if (!atual) return null;
            const nome = nomeDaPeca(a.produtoId) || T.antigas.umaPeca;
            return (
              <div key={a.id} className="gestao-grupo">
                <p className="gestao-texto">
                  {T.antigas.linha(nome, a.quantidade, realDoAcerto(a.valor))}
                </p>
                <CampoDeEscolha
                  id={`antiga-${a.id}`}
                  rotulo={T.antigas.decisao(nome)}
                  valor={atual.decisao}
                  opcoes={[
                    { valor: 'COBRAR_NO_ACERTO', texto: T.antigas.opcoes.COBRAR_NO_ACERTO ?? '' },
                    { valor: 'MANTER', texto: T.antigas.opcoes.MANTER ?? '' },
                  ]}
                  aoMudar={(v) =>
                    trocar({
                      pendenciasAntigas: decisoes.pendenciasAntigas.map((x, k) =>
                        k === indice ? { ...x, decisao: v as 'COBRAR_NO_ACERTO' | 'MANTER' } : x,
                      ),
                    })
                  }
                />
              </div>
            );
          })}
        </section>
      ) : null}
    </>
  );
}
