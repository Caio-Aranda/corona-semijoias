import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';

/**
 * O que o Corona Gestão mostra (ADR-0016, item 15): o acerto antigo da §7.4 (Acertos, Pendências, Comissão por meta, o botão
 * "Acerto" do painel e da tela inicial) fica escondido enquanto `GET /api/configuracoes/telas` disser que não é para mostrar.
 * Nada é apagado: as rotas e os dados continuam no servidor. Enquanto a resposta não chega, a tela espera (é rápida); se a resposta não puder ser lida, mostra como antes, para ninguém perder o acesso.
 */
export interface TelasDoGestao {
  acertoDoVendedorVisivel: boolean;
}

/** Sem o provedor (só nos testes de uma tela solta) vale o que sempre valeu: tudo à mostra. */
const COMO_ANTES: TelasDoGestao = { acertoDoVendedorVisivel: true };
const CONTEXTO = createContext<TelasDoGestao>(COMO_ANTES);

/** Lê a resposta do servidor; o que não tem o formato esperado vale como "não deu para saber" (`null`). */
export function lerTelasDoGestao(bruto: unknown): TelasDoGestao | null {
  if (typeof bruto !== 'object' || bruto === null) return null;
  const v = (bruto as { acertoDoVendedorVisivel?: unknown }).acertoDoVendedorVisivel;
  return typeof v === 'boolean' ? { acertoDoVendedorVisivel: v } : null;
}

export function ProvedorDasTelasDoGestao({
  ponte,
  children,
}: {
  ponte: PonteDoCorona;
  children: ReactNode;
}) {
  const [telas, setTelas] = useState<TelasDoGestao | null>(null);
  useEffect(() => {
    let valida = true;
    void ponte
      .chamarApi({ operacao: 'telas.ler' })
      .then((r) => {
        if (!valida) return;
        const lidas = r.ok ? lerTelasDoGestao(r.dados) : null;
        setTelas(lidas ?? { acertoDoVendedorVisivel: true });
      })
      .catch(() => {
        if (valida) setTelas({ acertoDoVendedorVisivel: true });
      });
    return () => {
      valida = false;
    };
  }, [ponte]);
  // Espera a resposta (é rápida) para a tela nunca mostrar o acerto antigo por um instante e depois escondê-lo.
  if (!telas) return <main className="gestao-pagina" aria-busy="true" />;
  return <CONTEXTO.Provider value={telas}>{children}</CONTEXTO.Provider>;
}

export function useTelasDoGestao(): TelasDoGestao {
  return useContext(CONTEXTO);
}
