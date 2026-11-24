import { Botao, Campo, EstadoDaLista, Paginacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import { ESCOPOS_DA_CONTAGEM, type EscopoDaContagem } from '../../../shared/estoque';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { lerOpcoes } from '../produtos/dados';
import { ContagemAberta } from './ContagemAberta';
import { dataEHoraParaTela, lerContagem, lerLinhaDeContagem, lerPagina } from './dados';

const T = TEXTOS_DE_ESTOQUE.contagem;
const TAMANHO = 20;

export interface TelaContagemProps {
  ponte: PonteDoCorona;
}

/** Contagem do estoque: começar uma contagem, continuar a que está em andamento e ver as anteriores. */
export function TelaContagem({ ponte }: TelaContagemProps) {
  const [aberta, setAberta] = useState<string | null>(null);
  const [pagina, setPagina] = useState(0);
  const [recarga, setRecarga] = useState(0);
  const lista = useBusca(
    () => chamar(ponte, 'contagens.listar', { pagina, tamanho: TAMANHO }),
    `contagens|${pagina}|${recarga}`,
  );
  const dados = lista.dados ? lerPagina(lista.dados, lerLinhaDeContagem) : null;

  if (aberta) {
    return (
      <ContagemAberta
        key={aberta}
        ponte={ponte}
        contagemId={aberta}
        aoVoltar={() => {
          setAberta(null);
          setRecarga((r) => r + 1);
        }}
      />
    );
  }

  const linhas = (dados?.itens ?? []).map((c) => ({
    inicio: dataEHoraParaTela(c.iniciadoEm),
    fim: dataEHoraParaTela(c.fechadoEm),
    o_que: T.escopoDaLinha(c.escopo, c.escopoNome),
    lidas: T.lidasDe(c.lidos, c.totalDePecas),
    situacao: T.situacoes[c.situacao] ?? c.situacao,
    acoes: (
      <Botao onClick={() => setAberta(c.id)}>
        {c.situacao === 'EM_ANDAMENTO' ? T.continuar : T.ver}
      </Botao>
    ),
  }));

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <ComecarContagem ponte={ponte} aoComecar={setAberta} />
      <h2 className="gestao-subtitulo">{T.anteriores}</h2>
      <EstadoDaLista
        carregando={lista.carregando && dados === null}
        erro={lista.erro && dados === null ? lista.erro.mensagem : null}
        textoCarregando={TEXTOS_DE_ESTOQUE.comuns.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        <Tabela
          legenda={T.legenda}
          textoVazio={T.vazio}
          colunas={[
            { chave: 'inicio', titulo: T.colunas.inicio },
            { chave: 'fim', titulo: T.colunas.fim },
            { chave: 'o_que', titulo: T.colunas.o_que },
            { chave: 'lidas', titulo: T.colunas.lidas, numerica: true },
            { chave: 'situacao', titulo: T.colunas.situacao },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
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
    </div>
  );
}

interface ComecarContagemProps {
  ponte: PonteDoCorona;
  aoComecar: (id: string) => void;
}

/** Escolhe o que contar e começa. O identificador nasce com o formulário: reenviar não começa duas. */
function ComecarContagem({ ponte, aoComecar }: ComecarContagemProps) {
  const [escopo, setEscopo] = useState<EscopoDaContagem>('TODOS');
  const [escopoId, setEscopoId] = useState('');
  const [observacoes, setObservacoes] = useState('');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const idDoPedido = useRef(crypto.randomUUID());
  const chaveDoPedido = useRef('');
  const trava = useRef(false);

  const categorias = useBusca(
    () => chamarTodasAsPaginas(ponte, 'categorias.listar', { situacao: 'TODOS' }),
    'categorias-da-contagem',
  );
  const fornecedores = useBusca(
    () => chamarTodasAsPaginas(ponte, 'fornecedores.listar', { situacao: 'TODOS' }),
    'fornecedores-da-contagem',
  );
  const opcoes =
    escopo === 'CATEGORIA'
      ? categorias.dados
        ? lerOpcoes(categorias.dados)
        : []
      : escopo === 'FORNECEDOR'
        ? fornecedores.dados
          ? lerOpcoes(fornecedores.dados)
          : []
        : [];
  const faltaEscolher = escopo !== 'TODOS' && escopoId === '';

  async function comecar() {
    if (trava.current) return;
    if (faltaEscolher) {
      setTentou(true);
      return;
    }
    const chave = JSON.stringify([escopo, escopoId, observacoes.trim()]);
    if (chaveDoPedido.current !== '' && chaveDoPedido.current !== chave) {
      idDoPedido.current = crypto.randomUUID();
    }
    chaveDoPedido.current = chave;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'contagens.comecar', {
      id: idDoPedido.current,
      escopo,
      ...(escopo !== 'TODOS' ? { escopoId } : {}),
      ...(observacoes.trim() ? { observacoes: observacoes.trim() } : {}),
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const contagem = lerContagem(r.dados);
    if (contagem) aoComecar(contagem.id);
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-comecar-contagem">
      <h2 id="titulo-comecar-contagem" className="gestao-subtitulo">
        {T.comecarTitulo}
      </h2>
      <p className="gestao-texto">{T.explicacao}</p>
      <div className="gestao-filtros">
        <div className="cs-campo">
          <label htmlFor="escopo-da-contagem">{T.escopo}</label>
          <select
            id="escopo-da-contagem"
            value={escopo}
            onChange={(e) => {
              setEscopo(e.target.value as EscopoDaContagem);
              setEscopoId('');
              setTentou(false);
            }}
          >
            {ESCOPOS_DA_CONTAGEM.map((e) => (
              <option key={e} value={e}>
                {T.escopos[e]}
              </option>
            ))}
          </select>
        </div>
        {escopo !== 'TODOS' ? (
          <div className="cs-campo">
            <label htmlFor="escolha-da-contagem">
              {escopo === 'CATEGORIA' ? T.escolhaCategoria : T.escolhaFornecedor}
            </label>
            <select
              id="escolha-da-contagem"
              value={escopoId}
              onChange={(e) => setEscopoId(e.target.value)}
            >
              <option value="">
                {escopo === 'CATEGORIA' ? T.escolhaCategoria : T.escolhaFornecedor}
              </option>
              {opcoes.map((o) => (
                <option key={o.id} value={o.id}>
                  {o.nome}
                </option>
              ))}
            </select>
            {tentou && faltaEscolher ? <p role="alert">{T.escolhaAviso}</p> : null}
          </div>
        ) : null}
        <Campo
          rotulo={T.observacoes}
          value={observacoes}
          maxLength={1000}
          autoComplete="off"
          onChange={(e) => setObservacoes(e.target.value)}
        />
        <Botao variante="primario" disabled={ocupado} onClick={() => void comecar()}>
          {ocupado ? T.comecando : T.comecar}
        </Botao>
      </div>
      {erro ? <AvisoDeErro erro={erro} /> : null}
    </section>
  );
}
