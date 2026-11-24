import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { useCotacao } from '../../nucleo/cotacao';
import {
  dolarDigitado,
  dolarParaCampo,
  guaranisDaLinha,
  guaranisPorRealDaTaxa,
  guaraniDigitado,
  guaraniParaCampo,
  horaEmBrasilia,
  lerHistorico,
  lerPrevia,
  reaisDeTaxa,
  type PreviaDaCotacao,
} from '../../nucleo/cotacao-da-loja';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';

const T = TEXTOS_DE_FINANCEIRO.cotacao;

/** O que a dona vê ao abrir: a cotação que vale agora (a dela, ou a do Banco Central) nos dois campos. */
function camposDaVigente(
  taxas: { dolar: string; guarani: string; guaranisPorReal?: string | null } | null,
): { dolar: string; guarani: string } {
  if (!taxas) return { dolar: '', guarani: '' };
  const gs = taxas.guaranisPorReal ?? guaranisPorRealDaTaxa(taxas.guarani);
  return { dolar: dolarParaCampo(taxas.dolar), guarani: gs ? guaraniParaCampo(gs) : '' };
}

/**
 * Financeiro → Cotação (e o botão "Mudar a cotação" da tela inicial): a dona digita o Dólar e o Guarani de agora (a cotação da
 * casa de câmbio da fronteira). Vale a partir de agora; o que já foi lançado não muda, e para corrigir se grava outra (ADR-0006,
 * item 15). O sistema confere os limites, compara com o Banco Central e avisa se estiver muito diferente, sem travar.
 */
export function TelaCotacao({ ponte }: { ponte: PonteDoCorona }) {
  const cotacao = useCotacao();
  const vigente = useMemo(() => camposDaVigente(cotacao.taxas), [cotacao.taxas]);
  const [dolar, setDolar] = useState<string | null>(null);
  const [guarani, setGuarani] = useState<string | null>(null);
  const mostradoDolar = dolar ?? vigente.dolar;
  const mostradoGuarani = guarani ?? vigente.guarani;
  const usd = dolarDigitado(mostradoDolar);
  const gs = guaraniDigitado(mostradoGuarani);
  const [tentou, setTentou] = useState(false);
  const [previa, setPrevia] = useState<PreviaDaCotacao | null>(null);
  const [gravando, setGravando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [gravada, setGravada] = useState<string | null>(null);
  const comandoId = useRef<string>(crypto.randomUUID());
  const trava = useRef(false);
  const [dia, setDia] = useState('');

  // A prévia (conversão e comparação com o Banco Central) vem do sistema, sem gravar nada.
  useEffect(() => {
    if (usd === null || gs === null) {
      setPrevia(null);
      return;
    }
    let valida = true;
    const espera = setTimeout(() => {
      void chamar(ponte, 'cotacao.previa', { usd, guaranisPorReal: gs }).then((r) => {
        if (valida && r.ok) setPrevia(lerPrevia(r.dados));
      });
    }, 250);
    return () => {
      valida = false;
      clearTimeout(espera);
    };
  }, [ponte, usd, gs]);

  const historico = useBusca(
    () => chamar(ponte, 'cotacao.dia', dia ? { data: dia } : {}),
    `cotacao-do-dia-${dia}`,
  );
  const linhas = historico.dados ? lerHistorico(historico.dados) : [];

  async function gravar() {
    setTentou(true);
    if (trava.current || usd === null || gs === null) return;
    trava.current = true;
    setGravando(true);
    setErro(null);
    setGravada(null);
    const r = await chamar(ponte, 'cotacao.gravar', {
      id: comandoId.current,
      usd,
      guaranisPorReal: gs,
    });
    trava.current = false;
    setGravando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const gravadaEm = (r.dados as { cotacao?: { vigenteDesde?: string } } | undefined)?.cotacao
      ?.vigenteDesde;
    setGravada(T.gravada(gravadaEm ? horaEmBrasilia(gravadaEm) : ''));
    // A próxima cotação é outro lançamento (outro id); os campos voltam a seguir a vigente.
    comandoId.current = crypto.randomUUID();
    setDolar(null);
    setGuarani(null);
    cotacao.recarregar();
    historico.recarregar();
  }

  const aviso =
    previa?.avisoDeDiferenca && previa.bancoCentral
      ? previa.moedaDoAviso === 'GUARANI'
        ? T.avisoDoGuarani(
            guaraniParaCampo(previa.bancoCentral.guaranisPorReal).split(',')[0] ?? '',
          )
        : T.avisoDoDolar(reaisDeTaxa(previa.bancoCentral.dolar))
      : null;

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      <form
        className="gestao-formulario gestao-formulario--larga"
        onSubmit={(e) => {
          e.preventDefault();
          void gravar();
        }}
      >
        <Campo
          id="cotacao-dolar"
          rotulo={T.dolar}
          inputMode="decimal"
          autoComplete="off"
          value={mostradoDolar}
          onChange={(e) => setDolar(e.target.value)}
          {...(tentou && usd === null ? { erro: T.invalido } : {})}
        />
        <Campo
          id="cotacao-guarani"
          rotulo={T.guarani}
          inputMode="decimal"
          autoComplete="off"
          value={mostradoGuarani}
          onChange={(e) => setGuarani(e.target.value)}
          {...(tentou && gs === null ? { erro: T.invalido } : {})}
        />
        {previa?.bancoCentral ? (
          <p className="gestao-texto" aria-live="polite">
            {T.bancoCentral(
              dataParaTela(previa.bancoCentral.data),
              reaisDeTaxa(previa.bancoCentral.dolar),
              guaraniParaCampo(previa.bancoCentral.guaranisPorReal),
            )}
          </p>
        ) : null}
        {aviso ? <Aviso tipo="atencao">{aviso}</Aviso> : null}
        {gravada ? <Aviso tipo="sucesso">{gravada}</Aviso> : null}
        {erro ? <AvisoDeErro erro={erro} /> : null}
        <div className="gestao-acoes-da-linha">
          <Botao type="submit" variante="primario" disabled={gravando}>
            {gravando ? T.gravando : T.gravar}
          </Botao>
        </div>
      </form>

      <h2 className="gestao-subtitulo">{T.hoje}</h2>
      <Campo
        id="cotacao-outro-dia"
        rotulo={T.outroDia}
        type="date"
        value={dia}
        onChange={(e) => setDia(e.target.value)}
      />
      {linhas.length === 0 && !historico.carregando ? (
        <p className="gestao-texto">{T.vazio}</p>
      ) : (
        <Tabela
          legenda={T.legenda}
          colunas={[
            { chave: 'desde', titulo: T.colunas.valeDesde },
            { chave: 'dolar', titulo: T.colunas.dolar, numerica: true },
            { chave: 'guarani', titulo: T.colunas.guarani, numerica: true },
            { chave: 'origem', titulo: T.colunas.origem },
          ]}
          linhas={linhas.map((l) => ({
            desde: `${horaEmBrasilia(l.vigenteDesde)}${l.valeAgora ? ` · ${T.valeAgora}` : ''}`,
            dolar: reaisDeTaxa(l.dolar),
            guarani: guaranisDaLinha(l),
            origem: l.origem === 'DONA' ? T.origem.loja(l.lancadaPor) : T.origem.BANCO_CENTRAL,
          }))}
        />
      )}
      <p className="gestao-texto">{T.corrigir}</p>
    </div>
  );
}
