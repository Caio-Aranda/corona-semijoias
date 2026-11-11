import { ValorTresMoedas } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { CotacaoDoDia, FotoPronta } from '../shared/dados';
import type { PedidoDeChamada, PonteDoCorona } from '../shared/ponte';
import { tresMoedas, type CotacaoParaTela } from './moedas';
import { TEXTOS } from './textos';

export type Resposta<T> = { ok: true; dados: T } | { ok: false; mensagem: string };

/** Pede algo ao processo principal e traduz a falha em texto para a pessoa (a mensagem da API vale primeiro). */
export async function chamar<T>(
  ponte: PonteDoCorona,
  pedido: PedidoDeChamada,
): Promise<Resposta<T>> {
  try {
    const r = await ponte.chamarApi(pedido);
    if (r.ok) return { ok: true, dados: r.dados as T };
    if (r.motivo === 'sem-conexao') return { ok: false, mensagem: TEXTOS.semInternet.curto };
    if (r.motivo === 'sessao-terminou') {
      return {
        ok: false,
        mensagem: `${TEXTOS.sessaoTerminou.titulo} ${TEXTOS.sessaoTerminou.texto}`,
      };
    }
    return { ok: false, mensagem: r.mensagem ?? TEXTOS.algoDeuErrado };
  } catch {
    return { ok: false, mensagem: TEXTOS.algoDeuErrado };
  }
}

/** Um UUID novo para cada registro que a tela cria (a venda, o cliente, a conferência): é a chave de idempotência. */
export function novoId(): string {
  return crypto.randomUUID();
}

/** A cotação de hoje, só para mostrar Dólar e Guarani. Sem cotação (ou sem rede), só Real. */
export function useCotacao(ponte: PonteDoCorona): {
  cotacao: CotacaoParaTela | null;
  data: string | null;
  carregada: boolean;
} {
  const [estado, setEstado] = useState<{
    cotacao: CotacaoParaTela | null;
    data: string | null;
    carregada: boolean;
  }>({
    cotacao: null,
    data: null,
    carregada: false,
  });
  useEffect(() => {
    let ativo = true;
    void chamar<CotacaoDoDia>(ponte, { operacao: 'cotacao.hoje' }).then((r) => {
      if (!ativo) return;
      if (r.ok && r.dados.disponivel && r.dados.dolar && r.dados.guarani) {
        setEstado({
          cotacao: { dolar: r.dados.dolar, guarani: r.dados.guarani },
          data: r.dados.dataDaCotacao,
          carregada: true,
        });
      } else {
        setEstado({ cotacao: null, data: null, carregada: true });
      }
    });
    return () => {
      ativo = false;
    };
  }, [ponte]);
  return estado;
}

/** Valor em reais (texto da API) nas três moedas, pela cotação do dia. */
export function Dinheiro({ reais, cotacao }: { reais: string; cotacao: CotacaoParaTela | null }) {
  const v = tresMoedas(reais, cotacao);
  if (!v) return <span>{reais}</span>;
  return (
    <ValorTresMoedas
      real={v.real}
      {...(v.dolar ? { dolar: v.dolar } : {})}
      {...(v.guarani ? { guarani: v.guarani } : {})}
    />
  );
}

const FOTOS_PRONTAS = new Map<string, string>();

/** Endereço da foto da peça (o processo principal baixa; a tela nunca leva token). Sem foto, a venda segue. */
export function useFoto(
  ponte: PonteDoCorona,
  hash: string | null | undefined,
  tamanho: 'miniatura' | 'media' | 'grande',
): string | null {
  const chave = hash ? `${hash}/${tamanho}` : '';
  const [url, setUrl] = useState<string | null>(chave ? (FOTOS_PRONTAS.get(chave) ?? null) : null);
  useEffect(() => {
    if (!hash) {
      setUrl(null);
      return;
    }
    const pronta = FOTOS_PRONTAS.get(chave);
    if (pronta) {
      setUrl(pronta);
      return;
    }
    let ativo = true;
    void chamar<FotoPronta>(ponte, { operacao: 'fotos.ver', hash, tamanho }).then((r) => {
      if (!ativo || !r.ok) return;
      FOTOS_PRONTAS.set(chave, r.dados.dataUrl);
      setUrl(r.dados.dataUrl);
    });
    return () => {
      ativo = false;
    };
  }, [ponte, hash, tamanho, chave]);
  return url;
}
