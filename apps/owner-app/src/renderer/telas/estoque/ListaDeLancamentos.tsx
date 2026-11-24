import { Botao, Campo, EstadoDaLista, Paginacao } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import { TIPOS_DO_FILTRO } from '../../../shared/estoque';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca } from '../../nucleo/chamadas';
import { rotuloDoTipo } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { lerProduto } from '../produtos/dados';
import { lerPaginaDeUsuarios } from '../usuarios/dados';
import { DesfazerLancamento } from './DesfazerLancamento';
import { TabelaDeLancamentos } from './TabelaDeLancamentos';
import { lerLancamento, lerPagina, type Lancamento } from './dados';

const T = TEXTOS_DE_ESTOQUE.lancamentos;
const TAMANHO = 20;

export interface ListaDeLancamentosProps {
  ponte: PonteDoCorona;
}

/** Entradas e saídas de todas as peças: filtros por período, tipo, peça e quem lançou. */
export function ListaDeLancamentos({ ponte }: ListaDeLancamentosProps) {
  const [de, setDe] = useState('');
  const [ate, setAte] = useState('');
  const [tipo, setTipo] = useState('');
  const [usuario, setUsuario] = useState('');
  const [codigo, setCodigo] = useState('');
  const [peca, setPeca] = useState<{ id: string; nome: string } | null>(null);
  const [pecaNaoAchada, setPecaNaoAchada] = useState<string | null>(null);
  const [pagina, setPagina] = useState(0);
  const [desfazendo, setDesfazendo] = useState<Lancamento | null>(null);
  const [recarga, setRecarga] = useState(0);

  const usuarios = useBusca(
    () => chamarTodasAsPaginas(ponte, 'usuarios.listar'),
    'usuarios-do-filtro-de-estoque',
  );
  const periodoInvertido = de !== '' && ate !== '' && ate < de;

  const lista = useBusca(
    () =>
      periodoInvertido
        ? Promise.resolve({
            ok: true as const,
            dados: { itens: [], pagina: 0, tamanho: TAMANHO, total: 0 },
          })
        : chamar(ponte, 'estoque.lancamentos', {
            pagina,
            tamanho: TAMANHO,
            ...(de ? { de } : {}),
            ...(ate ? { ate } : {}),
            ...(tipo ? { tipo } : {}),
            ...(usuario ? { usuarioId: usuario } : {}),
            ...(peca ? { produtoId: peca.id } : {}),
          }),
    `${de}|${ate}|${tipo}|${usuario}|${peca?.id ?? ''}|${pagina}|${periodoInvertido}|${recarga}`,
  );
  const dados = lista.dados ? lerPagina(lista.dados, lerLancamento) : null;
  const filtrando = de !== '' || ate !== '' || tipo !== '' || usuario !== '' || peca !== null;
  const listaDeUsuarios = usuarios.dados ? lerPaginaDeUsuarios(usuarios.dados).itens : [];

  async function procurarPeca(e: FormEvent) {
    e.preventDefault();
    const lido = codigo.trim();
    if (lido === '') return;
    const r = await chamar(ponte, 'produtos.porCodigo', { codigo: lido });
    const p = r.ok ? lerProduto(r.dados) : null;
    if (p) {
      setPeca({ id: p.id, nome: p.nome });
      setPecaNaoAchada(null);
      setCodigo('');
      setPagina(0);
    } else {
      setPecaNaoAchada(lido);
    }
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <Campo
          rotulo={T.filtros.de}
          type="date"
          value={de}
          onChange={(e) => {
            setDe(e.target.value);
            setPagina(0);
          }}
        />
        <Campo
          rotulo={T.filtros.ate}
          type="date"
          value={ate}
          onChange={(e) => {
            setAte(e.target.value);
            setPagina(0);
          }}
          {...(periodoInvertido ? { erro: T.filtros.dataInvertida } : {})}
        />
        <div className="cs-campo">
          <label htmlFor="filtro-tipo-estoque">{T.filtros.tipo}</label>
          <select
            id="filtro-tipo-estoque"
            value={tipo}
            onChange={(e) => {
              setTipo(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{TEXTOS_DE_ESTOQUE.comuns.todos}</option>
            {TIPOS_DO_FILTRO.map((t) => (
              <option key={t} value={t}>
                {rotuloDoTipo(t)}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-usuario-estoque">{T.filtros.usuario}</label>
          <select
            id="filtro-usuario-estoque"
            value={usuario}
            onChange={(e) => {
              setUsuario(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{TEXTOS_DE_ESTOQUE.comuns.todos}</option>
            {listaDeUsuarios.map((u) => (
              <option key={u.id} value={u.id}>
                {u.nome}
              </option>
            ))}
          </select>
        </div>
        <form onSubmit={(e) => void procurarPeca(e)}>
          <Campo
            rotulo={T.filtros.peca}
            ajuda={T.filtros.pecaAjuda}
            value={codigo}
            autoComplete="off"
            onChange={(e) => setCodigo(e.target.value)}
            {...(pecaNaoAchada ? { erro: T.filtros.pecaNaoAchada(pecaNaoAchada) } : {})}
          />
        </form>
        {filtrando ? (
          <Botao
            onClick={() => {
              setDe('');
              setAte('');
              setTipo('');
              setUsuario('');
              setPeca(null);
              setPecaNaoAchada(null);
              setPagina(0);
            }}
          >
            {TEXTOS_DE_ESTOQUE.comuns.limpar}
          </Botao>
        ) : null}
      </div>
      {peca ? (
        <p className="gestao-texto">
          {T.filtros.filtrandoPor(peca.nome)}{' '}
          <Botao
            onClick={() => {
              setPeca(null);
              setPagina(0);
            }}
          >
            {T.filtros.tirarPeca}
          </Botao>
        </p>
      ) : null}
      <EstadoDaLista
        carregando={lista.carregando && dados === null}
        erro={lista.erro && dados === null ? lista.erro.mensagem : null}
        textoCarregando={TEXTOS_DE_ESTOQUE.comuns.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        {lista.erro && dados !== null ? (
          <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} />
        ) : null}
        <TabelaDeLancamentos
          ponte={ponte}
          lancamentos={dados?.itens ?? []}
          mostrarPeca
          textoVazio={
            filtrando ? TEXTOS_DE_ESTOQUE.historico.semResultado : TEXTOS_DE_ESTOQUE.historico.vazio
          }
          aoDesfazer={setDesfazendo}
        />
        {dados ? (
          <Paginacao
            pagina={dados.pagina}
            tamanho={dados.tamanho}
            total={dados.total}
            nomeDoItem={TEXTOS_DE_ESTOQUE.historico.itens}
            aoMudar={setPagina}
          />
        ) : null}
      </EstadoDaLista>
      {desfazendo ? (
        <DesfazerLancamento
          key={desfazendo.id}
          ponte={ponte}
          lancamentoId={desfazendo.id}
          aoFeito={() => {
            setDesfazendo(null);
            setRecarga((r) => r + 1);
          }}
          aoCancelar={() => setDesfazendo(null)}
        />
      ) : null}
    </div>
  );
}
