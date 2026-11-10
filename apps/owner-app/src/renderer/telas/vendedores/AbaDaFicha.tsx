import { Aviso } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import type { AbaDoVendedor } from '../../../shared/vendedores';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { CAMPOS_DA_TELA_DAS_ABAS } from './campos';
import { lerRegistro } from './dados';
import { FormularioDeCampos } from './FormularioDeCampos';

const T = TEXTOS.vendedores;

export interface AbaDaFichaProps {
  ponte: PonteDoCorona;
  vendedorId: string;
  aba: AbaDoVendedor;
  aoMudarSujo: (sujo: boolean) => void;
}

/**
 * Uma das abas de dados (endereço, filiação e contato, trabalho, cônjuge, MEI, observações): busca a
 * aba pelo servidor e grava só ela, com a versão que leu (aba nunca gravada vem vazia, versão 0).
 */
export function AbaDaFicha({ ponte, vendedorId, aba, aoMudarSujo }: AbaDaFichaProps) {
  const busca = useBusca(
    () => chamar(ponte, 'vendedores.aba.ver', { id: vendedorId, aba }),
    `${vendedorId}|${aba}`,
  );
  const [feito, setFeito] = useState(false);

  if (busca.dados === null) {
    return busca.erro ? (
      <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
    ) : (
      <p className="gestao-texto" aria-busy="true">
        {T.ficha.carregando}
      </p>
    );
  }
  const registro = lerRegistro(busca.dados);
  return (
    <>
      {feito ? <Aviso tipo="sucesso">{T.feito.abaSalva}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <FormularioDeCampos
        // Recomeça com os dados novos depois de gravar ou de atualizar.
        key={registro.version}
        idBase={`aba-${aba}`}
        campos={CAMPOS_DA_TELA_DAS_ABAS[aba]}
        registro={registro}
        autoFocus={false}
        salvar={(corpo, version) =>
          chamar(ponte, 'vendedores.aba.gravar', { ...corpo, id: vendedorId, aba, version })
        }
        aoSalvo={() => {
          setFeito(true);
          busca.recarregar();
        }}
        aoAtualizar={() => {
          setFeito(false);
          busca.recarregar();
        }}
        aoMudarSujo={(sujo) => {
          if (sujo) setFeito(false);
          aoMudarSujo(sujo);
        }}
      />
    </>
  );
}
