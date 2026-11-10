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
import type { NomeDoCadastro } from '../../../shared/cadastros';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { DEFINICOES, lerPagina, type Registro } from './definicoes';
import { FormularioDeCadastro } from './FormularioDeCadastro';

const TAMANHO = 20;

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
      }),
    `${cadastro}|${buscaAplicada}|${pagina}`,
  );
  const dados = lista.dados ? lerPagina(lista.dados) : null;
  const filtrando = buscaAplicada !== '';

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
    acoes: (
      <span className="gestao-acoes-da-linha">
        <Botao onClick={() => setModo({ tela: 'formulario', registro: r })}>{T.editar}</Botao>
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
        {filtrando ? (
          <Botao
            onClick={() => {
              setBusca('');
              setBuscaAplicada('');
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
            { chave: 'acoes', titulo: T.colunaAcoes },
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
