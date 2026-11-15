import {
  Aviso,
  Botao,
  Campo,
  EstadoDaLista,
  JanelaDeConfirmacao,
  Paginacao,
  Tabela,
} from '@corona/ui';
import { useEffect, useState } from 'react';
import { TEM_ATIVO, type NomeDoCadastro } from '../../../shared/cadastros';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { FecharSemana } from '../financeiro/FecharSemana';
import { DEFINICOES, lerPagina, type Registro } from './definicoes';
import { FormularioDeCadastro } from './FormularioDeCadastro';

const TAMANHO = 20;
const TF = TEXTOS_DE_FINANCEIRO.semana;

const numeroDaSemana = (r: Registro): string =>
  typeof r.numero === 'number' ? String(r.numero) : '';

type SituacaoDaLista = 'ATIVOS' | 'INATIVOS' | 'TODOS';

type Modo = { tela: 'lista' } | { tela: 'formulario'; registro: Registro | null };

export interface TelaDeCadastroProps {
  ponte: PonteDoCorona;
  cadastro: NomeDoCadastro;
}

/**
 * Lista, cadastro, edição e exclusão de um cadastro básico (fornecedores, categorias, praças,
 * semanas, restrições e clientes). Excluir é lógico: o registro some das listas, nunca é apagado.
 */
export function TelaDeCadastro({ ponte, cadastro }: TelaDeCadastroProps) {
  const definicao = DEFINICOES[cadastro];
  const T = definicao.textos;
  const [modo, setModo] = useState<Modo>({ tela: 'lista' });
  const [busca, setBusca] = useState('');
  const [buscaAplicada, setBuscaAplicada] = useState('');
  const [pagina, setPagina] = useState(0);
  const [excluindo, setExcluindo] = useState<Registro | null>(null);
  const [inativando, setInativando] = useState<Registro | null>(null);
  const [fechando, setFechando] = useState<Registro | null>(null);
  const [situacao, setSituacao] = useState<SituacaoDaLista>('ATIVOS');
  const temAtivo = TEM_ATIVO[cadastro];
  const S = TEXTOS.cadastros.situacao;
  const [executando, setExecutando] = useState(false);
  const [erroDaAcao, setErroDaAcao] = useState<ErroDaTela | null>(null);
  const [feito, setFeito] = useState<string | null>(null);

  // A busca por texto espera a pessoa parar de digitar; a página muda na hora.
  useEffect(() => {
    const relogio = setTimeout(() => {
      setBuscaAplicada(busca.trim());
      setPagina(0);
    }, 300);
    return () => clearTimeout(relogio);
  }, [busca]);

  const lista = useBusca(
    () =>
      chamar(ponte, `${cadastro}.listar`, {
        pagina,
        tamanho: TAMANHO,
        ...(buscaAplicada ? { busca: buscaAplicada } : {}),
        ...(temAtivo ? { situacao } : {}),
      }),
    `${cadastro}|${buscaAplicada}|${situacao}|${pagina}`,
  );
  const dados = lista.dados ? lerPagina(lista.dados) : null;
  const filtrando = buscaAplicada !== '' || situacao !== 'ATIVOS';

  async function excluir() {
    if (!excluindo || executando) return;
    setExecutando(true);
    setErroDaAcao(null);
    const alvo = excluindo;
    const r = await chamar(ponte, `${cadastro}.excluir`, { id: alvo.id });
    setExecutando(false);
    setExcluindo(null);
    if (!r.ok) {
      setErroDaAcao(r.erro);
      return;
    }
    setFeito(T.feito.excluido(definicao.nome(alvo)));
    // Se era a única linha da última página, volta uma página.
    if (dados && dados.itens.length === 1 && pagina > 0) setPagina(pagina - 1);
    else lista.recarregar();
  }

  /** Inativar e reativar usam a versão que a tela leu; se outra pessoa mexeu antes, o servidor avisa. */
  async function mudarSituacao(alvo: Registro, acao: 'inativar' | 'reativar') {
    if (executando) return;
    setExecutando(true);
    setErroDaAcao(null);
    const r = await chamar(ponte, `${cadastro}.${acao}`, { id: alvo.id, version: alvo.version });
    setExecutando(false);
    setInativando(null);
    if (!r.ok) {
      setErroDaAcao(r.erro);
      return;
    }
    const nome = definicao.nome(alvo);
    setFeito(acao === 'inativar' ? T.feito.inativado(nome) : T.feito.reativado(nome));
    if (dados && dados.itens.length === 1 && pagina > 0 && situacao !== 'TODOS')
      setPagina(pagina - 1);
    else lista.recarregar();
  }

  if (modo.tela === 'formulario') {
    return (
      <div className="gestao-tela">
        <FormularioDeCadastro
          // Recomeça do zero ao trocar de registro.
          key={modo.registro?.id ?? 'novo'}
          ponte={ponte}
          definicao={definicao}
          registro={modo.registro}
          aoCancelar={() => setModo({ tela: 'lista' })}
          aoAtualizar={() => {
            setModo({ tela: 'lista' });
            lista.recarregar();
          }}
          aoSalvar={(nome) => {
            setModo({ tela: 'lista' });
            setErroDaAcao(null);
            setFeito(modo.registro ? T.feito.editado(nome) : T.feito.criado(nome));
            lista.recarregar();
          }}
        />
      </div>
    );
  }

  const linhas = (dados?.itens ?? []).map((r) => ({
    ...Object.fromEntries(definicao.colunas.map((c) => [c.chave, c.valor(r)])),
    situacaoDoCadastro: r.ativo === false ? S.inativo : S.ativo,
    acoes: (
      <span className="gestao-acoes-da-linha">
        <Botao onClick={() => setModo({ tela: 'formulario', registro: r })}>{T.editar}</Botao>
        {cadastro === 'semanas' && r.situacao === 'ABERTA' ? (
          <Botao
            aria-label={TF.fecharDe(numeroDaSemana(r))}
            onClick={() => {
              setFeito(null);
              setFechando(r);
            }}
          >
            {TF.fechar}
          </Botao>
        ) : null}
        {temAtivo && r.ativo === false ? (
          <Botao
            onClick={() => {
              setFeito(null);
              void mudarSituacao(r, 'reativar');
            }}
          >
            {T.reativar}
          </Botao>
        ) : null}
        {temAtivo && r.ativo !== false ? (
          <Botao
            onClick={() => {
              setFeito(null);
              setInativando(r);
            }}
          >
            {T.inativar}
          </Botao>
        ) : null}
        <Botao
          onClick={() => {
            setFeito(null);
            setExcluindo(r);
          }}
        >
          {T.excluir}
        </Botao>
      </span>
    ),
  }));

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao
          variante="primario"
          onClick={() => {
            setFeito(null);
            setModo({ tela: 'formulario', registro: null });
          }}
        >
          {T.novo}
        </Botao>
      </div>

      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {erroDaAcao ? <AvisoDeErro erro={erroDaAcao} aoAtualizar={lista.recarregar} /> : null}

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
        {temAtivo ? (
          <div className="cs-campo">
            <label htmlFor="filtro-situacao-cadastro">{S.rotulo}</label>
            <select
              id="filtro-situacao-cadastro"
              value={situacao}
              onChange={(e) => {
                setSituacao(e.target.value as SituacaoDaLista);
                setPagina(0);
              }}
            >
              <option value="ATIVOS">{S.ativos}</option>
              <option value="INATIVOS">{S.inativos}</option>
              <option value="TODOS">{S.todos}</option>
            </select>
          </div>
        ) : null}
        {filtrando ? (
          <Botao
            onClick={() => {
              setBusca('');
              setBuscaAplicada('');
              setSituacao('ATIVOS');
              setPagina(0);
            }}
          >
            {TEXTOS.lista.limparBusca}
          </Botao>
        ) : null}
      </form>

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
            ...definicao.colunas.map((c) => ({ chave: c.chave, titulo: c.titulo })),
            ...(temAtivo ? [{ chave: 'situacaoDoCadastro', titulo: S.coluna }] : []),
            { chave: 'acoes', titulo: T.colunaAcoes },
          ]}
          linhas={linhas}
          textoVazio={
            buscaAplicada === '' && situacao === 'INATIVOS'
              ? T.semInativos
              : filtrando
                ? T.semResultado
                : T.vazio
          }
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

      {fechando ? (
        <FecharSemana
          key={fechando.id}
          ponte={ponte}
          semanaId={fechando.id}
          numero={numeroDaSemana(fechando)}
          aoCancelar={() => setFechando(null)}
          aoFeito={(jaEstavaFechada) => {
            setFechando(null);
            setFeito(jaEstavaFechada ? TF.dialogo.jaFechada : TF.dialogo.feito);
            lista.recarregar();
          }}
        />
      ) : null}

      {inativando ? (
        <JanelaDeConfirmacao
          titulo={T.confirmar.tituloInativar(definicao.nome(inativando))}
          rotuloConfirmar={T.inativar}
          rotuloCancelar={T.confirmar.voltar}
          ocupado={executando}
          aoCancelar={() => setInativando(null)}
          aoConfirmar={() => void mudarSituacao(inativando, 'inativar')}
        >
          {T.confirmar.textoInativar(definicao.nome(inativando))}
        </JanelaDeConfirmacao>
      ) : null}

      {excluindo ? (
        <JanelaDeConfirmacao
          titulo={T.confirmar.titulo(definicao.nome(excluindo))}
          rotuloConfirmar={T.excluir}
          rotuloCancelar={T.confirmar.voltar}
          perigosa
          ocupado={executando}
          aoCancelar={() => setExcluindo(null)}
          aoConfirmar={() => void excluir()}
        >
          {T.confirmar.texto(definicao.nome(excluindo))}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
