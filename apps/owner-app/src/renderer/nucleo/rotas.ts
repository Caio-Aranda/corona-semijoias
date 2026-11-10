import { useCallback, useEffect, useState } from 'react';
import { TEXTOS } from '../textos';

/** As telas do menu. A chave aparece na barra de endereço (#/pessoas/usuarios), nunca na tela. */
export const ROTAS = [
  'inicio',
  'cadastros/produtos',
  'cadastros/fornecedores',
  'cadastros/categorias',
  'cadastros/pracas',
  'cadastros/semanas',
  'cadastros/restricoes',
  'pessoas/vendedores',
  'pessoas/clientes',
  'pessoas/usuarios',
] as const;
export type Rota = (typeof ROTAS)[number];

export const MENU = [
  { itens: [{ chave: 'inicio', rotulo: TEXTOS.menu.inicio }] },
  {
    titulo: TEXTOS.menu.cadastros,
    itens: [
      { chave: 'cadastros/produtos', rotulo: TEXTOS.menu.produtos },
      { chave: 'cadastros/fornecedores', rotulo: TEXTOS.menu.fornecedores },
      { chave: 'cadastros/categorias', rotulo: TEXTOS.menu.categorias },
      { chave: 'cadastros/pracas', rotulo: TEXTOS.menu.pracas },
      { chave: 'cadastros/semanas', rotulo: TEXTOS.menu.semanas },
      { chave: 'cadastros/restricoes', rotulo: TEXTOS.menu.restricoes },
    ],
  },
  {
    titulo: TEXTOS.menu.pessoas,
    itens: [
      { chave: 'pessoas/vendedores', rotulo: TEXTOS.menu.vendedores },
      { chave: 'pessoas/clientes', rotulo: TEXTOS.menu.clientes },
      { chave: 'pessoas/usuarios', rotulo: TEXTOS.menu.usuarios },
    ],
  },
];

/** Nome do item na tela, para "Em breve" e títulos. */
export function rotuloDaRota(rota: Rota): string {
  for (const grupo of MENU) {
    const item = grupo.itens.find((i) => i.chave === rota);
    if (item) return item.rotulo;
  }
  return TEXTOS.menu.inicio;
}

/** Lê a rota do endereço; o que não existe cai no início. */
export function lerRota(hash: string): Rota {
  const chave = hash.replace(/^#\/?/, '');
  return (ROTAS as readonly string[]).includes(chave) ? (chave as Rota) : 'inicio';
}

/** Rota atual pelo endereço da janela (#/...). Voltar e avançar do teclado funcionam. */
export function useRota(): [Rota, (rota: Rota) => void] {
  const [rota, setRota] = useState<Rota>(() => lerRota(window.location.hash));
  useEffect(() => {
    const aoMudar = () => setRota(lerRota(window.location.hash));
    window.addEventListener('hashchange', aoMudar);
    return () => window.removeEventListener('hashchange', aoMudar);
  }, []);
  const irPara = useCallback((nova: Rota) => {
    window.location.hash = `/${nova}`;
    setRota(nova);
  }, []);
  return [rota, irPara];
}
