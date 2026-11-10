import { Aviso, Botao, Campo, EstadoDaLista, Paginacao, Tabela } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { lerLista, lerPaginaDeVendedores, texto, telefoneDaLista } from './dados';
import { FichaDoVendedor } from './FichaDoVendedor';

const T = TEXTOS.vendedores;
const TAMANHO = 20;
/** Com filtro de praça a API não filtra: busca até 100 e a tela separa (poucos vendedores por loja). */
const TAMANHO_COM_PRACA = 100;

type Modo = { tela: 'lista' } | { tela: 'ficha'; id: string | null };

export interface TelaVendedoresProps {
  ponte: PonteDoCorona;
}

/** Pessoas → Vendedores: lista com busca, praça e situação, e a ficha com abas. */
export function TelaVendedores({ ponte }: TelaVendedoresProps) {
  const [modo, setModo] = useState<Modo>({ tela: 'lista' });
  const [busca, setBusca] = useState('');
  const [buscaAplicada, setBuscaAplicada] = useState('');
  const [praca, setPraca] = useState('');
  const [situacao, setSituacao] = useState('TODOS');
  const [pagina, setPagina] = useState(0);

  useEffect(() => {
    const relogio = setTimeout(() => {
      setBuscaAplicada(busca.trim());
      setPagina(0);
    }, 300);
    return () => clearTimeout(relogio);
  }, [busca]);

  const pracas = useBusca(
    () => chamar(ponte, 'pracas.listar', { pagina: 0, tamanho: 100 }),
    'pracas-do-filtro',
  );
  const listaDePracas = pracas.dados
    ? lerLista((pracas.dados as { itens?: unknown }).itens).filter((p) => p.id)
    : [];
  const nomeDaPraca = (id: unknown): string =>
    texto(listaDePracas.find((p) => p.id === id) ?? { version: 0 }, 'nome');

  const lista = useBusca(
    () =>
      chamar(ponte, 'vendedores.listar', {
        pagina: praca ? 0 : pagina,
        tamanho: praca ? TAMANHO_COM_PRACA : TAMANHO,
        situacao,
        ...(buscaAplicada ? { busca: buscaAplicada } : {}),
      }),
    `${buscaAplicada}|${situacao}|${praca ? 'praca' : pagina}`,
  );
  const bruta = lista.dados ? lerPaginaDeVendedores(lista.dados) : null;
  const dados = bruta && praca ? separarPorPraca(bruta, praca, pagina) : bruta;
  const filtrando = buscaAplicada !== '' || praca !== '' || situacao !== 'TODOS';

  if (modo.tela === 'ficha') {
    return (
      <FichaDoVendedor
        key={modo.id ?? 'novo'}
        ponte={ponte}
        vendedorId={modo.id}
        aoVoltar={(mudou) => {
          setModo({ tela: 'lista' });
          if (mudou) lista.recarregar();
        }}
      />
    );
  }

  const linhas = (dados?.itens ?? []).map((v) => ({
    codigo: String(v.codigo),
    nome: v.nome,
    telefone: telefoneDaLista(v),
    praca: nomeDaPraca(v.pracaId),
    situacao: v.ativo ? T.situacao.ativo : T.situacao.desativado,
    acoes: <Botao onClick={() => setModo({ tela: 'ficha', id: v.id })}>{T.abrir}</Botao>,
  }));

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao variante="primario" onClick={() => setModo({ tela: 'ficha', id: null })}>
          {T.novo}
        </Botao>
      </div>

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
          <label htmlFor="filtro-praca">{T.filtroPraca}</label>
          <select
            id="filtro-praca"
            value={praca}
            onChange={(e) => {
              setPraca(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{T.todas}</option>
            {listaDePracas.map((p) => (
              <option key={p.id} value={p.id}>
                {texto(p, 'nome')}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-situacao-vendedor">{T.filtroSituacao}</label>
          <select
            id="filtro-situacao-vendedor"
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
              setPraca('');
              setSituacao('TODOS');
              setPagina(0);
            }}
          >
            {TEXTOS.lista.limparBusca}
          </Botao>
        ) : null}
      </form>
      {pracas.erro ? <Aviso tipo="atencao">{pracas.erro.mensagem}</Aviso> : null}

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
            { chave: 'codigo', titulo: T.colunas.codigo },
            { chave: 'nome', titulo: T.colunas.nome },
            { chave: 'telefone', titulo: T.colunas.telefone },
            { chave: 'praca', titulo: T.colunas.praca },
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
    </div>
  );
}

/** Filtro de praça feito na tela: separa os vendedores da praça e pagina de 20 em 20. */
export function separarPorPraca(
  todos: ReturnType<typeof lerPaginaDeVendedores>,
  praca: string,
  pagina: number,
): ReturnType<typeof lerPaginaDeVendedores> {
  const daPraca = todos.itens.filter((v) => v.pracaId === praca);
  return {
    itens: daPraca.slice(pagina * TAMANHO, (pagina + 1) * TAMANHO),
    pagina,
    tamanho: TAMANHO,
    total: daPraca.length,
  };
}
