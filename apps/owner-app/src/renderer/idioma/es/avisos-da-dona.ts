/* eslint-disable no-cond-assign -- cada aviso é reconhecido por um padrão e usa os trechos achados */
/**
 * Avisos de "Lo que necesita su atención" em espanhol. O servidor manda cada aviso em português (título e texto);
 * aqui cada um é reconhecido pelo tipo e pelo formato do texto e escrito em espanhol, com os mesmos números, nomes e datas.
 * O que não for reconhecido (aviso novo do servidor) aparece como veio, em português, até ganhar a tradução.
 */
export interface AvisoParaTraduzir {
  tipo: string;
  titulo: string;
  texto: string;
}

const unico = (n: string, um: string, varios: (n: string) => string) =>
  n === '1' ? um : varios(n);

/** "1 dia" / "3 dias" → "1 día" / "3 días". */
const dias = (t: string) => t.replace(/\bdias?\b/, (d) => (d === 'dia' ? 'día' : 'días'));

const SUPORTE_PT =
  'O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.';
const SUPORTE_ES =
  'El sistema lo intenta de nuevo solo. Si el aviso continúa mañana, hable con el soporte del sistema.';

function titulo(a: AvisoParaTraduzir): string | null {
  const m = (re: RegExp) => re.exec(a.titulo);
  let r: RegExpExecArray | null;
  switch (a.tipo) {
    case 'CAIXA_NEGATIVO':
      return (r = m(/^O caixa está negativo em (.+)\.$/))
        ? `La caja está en negativo en ${r[1]}.`
        : null;
    case 'CONFERENCIA_COM_DIFERENCA':
      return (r = m(/^A conferência de hoje achou (\d+) diferenças?$/))
        ? `La verificación de hoy encontró ${unico(r[1]!, '1 diferencia', (n) => `${n} diferencias`)}`
        : null;
    case 'CONFERENCIA_NAO_RODOU':
      return a.titulo === 'A conferência de hoje não rodou'
        ? 'La verificación de hoy no se ejecutó'
        : null;
    case 'COTACAO_NAO_CHEGOU':
      if (a.titulo === 'A cotação de hoje ainda não chegou.')
        return 'La cotización de hoy todavía no llegó.';
      if ((r = m(/^As cotações de (.+) não chegaram\.$/)))
        return `Las cotizaciones de ${r[1]!.replace(/ e (?=[^ ]+$)/, ' y ')} no llegaron.`;
      return (r = m(/^A cotação de (.+) não chegou\.$/))
        ? `La cotización del ${r[1]} no llegó.`
        : null;
    case 'CREDITOS_SEM_SALDO_PARA_REVISAR':
      return (r = m(/^Há (\d+) vendas? pagas? com crédito que já tinha acabado\.$/))
        ? unico(
            r[1]!,
            'Hay 1 venta pagada con un crédito que ya se había acabado.',
            (n) => `Hay ${n} ventas pagadas con un crédito que ya se había acabado.`,
          )
        : null;
    case 'DEVOLUCOES_PARA_AUTORIZAR':
      return (r = m(/^Há (\d+) pedidos? de autorização de devolução esperando você\.$/))
        ? unico(
            r[1]!,
            'Hay 1 pedido de autorización de devolución esperándola.',
            (n) => `Hay ${n} pedidos de autorización de devolución esperándola.`,
          )
        : null;
    case 'DEVOLUCOES_PARA_REVISAR':
      return (r = m(/^Há (\d+) devoluç(?:ão|ões) para revisar\.$/))
        ? unico(
            r[1]!,
            'Hay 1 devolución para revisar.',
            (n) => `Hay ${n} devoluciones para revisar.`,
          )
        : null;
    case 'MUITAS_DEVOLUCOES':
      return (r = m(/^Muitas devoluções de (.+)\.$/)) ? `Muchas devoluciones de ${r[1]}.` : null;
    case 'ESTOQUE_BAIXO':
      return (r = m(/^(\d+) produtos? (?:está|estão) acabando\.$/))
        ? unico(r[1]!, '1 producto se está acabando.', (n) => `${n} productos se están acabando.`)
        : null;
    case 'COMPUTADOR_SEM_ENVIAR':
      return (r = m(/^O computador de (.+) está há (.+) sem enviar\.$/))
        ? `La computadora de ${r[1]} está hace ${dias(r[2]!)} sin enviar.`
        : null;
    case 'COMPUTADOR_NOVO':
      return (r = m(/^(.+) entrou num computador novo\.$/))
        ? `${r[1]} entró en una computadora nueva.`
        : null;
    case 'ENVIOS_COM_PROBLEMA':
      return (r = m(/^Há (\d+) envios? com problema\.$/))
        ? unico(r[1]!, 'Hay 1 envío con problema.', (n) => `Hay ${n} envíos con problema.`)
        : null;
    case 'VENDAS_DE_COMPUTADOR_BLOQUEADO':
      return (r = m(/^Há (\d+) vendas? de um computador bloqueado esperando você\.$/))
        ? unico(
            r[1]!,
            'Hay 1 venta de una computadora bloqueada esperándola.',
            (n) => `Hay ${n} ventas de una computadora bloqueada esperándola.`,
          )
        : null;
    default:
      return null;
  }
}

function texto(a: AvisoParaTraduzir): string | null {
  const m = (re: RegExp) => re.exec(a.texto);
  let r: RegExpExecArray | null;
  switch (a.tipo) {
    case 'CAIXA_NEGATIVO':
      return 'Entró menos dinero del que salió. Revise los movimientos de la caja y registre lo que falte.';
    case 'CONFERENCIA_COM_DIFERENCA':
      return 'Abra la verificación para ver lo que no cuadra. No se cambió nada solo.';
    case 'CONFERENCIA_NAO_RODOU':
      if (a.texto === 'A conferência automática ainda não rodou. Toque em Conferir agora.')
        return 'La verificación automática todavía no se ejecutó. Toque en Verificar ahora.';
      if (
        a.texto ===
        'A conferência automática não rodou nas últimas 26 horas. Toque em Conferir agora.'
      )
        return 'La verificación automática no se ejecutó en las últimas 26 horas. Toque en Verificar ahora.';
      if (
        a.texto ===
        'Uma parte da conferência não terminou. Toque em Conferir agora; se continuar, avise o suporte.'
      )
        return 'Una parte de la verificación no terminó. Toque en Verificar ahora; si continúa, avise al soporte.';
      return null;
    case 'COTACAO_NAO_CHEGOU':
      if (!a.texto.endsWith(SUPORTE_PT)) return null;
      if (a.texto.startsWith('Os valores aparecem só em reais por enquanto. '))
        return `Los valores aparecen solo en reales por ahora. ${SUPORTE_ES}`;
      return (r = m(
        /^Os valores em Dólar e Guarani (estão|desses dias estão|desse dia estão) usando a cotação de (.+?)\. /,
      ))
        ? `Los valores en Dólar y Guaraní ${
            r[1] === 'estão'
              ? 'están'
              : r[1] === 'desses dias estão'
                ? 'de esos días están'
                : 'de ese día están'
          } usando la cotización del ${r[2]}. ${SUPORTE_ES}`
        : null;
    case 'CREDITOS_SEM_SALDO_PARA_REVISAR':
      return 'Abra Créditos de los clientes para revisar y elija Mantener o No aceptar.';
    case 'DEVOLUCOES_PARA_AUTORIZAR':
      return 'Abra Devoluciones para autorizar y elija Aprobar o Negar.';
    case 'DEVOLUCOES_PARA_REVISAR':
      return 'Abra Devoluciones para revisar y elija Mantener o No aceptar.';
    case 'MUITAS_DEVOLUCOES':
      if (
        (r = m(
          /^Nas últimas 4 semanas, (.+) teve devoluções e nenhuma venda\. Confira as devoluções dele\.$/,
        ))
      )
        return `En las últimas 4 semanas, ${r[1]} tuvo devoluciones y ninguna venta. Revise sus devoluciones.`;
      return (r = m(
        /^Nas últimas 4 semanas, (.+) devolveu (.+)% do que vendeu\. Confira as devoluções dele\.$/,
      ))
        ? `En las últimas 4 semanas, ${r[1]} devolvió el ${r[2]}% de lo que vendió. Revise sus devoluciones.`
        : null;
    case 'ESTOQUE_BAIXO':
      return 'Esos productos llegaron al stock mínimo. Vea la lista y haga la reposición.';
    case 'COMPUTADOR_SEM_ENVIAR':
      return (r = m(/^Peça a (.+) para conectar o .+ à internet\. /))
        ? `Pídale a ${r[1]} que conecte su programa a internet. Las ventas hechas sin internet solo llegan cuando la computadora se conecte.`
        : null;
    case 'COMPUTADOR_NOVO':
      return (r = m(
        /^Para (.+) vender nesse computador, abra a ficha do vendedor e use Trocar computador\. Se não foi (.+), use Bloquear computador\.$/,
      ))
        ? `Para que ${r[1]} venda en esa computadora, abra la ficha del representante y use Cambiar computadora. Si no fue ${r[2]}, use Bloquear computadora.`
        : null;
    case 'ENVIOS_COM_PROBLEMA':
      return 'Son ventas o registros hechos en la computadora del representante que no pudieron entrar. Abra Envíos con problema y elija Intentar de nuevo o Descartar.';
    case 'VENDAS_DE_COMPUTADOR_BLOQUEADO':
      return 'Todavía no valen. Abra Ventas de computadora bloqueada y elija Aprobar o Descartar.';
    default:
      return null;
  }
}

/** O aviso em espanhol; o que não for reconhecido volta como veio. */
export function avisoEmEspanhol<T extends AvisoParaTraduzir>(a: T): T {
  return { ...a, titulo: titulo(a) ?? a.titulo, texto: texto(a) ?? a.texto };
}
