/**
 * Os exemplos do consignado ao cliente (ESPECIFICACAO §23.8) que o servidor e o Corona Vendas leem dos MESMOS números
 * (`packages/shared/exemplos/consignado-acerto.json`). Fica em exportação própria (`@corona/shared/exemplos-do-consignado`)
 * para não entrar no pacote do aplicativo: só os testes importam.
 */
import exemplos from '../../exemplos/consignado-acerto.json';

export const EXEMPLOS_DO_CONSIGNADO = exemplos;
