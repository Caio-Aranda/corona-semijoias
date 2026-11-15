import { Aviso, Botao, Campo } from '@corona/ui';
import { useRef, useState } from 'react';
import { FORMAS_DO_FINANCEIRO, TIPOS_DO_CAIXA } from '../../../shared/financeiro';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { erroDoFinanceiro, lerCategorias } from './dados';

const T = TEXTOS_DE_FINANCEIRO.caixa.novo;
const FORMAS = TEXTOS_DE_FINANCEIRO.formas;

export interface LancarNoCaixaProps {
  ponte: PonteDoCorona;
  /** As categorias como a API devolveu (a tela só escolhe as da loja, ativas e do mesmo tipo). */
  categoriasBrutas: unknown;
  aoFeito: (jaRegistrado: boolean) => void;
  aoCancelar: () => void;
}

/** O formulário de lançamento manual. O id nasce com o formulário e vale para toda nova tentativa. */
export function LancarNoCaixa({
  ponte,
  categoriasBrutas,
  aoFeito,
  aoCancelar,
}: LancarNoCaixaProps) {
  const comandoId = useRef(crypto.randomUUID());
  const [tipo, setTipo] = useState<(typeof TIPOS_DO_CAIXA)[number]>('ENTRADA');
  const [categoriaId, setCategoriaId] = useState('');
  const [forma, setForma] = useState<string>('DINHEIRO');
  const [data, setData] = useState('');
  const [descricao, setDescricao] = useState('');
  const [digitado, setDigitado] = useState('');
  const [valor, setValor] = useState<string | null>(null);
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  const categorias = lerCategorias(categoriasBrutas).filter(
    (c) => c.ativa && !c.sistema && c.tipo === tipo,
  );
  const semDescricao = descricao.trim() === '';

  async function lancar() {
    setTentou(true);
    if (trava.current) return;
    if (!categoriaId || !data || semDescricao || valor === null || !forma) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'caixa.lancar', {
      id: comandoId.current,
      tipo,
      forma,
      categoriaId,
      data,
      descricao: descricao.trim(),
      valor,
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(erroDoFinanceiro(r.erro));
      return;
    }
    aoFeito(r.jaRegistrado === true);
  }

  return (
    <form
      className="gestao-formulario gestao-formulario--larga"
      onSubmit={(e) => {
        e.preventDefault();
        void lancar();
      }}
    >
      <h2 className="gestao-subtitulo">{T.titulo}</h2>
      <fieldset className="gestao-opcoes">
        <legend>{T.tipo}</legend>
        {TIPOS_DO_CAIXA.map((t) => (
          <label key={t} className="gestao-opcao">
            <input
              type="radio"
              name="tipo-do-lancamento"
              checked={tipo === t}
              onChange={() => {
                setTipo(t);
                setCategoriaId('');
              }}
            />{' '}
            {t === 'ENTRADA' ? T.entrou : T.saiu}
          </label>
        ))}
      </fieldset>
      <CampoDeEscolha
        id="lancamento-categoria"
        rotulo={T.categoria}
        valor={categoriaId}
        aoMudar={setCategoriaId}
        opcoes={categorias.map((c) => ({ valor: c.id, texto: c.nome }))}
        vazio={T.semCategoria}
      />
      {tentou && !categoriaId ? <Aviso tipo="erro">{T.avisoCategoria}</Aviso> : null}
      {categorias.length === 0 ? <p className="gestao-texto">{T.semCategoriasDoTipo}</p> : null}
      <CampoDeEscolha
        id="lancamento-forma"
        rotulo={T.forma}
        valor={forma}
        aoMudar={setForma}
        opcoes={FORMAS_DO_FINANCEIRO.map((f) => ({ valor: f, texto: FORMAS[f] ?? f }))}
      />
      <Campo
        type="date"
        rotulo={T.data}
        value={data}
        {...(tentou && !data ? { erro: T.avisoData } : {})}
        onChange={(e) => setData(e.target.value)}
      />
      <Campo
        rotulo={T.descricao}
        value={descricao}
        maxLength={200}
        autoComplete="off"
        {...(tentou && semDescricao ? { erro: T.avisoDescricao } : {})}
        onChange={(e) => setDescricao(e.target.value)}
      />
      <CampoDeDinheiro
        id="lancamento-valor"
        rotulo={T.valor}
        valor={digitado}
        {...(tentou && valor === null ? { erro: T.avisoValor } : {})}
        aoMudar={(d, v) => {
          setDigitado(d);
          setValor(v !== null && Number(v) > 0 ? v : null);
        }}
      />
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao type="submit" variante="primario" disabled={ocupado}>
          {T.salvar}
        </Botao>
        <Botao onClick={aoCancelar} disabled={ocupado}>
          {T.cancelar}
        </Botao>
      </div>
    </form>
  );
}
