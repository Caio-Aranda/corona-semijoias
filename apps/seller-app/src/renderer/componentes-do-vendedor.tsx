import {
  Campo as CampoBase,
  EstadoDaLista as EstadoDaListaBase,
  JanelaDeConfirmacao as JanelaBase,
  PainelDePecaLida as PainelBase,
  Tabela as TabelaBase,
  type CampoProps,
  type EstadoDaListaProps,
  type JanelaDeConfirmacaoProps,
  type PainelDePecaLidaProps,
  type TabelaProps,
} from '@corona/ui';
import { TEXTOS } from './textos';

/**
 * Os componentes comuns com os textos fixos no idioma escolhido (o pacote da interface fala português por padrão).
 * As telas do Corona Vendas importam estes, nunca os originais. Quem passa o texto na própria tela, vale o da tela.
 */
export function Campo(props: CampoProps) {
  return (
    <CampoBase
      rotuloMostrar={TEXTOS.comum.mostrar}
      rotuloOcultar={TEXTOS.comum.ocultar}
      {...props}
    />
  );
}

export function Tabela(props: TabelaProps) {
  return <TabelaBase textoVazio={TEXTOS.comum.nadaParaMostrar} {...props} />;
}

export function EstadoDaLista(props: EstadoDaListaProps) {
  return <EstadoDaListaBase rotuloTentarDeNovo={TEXTOS.tentarDeNovo} {...props} />;
}

export function JanelaDeConfirmacao(props: JanelaDeConfirmacaoProps) {
  return <JanelaBase rotuloCancelar={TEXTOS.botoes.cancelar} {...props} />;
}

export function PainelDePecaLida(props: PainelDePecaLidaProps) {
  return (
    <PainelBase
      textos={{
        pecaLida: TEXTOS.comum.pecaLida,
        foto: TEXTOS.comum.fotoDe,
        semFoto: TEXTOS.comum.semFoto,
        codigo: TEXTOS.comum.codigoDe,
      }}
      {...props}
    />
  );
}
