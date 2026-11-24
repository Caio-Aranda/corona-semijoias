import { Botao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { TIPOS_DE_MALETA, type TipoDeMaleta } from '../../../shared/maletas';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { lerOpcoes } from '../produtos/dados';
import { lerMaleta, lerSemanasParaEscolher, lerVendedoresParaEscolher, type Maleta } from './dados';

const T = TEXTOS_DE_MALETAS.nova;

export interface NovaMaletaProps {
  ponte: PonteDoCorona;
  /** A maleta (rascunho) já criada: a tela passa a montar. */
  aoCriar: (maleta: Maleta) => void;
}

/** Vendedor, semana, praça e tipo: cria o rascunho. Os bloqueios (inativo, restrição) vêm do servidor. */
export function NovaMaleta({ ponte, aoCriar }: NovaMaletaProps) {
  const vendedores = useBusca(() => chamar(ponte, 'vendedores.escolha'), 'maleta-vendedores');
  const semanas = useBusca(() => chamarTodasAsPaginas(ponte, 'semanas.listar'), 'maleta-semanas');
  const pracas = useBusca(
    () => chamarTodasAsPaginas(ponte, 'pracas.listar', { situacao: 'ATIVOS' }),
    'maleta-pracas',
  );
  const [vendedorId, setVendedorId] = useState('');
  const [semanaId, setSemanaId] = useState('');
  const [pracaId, setPracaId] = useState('');
  const [tipo, setTipo] = useState<TipoDeMaleta>('NOVA');
  const [tentou, setTentou] = useState(false);
  const [criando, setCriando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  // O mesmo identificador vale até gravar: tentar de novo depois de uma falha de rede não duplica.
  const idDaMaleta = useRef(crypto.randomUUID());
  const trava = useRef(false);

  const listaDeVendedores = vendedores.dados ? lerVendedoresParaEscolher(vendedores.dados) : [];
  const listaDeSemanas = semanas.dados ? lerSemanasParaEscolher(semanas.dados) : [];
  const listaDePracas = pracas.dados ? lerOpcoes(pracas.dados) : [];
  const vendedor = listaDeVendedores.find((v) => v.id === vendedorId);
  const pracaDoVendedor = listaDePracas.find((p) => p.id === vendedor?.pracaId)?.nome;

  async function comecar() {
    setTentou(true);
    if (!vendedorId || !semanaId || trava.current) return;
    trava.current = true;
    setCriando(true);
    setErro(null);
    const r = await chamar(ponte, 'maletas.criar', {
      id: idDaMaleta.current,
      vendedorId,
      semanaId,
      tipo,
      ...(pracaId ? { pracaId } : {}),
    });
    trava.current = false;
    setCriando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const maleta = lerMaleta(r.dados);
    if (maleta) {
      idDaMaleta.current = crypto.randomUUID();
      aoCriar(maleta);
    }
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-nova-maleta">
      <h2 id="titulo-nova-maleta" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-formulario gestao-formulario--larga">
        <div className={`cs-campo${tentou && !vendedorId ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="maleta-vendedor">{T.vendedor}</label>
          <select
            id="maleta-vendedor"
            value={vendedorId}
            onChange={(e) => {
              setVendedorId(e.target.value);
              setErro(null);
            }}
          >
            <option value="">{T.escolhaVendedor}</option>
            {listaDeVendedores.map((v) => (
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
        <div className={`cs-campo${tentou && !semanaId ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="maleta-semana">{T.semana}</label>
          <select
            id="maleta-semana"
            value={semanaId}
            onChange={(e) => {
              setSemanaId(e.target.value);
              setErro(null);
            }}
          >
            <option value="">{T.escolhaSemana}</option>
            {listaDeSemanas
              .filter((s) => s.aberta)
              .map((s) => (
                <option key={s.id} value={s.id}>
                  {T.nomeDaSemana(s.numero)}
                </option>
              ))}
          </select>
          {tentou && !semanaId ? (
            <span role="alert" className="cs-campo__erro">
              {T.semanaAviso}
            </span>
          ) : null}
        </div>
        <div className="cs-campo">
          <label htmlFor="maleta-praca">{T.praca}</label>
          <select id="maleta-praca" value={pracaId} onChange={(e) => setPracaId(e.target.value)}>
            <option value="">
              {pracaDoVendedor ? `${T.pracaDoVendedor} (${pracaDoVendedor})` : T.pracaDoVendedor}
            </option>
            {listaDePracas.map((p) => (
              <option key={p.id} value={p.id}>
                {p.nome}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="maleta-tipo">{T.tipo}</label>
          <select
            id="maleta-tipo"
            value={tipo}
            onChange={(e) => setTipo(e.target.value as TipoDeMaleta)}
          >
            {TIPOS_DE_MALETA.map((t) => (
              <option key={t} value={t}>
                {T.tipos[t]}
              </option>
            ))}
          </select>
        </div>
      </div>
      {erro ? <AvisoDeErro erro={erro} aoTentarDeNovo={() => void comecar()} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao variante="primario" onClick={() => void comecar()} disabled={criando}>
          {criando ? T.comecando : T.comecar}
        </Botao>
      </div>
    </section>
  );
}
