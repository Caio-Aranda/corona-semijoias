import { Aviso } from '@corona/ui';
import type { ResultadoDeEntrada } from '../../shared/ponte';
import { mensagemDoErro } from '../erros-es';
import { useIdioma } from '../idioma';
import { TEXTOS } from '../textos';

/** Mostra o que deu errado ao entrar ou trocar a senha. O texto de erro vem da API sempre que existir. */
export function MensagemDeEntrada({ resultado }: { resultado: ResultadoDeEntrada | null }) {
  const idioma = useIdioma();
  if (!resultado) return null;
  switch (resultado.tipo) {
    case 'recusado':
      return (
        <Aviso tipo="erro">
          {mensagemDoErro(idioma, resultado.codigo, resultado.mensagem, TEXTOS.algoDeuErrado)}
        </Aviso>
      );
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
