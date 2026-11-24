import { Botao } from '@corona/ui';
import { useEffect, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { lerEmAberto, lerSemanasParaEscolher, lerVendedoresParaEscolher } from '../maletas/dados';
import { erroDoAcerto, lerFicha, type FichaDoAcerto } from './dados';

const T = TEXTOS_DE_ACERTOS.comecar;

export interface ComecarAcertoProps {
  ponte: PonteDoCorona;
  aoComecar: (ficha: FichaDoAcerto) => void;
  aoVoltar: () => void;
}

/** Escolhe o vendedor (e, se precisar, a semana) e começa o acerto. O id do acerto nasce aqui e vale até gravar. */
export function ComecarAcerto({ ponte, aoComecar, aoVoltar }: ComecarAcertoProps) {
  const vendedores = useBusca(() => chamar(ponte, 'vendedores.escolha'), 'acerto-vendedores');
  const semanas = useBusca(() => chamarTodasAsPaginas(ponte, 'semanas.listar'), 'acerto-semanas');
  const [vendedorId, setVendedorId] = useState('');
  const [semanaId, setSemanaId] = useState('');
  const [maisOpcoes, setMaisOpcoes] = useState(false);
  const [semMaleta, setSemMaleta] = useState(false);
  const [tentou, setTentou] = useState(false);
  const [comecando, setComecando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  // O mesmo id vale até o servidor responder: tentar de novo depois de uma falha de rede não duplica o acerto.
  const idDoAcerto = useRef(crypto.randomUUID());
  const trava = useRef(false);

  useEffect(() => {
    setSemMaleta(false);
    if (!vendedorId) return;
    let valida = true;
    void chamar(ponte, 'maletas.emAberto', { vendedorId }).then((r) => {
      if (valida && r.ok) setSemMaleta(lerEmAberto(r.dados).maletas.length === 0);
    });
    return () => {
      valida = false;
    };
  }, [ponte, vendedorId]);

  async function comecar() {
    setTentou(true);
    if (!vendedorId || trava.current) return;
    trava.current = true;
    setComecando(true);
    setErro(null);
    const r = await chamar(ponte, 'acertos.iniciar', {
      id: idDoAcerto.current,
      vendedorId,
      ...(semanaId ? { semanaId } : {}),
    });
    trava.current = false;
    setComecando(false);
    if (!r.ok) {
      setErro(erroDoAcerto(r.erro));
      return;
    }
    const ficha = lerFicha(r.dados);
    if (!ficha) {
      setErro({ tipo: 'outro', mensagem: TEXTOS_DE_ACERTOS.ficha.naoAbriu, campos: {} });
      return;
    }
    idDoAcerto.current = crypto.randomUUID();
    aoComecar(ficha);
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-comecar-acerto">
      <h2 id="titulo-comecar-acerto" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-formulario gestao-formulario--larga">
        <div className={`cs-campo${tentou && !vendedorId ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="acerto-vendedor">{T.vendedor}</label>
          <select
            id="acerto-vendedor"
            value={vendedorId}
            onChange={(e) => {
              setVendedorId(e.target.value);
              setErro(null);
            }}
          >
            <option value="">{T.escolhaVendedor}</option>
            {(vendedores.dados ? lerVendedoresParaEscolher(vendedores.dados) : []).map((v) => (
              <option key={v.id} value={v.id}>
                {v.nome}
              </option>
            ))}
          </select>
          {tentou && !vendedorId ? (
            <span role="alert" className="cs-campo__erro">
              {T.vendedorAviso}
            </span>
          ) : null}
        </div>
        {semMaleta ? <p className="gestao-texto">{T.semMaleta}</p> : null}
        <div>
          <Botao aria-expanded={maisOpcoes} onClick={() => setMaisOpcoes((a) => !a)}>
            {maisOpcoes ? T.menosOpcoes : T.maisOpcoes}
          </Botao>
        </div>
        {maisOpcoes ? (
          <div className="cs-campo">
            <label htmlFor="acerto-semana">{T.semana}</label>
            <select
              id="acerto-semana"
              value={semanaId}
              onChange={(e) => {
                setSemanaId(e.target.value);
                setErro(null);
              }}
            >
              <option value="">{T.semanaPadrao}</option>
              {(semanas.dados ? lerSemanasParaEscolher(semanas.dados) : [])
                .filter((s) => s.aberta)
                .map((s) => (
                  <option key={s.id} value={s.id}>
                    {T.nomeDaSemana(s.numero)}
                  </option>
                ))}
            </select>
            <span className="cs-campo__ajuda">{T.semanaAjuda}</span>
          </div>
        ) : null}
        {erro ? <AvisoDeErro erro={erro} aoTentarDeNovo={() => void comecar()} /> : null}
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" disabled={comecando} onClick={() => void comecar()}>
            {comecando ? T.comecando : T.comecar}
          </Botao>
          <Botao onClick={aoVoltar}>{T.voltar}</Botao>
        </div>
      </div>
    </section>
  );
}
