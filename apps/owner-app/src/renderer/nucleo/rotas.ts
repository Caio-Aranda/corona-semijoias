import { useCallback, useEffect, useState } from 'react';
import { TEXTOS } from '../textos';
import { TEXTOS_DE_COMPRAS } from '../textos-compras';
import { TEXTOS_DE_ESTOQUE } from '../textos-estoque';
import { TEXTOS_RELATORIOS } from '../textos-relatorios';
import { ORDEM_DOS_RELATORIOS, RELATORIOS } from './relatorios';
import {
  ORDEM_DOS_RELATORIOS_DE_COMPRAS,
  ORDEM_DOS_RELATORIOS_DE_ESTOQUE,
} from './relatorios-estoque';

/** As telas do menu. A chave aparece na barra de endereço (#/pessoas/usuarios), nunca na tela. */
export const ROTAS = [
  'inicio',
  'cadastros/produtos',
  'cadastros/fornecedores',
  'cadastros/categorias',
  'cadastros/pracas',
  'cadastros/semanas',
  'cadastros/restricoes',
  'estoque/posicao',
  'estoque/entradas-e-saidas',
  'estoque/acabando',
  'estoque/contagem',
  'estoque/receber-compra',
  'estoque/compras',
  'pessoas/vendedores',
  'pessoas/clientes',
  'pessoas/usuarios',
  'relatorios/vendedores',
  'relatorios/clientes',
  'relatorios/fornecedores',
  'relatorios/produtos',
  'relatorios/sem-foto',
  'relatorios/sem-codigo-de-barras',
  'relatorios/codigos-em-conflito',
  'relatorios/aniversariantes',
  'relatorios/alteracoes-de-produtos',
  'relatorios/resultado-das-importacoes',
  'relatorios/estoque-posicao',
  'relatorios/estoque-movimentos',
  'relatorios/estoque-minimo',
  'relatorios/estoque-parados',
  'relatorios/estoque-contagens',
  'relatorios/estoque-conferencia',
  'relatorios/compras-por-compra',
  'relatorios/compras-por-fornecedor',
  'relatorios/compras-custos',
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
    titulo: TEXTOS_DE_COMPRAS.menu.grupo,
    itens: [
      { chave: 'estoque/posicao', rotulo: TEXTOS_DE_ESTOQUE.menu.estoque },
      { chave: 'estoque/entradas-e-saidas', rotulo: TEXTOS_DE_ESTOQUE.menu.entradasESaidas },
      { chave: 'estoque/acabando', rotulo: TEXTOS_DE_ESTOQUE.menu.acabando },
      { chave: 'estoque/contagem', rotulo: TEXTOS_DE_ESTOQUE.menu.contagem },
      { chave: 'estoque/receber-compra', rotulo: TEXTOS_DE_COMPRAS.menu.receber },
      { chave: 'estoque/compras', rotulo: TEXTOS_DE_COMPRAS.menu.compras },
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
  {
    titulo: TEXTOS_RELATORIOS.grupoDoMenu,
    itens: ORDEM_DOS_RELATORIOS.map((nome) => ({
      chave: `relatorios/${nome}`,
      rotulo: RELATORIOS[nome].titulo,
    })),
  },
  {
    titulo: TEXTOS_RELATORIOS.grupoDoEstoque,
    itens: ORDEM_DOS_RELATORIOS_DE_ESTOQUE.map((nome) => ({
      chave: `relatorios/${nome}`,
      rotulo: RELATORIOS[nome].titulo,
    })),
  },
  {
    titulo: TEXTOS_RELATORIOS.grupoDasCompras,
    itens: ORDEM_DOS_RELATORIOS_DE_COMPRAS.map((nome) => ({
      chave: `relatorios/${nome}`,
      rotulo: RELATORIOS[nome].titulo,
    })),
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
