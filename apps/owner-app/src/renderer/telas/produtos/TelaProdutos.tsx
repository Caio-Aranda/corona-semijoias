import { Aviso, Botao, Campo, EstadoDaLista, Paginacao, Tabela } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { tresMoedas } from '../../nucleo/moedas';
import { TEXTOS } from '../../textos';
import { ConsultaDeProduto } from './ConsultaDeProduto';
import { FichaDoProduto } from './FichaDoProduto';
import { ImportacaoDeFotos } from './ImportacaoDeFotos';
import { ImportacaoDePlanilha } from './ImportacaoDePlanilha';
import { codigoPrincipal, lerOpcoes, lerPaginaDeProdutos } from './dados';

const T = TEXTOS.produtos;
const TAMANHO = 20;

type Modo =
  { tela: 'lista' | 'planilha' | 'fotos' | 'consulta' } | { tela: 'ficha'; id: string | null };

export interface TelaProdutosProps {
  ponte: PonteDoCorona;
}

const reais = (valor: string): string => tresMoedas(valor, null)?.real ?? valor;

/** Cadastros → Produtos: lista com foto, ficha, importações e consulta. */
export function TelaProdutos({ ponte }: TelaProdutosProps) {
  const [modo, setModo] = useState<Modo>({ tela: 'lista' });
  const [busca, setBusca] = useState('');
  const [buscaAplicada, setBuscaAplicada] = useState('');
  const [categoria, setCategoria] = useState('');
  const [fornecedor, setFornecedor] = useState('');
  const [situacao, setSituacao] = useState('TODOS');
  const [pagina, setPagina] = useState(0);

  useEffect(() => {
    const relogio = setTimeout(() => {
      setBuscaAplicada(busca.trim());
      setPagina(0);
    }, 300);
    return () => clearTimeout(relogio);
  }, [busca]);

  const categorias = useBusca(
    () => chamar(ponte, 'categorias.listar', { pagina: 0, tamanho: 100 }),
    'categorias-do-filtro',
  );
  const fornecedores = useBusca(
    () => chamar(ponte, 'fornecedores.listar', { pagina: 0, tamanho: 100 }),
    'fornecedores-do-filtro',
  );
  const listaDeCategorias = categorias.dados ? lerOpcoes(categorias.dados) : [];
  const listaDeFornecedores = fornecedores.dados ? lerOpcoes(fornecedores.dados) : [];

  const lista = useBusca(
    () =>
      chamar(ponte, 'produtos.listar', {
        pagina,
        tamanho: TAMANHO,
        ...(buscaAplicada ? { busca: buscaAplicada } : {}),
        ...(categoria ? { categoriaId: categoria } : {}),
        ...(fornecedor ? { fornecedorId: fornecedor } : {}),
        ...(situacao === 'ATIVOS'
          ? { ativo: true }
          : situacao === 'INATIVOS'
            ? { ativo: false }
            : {}),
      }),
    `${buscaAplicada}|${categoria}|${fornecedor}|${situacao}|${pagina}`,
  );
  const dados = lista.dados ? lerPaginaDeProdutos(lista.dados) : null;
  const filtrando =
    buscaAplicada !== '' || categoria !== '' || fornecedor !== '' || situacao !== 'TODOS';
  const nomeDaCategoria = (id: string) => listaDeCategorias.find((c) => c.id === id)?.nome ?? '';

  if (modo.tela === 'ficha') {
    return (
      <FichaDoProduto
        key={modo.id ?? 'novo'}
        ponte={ponte}
        produtoId={modo.id}
        aoAbrirOutro={(id) => setModo({ tela: 'ficha', id })}
        aoVoltar={(mudou) => {
          setModo({ tela: 'lista' });
          if (mudou) lista.recarregar();
        }}
      />
    );
  }

  const abas: { chave: Modo['tela']; rotulo: string }[] = [
    { chave: 'lista', rotulo: T.abas.lista },
    { chave: 'planilha', rotulo: T.abas.planilha },
    { chave: 'fotos', rotulo: T.abas.fotos },
    { chave: 'consulta', rotulo: T.abas.consulta },
  ];

  const linhas = (dados?.itens ?? []).map((p) => ({
    foto: (
      <ImagemDoProduto
        ponte={ponte}
        hash={p.fotoPrincipal?.hash}
        tamanho="miniatura"
        nome={p.nome}
      />
    ),
    nome: p.nome,
    codigo: codigoPrincipal(p),
    categoria: nomeDaCategoria(p.categoriaId),
    preco: reais(p.precoVenda),
    situacao: p.ativo ? T.situacao.ativo : T.situacao.desativado,
    acoes: <Botao onClick={() => setModo({ tela: 'ficha', id: p.id })}>{T.abrir}</Botao>,
  }));

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao variante="primario" onClick={() => setModo({ tela: 'ficha', id: null })}>
          {T.novo}
        </Botao>
      </div>

      <div className="gestao-abas" role="tablist" aria-label={T.abas.rotulo}>
        {abas.map((a) => (
          <button
            key={a.chave}
            type="button"
            role="tab"
            aria-selected={modo.tela === a.chave}
            className={`gestao-aba-botao${modo.tela === a.chave ? ' gestao-aba-botao--atual' : ''}`}
            onClick={() => setModo({ tela: a.chave } as Modo)}
          >
            {a.rotulo}
          </button>
        ))}
      </div>

      {modo.tela === 'planilha' ? (
        <ImportacaoDePlanilha ponte={ponte} aoMudar={lista.recarregar} />
      ) : modo.tela === 'fotos' ? (
        <ImportacaoDeFotos ponte={ponte} aoMudar={lista.recarregar} />
      ) : modo.tela === 'consulta' ? (
        <ConsultaDeProduto ponte={ponte} aoAbrir={(id) => setModo({ tela: 'ficha', id })} />
      ) : (
        <>
          <form
            className="gestao-filtros"
            role="search"
            onSubmit={(e) => {
              e.preventDefault();
              setBuscaAplicada(busca.trim());
              setPagina(0);
            }}
          >
            <Campo
              rotulo={T.buscaRotulo}
              type="search"
              value={busca}
              autoComplete="off"
              autoFocus
              onChange={(e) => setBusca(e.target.value)}
            />
            <div className="cs-campo">
              <label htmlFor="filtro-categoria-produto">{T.filtroCategoria}</label>
              <select
                id="filtro-categoria-produto"
                value={categoria}
                onChange={(e) => {
                  setCategoria(e.target.value);
                  setPagina(0);
                }}
              >
                <option value="">{T.todas}</option>
                {listaDeCategorias.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.nome}
                  </option>
                ))}
              </select>
            </div>
            <div className="cs-campo">
              <label htmlFor="filtro-fornecedor-produto">{T.filtroFornecedor}</label>
              <select
                id="filtro-fornecedor-produto"
                value={fornecedor}
                onChange={(e) => {
                  setFornecedor(e.target.value);
                  setPagina(0);
                }}
              >
                <option value="">{T.todos}</option>
                {listaDeFornecedores.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.nome}
                  </option>
                ))}
              </select>
            </div>
            <div className="cs-campo">
              <label htmlFor="filtro-situacao-produto">{T.filtroSituacao}</label>
              <select
                id="filtro-situacao-produto"
                value={situacao}
                onChange={(e) => {
                  setSituacao(e.target.value);
                  setPagina(0);
                }}
              >
                <option value="TODOS">{T.todos}</option>
                <option value="ATIVOS">{T.ativos}</option>
                <option value="INATIVOS">{T.desativados}</option>
              </select>
            </div>
            {filtrando ? (
              <Botao
                onClick={() => {
                  setBusca('');
                  setBuscaAplicada('');
                  setCategoria('');
                  setFornecedor('');
                  setSituacao('TODOS');
                  setPagina(0);
                }}
              >
                {TEXTOS.lista.limparBusca}
              </Botao>
            ) : null}
          </form>
          {categorias.erro ? <Aviso tipo="atencao">{categorias.erro.mensagem}</Aviso> : null}

          <EstadoDaLista
            carregando={lista.carregando && dados === null}
            erro={lista.erro && dados === null ? lista.erro.mensagem : null}
            textoCarregando={T.buscando}
            aoTentarDeNovo={lista.recarregar}
          >
            {lista.erro && dados !== null ? (
              <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} />
            ) : null}
            <Tabela
              legenda={T.legenda}
              colunas={[
                { chave: 'foto', titulo: T.colunas.foto },
                { chave: 'nome', titulo: T.colunas.nome },
                { chave: 'codigo', titulo: T.colunas.codigo },
                { chave: 'categoria', titulo: T.colunas.categoria },
                { chave: 'preco', titulo: T.colunas.preco, numerica: true },
                { chave: 'situacao', titulo: T.colunas.situacao },
                { chave: 'acoes', titulo: T.colunas.acoes },
              ]}
              linhas={linhas}
              textoVazio={filtrando ? T.semResultado : T.vazio}
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
        </>
      )}
    </div>
  );
}
