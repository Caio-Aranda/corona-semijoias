import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DE_RELATORIOS_DE_CONTROLE } from '../../textos-relatorios-controle';

export const ES_TEXTOS_DE_RELATORIOS_DE_CONTROLE: NoMesmoFormato<
  typeof PT_TEXTOS_DE_RELATORIOS_DE_CONTROLE
> = {
  grupoDoMenu: 'Control',
  auditoria: {
    titulo: 'Auditoría: quién hizo qué',
    explicacao:
      'Cada cambio importante hecho en el sistema (precio, stock, descuento, cancelación, reembolso, autorización, restricción y otros): quién lo hizo, cuándo, qué fue y cómo era antes y después.',
    nota: 'Las contraseñas nunca aparecen aquí. Cuando no hay una persona, el cambio lo hizo el propio sistema.',
  },
  computadores: {
    titulo: 'Computadoras y envíos',
    explicacao:
      'Las computadoras de cada representante: cuándo recibieron datos de la tienda por última vez, cuándo enviaron las ventas, cuántas ventas esperan para ser enviadas y cuántos envíos necesitan su decisión.',
    nota: 'Para decidir un envío, abra Computadoras en el menú.',
    situacoes: {
      TODAS: 'Todas las computadoras',
      ATIVO: 'En uso',
      AGUARDANDO_LIBERACAO: 'Esperando su habilitación',
      SUBSTITUIDO: 'Cambiadas por otra',
      BLOQUEADO: 'Bloqueadas',
    },
  },
  acessos: {
    titulo: 'Accesos: entradas y errores',
    explicacao:
      'Cada vez que alguien intentó entrar: quién, en qué programa, si salió bien, la dirección de internet y si el acceso quedó bloqueado por muchos errores.',
    nota: 'Si el nombre de la persona está vacío, el usuario escrito no existe.',
    semPessoa: 'Usuario que no existe',
    resultados: {
      SUCESSO: 'Entró',
      SENHA_INCORRETA: 'Contraseña incorrecta',
      BLOQUEADO: 'Rechazada: acceso bloqueado',
      USUARIO_DESATIVADO: 'Usuario inactivo',
      APP_INCOMPATIVEL: 'Programa muy antiguo',
      SENHA_TEMPORARIA_VENCIDA: 'Contraseña temporal vencida',
      PEDIDO_INVALIDO: 'Computadora que no coincide con el acceso',
      BLOQUEIO_LIMPO: 'Bloqueo quitado al cambiar la contraseña',
    },
  },
};
