import { useEffect } from 'react';

/**
 * Aviso de "alterações não salvas". Uma tela com formulário registra aqui uma função que diz se há
 * alteração por salvar; o menu e o fechamento da janela consultam antes de sair.
 */
let guarda: (() => boolean) | null = null;

export function temAlteracoesNaoSalvas(): boolean {
  return guarda ? guarda() : false;
}

/** Enquanto a tela estiver aberta, `haAlteracoes` diz se há algo por salvar. */
export function useGuardaDeSaida(haAlteracoes: () => boolean): void {
  useEffect(() => {
    const minha = haAlteracoes;
    guarda = minha;
    const aoFechar = (e: BeforeUnloadEvent) => {
      if (!minha()) return;
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', aoFechar);
    return () => {
      window.removeEventListener('beforeunload', aoFechar);
      if (guarda === minha) guarda = null;
    };
  }, [haAlteracoes]);
}
