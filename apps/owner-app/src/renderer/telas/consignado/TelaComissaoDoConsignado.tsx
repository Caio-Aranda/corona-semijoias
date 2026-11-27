import { Aviso, Botao, Campo } from '@corona/ui';
import { useEffect, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import {
  DIAS_MAXIMOS_DO_RETORNO,
  META_MAXIMA_EM_GUARANI,
  PERCENTUAL_MAXIMO_DO_CONSIGNADO,
} from '../../../shared/consignado';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { formatarGuarani } from '../../nucleo/moedas';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { lerConfiguracaoDoConsignado } from './dados';

const T = TEXTOS_DE_CONSIGNADO.comissao;

/** Só dígitos; "" ou número fora da faixa vira `null`. */
function numeroDe(digitado: string, de: number, ate: number): number | null {
  if (!/^\d{1,10}$/.test(digitado)) return null;
  const n = Number(digitado);
  return n >= de && n <= ate ? n : null;
}

/**
 * Consignado → Comissão e meta: a meta em Guarani, as duas comissões e os dias até o retorno. Vale para os próximos
 * acertos e notas (ESPECIFICACAO §23.13). Quem confere de verdade é o servidor.
 */
export function TelaComissaoDoConsignado({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'consignado.configuracao'), 'consignado-configuracao');
  const [meta, setMeta] = useState('');
  const [abaixo, setAbaixo] = useState('');
  const [naMeta, setNaMeta] = useState('');
  const [dias, setDias] = useState('');
  const [tentou, setTentou] = useState(false);
  const [salvando, setSalvando] = useState(false);
  const [salvo, setSalvo] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);

  useEffect(() => {
    if (!busca.dados) return;
    const c = lerConfiguracaoDoConsignado(busca.dados);
    setMeta(String(c.metaGuarani));
    setAbaixo(String(c.percentualAbaixoDaMeta));
    setNaMeta(String(c.percentualNaMeta));
    setDias(String(c.diasDoRetorno));
  }, [busca.dados]);

  const metaN = numeroDe(meta, 1, META_MAXIMA_EM_GUARANI);
  const abaixoN = numeroDe(abaixo, 0, PERCENTUAL_MAXIMO_DO_CONSIGNADO);
  const naMetaN = numeroDe(naMeta, 0, PERCENTUAL_MAXIMO_DO_CONSIGNADO);
  const diasN = numeroDe(dias, 1, DIAS_MAXIMOS_DO_RETORNO);
  const ordemRuim = abaixoN !== null && naMetaN !== null && abaixoN > naMetaN;
  const valido =
    metaN !== null && abaixoN !== null && naMetaN !== null && diasN !== null && !ordemRuim;

  const digitando = (mudar: (v: string) => void) => (e: { target: { value: string } }) => {
    mudar(e.target.value.replace(/\D/g, ''));
    setSalvo(false);
  };

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setTentou(true);
    setSalvo(false);
    setErro(null);
    if (!valido || salvando) return;
    setSalvando(true);
    const r = await chamar(ponte, 'consignado.configuracaoAlterar', {
      metaGuarani: metaN,
      percentualAbaixoDaMeta: abaixoN,
      percentualNaMeta: naMetaN,
      diasDoRetorno: diasN,
    });
    setSalvando(false);
    if (!r.ok) {
      setErro(r.erro);
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
      {busca.carregando && !busca.dados ? <p className="gestao-texto">{T.carregando}</p> : null}
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
            onChange={digitando(setMeta)}
            {...(metaN !== null ? { ajuda: formatarGuarani(BigInt(metaN)) } : {})}
            {...(tentou && metaN === null ? { erro: T.metaAviso } : {})}
          />
          <Campo
            rotulo={T.abaixo}
            inputMode="numeric"
            autoComplete="off"
            value={abaixo}
            onChange={digitando(setAbaixo)}
            {...(tentou && abaixoN === null ? { erro: T.percentualAviso } : {})}
          />
          <Campo
            rotulo={T.naMeta}
            inputMode="numeric"
            autoComplete="off"
            value={naMeta}
            onChange={digitando(setNaMeta)}
            {...(tentou && naMetaN === null ? { erro: T.percentualAviso } : {})}
          />
          <Campo
            rotulo={T.dias}
            inputMode="numeric"
            autoComplete="off"
            value={dias}
            onChange={digitando(setDias)}
            ajuda={T.diasAjuda}
            {...(tentou && diasN === null ? { erro: T.diasAviso } : {})}
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
