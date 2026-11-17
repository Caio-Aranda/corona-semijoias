import { Aviso } from '@corona/ui';
import type { ResultadoDeEntrada } from '../../shared/ponte';
import { mensagemDaApi } from '../idioma/erros';
import { TEXTOS } from '../textos';

/** Mostra o que deu errado ao entrar ou trocar a senha. O texto de erro vem da API sempre que existir. */
export function MensagemDeEntrada({ resultado }: { resultado: ResultadoDeEntrada | null }) {
  if (!resultado) return null;
  switch (resultado.tipo) {
    case 'recusado':
      return <Aviso tipo="erro">{mensagemDaApi(resultado)}</Aviso>;
    case 'sem-conexao':
      return (
        <Aviso tipo="erro" titulo={TEXTOS.semInternet.titulo}>
          {TEXTOS.semInternet.texto}
        </Aviso>
      );
    case 'erro-inesperado':
      return <Aviso tipo="erro">{TEXTOS.algoDeuErrado}</Aviso>;
    default:
      return null;
  }
}
