import { useCallback, useEffect, useRef, useState } from 'react';
import type { OperacaoDaApi, PonteDoCorona, ResultadoDeChamada } from '../../shared/ponte';
import { mensagemDaApi } from '../idioma/erros';
import { TEXTOS } from '../textos';

/** Quanto esperar antes de tentar de novo sozinho quando a internet cai. */
export const ESPERA_NOVA_TENTATIVA_MS = 15_000;

export type TipoDeErro =
  /** Sem internet ou servidor fora do ar: a sessão continua guardada. */
  | 'sem-conexao'
  /** O servidor encerrou a sessão: a tela de entrar assume (o App é avisado pela ponte). */
  | 'sessao-terminou'
  /** Outra pessoa alterou o cadastro (409 de versão). */
  | 'versao'
  /** O servidor recusou campos do formulário (400/409 com campo). */
  | 'campos'
  | 'outro';

export interface ErroDaTela {
  tipo: TipoDeErro;
  /** Texto em português para a pessoa, sempre vindo do servidor ou do glossário. */
  mensagem: string;
  /** Texto de cada campo recusado, pelo nome do campo na API. */
  campos: Record<string, string>;
  status?: number;
  codigo?: string;
}

/** O que a tela mostra para uma chamada que não deu certo. */
export function traduzirErro(r: Extract<ResultadoDeChamada, { ok: false }>): ErroDaTela {
  const campos: Record<string, string> = {};
  for (const c of r.campos ?? []) campos[c.campo] ??= c.mensagem;
  const base = {
    campos,
    ...(r.status === undefined ? {} : { status: r.status }),
    ...(r.codigo ? { codigo: r.codigo } : {}),
  };
  switch (r.motivo) {
    case 'sem-conexao':
      return { ...base, tipo: 'sem-conexao', mensagem: TEXTOS.semConexao.texto };
    case 'sessao-terminou':
      return { ...base, tipo: 'sessao-terminou', mensagem: TEXTOS.sessaoTerminou.texto };
    case 'app-desatualizado':
      return {
        ...base,
        tipo: 'outro',
        mensagem:
          mensagemDaApi(r) || `${TEXTOS.desatualizado.titulo} ${TEXTOS.desatualizado.texto}`,
      };
    case 'erro':
      if (r.codigo === 'CADASTRO_ALTERADO') {
        return {
          ...base,
          tipo: 'versao',
          mensagem: mensagemDaApi(r) || TEXTOS.formulario.alteradoPorOutraPessoa,
        };
      }
      if (Object.keys(campos).length > 0) {
        return {
          ...base,
          tipo: 'campos',
          mensagem: mensagemDaApi(r) || TEXTOS.formulario.campoNaoAceito,
        };
      }
      return { ...base, tipo: 'outro', mensagem: mensagemDaApi(r) || TEXTOS.algoDeuErrado };
  }
}

/** {@code jaRegistrado}: o servidor respondeu 200 (reenvio), não 201; só nas chamadas que distinguem. */
export type Retorno<T> =
  { ok: true; dados: T; jaRegistrado?: boolean } | { ok: false; erro: ErroDaTela };

/** Uma chamada à API pela ponte, já com o erro traduzido. Nunca lança. */
export async function chamar<T = unknown>(
  ponte: PonteDoCorona,
  operacao: OperacaoDaApi,
  parametros?: Record<string, unknown>,
): Promise<Retorno<T>> {
  try {
    const r = await ponte.chamarApi(parametros ? { operacao, parametros } : { operacao });
    if (r.ok) {
      return r.criado === false
        ? { ok: true, dados: r.dados as T, jaRegistrado: true }
        : { ok: true, dados: r.dados as T };
    }
    return { ok: false, erro: traduzirErro(r) };
  } catch {
    return { ok: false, erro: { tipo: 'outro', mensagem: TEXTOS.algoDeuErrado, campos: {} } };
  }
}

/** Itens por pedido nas listas que pedem tudo (o máximo que a API aceita). */
const TAMANHO_DA_PAGINA = 100;
/** Trava de segurança: nunca mais que 50 páginas (5.000 itens) numa lista de escolha. */
const PAGINAS_NO_MAXIMO = 50;

/**
 * Para listas de escolha e listas curtas que precisam de tudo (fornecedores, categorias, praças, semanas...): pede a página 0
 * com 100 itens, como antes, e só continua pedindo as próximas quando o total passa de 100 (revisão de 23/11, achado B3: o 101º
 * fornecedor sumia de Receber compra). Devolve uma página só, com todos os itens.
 */
export async function chamarTodasAsPaginas<T = unknown>(
  ponte: PonteDoCorona,
  operacao: OperacaoDaApi,
  parametros: Record<string, unknown> = {},
): Promise<Retorno<T>> {
  const primeira = await chamar<unknown>(ponte, operacao, {
    ...parametros,
    pagina: 0,
    tamanho: TAMANHO_DA_PAGINA,
  });
  if (!primeira.ok) return primeira;
  const p = primeira.dados as { itens?: unknown; total?: unknown } | null;
  if (!p || !Array.isArray(p.itens) || typeof p.total !== 'number') {
    return primeira as Retorno<T>;
  }
  const itens: unknown[] = [...(p.itens as unknown[])];
  for (let pagina = 1; itens.length < p.total && pagina < PAGINAS_NO_MAXIMO; pagina += 1) {
    const r = await chamar<unknown>(ponte, operacao, {
      ...parametros,
      pagina,
      tamanho: TAMANHO_DA_PAGINA,
    });
    if (!r.ok) return r;
    const mais = (r.dados as { itens?: unknown } | null)?.itens;
    if (!Array.isArray(mais) || mais.length === 0) break;
    itens.push(...(mais as unknown[]));
  }
  return { ok: true, dados: { ...p, itens, pagina: 0, tamanho: itens.length } as T };
}

export interface EstadoDaBusca<T> {
  carregando: boolean;
  dados: T | null;
  erro: ErroDaTela | null;
  recarregar: () => void;
}

/**
 * Busca dados ao abrir a tela e sempre que `chave` mudar. Se a internet cair, mostra o erro e tenta
 * de novo sozinho depois de um tempo; `recarregar` tenta na hora. Respostas que chegam depois de a
 * busca já ter mudado (ou da tela fechar) são descartadas.
 */
export function useBusca<T>(
  buscar: () => Promise<Retorno<T>>,
  chave: string,
  esperaMs: number = ESPERA_NOVA_TENTATIVA_MS,
): EstadoDaBusca<T> {
  const [estado, setEstado] = useState<{
    carregando: boolean;
    dados: T | null;
    erro: ErroDaTela | null;
  }>({ carregando: true, dados: null, erro: null });
  const [tentativa, setTentativa] = useState(0);
  const funcao = useRef(buscar);
  funcao.current = buscar;

  useEffect(() => {
    let valida = true;
    let relogio: ReturnType<typeof setTimeout> | undefined;
    setEstado((e) => ({ ...e, carregando: true, erro: null }));
    void funcao.current().then((r) => {
      if (!valida) return;
      if (r.ok) {
        setEstado({ carregando: false, dados: r.dados, erro: null });
        return;
      }
      setEstado((e) => ({ carregando: false, dados: e.dados, erro: r.erro }));
      if (r.erro.tipo === 'sem-conexao') {
        relogio = setTimeout(() => setTentativa((n) => n + 1), esperaMs);
      }
    });
    return () => {
      valida = false;
      if (relogio) clearTimeout(relogio);
    };
  }, [chave, tentativa, esperaMs]);

  const recarregar = useCallback(() => setTentativa((n) => n + 1), []);
  return { ...estado, recarregar };
}
