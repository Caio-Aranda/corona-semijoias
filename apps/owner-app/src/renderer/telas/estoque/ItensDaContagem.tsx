import { Botao, Campo, EstadoDaLista, Paginacao, Tabela } from '@corona/ui';
import { useState } from 'react';
import { FILTROS_DOS_ITENS } from '../../../shared/estoque';
import type { PonteDoCorona } from '../../../shared/ponte';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { quantidadeComSinal } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { lerItem, lerPagina, type ItemDaContagem } from './dados';

const T = TEXTOS_DE_ESTOQUE.contando.lista;
const TAMANHO = 20;

export interface ItensDaContagemProps {
  ponte: PonteDoCorona;
  contagemId: string;
  /** Muda a cada leitura: a lista se atualiza na hora. */
  versao: number;
  /** Sem isso (contagem encerrada) a lista é só de leitura. */
  aoCorrigir: ((item: ItemDaContagem) => void) | null;
}

/** Esperado e contado de cada peça da contagem, com filtro, busca e páginas. */
export function ItensDaContagem({ ponte, contagemId, versao, aoCorrigir }: ItensDaContagemProps) {
  const [filtro, setFiltro] = useState<(typeof FILTROS_DOS_ITENS)[number]>('DIVERGENTES');
  const [busca, setBusca] = useState('');
  const [pagina, setPagina] = useState(0);
  const lista = useBusca(
    () =>
      chamar(ponte, 'contagens.itens', {
        id: contagemId,
        filtro,
        ...(busca.trim() ? { busca: busca.trim() } : {}),
        pagina,
        tamanho: TAMANHO,
      }),
    `itens|${contagemId}|${filtro}|${busca}|${pagina}|${versao}`,
  );
  const dados = lista.dados ? lerPagina(lista.dados, lerItem) : null;

  const colunas = [
    { chave: 'foto', titulo: TEXTOS_DE_ESTOQUE.comuns.foto },
    { chave: 'nome', titulo: TEXTOS_DE_ESTOQUE.comuns.peca },
    { chave: 'codigo', titulo: TEXTOS_DE_ESTOQUE.comuns.codigo },
    { chave: 'esperado', titulo: T.colunas.esperado, numerica: true },
    { chave: 'contado', titulo: T.colunas.contado, numerica: true },
    { chave: 'diferenca', titulo: T.colunas.diferenca, numerica: true },
    ...(aoCorrigir ? [{ chave: 'acoes', titulo: T.colunas.acoes }] : []),
  ];
  const linhas = (dados?.itens ?? []).map((i) => ({
    foto: (
      <ImagemDoProduto
        ponte={ponte}
        hash={i.peca.fotoHash}
        tamanho="miniatura"
        nome={i.peca.nome}
      />
    ),
    nome: i.peca.nome,
    codigo: i.peca.codigo,
    esperado: i.esperado,
    contado: i.contado === null ? T.naoLido : i.contado,
    diferenca: i.diferenca === null ? '' : quantidadeComSinal(i.diferenca),
    acoes: aoCorrigir ? (
      <Botao aria-label={T.corrigirDe(i.peca.nome)} onClick={() => aoCorrigir(i)}>
        {T.corrigir}
      </Botao>
    ) : null,
  }));

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-itens-da-contagem">
      <h2 id="titulo-itens-da-contagem" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <div className="gestao-filtros">
        <div className="cs-campo">
          <label htmlFor="filtro-itens-da-contagem">{T.mostrar}</label>
          <select
            id="filtro-itens-da-contagem"
            value={filtro}
            onChange={(e) => {
              setFiltro(e.target.value as typeof filtro);
              setPagina(0);
            }}
          >
            {FILTROS_DOS_ITENS.map((f) => (
              <option key={f} value={f}>
                {T.filtros[f]}
              </option>
            ))}
          </select>
        </div>
        <Campo
          rotulo={T.busca}
          value={busca}
          autoComplete="off"
          onChange={(e) => {
            setBusca(e.target.value);
            setPagina(0);
          }}
        />
      </div>
      <EstadoDaLista
        carregando={lista.carregando && dados === null}
        erro={lista.erro && dados === null ? lista.erro.mensagem : null}
        textoCarregando={TEXTOS_DE_ESTOQUE.comuns.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        <Tabela
          legenda={T.legenda}
          textoVazio={T.vazio[filtro]}
          colunas={colunas}
          linhas={linhas}
        />
        {dados ? (
          <Paginacao
            pagina={dados.pagina}
            tamanho={dados.tamanho}
            total={dados.total}
            nomeDoItem={T.itens}
            aoMudar={setPagina}
          />
        ) : null}
      </EstadoDaLista>
    </section>
  );
}
