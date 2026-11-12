/** Leitura (sem confiar no formato) do que a API devolve para as telas dos computadores dos vendedores. */

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null);
const lista = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

export interface Computador {
  id: string;
  situacao: string;
  ultimoContatoEm: string | null;
  aguardandoEnvio: number | null;
  ultimoEnvioCompletoEm: string | null;
}

export function lerComputadores(bruto: unknown): Computador[] {
  return lista(bruto).flatMap((c) => {
    const o = objeto(c);
    const id = texto(o.id);
    const situacao = texto(o.situacao);
    if (!id || !situacao) return [];
    return [
      {
        id,
        situacao,
        ultimoContatoEm: texto(o.ultimoContatoEm),
        aguardandoEnvio: typeof o.aguardandoEnvio === 'number' ? o.aguardandoEnvio : null,
        ultimoEnvioCompletoEm: texto(o.ultimoEnvioCompletoEm),
      },
    ];
  });
}

export interface EnvioComProblema {
  id: string;
  tipo: string;
  resultado: string;
  codigo: string | null;
  mensagem: string;
  recebidoEm: string | null;
  vendedorNome: string;
  /** O conteúdo guardado (vazio depois de 90 dias). */
  dados: Record<string, unknown> | null;
}

export function lerEnvios(bruto: unknown): EnvioComProblema[] {
  return lista(bruto).flatMap((e) => {
    const o = objeto(e);
    const id = texto(o.id);
    const tipo = texto(o.tipo);
    if (!id || !tipo) return [];
    return [
      {
        id,
        tipo,
        resultado: texto(o.resultado) ?? '',
        codigo: texto(o.codigo),
        mensagem: texto(o.mensagem) ?? '',
        recebidoEm: texto(o.recebidoEm),
        vendedorNome: texto(o.vendedorNome) ?? '',
        dados: o.dados && typeof o.dados === 'object' ? objeto(o.dados) : null,
      },
    ];
  });
}

/** Cliente novo feito sem internet cujo CPF já existia: a dona junta os cadastros. */
export function ehCpfRepetido(e: EnvioComProblema): boolean {
  return e.codigo === 'CLIENTE_CPF_JA_CADASTRADO';
}

export interface VendaGuardada {
  id: string;
  tipo: string;
  vendedorNome: string;
  recebidoEm: string | null;
  criadoEmLocal: string | null;
}

export function lerVendasGuardadas(bruto: unknown): VendaGuardada[] {
  return lista(bruto).flatMap((e) => {
    const o = objeto(e);
    const id = texto(o.id);
    const tipo = texto(o.tipo);
    if (!id || !tipo) return [];
    return [
      {
        id,
        tipo,
        vendedorNome: texto(o.vendedorNome) ?? '',
        recebidoEm: texto(o.recebidoEm),
        criadoEmLocal: texto(o.criadoEmLocal),
      },
    ];
  });
}

export interface ResultadoDaAprovacao {
  resultado: string;
  mensagem: string;
}

export function lerResultadoDaAprovacao(bruto: unknown): ResultadoDaAprovacao {
  const o = objeto(bruto);
  return { resultado: texto(o.resultado) ?? '', mensagem: texto(o.mensagem) ?? '' };
}

export interface ValorSubstituido {
  id: string;
  clienteNome: string;
  quemMudou: string;
  quando: string | null;
  campos: { campo: string; valorAnterior: string | null; valorNovo: string | null }[];
}

export function lerValoresSubstituidos(bruto: unknown): ValorSubstituido[] {
  return lista(bruto).flatMap((v) => {
    const o = objeto(v);
    const id = texto(o.id);
    if (!id) return [];
    return [
      {
        id,
        clienteNome: texto(o.clienteNome) ?? '',
        quemMudou: texto(o.quemMudou) ?? '',
        quando: texto(o.quando),
        campos: lista(o.campos).flatMap((c) => {
          const k = objeto(c);
          const campo = texto(k.campo);
          return campo
            ? [{ campo, valorAnterior: texto(k.valorAnterior), valorNovo: texto(k.valorNovo) }]
            : [];
        }),
      },
    ];
  });
}

/** "2026-11-17T15:30:00Z" → "17/11/2026 às 12:30" (hora de Brasília). */
export function dataEHora(instante: string | null): string {
  if (!instante) return '';
  const d = new Date(instante);
  if (Number.isNaN(d.getTime())) return '';
  const partes = new Intl.DateTimeFormat('pt-BR', {
    timeZone: 'America/Sao_Paulo',
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(d);
  const v = (t: string) => partes.find((p) => p.type === t)?.value ?? '';
  return `${v('day')}/${v('month')}/${v('year')} às ${v('hour')}:${v('minute')}`;
}
