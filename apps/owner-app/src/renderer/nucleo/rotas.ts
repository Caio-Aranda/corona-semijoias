import { useCallback, useEffect, useState } from 'react';
import { TEXTOS } from '../textos';
import { TEXTOS_DE_ACERTOS } from '../textos-acertos';
import { TEXTOS_DE_COMPRAS } from '../textos-compras';
import { TEXTOS_DE_CONCILIACOES } from '../textos-conciliacoes';
import { TEXTOS_DE_DEVOLUCOES } from '../textos-devolucoes';
import { TEXTOS_DE_ESTOQUE } from '../textos-estoque';
import { TEXTOS_DE_FINANCEIRO } from '../textos-financeiro';
import { TEXTOS_DE_MALETAS } from '../textos-maletas';
import { TEXTOS_RELATORIOS } from '../textos-relatorios';
import { TEXTOS_DE_SINCRONIZACAO } from '../textos-sincronizacao';
import { ORDEM_DOS_RELATORIOS, RELATORIOS } from './relatorios';
import {
  ORDEM_DOS_RELATORIOS_DE_COMPRAS,
  ORDEM_DOS_RELATORIOS_DE_ESTOQUE,
} from './relatorios-estoque';
import { ORDEM_DOS_RELATORIOS_DE_DEVOLUCAO } from './relatorios-devolucao';
import { ORDEM_DOS_RELATORIOS_DE_MALETA } from './relatorios-maleta';
import { ORDEM_DOS_RELATORIOS_FINANCEIROS } from './relatorios-financeiros';
import {
  ORDEM_DOS_RELATORIOS_DE_VENDAS,
  RELATORIOS_DE_VENDAS_NO_ESTOQUE,
} from './relatorios-vendas';
import { TEXTOS_DE_RELATORIOS_DE_VENDAS } from '../textos-relatorios-vendas';
import { TEXTOS_DE_RELATORIOS_FINANCEIROS } from '../textos-relatorios-financeiros';
import { TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from '../textos-relatorios-devolucao';

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
  'maletas/lista',
  'maletas/montar',
  'pessoas/vendedores',
  'pessoas/clientes',
  'pessoas/usuarios',
  'computadores/lista',
  'computadores/envios',
  'computadores/vendas',
  'computadores/clientes',
  'devolucoes/autorizar',
  'devolucoes/revisar',
  'devolucoes/lista',
  'devolucoes/trocas',
  'devolucoes/creditos',
  'devolucoes/garantia',
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
  'relatorios/maleta-remessas',
  'relatorios/maleta-saldo',
  'relatorios/maleta-mercadoria',
  'relatorios/maleta-em-aberto',
  'relatorios/maleta-reposicoes',
  'relatorios/devolucao-por-motivo',
  'relatorios/devolucao-por-vendedor',
  'relatorios/devolucao-defeitos',
  'relatorios/devolucao-trocas',
  'relatorios/devolucao-creditos',
  'relatorios/devolucao-reembolsos',
  'relatorios/devolucao-autorizacoes',
  'relatorios/devolucao-conferencia',
  'relatorios/financeiro-acertos',
  'relatorios/financeiro-pendencias',
  'relatorios/financeiro-esperado-contado',
  'relatorios/financeiro-comissoes',
  'relatorios/financeiro-a-receber',
  'relatorios/financeiro-a-pagar',
  'relatorios/financeiro-inadimplencia',
  'relatorios/financeiro-caixa',
  'relatorios/financeiro-recebimentos',
  'relatorios/financeiro-resultado',
  'relatorios/financeiro-resultado-registros',
  'relatorios/financeiro-lucro-produto',
  'relatorios/financeiro-lucro-vendedor',
  'relatorios/vendas-lista',
  'relatorios/vendas-canceladas',
  'relatorios/vendas-por-vendedor',
  'relatorios/vendas-por-praca',
  'relatorios/vendas-por-cliente',
  'relatorios/vendas-por-produto',
  'relatorios/vendas-por-categoria',
  'relatorios/vendas-por-periodo',
  'relatorios/vendas-ranking-vendedores',
  'relatorios/vendas-ranking-pecas',
  'relatorios/vendas-ticket-medio',
  'relatorios/vendas-curva-abc',
  'acertos/lista',
  'acertos/pendencias',
  'acertos/comissao',
  'financeiro/caixa',
  'financeiro/categorias',
  'financeiro/contas-a-pagar',
  'financeiro/contas-a-receber',
  'financeiro/recebimentos',
  'conciliacoes/dinheiro',
  'conciliacoes/conferencia-diaria',
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
    titulo: TEXTOS_DE_ACERTOS.menu.grupo,
    itens: [
      { chave: 'acertos/lista', rotulo: TEXTOS_DE_ACERTOS.menu.acertos },
      { chave: 'acertos/pendencias', rotulo: TEXTOS_DE_ACERTOS.menu.pendencias },
      { chave: 'acertos/comissao', rotulo: TEXTOS_DE_ACERTOS.menu.comissao },
    ],
  },
  {
    titulo: TEXTOS_DE_FINANCEIRO.menu.grupo,
    itens: [
      { chave: 'financeiro/caixa', rotulo: TEXTOS_DE_FINANCEIRO.menu.caixa },
      { chave: 'financeiro/categorias', rotulo: TEXTOS_DE_FINANCEIRO.menu.categorias },
      { chave: 'financeiro/contas-a-pagar', rotulo: TEXTOS_DE_FINANCEIRO.menu.contasAPagar },
      { chave: 'financeiro/contas-a-receber', rotulo: TEXTOS_DE_FINANCEIRO.menu.contasAReceber },
      { chave: 'financeiro/recebimentos', rotulo: TEXTOS_DE_FINANCEIRO.menu.recebimentos },
    ],
  },
  {
    titulo: TEXTOS_DE_MALETAS.menu.grupo,
    itens: [
      { chave: 'maletas/lista', rotulo: TEXTOS_DE_MALETAS.menu.lista },
      { chave: 'maletas/montar', rotulo: TEXTOS_DE_MALETAS.menu.montar },
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
    titulo: TEXTOS_DE_SINCRONIZACAO.menu.grupo,
    itens: [
      { chave: 'computadores/lista', rotulo: TEXTOS_DE_SINCRONIZACAO.menu.computadores },
      { chave: 'computadores/envios', rotulo: TEXTOS_DE_SINCRONIZACAO.menu.envios },
      { chave: 'computadores/vendas', rotulo: TEXTOS_DE_SINCRONIZACAO.menu.vendas },
      { chave: 'computadores/clientes', rotulo: TEXTOS_DE_SINCRONIZACAO.menu.clientes },
    ],
  },
  {
    titulo: TEXTOS_DE_DEVOLUCOES.menu.grupo,
    itens: [
      { chave: 'devolucoes/autorizar', rotulo: TEXTOS_DE_DEVOLUCOES.menu.autorizar },
      { chave: 'devolucoes/revisar', rotulo: TEXTOS_DE_DEVOLUCOES.menu.revisar },
      { chave: 'devolucoes/lista', rotulo: TEXTOS_DE_DEVOLUCOES.menu.devolucoes },
      { chave: 'devolucoes/trocas', rotulo: TEXTOS_DE_DEVOLUCOES.menu.trocas },
      { chave: 'devolucoes/creditos', rotulo: TEXTOS_DE_DEVOLUCOES.menu.creditos },
      { chave: 'devolucoes/garantia', rotulo: TEXTOS_DE_DEVOLUCOES.menu.garantia },
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
    itens: [...ORDEM_DOS_RELATORIOS_DE_ESTOQUE, ...RELATORIOS_DE_VENDAS_NO_ESTOQUE].map((nome) => ({
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
  {
    titulo: TEXTOS_DE_MALETAS.relatorios.grupoDoMenu,
    itens: ORDEM_DOS_RELATORIOS_DE_MALETA.map((nome) => ({
      chave: `relatorios/${nome}`,
      rotulo: RELATORIOS[nome].titulo,
    })),
  },
  {
    titulo: TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO.grupoDoMenu,
    itens: [
      ...ORDEM_DOS_RELATORIOS_DE_DEVOLUCAO.map((nome) => ({
        chave: `relatorios/${nome}`,
        rotulo: RELATORIOS[nome].titulo,
      })),
      {
        chave: 'relatorios/devolucao-conferencia',
        rotulo: TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO.conferencia.menu,
      },
    ],
  },
  {
    titulo: TEXTOS_DE_RELATORIOS_DE_VENDAS.grupoDoMenu,
    itens: ORDEM_DOS_RELATORIOS_DE_VENDAS.map((nome) => ({
      chave: `relatorios/${nome}`,
      rotulo: RELATORIOS[nome].titulo,
    })),
  },
  {
    titulo: TEXTOS_DE_RELATORIOS_FINANCEIROS.grupoDoMenu,
    itens: ORDEM_DOS_RELATORIOS_FINANCEIROS.map((nome) => ({
      chave: `relatorios/${nome}`,
      rotulo: RELATORIOS[nome].titulo,
    })),
  },
  {
    titulo: TEXTOS_DE_CONCILIACOES.grupoDoMenu,
    itens: [
      { chave: 'conciliacoes/dinheiro', rotulo: TEXTOS_DE_CONCILIACOES.menu },
      {
        chave: 'conciliacoes/conferencia-diaria',
        rotulo: TEXTOS_DE_CONCILIACOES.diaria.menu,
      },
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
