import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_RELATORIOS_FINANCEIROS } from '../../textos-relatorios-financeiros';

export const ES_TEXTOS_DE_RELATORIOS_FINANCEIROS: NoMesmoFormato<
  typeof PT_TEXTOS_DE_RELATORIOS_FINANCEIROS
> = {
  grupoDoMenu: 'Informes financieros',
  resultadoNota:
    'La devolución descuenta el mes en que se hizo la venta; por eso el resultado de un mes puede cambiar después. El costo de las piezas es el de la compra más reciente hasta el día de la venta; donde no hay compra, vale el costo del registro (aparece avisado), que cambia si el registro cambia. Pérdidas, cobrado y caja son de la tienda: no se dividen por pieza ni por vendedor.',
  lucroNota:
    'Ingreso neto (ventas menos devoluciones y cambios) menos el costo de las piezas vendidas. "Costo del registro" quiere decir que no había compra registrada hasta la venta: es una estimación.',
  sinaisDaLinha: { SOMA: '(+) ', SUBTRAI: '(−) ', TOTAL: '= ', AVISO: '' } as Record<
    string,
    string
  >,
  cotacao:
    'Dólar y Guaraní por la cotización del Banco Central del día de cada movimiento. Todo se guarda en Real.',
  cotacaoDoAcerto:
    'Dólar y Guaraní por la cotización del Banco Central del día en que se cerró cada acerto. Todo se guarda en Real.',
  situacoesDaConta: {
    ABERTA: 'Abierta',
    PARCIAL: 'Pagada en parte',
    PAGA: 'Pagada',
    CANCELADA: 'Cancelada',
    VENCIDA: 'Vencida',
  } as Record<string, string>,
  quemDeve: {
    CLIENTE: 'Cliente',
    VENDEDOR: 'Vendedor',
    FORNECEDOR: 'Proveedor',
  } as Record<string, string>,
  tiposDaPendencia: {
    FALTA: 'Pieza que faltó',
    DEFEITO: 'Pieza con defecto',
    DEVOLUCAO_NAO_ACEITA: 'Devolución no aceptada',
    CREDITO_SEM_SALDO_NAO_ACEITO: 'Crédito sin saldo',
  } as Record<string, string>,
  situacoesDaPendencia: {
    ABERTA: 'Abierta',
    COBRADA: 'Cobrada al vendedor',
    ASSUMIDA_PELA_LOJA: 'La tienda asumió',
    ENCONTRADA: 'Pieza encontrada',
    APROVADA: 'Defecto aprobado',
    RECUSADA: 'Defecto rechazado',
  } as Record<string, string>,
  formas: { DINHEIRO: 'Efectivo', PIX: 'Transferencia', BOLETO: 'Boleto' } as Record<
    string,
    string
  >,
  tiposDoCaixa: { ENTRADA: 'Entrada', SAIDA: 'Salida' } as Record<string, string>,
  origensDoCaixa: {
    RECEBIMENTO_VENDEDOR: 'Cobrado del vendedor',
    ACERTO_A_FAVOR_DO_VENDEDOR: 'Pagado al vendedor en el acerto',
    PAGAMENTO_FORNECEDOR: 'Pagado a proveedor',
    MANUAL: 'Registrado por usted',
  } as Record<string, string>,
  momentos: { ACERTO: 'En el acerto', AVULSO: 'Después del acerto' } as Record<string, string>,
  estorno: 'Anulación',
  comissaoPaga: 'Pagada en el acerto',
};
