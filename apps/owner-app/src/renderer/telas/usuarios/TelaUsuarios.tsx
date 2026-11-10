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
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { SenhaTemporaria } from '../../componentes/SenhaTemporaria';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { lerPaginaDeUsuarios, lerSenhaCriada, type SenhaCriada, type Usuario } from './dados';
import { FormularioDeUsuario } from './FormularioDeUsuario';

const TAMANHO = 20;
const T = TEXTOS.usuarios;

type Acao = { tipo: 'desativar' | 'reativar' | 'senha'; usuario: Usuario };
type Modo = { tela: 'lista' } | { tela: 'formulario'; usuario: Usuario | null };

export interface TelaUsuariosProps {
  ponte: PonteDoCorona;
}

/** Pessoas → Usuários: quem entra nos aplicativos. A senha temporária só existe nesta tela e some ao sair. */
export function TelaUsuarios({ ponte }: TelaUsuariosProps) {
  const [modo, setModo] = useState<Modo>({ tela: 'lista' });
  const [busca, setBusca] = useState('');
  const [buscaAplicada, setBuscaAplicada] = useState('');
  const [acesso, setAcesso] = useState('');
  const [situacao, setSituacao] = useState('');
  const [pagina, setPagina] = useState(0);
  const [acao, setAcao] = useState<Acao | null>(null);
  const [executando, setExecutando] = useState(false);
  const [erroDaAcao, setErroDaAcao] = useState<ErroDaTela | null>(null);
  const [feito, setFeito] = useState<string | null>(null);
  const [senha, setSenha] = useState<SenhaCriada | null>(null);

  // A busca por texto espera a pessoa parar de digitar; filtros e páginas valem na hora.
  useEffect(() => {
    const relogio = setTimeout(() => {
      setBuscaAplicada(busca.trim());
      setPagina(0);
    }, 300);
    return () => clearTimeout(relogio);
  }, [busca]);

  const chave = `${buscaAplicada}|${acesso}|${situacao}|${pagina}`;
  const lista = useBusca(
    () =>
      chamar(ponte, 'usuarios.listar', {
        pagina,
        tamanho: TAMANHO,
        ...(buscaAplicada ? { busca: buscaAplicada } : {}),
        ...(acesso ? { perfil: acesso } : {}),
        ...(situacao ? { ativo: situacao === 'ativos' } : {}),
      }),
    chave,
  );
  const dados = lista.dados ? lerPaginaDeUsuarios(lista.dados) : null;
  const filtrando = buscaAplicada !== '' || acesso !== '' || situacao !== '';

  async function executar() {
    if (!acao || executando) return;
    setExecutando(true);
    setErroDaAcao(null);
    const { tipo, usuario } = acao;
    const operacao =
      tipo === 'desativar'
        ? 'usuarios.desativar'
        : tipo === 'reativar'
          ? 'usuarios.reativar'
          : 'usuarios.redefinirSenha';
    const r = await chamar(ponte, operacao, { id: usuario.id });
    setExecutando(false);
    setAcao(null);
    if (!r.ok) {
      setErroDaAcao(r.erro);
      return;
    }
    if (tipo === 'senha') {
      const nova = lerSenhaCriada(r.dados, usuario.nome);
      if (nova) setSenha(nova);
    } else {
      setFeito(
        tipo === 'desativar' ? T.feito.desativado(usuario.nome) : T.feito.reativado(usuario.nome),
      );
    }
    lista.recarregar();
  }

  if (modo.tela === 'formulario') {
    return (
      <div className="gestao-tela">
        <FormularioDeUsuario
          ponte={ponte}
          usuario={modo.usuario}
          aoCancelar={() => setModo({ tela: 'lista' })}
          aoAtualizar={() => {
            setModo({ tela: 'lista' });
            lista.recarregar();
          }}
          aoSalvar={(nome, nova) => {
            setModo({ tela: 'lista' });
            setErroDaAcao(null);
            setFeito(modo.usuario ? T.feito.editado(nome) : T.feito.criado(nome));
            setSenha(nova);
            lista.recarregar();
          }}
        />
      </div>
    );
  }

  const linhas = (dados?.itens ?? []).map((u) => ({
    nome: u.nome,
    usuario: u.login,
    acesso: T.acesso[u.perfil] ?? u.perfil,
    situacao: u.ativo ? T.situacao.ativo : T.situacao.desativado,
    acoes: (
      <span className="gestao-acoes-da-linha">
        <Botao onClick={() => setModo({ tela: 'formulario', usuario: u })}>{T.editar}</Botao>
        {u.ativo ? (
          <Botao onClick={() => setAcao({ tipo: 'desativar', usuario: u })}>{T.desativar}</Botao>
        ) : (
          <Botao onClick={() => setAcao({ tipo: 'reativar', usuario: u })}>{T.reativar}</Botao>
        )}
        <Botao onClick={() => setAcao({ tipo: 'senha', usuario: u })}>{T.novaSenha}</Botao>
      </span>
    ),
  }));

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao variante="primario" onClick={() => setModo({ tela: 'formulario', usuario: null })}>
          {T.novo}
        </Botao>
      </div>

      {senha ? (
        <SenhaTemporaria
          nome={senha.nome}
          senha={senha.senha}
          {...(senha.validaAte ? { validaAte: senha.validaAte } : {})}
          aoFechar={() => setSenha(null)}
        />
      ) : null}
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
        <div className="cs-campo">
          <label htmlFor="filtro-acesso">{T.filtroAcesso}</label>
          <select
            id="filtro-acesso"
            value={acesso}
            onChange={(e) => {
              setAcesso(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{T.todos}</option>
            <option value="ADMIN">{T.acesso.ADMIN}</option>
            <option value="VENDEDOR">{T.acesso.VENDEDOR}</option>
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-situacao">{T.filtroSituacao}</label>
          <select
            id="filtro-situacao"
            value={situacao}
            onChange={(e) => {
              setSituacao(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{T.todos}</option>
            <option value="ativos">{T.ativos}</option>
            <option value="desativados">{T.desativados}</option>
          </select>
        </div>
        {filtrando ? (
          <Botao
            onClick={() => {
              setBusca('');
              setBuscaAplicada('');
              setAcesso('');
              setSituacao('');
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
            { chave: 'nome', titulo: T.colunas.nome },
            { chave: 'usuario', titulo: T.colunas.usuario },
            { chave: 'acesso', titulo: T.colunas.acesso },
            { chave: 'situacao', titulo: T.colunas.situacao },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={linhas}
          textoVazio={filtrando ? TEXTOS.lista.semResultado(T.item) : TEXTOS.lista.vazio(T.item)}
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

      {acao ? (
        <JanelaDeConfirmacao
          titulo={
            acao.tipo === 'desativar'
              ? T.confirmar.desativarTitulo(acao.usuario.nome)
              : acao.tipo === 'reativar'
                ? T.confirmar.reativarTitulo(acao.usuario.nome)
                : T.confirmar.novaSenhaTitulo(acao.usuario.nome)
          }
          rotuloConfirmar={
            acao.tipo === 'desativar'
              ? T.desativar
              : acao.tipo === 'reativar'
                ? T.reativar
                : T.criarSenha
          }
          rotuloCancelar={T.confirmar.voltar}
          perigosa={acao.tipo !== 'reativar'}
          ocupado={executando}
          aoCancelar={() => setAcao(null)}
          aoConfirmar={() => void executar()}
        >
          {acao.tipo === 'desativar'
            ? T.confirmar.desativarTexto(acao.usuario.nome)
            : acao.tipo === 'reativar'
              ? T.confirmar.reativarTexto(acao.usuario.nome)
              : T.confirmar.novaSenhaTexto(acao.usuario.nome)}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
