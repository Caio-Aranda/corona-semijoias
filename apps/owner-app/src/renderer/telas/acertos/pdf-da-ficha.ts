/** Monta o PDF do acerto fechado com os mesmos textos e valores da ficha na tela (o PDF só desenha o que a API devolveu). */
import type { AcertoParaPdf } from '../../nucleo/exportacao/pdf-acerto';
import { formatarGuarani, dataParaTela, tresMoedas } from '../../nucleo/moedas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { dataEHora } from '../sincronizacao/dados';
import type { FichaDoAcerto } from './dados';
import type { FechamentoGravado } from './fechada';
import { percentualDoAcerto, realDoAcerto } from './Valor';

const T = TEXTOS_DE_ACERTOS;

/** "R$ 10,00" e, havendo cotação gravada, "R$ 10,00 (US$ 2,00 · Gs. 12.500)". Negativo: só Real. */
export function valorEmTexto(valor: string, f: FechamentoGravado): string {
  const real = realDoAcerto(valor);
  if (valor.startsWith('-')) return real;
  const t = tresMoedas(valor, f.cotacao);
  const outras = [t?.dolar, t?.guarani].filter(Boolean);
  return outras.length > 0 ? `${real} (${outras.join(' · ')})` : real;
}

/** A frase da cotação gravada, ou o aviso de que não há. */
export function fraseDaCotacao(f: FechamentoGravado): string {
  const t = tresMoedas('1.00', f.cotacao);
  return t?.dolar ? T.previa.cotacao.frase(dataParaTela(f.cotacaoData)) : T.previa.cotacao.sem;
}

/** As duas frases da meta, com a soma em Guarani que a decidiu e o percentual gravado. */
export function frasesDaMeta(f: FechamentoGravado): [string, string] {
  const C = T.previa.comissao;
  const percentual = percentualDoAcerto(f.percentualComissao);
  const bateu = f.vendidoParaMetaGuarani >= f.metaGuarani;
  return [
    C.vendidoNaSemana(
      formatarGuarani(BigInt(f.vendidoParaMetaGuarani)),
      formatarGuarani(BigInt(f.metaGuarani)),
    ),
    bateu ? C.bateu(percentual) : C.naoBateu(percentual),
  ];
}

export function nomeDoArquivo(ficha: FichaDoAcerto): string {
  const limpo = ficha.vendedorNome
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
  return `acerto-${limpo || 'vendedor'}-semana-${ficha.semanaNumero}.pdf`;
}

export function acertoParaPdf(ficha: FichaDoAcerto, f: FechamentoGravado): AcertoParaPdf {
  const P = T.previa;
  const R = T.fechada;
  const [soma, resultado] = frasesDaMeta(f);
  let n = 0;
  return {
    loja: 'Corona Semijoias',
    titulo: T.ficha.titulo(ficha.vendedorNome, ficha.semanaNumero),
    cabecalho: [R.fechadoEm(dataEHora(f.fechadoEm))],
    secoes: [
      {
        titulo: P.pecas.titulo,
        tabela: {
          titulos: [
            P.pecas.colunas.peca,
            P.pecas.colunas.saiu,
            P.pecas.colunas.boa,
            P.pecas.colunas.defeito,
            P.pecas.colunas.faltou,
            R.pecas.defeitoAprovado,
          ],
          pesos: [4, 1, 1, 1, 1, 1],
          linhas: f.itens.map((i) => [
            `${i.produto.nome} (${i.produto.codigo})`,
            String(i.esperado),
            String(i.contadoBoa),
            String(i.contadoDefeito),
            String(i.falta),
            String(i.defeitoAprovado),
          ]),
        },
      },
      {
        titulo: R.pendencias.legenda,
        tabela: {
          titulos: Object.values(R.pendencias.colunas),
          pesos: [4, 3, 1, 2, 2],
          linhas: f.pendencias.map((p) => [
            p.produto ? `${p.produto.nome} (${p.produto.codigo})` : R.pendencias.semPeca,
            R.pendencias.tipos[p.tipo] ?? p.tipo,
            String(p.quantidade),
            realDoAcerto(p.valor),
            R.pendencias.situacoes[p.status] ?? p.status,
          ]),
        },
      },
      {
        titulo: P.comissao.titulo,
        linhas: [{ texto: soma }, { texto: resultado, negrito: true }],
        tabela: {
          titulos: Object.values(P.comissao.colunas),
          linhas: f.comissoes.map((l) => {
            n += 1;
            return [
              l.tipo === 'ESTORNO' ? P.comissao.devolucao(n) : P.comissao.venda(n),
              realDoAcerto(l.base),
              percentualDoAcerto(l.percentual),
              realDoAcerto(l.valor),
            ];
          }),
        },
      },
      {
        titulo: P.totais.titulo,
        linhas: [
          { rotulo: P.totais.vendido, valor: valorEmTexto(f.vendidoBruto, f) },
          { rotulo: P.totais.descontos, valor: valorEmTexto(f.descontos, f) },
          { rotulo: P.totais.devolvido, valor: valorEmTexto(f.devolvido, f) },
          { rotulo: P.totais.vendidoLiquido, valor: valorEmTexto(f.vendidoLiquido, f) },
          { rotulo: P.totais.comissao, valor: valorEmTexto(f.comissao, f) },
          { rotulo: P.totais.faltasCobradas, valor: valorEmTexto(f.faltasCobradas, f) },
          { rotulo: P.totais.liquido, valor: valorEmTexto(f.liquido, f), negrito: true },
          { rotulo: P.totais.creditoUsado, valor: valorEmTexto(f.creditoUsado, f) },
          { rotulo: R.totais.recebido, valor: valorEmTexto(f.recebidoNoAcerto, f) },
          { rotulo: R.totais.ficouDevendo, valor: valorEmTexto(f.ficouDevendo, f) },
          { rotulo: R.totais.pagoAoVendedor, valor: valorEmTexto(f.pagoAoVendedor, f) },
        ],
      },
      {
        titulo: R.recebimentos.legenda,
        tabela: {
          titulos: Object.values(R.recebimentos.colunas),
          linhas: f.recebimentos.map((r) => [
            R.recebimentos.formas[r.forma] ?? r.forma,
            realDoAcerto(r.valor),
            dataEHora(r.recebidoEm),
            r.estornado ? R.recebimentos.desfeito : '',
          ]),
        },
      },
      {
        titulo: R.contas.legenda,
        tabela: {
          titulos: Object.values(R.contas.colunas),
          linhas: f.contas.map((c) => [
            R.contas.parcela(c.numero, c.totalDeParcelas),
            realDoAcerto(c.valor),
            dataParaTela(c.vencimento),
            R.recebimentos.formas[c.formaPrevista] ?? c.formaPrevista,
            R.contas.situacoes[c.status] ?? c.status,
          ]),
        },
      },
    ],
    rodape: fraseDaCotacao(f),
  };
}
