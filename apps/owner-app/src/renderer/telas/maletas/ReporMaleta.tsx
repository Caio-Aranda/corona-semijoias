import { Botao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { Dinheiro } from './Dinheiro';
import { FaltaDeCadastro } from '../../componentes/FaltaDeCadastro';
import { TEXTOS as TEXTOS_GERAIS } from '../../textos';
import { lerEmAberto, lerMaleta, lerSemanasParaEscolher, type Maleta } from './dados';

const T = TEXTOS_DE_MALETAS.reposicao;

export interface ReporMaletaProps {
  ponte: PonteDoCorona;
  vendedorId: string;
  vendedorNome: string;
  /** Semana e praça da maleta que está sendo reposta (podem ser trocadas/omitidas). */
  semanaId?: string;
  pracaId?: string;
  aoCriar: (maleta: Maleta) => void;
  aoVoltar: () => void;
}

/**
 * Nova reposição: sempre uma maleta nova, do mesmo vendedor (ADR-0010), sem copiar peças.
 * Mostra o que ele já tem e o limite antes de começar a ler as peças.
 */
export function ReporMaleta({
  ponte,
  vendedorId,
  vendedorNome,
  semanaId: semanaInicial,
  pracaId,
  aoCriar,
  aoVoltar,
}: ReporMaletaProps) {
  const emAberto = useBusca(
    () => chamar(ponte, 'maletas.emAberto', { vendedorId }),
    `repor-aberto-${vendedorId}`,
  );
  const semanas = useBusca(() => chamarTodasAsPaginas(ponte, 'semanas.listar'), 'repor-semanas');
  const [semanaId, setSemanaId] = useState(semanaInicial ?? '');
  const [tentou, setTentou] = useState(false);
  const [criando, setCriando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const idDaMaleta = useRef(crypto.randomUUID());
  const trava = useRef(false);

  const abertas = (semanas.dados ? lerSemanasParaEscolher(semanas.dados) : []).filter(
    (s) => s.aberta || s.id === semanaId,
  );
  const aberto = emAberto.dados ? lerEmAberto(emAberto.dados) : null;

  async function comecar() {
    setTentou(true);
    if (!semanaId || trava.current) return;
    trava.current = true;
    setCriando(true);
    setErro(null);
    const r = await chamar(ponte, 'maletas.criar', {
      id: idDaMaleta.current,
      vendedorId,
      semanaId,
      tipo: 'REPOSICAO',
      ...(pracaId ? { pracaId } : {}),
    });
    trava.current = false;
    setCriando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const maleta = lerMaleta(r.dados);
    if (maleta) aoCriar(maleta);
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-reposicao">
      <h2 id="titulo-reposicao" className="gestao-subtitulo">
        {T.titulo}: {vendedorNome}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      {emAberto.erro ? (
        <AvisoDeErro erro={emAberto.erro} aoTentarDeNovo={emAberto.recarregar} />
      ) : null}
      {!aberto && !emAberto.erro ? <p role="status">{T.conferindo}</p> : null}
      {aberto ? (
        <dl className="gestao-resumo">
          <div>
            <dt>{T.jaTem}</dt>
            <dd>
              <Dinheiro valor={aberto.valorEmAberto} />
            </dd>
          </div>
          <div>
            <dt>{T.limite}</dt>
            <dd>
              {aberto.limite !== null ? (
                <Dinheiro valor={aberto.limite} />
              ) : aberto.semLimite ? (
                T.semLimite
              ) : (
                T.naoDefinido
              )}
            </dd>
          </div>
          {aberto.sobra !== null ? (
            <div>
              <dt>{T.sobra}</dt>
              <dd>
                <Dinheiro valor={aberto.sobra} />
              </dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      {semanas.dados !== null && abertas.length === 0 ? (
        <FaltaDeCadastro
          mensagem={TEXTOS_GERAIS.faltaCadastro.semSemanaParaRepor}
          rotuloDoBotao={TEXTOS_GERAIS.faltaCadastro.cadastrarSemana}
          rota="cadastros/semanas"
        />
      ) : null}
      <div className="gestao-formulario gestao-formulario--larga">
        <div className={`cs-campo${tentou && !semanaId ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="reposicao-semana">{T.semana}</label>
          <select
            id="reposicao-semana"
            value={semanaId}
            onChange={(e) => {
              setSemanaId(e.target.value);
              setErro(null);
            }}
          >
            <option value="">{T.escolhaSemana}</option>
            {abertas.map((s) => (
              <option key={s.id} value={s.id}>
                {TEXTOS_DE_MALETAS.nova.nomeDaSemana(s.numero)}
              </option>
            ))}
          </select>
          {tentou && !semanaId ? (
            <span role="alert" className="cs-campo__erro">
              {T.semanaAviso}
            </span>
          ) : null}
        </div>
      </div>
      {erro ? <AvisoDeErro erro={erro} aoTentarDeNovo={() => void comecar()} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao variante="primario" onClick={() => void comecar()} disabled={criando}>
          {criando ? T.comecando : T.comecar}
        </Botao>
        <Botao onClick={aoVoltar} disabled={criando}>
          {T.voltar}
        </Botao>
      </div>
    </section>
  );
}
