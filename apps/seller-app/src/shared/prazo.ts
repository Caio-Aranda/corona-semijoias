/**
 * O prazo máximo sem internet do computador (tarefa 5.8; ADR-0013, item 12). Só o que a tela precisa: a situação, os
 * dias que faltam e o motivo. As horas e os contatos nunca atravessam a ponte.
 */
export type SituacaoDoPrazo = 'EM_DIA' | 'AVISO' | 'ESGOTADO';

export interface PrazoOffline {
  situacao: SituacaoDoPrazo;
  /** Dias inteiros que faltam, arredondados para cima (1 no último dia). 0 quando esgotado ou em dia sem aviso. */
  diasRestantes: number;
  /** Por que esgotou: os dias passaram ou o relógio do computador está errado. `null` se não esgotou. */
  motivo: 'PRAZO' | 'RELOGIO' | null;
}

export const PRAZO_EM_DIA: PrazoOffline = Object.freeze({
  situacao: 'EM_DIA',
  diasRestantes: 0,
  motivo: null,
});

/** Copia só os campos documentados, com o tipo certo. */
export function limparPrazo(v: unknown): PrazoOffline {
  const o = (typeof v === 'object' && v !== null ? v : {}) as Record<string, unknown>;
  const situacao = o['situacao'];
  if (situacao !== 'AVISO' && situacao !== 'ESGOTADO') return { ...PRAZO_EM_DIA };
  const dias = o['diasRestantes'];
  const motivo = o['motivo'];
  return {
    situacao,
    diasRestantes: typeof dias === 'number' && Number.isSafeInteger(dias) && dias >= 0 ? dias : 0,
    motivo: situacao === 'ESGOTADO' ? (motivo === 'RELOGIO' ? 'RELOGIO' : 'PRAZO') : null,
  };
}

export interface TextoDeAviso {
  titulo: string;
  texto: string;
}

const TEXTOS_DO_PRAZO_ES = {
  hoje: 'Hoy esta computadora necesita internet.',
  faltam: (d: number) => `Faltan ${d} días para que esta computadora necesite internet.`,
  avisoTitulo: 'Conectá esta computadora a internet pronto.',
  avisoTexto: (prazo: string) =>
    `${prazo} Tus ventas están guardadas y se enviarán apenas te conectes.`,
  relogioTitulo: 'La fecha de esta computadora parece estar mal.',
  relogioTexto:
    'Revisá la fecha y la hora de la computadora y conectate a internet para seguir vendiendo. Tus ventas están guardadas y se enviarán apenas te conectes.',
  esgotadoTitulo: 'Esta computadora estuvo muchos días sin internet.',
  esgotadoTexto:
    'Tus ventas están guardadas y se enviarán apenas te conectes. Para hacer ventas nuevas, conectate a internet.',
};

/**
 * Os textos do glossário (parte 6, "Abrir sem internet, prazo e computador desligado"), no idioma da tela (português
 * por padrão aqui; a tela do Corona Vendas passa o idioma escolhido). `null` quando está tudo em dia.
 */
export function textoDoPrazo(p: PrazoOffline, idioma: 'es' | 'pt' = 'pt'): TextoDeAviso | null {
  if (p.situacao === 'EM_DIA') return null;
  const es = idioma === 'es';
  if (p.situacao === 'AVISO') {
    if (es) {
      const prazo =
        p.diasRestantes <= 1 ? TEXTOS_DO_PRAZO_ES.hoje : TEXTOS_DO_PRAZO_ES.faltam(p.diasRestantes);
      return {
        titulo: TEXTOS_DO_PRAZO_ES.avisoTitulo,
        texto: TEXTOS_DO_PRAZO_ES.avisoTexto(prazo),
      };
    }
    const prazo =
      p.diasRestantes <= 1
        ? 'Hoje este computador precisa de internet.'
        : `Faltam ${p.diasRestantes} dias para este computador precisar de internet.`;
    return {
      titulo: 'Conecte este computador à internet em breve.',
      texto: `${prazo} Suas vendas estão salvas e serão enviadas assim que você conectar.`,
    };
  }
  if (p.motivo === 'RELOGIO') {
    if (es)
      return { titulo: TEXTOS_DO_PRAZO_ES.relogioTitulo, texto: TEXTOS_DO_PRAZO_ES.relogioTexto };
    return {
      titulo: 'A data deste computador parece errada.',
      texto:
        'Confira a data e a hora do computador e conecte-se à internet para continuar vendendo. Suas vendas estão salvas e serão enviadas assim que você conectar.',
    };
  }
  if (es)
    return { titulo: TEXTOS_DO_PRAZO_ES.esgotadoTitulo, texto: TEXTOS_DO_PRAZO_ES.esgotadoTexto };
  return {
    titulo: 'Este computador ficou muitos dias sem internet.',
    texto:
      'Suas vendas estão salvas e serão enviadas assim que você conectar. Para fazer vendas novas, conecte-se à internet.',
  };
}

/** O texto de quando a pessoa tenta gravar com o prazo acabado. */
export function textoDeGravacaoBloqueada(motivo: 'PRAZO' | 'RELOGIO'): string {
  return motivo === 'RELOGIO'
    ? 'A data deste computador parece errada. Confira a data e a hora do computador e conecte-se à internet para continuar.'
    : 'Este computador ficou muitos dias sem internet. Conecte-se à internet para fazer vendas novas.';
}
