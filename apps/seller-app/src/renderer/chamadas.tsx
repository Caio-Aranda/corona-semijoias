import { ValorTresMoedas } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { CotacaoDaVenda, CotacaoDoDia, FotoPronta } from '../shared/dados';
import type { PedidoDeChamada, PonteDoCorona } from '../shared/ponte';
import {
  centavosDeTexto,
  formatarDolar,
  formatarGuarani,
  formatarReais,
  dataParaTela,
  tresMoedas,
  type CotacaoParaTela,
} from './moedas';
import { comMotivos, mensagemDoErro } from './erros-es';
import { idiomaAtual } from './idioma';
import { TEXTOS } from './textos';

export type Resposta<T> =
  | { ok: true; dados: T }
  | {
      ok: false;
      mensagem: string;
      clienteExistente?: { id: string; nome: string; telefone?: string; cidade?: string };
    };

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
    return {
      ok: false,
      mensagem: comMotivos(
        mensagemDoErro(idiomaAtual(), r.codigo, r.mensagem, TEXTOS.algoDeuErrado),
        r.campos,
      ),
      ...(r.clienteExistente ? { clienteExistente: r.clienteExistente } : {}),
    };
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
  /** "Cotação do Dólar e do Guarani de hoje, 06/11/2026." ou a da última que o computador recebeu, sempre com a data. */
  aviso: string | null;
  /** "Cotação de 06/11/2026." (para a linha "Dados até..." dos relatórios). */
  curta: string | null;
} {
  const [estado, setEstado] = useState<{
    cotacao: CotacaoParaTela | null;
    data: string | null;
    carregada: boolean;
    aviso: string | null;
    curta: string | null;
  }>({
    cotacao: null,
    data: null,
    carregada: false,
    aviso: null,
    curta: null,
  });
  useEffect(() => {
    let ativo = true;
    void chamar<CotacaoDoDia>(ponte, { operacao: 'cotacao.hoje' }).then((r) => {
      if (!ativo) return;
      if (r.ok && r.dados.disponivel && r.dados.dolar && r.dados.guarani) {
        const data = r.dados.dataDaCotacao;
        const naTela = data ? dataParaTela(data) : null;
        setEstado({
          cotacao: { dolar: r.dados.dolar, guarani: r.dados.guarani },
          data,
          carregada: true,
          aviso: naTela
            ? r.dados.deDiaAnterior
              ? TEXTOS.cotacao.ultima(naTela)
              : TEXTOS.cotacao.deHoje(naTela)
            : null,
          curta: naTela ? TEXTOS.cotacao.doDia(naTela) : null,
        });
      } else {
        setEstado({ cotacao: null, data: null, carregada: true, aviso: null, curta: null });
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

/**
 * Valor que o servidor já converteu, cada lançamento pela cotação do seu dia (ADR-0006, item 10): a tela só formata.
 * Sem Dólar ou Guarani (faltou cotação), só Real.
 */
export function DinheiroConvertido({
  reais,
  dolar,
  guarani,
}: {
  reais: string;
  dolar?: string | null | undefined;
  guarani?: string | null | undefined;
}) {
  const real = centavosDeTexto(reais);
  if (real === null) return <span>{reais}</span>;
  const centavosDeDolar = dolar ? centavosDeTexto(dolar) : null;
  const centavosDeGuarani = guarani ? centavosDeTexto(guarani) : null;
  return (
    <ValorTresMoedas
      real={formatarReais(real)}
      {...(centavosDeDolar !== null && centavosDeGuarani !== null
        ? {
            dolar: formatarDolar(centavosDeDolar),
            guarani: formatarGuarani(centavosDeGuarani / 100n),
          }
        : {})}
    />
  );
}

/** A cotação gravada na venda (a do dia dela), para mostrar os valores dela em Dólar e Guarani. */
export function cotacaoDaVenda(venda: { cotacao: CotacaoDaVenda | null }): CotacaoParaTela | null {
  return venda.cotacao ? { dolar: venda.cotacao.dolar, guarani: venda.cotacao.guarani } : null;
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
