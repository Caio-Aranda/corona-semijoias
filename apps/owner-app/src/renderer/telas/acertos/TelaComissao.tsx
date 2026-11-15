import { Aviso, Botao, Campo } from '@corona/ui';
import { useEffect, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { formatarGuarani } from '../../nucleo/moedas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { erroDoAcerto, inteiro, objeto } from './dados';

const T = TEXTOS_DE_ACERTOS.comissao;

/** Só dígitos, sem zeros que enganam; "" vira `null`. */
const inteiroDe = (digitado: string): number | null =>
  /^\d{1,10}$/.test(digitado) ? Number(digitado) : null;

/** Acerto → Comissão e meta: a meta da semana em Guarani e os dois percentuais. Vale para os próximos acertos. */
export function TelaComissao({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'comissao.ler'), 'comissao');
  const [meta, setMeta] = useState('');
  const [abaixo, setAbaixo] = useState('');
  const [naMeta, setNaMeta] = useState('');
  const [tentou, setTentou] = useState(false);
  const [salvando, setSalvando] = useState(false);
  const [salvo, setSalvo] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);

  useEffect(() => {
    if (!busca.dados) return;
    const o = objeto(busca.dados);
    setMeta(String(inteiro(o.metaGuarani)));
    setAbaixo(String(inteiro(o.percentualAbaixoDaMeta)));
    setNaMeta(String(inteiro(o.percentualNaMeta)));
  }, [busca.dados]);

  const metaN = inteiroDe(meta);
  const abaixoN = inteiroDe(abaixo);
  const naMetaN = inteiroDe(naMeta);
  const percentualOk = (n: number | null) => n !== null && n >= 0 && n <= 100;
  const ordemRuim =
    percentualOk(abaixoN) && percentualOk(naMetaN) && (abaixoN as number) > (naMetaN as number);
  const valido = metaN !== null && percentualOk(abaixoN) && percentualOk(naMetaN) && !ordemRuim;

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setTentou(true);
    setSalvo(false);
    setErro(null);
    if (!valido || salvando) return;
    setSalvando(true);
    const r = await chamar(ponte, 'comissao.alterar', {
      metaGuarani: metaN,
      percentualAbaixoDaMeta: abaixoN,
      percentualNaMeta: naMetaN,
    });
    setSalvando(false);
    if (!r.ok) {
      setErro(erroDoAcerto(r.erro));
      return;
    }
    setSalvo(true);
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <p className="gestao-texto">{T.valeParaOsProximos}</p>
      {busca.erro && !busca.dados ? (
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
      ) : null}
      {busca.dados ? (
        <form
          className="gestao-formulario gestao-formulario--larga"
          onSubmit={(e) => void salvar(e)}
        >
          <Campo
            rotulo={T.meta}
            inputMode="numeric"
            autoComplete="off"
            value={meta}
            onChange={(e) => {
              setMeta(e.target.value.replace(/\D/g, ''));
              setSalvo(false);
            }}
            {...(metaN !== null ? { ajuda: formatarGuarani(BigInt(metaN)) } : {})}
            {...(tentou && metaN === null ? { erro: T.metaAviso } : {})}
          />
          <Campo
            rotulo={T.abaixo}
            inputMode="numeric"
            autoComplete="off"
            value={abaixo}
            onChange={(e) => {
              setAbaixo(e.target.value.replace(/\D/g, ''));
              setSalvo(false);
            }}
            {...(tentou && !percentualOk(abaixoN) ? { erro: T.percentualAviso } : {})}
          />
          <Campo
            rotulo={T.naMeta}
            inputMode="numeric"
            autoComplete="off"
            value={naMeta}
            onChange={(e) => {
              setNaMeta(e.target.value.replace(/\D/g, ''));
              setSalvo(false);
            }}
            {...(tentou && !percentualOk(naMetaN) ? { erro: T.percentualAviso } : {})}
          />
          {tentou && ordemRuim ? <Aviso tipo="erro">{T.ordemAviso}</Aviso> : null}
          {erro ? <AvisoDeErro erro={erro} /> : null}
          {salvo ? <Aviso tipo="sucesso">{T.salvo}</Aviso> : null}
          <div className="gestao-acoes-da-linha">
            <Botao type="submit" variante="primario" disabled={salvando}>
              {salvando ? T.salvando : T.salvar}
            </Botao>
          </div>
        </form>
      ) : null}
    </div>
  );
}
