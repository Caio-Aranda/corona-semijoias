import {
  ERROS_EM_ESPANHOL,
  erroDeBloqueio,
  erroDePecaDeOutraMaleta,
  erroDoLimite,
} from './erros-es';
import { idiomaAtual } from './idioma';

/**
 * O texto de um erro recusado pela API. Em espanhol vale o texto do código de erro; em português (ou com um
 * código que não conhecemos) vale o que a API mandou.
 */
export function mensagemDaApi(r: {
  mensagem?: string | undefined;
  codigo?: string | undefined;
}): string | undefined {
  if (idiomaAtual() === 'es' && r.codigo) {
    if (r.codigo === 'ACESSO_BLOQUEADO') return erroDeBloqueio(r.mensagem);
    if (r.codigo === 'PECA_NAO_E_DESTA_MALETA') return erroDePecaDeOutraMaleta(r.mensagem);
    if (r.codigo === 'LIMITE_DE_CONSIGNACAO_EXCEDIDO') return erroDoLimite(r.mensagem);
    const texto = ERROS_EM_ESPANHOL[r.codigo];
    if (texto) return texto;
  }
  return r.mensagem;
}
