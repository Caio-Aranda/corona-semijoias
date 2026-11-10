import { Botao, Campo, EstadoDaLista, Paginacao, Tabela } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { estaAcabando } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { codigoPrincipal, lerOpcoes, lerPaginaDeProdutos } from '../produtos/dados';
import { FichaDaPeca } from './FichaDaPeca';
import { lerAcabando, lerPagina, type Pagina } from './dados';

const TAMANHO = 20;

interface Linha {
  id: string;
  nome: string;
  codigo: string;
  fotoHash: string | null;
  saldo: number;
  minimo: number;
  falta: number;
}

export interface ListaDoEstoqueProps {
  ponte: PonteDoCorona;
  /** "posicao" mostra todas as peças; "acabando" só as que chegaram ao estoque mínimo. */
  modo: 'posicao' | 'acabando';
}

/** Estoque: peças com foto, quantidade, mínimo e situação; filtros, páginas e a ficha da peça. */
export function ListaDoEstoque({ ponte, modo }: ListaDoEstoqueProps) {
  const [busca, setBusca] = useState('');
  const [termo, setTermo] = useState('');
  const [categoria, setCategoria] = useState('');
  const [fornecedor, setFornecedor] = useState('');
  const [soAcabando, setSoAcabando] = useState(false);
  const [pagina, setPagina] = useState(0);
  const [aberta, setAberta] = useState<string | null>(null);

  const categorias = useBusca(
    () => chamar(ponte, 'categorias.listar', { pagina: 0, tamanho: 100, situacao: 'TODOS' }),
    'categorias-do-estoque',
  );
  const fornecedores = useBusca(
    () => chamar(ponte, 'fornecedores.listar', { pagina: 0, tamanho: 100, situacao: 'TODOS' }),
    'fornecedores-do-estoque',
  );
  const apenasAcabando = modo === 'acabando' || soAcabando;

  const lista = useBusca<Pagina<Linha>>(async () => {
    const filtros = {
      pagina,
      tamanho: TAMANHO,
      ...(termo ? { busca: termo } : {}),
      ...(categoria ? { categoriaId: categoria } : {}),
      ...(fornecedor ? { fornecedorId: fornecedor } : {}),
    };
    if (apenasAcabando) {
      const r = await chamar(ponte, 'estoque.acabando', filtros);
      if (!r.ok) return r;
      const p = lerPagina(r.dados, lerAcabando);
      return {
        ok: true,
        dados: {
          ...p,
          itens: p.itens.map((a) => ({
            id: a.peca.id,
            nome: a.peca.nome,
            codigo: a.peca.codigo,
            fotoHash: a.peca.fotoHash,
            saldo: a.saldo,
            minimo: a.minimo,
            falta: a.falta,
          })),
        },
      };
    }
    const r = await chamar(ponte, 'produtos.listar', { ...filtros, ativo: true });
    if (!r.ok) return r;
    const p = lerPaginaDeProdutos(r.dados);
    return {
      ok: true,
      dados: {
        ...p,
        itens: p.itens.map((x) => ({
          id: x.id,
          nome: x.nome,
          codigo: codigoPrincipal(x),
          fotoHash: x.fotoPrincipal?.hash ?? null,
          saldo: x.estoqueAtual,
          minimo: x.estoqueMinimo,
          falta: Math.max(0, x.estoqueMinimo - x.estoqueAtual),
        })),
      },
    };
  }, `${modo}|${apenasAcabando}|${termo}|${categoria}|${fornecedor}|${pagina}`);

  const dados = lista.dados;
  const filtrando = termo !== '' || categoria !== '' || fornecedor !== '' || soAcabando;
  const P = TEXTOS_DE_ESTOQUE.posicao;
  const A = TEXTOS_DE_ESTOQUE.acabando;

  if (aberta) {
    return (
      <FichaDaPeca
        key={aberta}
        ponte={ponte}
        pecaId={aberta}
        aoVoltar={() => {
          setAberta(null);
          lista.recarregar();
        }}
      />
    );
  }

  const colunas = [
    { chave: 'foto', titulo: TEXTOS_DE_ESTOQUE.comuns.foto },
    { chave: 'nome', titulo: TEXTOS_DE_ESTOQUE.comuns.peca },
    { chave: 'codigo', titulo: TEXTOS_DE_ESTOQUE.comuns.codigo },
    { chave: 'saldo', titulo: P.colunas.noEstoque, numerica: true },
    { chave: 'minimo', titulo: P.colunas.minimo, numerica: true },
    ...(modo === 'acabando'
      ? [{ chave: 'falta', titulo: A.colunas.faltam, numerica: true }]
      : [{ chave: 'situacao', titulo: P.colunas.situacao }]),
    { chave: 'acoes', titulo: P.colunas.acoes },
  ];
  const linhas = (dados?.itens ?? []).map((l) => {
    const acabando = estaAcabando(l.saldo, l.minimo);
    return {
      foto: <ImagemDoProduto ponte={ponte} hash={l.fotoHash} tamanho="miniatura" nome={l.nome} />,
      nome: l.nome,
      codigo: l.codigo,
      saldo: l.saldo,
      minimo: l.minimo,
      falta: l.falta,
      situacao: acabando ? (
        <strong className="gestao-acabando">{P.situacao.acabando}</strong>
      ) : (
        P.situacao.emDia
      ),
      acoes: (
        <Botao aria-label={P.abrirDe(l.nome)} onClick={() => setAberta(l.id)}>
          {TEXTOS_DE_ESTOQUE.comuns.abrir}
        </Botao>
      ),
    };
  });

  function procurar(e: FormEvent) {
    e.preventDefault();
    setTermo(busca.trim());
    setPagina(0);
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{modo === 'acabando' ? A.titulo : P.titulo}</h1>
      </div>
      {modo === 'acabando' ? <p className="gestao-texto">{A.ajuda}</p> : null}
      <form className="gestao-filtros" onSubmit={procurar}>
        <Campo
          rotulo={P.filtros.busca}
          value={busca}
          autoComplete="off"
          onChange={(e) => setBusca(e.target.value)}
        />
        <div className="cs-campo">
          <label htmlFor="filtro-categoria-estoque">{P.filtros.categoria}</label>
          <select
            id="filtro-categoria-estoque"
            value={categoria}
            onChange={(e) => {
              setCategoria(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{TEXTOS_DE_ESTOQUE.comuns.todas}</option>
            {(categorias.dados ? lerOpcoes(categorias.dados) : []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.nome}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-fornecedor-estoque">{P.filtros.fornecedor}</label>
          <select
            id="filtro-fornecedor-estoque"
            value={fornecedor}
            onChange={(e) => {
              setFornecedor(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{TEXTOS_DE_ESTOQUE.comuns.todos}</option>
            {(fornecedores.dados ? lerOpcoes(fornecedores.dados) : []).map((f) => (
              <option key={f.id} value={f.id}>
                {f.nome}
              </option>
            ))}
          </select>
        </div>
        {modo === 'posicao' ? (
          <label className="gestao-opcao">
            <input
              type="checkbox"
              checked={soAcabando}
              onChange={(e) => {
                setSoAcabando(e.target.checked);
                setPagina(0);
              }}
            />{' '}
            {P.filtros.soAcabando}
          </label>
        ) : null}
        <Botao type="submit">{TEXTOS_DE_ESTOQUE.comuns.buscar}</Botao>
        {filtrando ? (
          <Botao
            onClick={() => {
              setBusca('');
              setTermo('');
              setCategoria('');
              setFornecedor('');
              setSoAcabando(false);
              setPagina(0);
            }}
          >
            {TEXTOS_DE_ESTOQUE.comuns.limpar}
          </Botao>
        ) : null}
      </form>
      <EstadoDaLista
        carregando={lista.carregando && dados === null}
        erro={lista.erro && dados === null ? lista.erro.mensagem : null}
        textoCarregando={TEXTOS_DE_ESTOQUE.comuns.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        {lista.erro && dados !== null ? (
          <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} />
        ) : null}
        <Tabela
          legenda={modo === 'acabando' ? A.legenda : P.legenda}
          textoVazio={filtrando ? P.semResultado : modo === 'acabando' ? A.vazio : P.vazio}
          colunas={colunas}
          linhas={linhas}
        />
        {dados ? (
          <Paginacao
            pagina={dados.pagina}
            tamanho={dados.tamanho}
            total={dados.total}
            nomeDoItem={P.itens}
            aoMudar={setPagina}
          />
        ) : null}
      </EstadoDaLista>
    </div>
  );
}
