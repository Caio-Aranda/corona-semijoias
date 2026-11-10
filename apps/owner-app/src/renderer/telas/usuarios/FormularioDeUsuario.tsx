import { Aviso, Botao, Campo, RodapeDeAcoes } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { lerSenhaCriada, lerVendedores, type SenhaCriada, type Usuario } from './dados';

export interface FormularioDeUsuarioProps {
  ponte: PonteDoCorona;
  /** Sem usuário: cadastro novo. Com usuário: edição (o Usuário/login não muda). */
  usuario: Usuario | null;
  /** Salvou. Para cadastro novo traz a senha temporária, que só existe agora. */
  aoSalvar: (nome: string, senha: SenhaCriada | null) => void;
  aoCancelar: () => void;
  /** Outra pessoa alterou: volta para a lista já atualizada. */
  aoAtualizar: () => void;
}

const T = TEXTOS.usuarios.formulario;

/** Cadastro e edição de usuário. O primeiro campo já está com o cursor; Enter salva. */
export function FormularioDeUsuario({
  ponte,
  usuario,
  aoSalvar,
  aoCancelar,
  aoAtualizar,
}: FormularioDeUsuarioProps) {
  const novo = usuario === null;
  const [nome, setNome] = useState(usuario?.nome ?? '');
  const [login, setLogin] = useState('');
  const [perfil, setPerfil] = useState(usuario?.perfil || 'VENDEDOR');
  const [vendedorId, setVendedorId] = useState(usuario?.vendedorId ?? '');
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [locais, setLocais] = useState<Record<string, string>>({});

  const vendedores = useBusca(() => chamar(ponte, 'vendedores.escolha'), 'vendedores');
  const lista = vendedores.dados ? lerVendedores(vendedores.dados) : [];
  const atualForaDaLista =
    vendedorId !== '' && vendedores.dados !== null && !lista.some((v) => v.id === vendedorId);

  // O servidor recusa o usuário repetido ou fora do formato com um código próprio, sem campo.
  const erroDoUsuario =
    erro?.codigo === 'LOGIN_JA_USADO' || erro?.codigo === 'LOGIN_FORA_DO_FORMATO';
  const doServidor: Record<string, string> = {
    ...(erro?.campos ?? {}),
    ...(erroDoUsuario && erro ? { login: erro.mensagem } : {}),
  };
  const mensagem = (campo: string): string | undefined => locais[campo] ?? doServidor[campo];

  function limparCampo(campo: string) {
    setLocais((l) => ({ ...l, [campo]: '' }));
    setErro((e) => (e ? { ...e, campos: { ...e.campos, [campo]: '' } } : e));
  }

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    if (salvando) return;
    const faltas: Record<string, string> = {};
    if (!nome.trim()) faltas.nome = TEXTOS.formulario.obrigatorio;
    if (novo && !login.trim()) faltas.login = TEXTOS.formulario.obrigatorio;
    if (perfil === 'VENDEDOR' && !vendedorId) faltas.vendedorId = T.escolhaOVendedor;
    setLocais(faltas);
    if (Object.values(faltas).some(Boolean)) return;

    setSalvando(true);
    setErro(null);
    const ligado = perfil === 'VENDEDOR' ? { vendedorId } : {};
    const r = novo
      ? await chamar(ponte, 'usuarios.criar', {
          nome: nome.trim(),
          login: login.trim(),
          perfil,
          ...ligado,
        })
      : await chamar(ponte, 'usuarios.editar', {
          id: usuario.id,
          nome: nome.trim(),
          perfil,
          version: usuario.version,
          ...ligado,
        });
    setSalvando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoSalvar(nome.trim(), novo ? lerSenhaCriada(r.dados, nome.trim()) : null);
  }

  const semVendedor = perfil === 'VENDEDOR' && vendedores.dados !== null && lista.length === 0;
  // Mensagens de campos que esta tela não mostra (o servidor pode recusar outros): aparecem no topo.
  const camposDaTela = ['nome', 'login', 'perfil', 'vendedorId'];
  const soltas = Object.entries(doServidor).filter(([c, m]) => m && !camposDaTela.includes(c));

  return (
    <form className="gestao-formulario" onSubmit={(e) => void enviar(e)} noValidate>
      <h2 className="gestao-subtitulo">{novo ? T.tituloNovo : T.tituloEditar}</h2>
      {erro && erro.tipo !== 'campos' && !erroDoUsuario ? (
        <AvisoDeErro erro={erro} aoAtualizar={aoAtualizar} />
      ) : null}
      {erro?.tipo === 'campos' && soltas.length > 0 ? (
        <Aviso tipo="erro">{soltas.map(([, m]) => m).join(' ')}</Aviso>
      ) : null}
      <Campo
        rotulo={T.nome}
        value={nome}
        autoFocus
        autoComplete="off"
        maxLength={120}
        onChange={(e) => {
          setNome(e.target.value);
          limparCampo('nome');
        }}
        {...(mensagem('nome') ? { erro: mensagem('nome') } : {})}
      />
      <Campo
        rotulo={T.usuario}
        value={novo ? login : usuario.login}
        readOnly={!novo}
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
        onChange={(e) => {
          setLogin(e.target.value);
          limparCampo('login');
        }}
        {...(novo ? { ajuda: T.usuarioAjuda } : {})}
        {...(mensagem('login') ? { erro: mensagem('login') } : {})}
      />
      <div className={`cs-campo${mensagem('perfil') ? ' cs-campo--erro' : ''}`}>
        <label htmlFor="campo-acesso">{T.acesso}</label>
        <select
          id="campo-acesso"
          value={perfil}
          onChange={(e) => {
            setPerfil(e.target.value);
            limparCampo('perfil');
          }}
        >
          <option value="VENDEDOR">{TEXTOS.usuarios.acesso.VENDEDOR}</option>
          <option value="ADMIN">{TEXTOS.usuarios.acesso.ADMIN}</option>
        </select>
        {mensagem('perfil') ? (
          <span role="alert" className="cs-campo__erro">
            {mensagem('perfil')}
          </span>
        ) : null}
      </div>
      {perfil === 'VENDEDOR' ? (
        <div className={`cs-campo${mensagem('vendedorId') ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="campo-vendedor">{T.vendedor}</label>
          <select
            id="campo-vendedor"
            value={vendedorId}
            disabled={vendedores.dados === null}
            onChange={(e) => {
              setVendedorId(e.target.value);
              limparCampo('vendedorId');
            }}
          >
            <option value="">{T.escolherVendedor}</option>
            {atualForaDaLista ? <option value={vendedorId}>{T.vendedorAtual}</option> : null}
            {lista.map((v) => (
              <option key={v.id} value={v.id}>
                {v.nome}
              </option>
            ))}
          </select>
          {semVendedor ? <span className="cs-campo__ajuda">{T.semVendedores}</span> : null}
          {mensagem('vendedorId') ? (
            <span role="alert" className="cs-campo__erro">
              {mensagem('vendedorId')}
            </span>
          ) : null}
        </div>
      ) : null}
      {vendedores.erro ? (
        <AvisoDeErro erro={vendedores.erro} aoTentarDeNovo={vendedores.recarregar} />
      ) : null}
      <RodapeDeAcoes
        cancelar={<Botao onClick={aoCancelar}>{TEXTOS.formulario.cancelar}</Botao>}
        principal={
          <Botao type="submit" variante="primario" disabled={salvando}>
            {salvando ? TEXTOS.formulario.salvando : T.salvar}
          </Botao>
        }
      />
    </form>
  );
}
