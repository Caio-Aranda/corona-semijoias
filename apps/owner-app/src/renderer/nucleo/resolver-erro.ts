import { TEXTOS } from '../textos';
import type { Rota } from './rotas';

/** O botão que leva à tela que resolve o erro: o código da API decide (o texto continua o do servidor). */
export interface AcaoDoErro {
  rotulo: string;
  rota: Rota;
}

const ACOES: Record<string, { rota: Rota; rotulo: () => string }> = {
  SEMANA_FECHADA: { rota: 'cadastros/semanas', rotulo: () => TEXTOS.resolverErro.cadastrarSemana },
  SEMANA_NAO_ENCONTRADA: {
    rota: 'cadastros/semanas',
    rotulo: () => TEXTOS.resolverErro.cadastrarSemana,
  },
  LIMITE_DE_CONSIGNACAO_NAO_DEFINIDO: {
    rota: 'pessoas/vendedores',
    rotulo: () => TEXTOS.resolverErro.abrirRepresentantes,
  },
  LIMITE_DE_CONSIGNACAO_EXCEDIDO: {
    rota: 'pessoas/vendedores',
    rotulo: () => TEXTOS.resolverErro.abrirRepresentantes,
  },
  VENDEDOR_NAO_RECEBE_MALETA: {
    rota: 'pessoas/vendedores',
    rotulo: () => TEXTOS.resolverErro.abrirRepresentantes,
  },
  SEM_COTACAO_PARA_O_ACERTO: {
    rota: 'financeiro/cotacao',
    rotulo: () => TEXTOS.resolverErro.lancarCotacao,
  },
  SEM_COTACAO_PARA_O_RECEBIMENTO: {
    rota: 'financeiro/cotacao',
    rotulo: () => TEXTOS.resolverErro.lancarCotacao,
  },
  ESTOQUE_INSUFICIENTE: {
    rota: 'estoque/receber-compra',
    rotulo: () => TEXTOS.resolverErro.receberCompra,
  },
};

/** A ação que resolve o erro, quando há uma tela certa para isso; senão, nada. */
export function acaoDoErro(codigo: string | undefined): AcaoDoErro | null {
  const a = codigo ? ACOES[codigo] : undefined;
  return a ? { rota: a.rota, rotulo: a.rotulo() } : null;
}
