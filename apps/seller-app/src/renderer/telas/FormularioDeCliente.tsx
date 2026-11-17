import { Aviso, Botao, RodapeDeAcoes } from '@corona/ui';
import { Campo } from '../componentes-do-vendedor';
import { useRef, useState, type FormEvent } from 'react';
import type { Cliente } from '../../shared/dados';
import {
  CAMPOS_DO_CLIENTE_EDITAVEIS,
  type CampoDoClienteEditavel,
  type ClienteParaCadastrar,
} from '../../shared/operacoes';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, novoId } from '../chamadas';
import { TEXTOS } from '../textos';

export interface FormularioDeClienteProps {
  ponte: PonteDoCorona;
  aoSalvar: (cliente: Cliente) => void;
  aoCancelar: () => void;
  /** Quando vier, o formulário muda o cadastro deste cliente (só os que o vendedor atende) em vez de cadastrar. */
  editar?: Cliente;
}

/** Cadastro de cliente do vendedor. O cliente novo já nasce com ele como responsável (o servidor sabe pelo login). */
export function FormularioDeCliente({
  ponte,
  aoSalvar,
  aoCancelar,
  editar,
}: FormularioDeClienteProps) {
  const T = TEXTOS.cliente;
  const [nome, setNome] = useState(editar?.nome ?? '');
  const [telefone, setTelefone] = useState(editar?.telefone ?? '');
  const [whatsapp, setWhatsapp] = useState(editar?.whatsapp ?? '');
  const [cpf, setCpf] = useState(editar?.cpf ?? '');
  const [nascimento, setNascimento] = useState(editar?.dataNascimento ?? '');
  const [observacoes, setObservacoes] = useState(editar?.observacoes ?? '');
  const [erroDoNome, setErroDoNome] = useState<string | undefined>();
  const [erro, setErro] = useState<string | null>(null);
  const [existente, setExistente] = useState<{ id: string; nome: string } | null>(null);
  const [salvando, setSalvando] = useState(false);
  // Salvar de novo depois de uma falha de rede reaproveita o mesmo id: o cliente não duplica.
  const id = useRef(novoId());

  async function salvar(evento: FormEvent) {
    evento.preventDefault();
    if (salvando) return;
    if (!nome.trim()) {
      setErroDoNome(T.faltaNome);
      return;
    }
    setErroDoNome(undefined);
    setErro(null);
    setExistente(null);
    setSalvando(true);
    if (editar) {
      await salvarEdicao(editar);
      return;
    }
    const cliente: ClienteParaCadastrar = { id: id.current, nome: nome.trim() };
    if (telefone.trim()) cliente.telefone = telefone.trim();
    if (whatsapp.trim()) cliente.whatsapp = whatsapp.trim();
    if (cpf.trim()) cliente.cpf = cpf.trim();
    if (nascimento) cliente.dataNascimento = nascimento;
    const r = await chamar<Cliente>(ponte, { operacao: 'clientes.cadastrar', cliente });
    setSalvando(false);
    if (r.ok) aoSalvar(r.dados);
    else if (r.clienteExistente) {
      // CPF repetido: em vez de cadastrar de novo, a pessoa escolhe o cliente que já existe.
      setExistente(r.clienteExistente);
      setErro(T.jaCadastrado(r.clienteExistente.nome));
    } else setErro(r.mensagem);
  }

  /** Manda só o que mudou; o cadastro novo vale na hora e o envio acontece sozinho. */
  async function salvarEdicao(atual: Cliente) {
    const antes: Record<CampoDoClienteEditavel, string> = {
      nome: atual.nome,
      cpf: atual.cpf ?? '',
      telefone: atual.telefone ?? '',
      whatsapp: atual.whatsapp ?? '',
      observacoes: atual.observacoes ?? '',
      dataNascimento: atual.dataNascimento ?? '',
    };
    const depois: Record<CampoDoClienteEditavel, string> = {
      nome: nome.trim(),
      cpf: cpf.trim(),
      telefone: telefone.trim(),
      whatsapp: whatsapp.trim(),
      observacoes: observacoes.trim(),
      dataNascimento: nascimento,
    };
    const campos: Partial<Record<CampoDoClienteEditavel, string>> = {};
    for (const campo of CAMPOS_DO_CLIENTE_EDITAVEIS) {
      if (antes[campo] !== depois[campo]) campos[campo] = depois[campo];
    }
    if (Object.keys(campos).length === 0) {
      setSalvando(false);
      setErro(T.semMudanca);
      return;
    }
    const r = await chamar<Cliente>(ponte, { operacao: 'clientes.alterar', id: atual.id, campos });
    setSalvando(false);
    if (r.ok) aoSalvar(r.dados);
    else if (r.clienteExistente) setErro(T.jaCadastrado(r.clienteExistente.nome));
    else setErro(r.mensagem);
  }

  async function usarExistente() {
    if (!existente || salvando) return;
    setSalvando(true);
    const r = await chamar<Cliente>(ponte, { operacao: 'clientes.ver', id: existente.id });
    setSalvando(false);
    if (r.ok) aoSalvar(r.dados);
    else setErro(r.mensagem);
  }

  return (
    <form
      className="vendas-cartao vendas-cartao--larga"
      onSubmit={(e) => void salvar(e)}
      noValidate
    >
      <h2 className="vendas-subtitulo">{editar ? T.tituloDaEdicao : T.titulo}</h2>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      {existente ? (
        <Botao variante="primario" disabled={salvando} onClick={() => void usarExistente()}>
          {T.usarExistente}
        </Botao>
      ) : null}
      <Campo
        rotulo={T.nome}
        value={nome}
        onChange={(e) => setNome(e.target.value)}
        {...(erroDoNome ? { erro: erroDoNome } : {})}
        autoComplete="off"
        autoFocus
      />
      <Campo
        rotulo={T.telefone}
        value={telefone}
        onChange={(e) => setTelefone(e.target.value)}
        inputMode="tel"
        autoComplete="off"
      />
      <Campo
        rotulo={T.whatsapp}
        value={whatsapp}
        onChange={(e) => setWhatsapp(e.target.value)}
        inputMode="tel"
        autoComplete="off"
      />
      <Campo
        rotulo={T.cpf}
        value={cpf}
        onChange={(e) => setCpf(e.target.value)}
        autoComplete="off"
      />
      <Campo
        rotulo={T.nascimento}
        type="date"
        value={nascimento}
        onChange={(e) => setNascimento(e.target.value)}
      />
      {editar ? (
        <Campo
          rotulo={T.observacoes}
          value={observacoes}
          onChange={(e) => setObservacoes(e.target.value)}
          autoComplete="off"
        />
      ) : null}
      <RodapeDeAcoes
        cancelar={<Botao onClick={aoCancelar}>{TEXTOS.botoes.cancelar}</Botao>}
        principal={
          <Botao type="submit" variante="primario" disabled={salvando}>
            {editar ? T.salvarEdicao : T.salvar}
          </Botao>
        }
      />
    </form>
  );
}
