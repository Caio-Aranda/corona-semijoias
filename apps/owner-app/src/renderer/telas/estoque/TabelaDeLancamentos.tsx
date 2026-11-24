import { Botao, Tabela } from '@corona/ui';
import type { ReactNode } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { podeDesfazer, quantidadeComSinal, rotuloDoTipo } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { dataEHoraParaTela, type Lancamento } from './dados';

const T = TEXTOS_DE_ESTOQUE.historico;

export interface TabelaDeLancamentosProps {
  ponte: PonteDoCorona;
  lancamentos: Lancamento[];
  /** Na lista de todas as peças aparece a coluna da peça, com foto. */
  mostrarPeca: boolean;
  textoVazio: string;
  aoDesfazer: (l: Lancamento) => void;
}

/** Descrição curta de um lançamento, para ligar o "desfazer" ao original. */
function descreverOriginal(l: Lancamento): string {
  return `${rotuloDoTipo(l.tipo)} ${quantidadeComSinal(l.quantidade)} de ${dataEHoraParaTela(l.ocorridaEm).slice(0, 10)}`;
}

/**
 * Histórico do estoque: data, tipo em palavras da loja, quantidade com sinal, quanto ficou, quem e
 * o motivo. O que já foi desfeito, e o próprio "desfazer", não oferecem desfazer de novo.
 */
export function TabelaDeLancamentos({
  ponte,
  lancamentos,
  mostrarPeca,
  textoVazio,
  aoDesfazer,
}: TabelaDeLancamentosProps) {
  const porId = new Map(lancamentos.map((l) => [l.id, l]));
  const colunas = [
    { chave: 'data', titulo: T.colunas.data },
    ...(mostrarPeca
      ? [
          { chave: 'foto', titulo: TEXTOS_DE_ESTOQUE.comuns.foto },
          { chave: 'peca', titulo: TEXTOS_DE_ESTOQUE.comuns.peca },
        ]
      : []),
    { chave: 'tipo', titulo: T.colunas.tipo },
    { chave: 'quantidade', titulo: T.colunas.quantidade, numerica: true },
    { chave: 'saldoDepois', titulo: T.colunas.saldoDepois, numerica: true },
    { chave: 'quem', titulo: T.colunas.quem },
    { chave: 'motivo', titulo: T.colunas.motivo },
    { chave: 'situacao', titulo: T.colunas.situacao },
    { chave: 'acoes', titulo: T.colunas.acoes },
  ];
  const linhas = lancamentos.map((l) => {
    const original = l.desfazId ? porId.get(l.desfazId) : undefined;
    const situacao: ReactNode = l.desfazId
      ? T.desfazDe(
          original
            ? descreverOriginal(original)
            : T.desfazUmAnterior.replace(/^(Desfaz|Deshace) /, ''),
        )
      : l.desfeitoPorId
        ? T.desfeito
        : '';
    return {
      data: dataEHoraParaTela(l.ocorridaEm),
      foto: (
        <ImagemDoProduto
          ponte={ponte}
          hash={l.peca.fotoHash}
          tamanho="miniatura"
          nome={l.peca.nome}
        />
      ),
      peca: (
        <>
          {l.peca.nome}
          <br />
          <span className="gestao-codigo">{l.peca.codigo}</span>
        </>
      ),
      tipo: rotuloDoTipo(l.tipo),
      quantidade: quantidadeComSinal(l.quantidade),
      saldoDepois: l.saldoDepois,
      quem: l.usuarioNome,
      motivo: l.motivo,
      situacao,
      acoes: podeDesfazer({
        desfazDeOutro: l.desfazId !== null,
        desfeito: l.desfeitoPorId !== null,
      }) ? (
        <Botao
          aria-label={T.desfazerDe(
            rotuloDoTipo(l.tipo),
            quantidadeComSinal(l.quantidade),
            l.peca.nome,
          )}
          onClick={() => aoDesfazer(l)}
        >
          {T.desfazer}
        </Botao>
      ) : null,
    };
  });
  return <Tabela legenda={T.legenda} colunas={colunas} linhas={linhas} textoVazio={textoVazio} />;
}
