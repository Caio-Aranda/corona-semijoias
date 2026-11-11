import { Aviso, Botao, Campo, RodapeDeAcoes } from '@corona/ui';
import { useRef, useState, type FormEvent } from 'react';
import type { Cliente } from '../../shared/dados';
import type { ClienteParaCadastrar } from '../../shared/operacoes';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, novoId } from '../chamadas';
import { TEXTOS } from '../textos';

export interface FormularioDeClienteProps {
  ponte: PonteDoCorona;
  aoSalvar: (cliente: Cliente) => void;
  aoCancelar: () => void;
}

/** Cadastro de cliente do vendedor. O cliente novo já nasce com ele como responsável (o servidor sabe pelo login). */
export function FormularioDeCliente({ ponte, aoSalvar, aoCancelar }: FormularioDeClienteProps) {
  const T = TEXTOS.cliente;
  const [nome, setNome] = useState('');
  const [telefone, setTelefone] = useState('');
  const [whatsapp, setWhatsapp] = useState('');
  const [cpf, setCpf] = useState('');
  const [nascimento, setNascimento] = useState('');
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
      <h2 className="vendas-subtitulo">{T.titulo}</h2>
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
      <RodapeDeAcoes
        cancelar={<Botao onClick={aoCancelar}>Cancelar</Botao>}
        principal={
          <Botao type="submit" variante="primario" disabled={salvando}>
            {T.salvar}
          </Botao>
        }
      />
    </form>
  );
}
