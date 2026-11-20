import { Aviso, Botao, Campo, RodapeDeAcoes } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import { ACEITA_ID_NO_APP } from '../../../shared/cadastros';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { lerVendedores } from '../usuarios/dados';
import { valorInicial, type CampoDaTela, type Definicao, type Registro } from './definicoes';

export interface FormularioDeCadastroProps {
  ponte: PonteDoCorona;
  definicao: Definicao;
  /** Sem registro: cadastro novo. Com registro: edição, com a versão que a tela leu. */
  registro: Registro | null;
  aoSalvar: (nome: string) => void;
  aoCancelar: () => void;
  /** Outra pessoa alterou: volta para a lista já atualizada. */
  aoAtualizar: () => void;
}

const chaveDe = (c: CampoDaTela) => (c.endereco ? `endereco.${c.nome}` : c.nome);

/** Cadastro e edição de qualquer cadastro básico. O primeiro campo já está com o cursor; Enter salva. */
export function FormularioDeCadastro({
  ponte,
  definicao,
  registro,
  aoSalvar,
  aoCancelar,
  aoAtualizar,
}: FormularioDeCadastroProps) {
  const novo = registro === null;
  const T = definicao.textos;
  const [valores, setValores] = useState<Record<string, string>>(() =>
    Object.fromEntries(definicao.campos.map((c) => [chaveDe(c), valorInicial(registro, c)])),
  );
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [locais, setLocais] = useState<Record<string, string>>({});

  const precisaDeVendedor = definicao.campos.some((c) => c.tipo === 'vendedor');
  const vendedores = useBusca(
    () =>
      precisaDeVendedor
        ? chamar(ponte, 'vendedores.escolha')
        : Promise.resolve({ ok: true as const, dados: { itens: [] } }),
    'vendedores',
  );
  const listaDeVendedores = vendedores.dados ? lerVendedores(vendedores.dados) : [];

  // O servidor pode dar o nome do campo como `cep` ou `endereco.cep`.
  const doServidor = (c: CampoDaTela): string | undefined =>
    erro?.campos[chaveDe(c)] ||
    erro?.campos[c.nome] ||
    (c.alternativa ? erro?.campos[c.alternativa] : undefined) ||
    undefined;
  const mensagem = (c: CampoDaTela): string | undefined => locais[chaveDe(c)] || doServidor(c);

  function mudar(c: CampoDaTela, valor: string) {
    setValores((v) => ({ ...v, [chaveDe(c)]: valor }));
    setLocais((l) => ({ ...l, [chaveDe(c)]: '' }));
    setErro((e) => (e ? { ...e, campos: { ...e.campos, [chaveDe(c)]: '', [c.nome]: '' } } : e));
  }

  function montarCorpo(): Record<string, unknown> {
    const corpo: Record<string, unknown> = {};
    const endereco: Record<string, string> = {};
    for (const c of definicao.campos) {
      const bruto = valores[chaveDe(c)] ?? '';
      const v = bruto.trim();
      if (c.tipo === 'sim-nao') {
        corpo[c.nome] = v === 'sim';
      } else if (v === '') {
        continue;
      } else if (c.endereco) {
        endereco[c.nome] = v;
      } else if (c.tipo === 'numero') {
        corpo[c.nome] = Number.parseInt(v, 10);
      } else if (c.tipo === 'percentual') {
        corpo[c.nome] = v.replace(',', '.');
      } else {
        corpo[c.nome] = v;
      }
    }
    if (Object.keys(endereco).length > 0) corpo.endereco = endereco;
    // Quem indicou já era um cliente cadastrado e a dona não escreveu outro nome: mantém o indicador.
    const indicador = registro?.indicadoPorClienteId;
    if (typeof indicador === 'string' && indicador && !corpo.indicadoPorNome) {
      corpo.indicadoPorClienteId = indicador;
    }
    return corpo;
  }

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    if (salvando) return;
    const faltas: Record<string, string> = {};
    for (const c of definicao.campos) {
      if (c.obrigatorio && !(valores[chaveDe(c)] ?? '').trim()) {
        faltas[chaveDe(c)] = TEXTOS.formulario.obrigatorio;
      }
      if (c.tipo === 'percentual' && (valores[chaveDe(c)] ?? '').trim()) {
        const n = Number(valores[chaveDe(c)]!.trim().replace(',', '.'));
        if (!/^\d{1,2}([.,]\d{1,2})?$/.test(valores[chaveDe(c)]!.trim()) || n < 0 || n > 40) {
          faltas[chaveDe(c)] = TEXTOS.cadastros.comissaoInvalida;
        }
      }
      if (c.tipo === 'numero' && (valores[chaveDe(c)] ?? '').trim()) {
        if (!/^\d{1,9}$/.test(valores[chaveDe(c)]!.trim())) {
          faltas[chaveDe(c)] = TEXTOS.cadastros.numeroInvalido;
        }
      }
    }
    setLocais(faltas);
    if (Object.values(faltas).some(Boolean)) return;

    setSalvando(true);
    setErro(null);
    const corpo = montarCorpo();
    const nome = definicao.nome({ id: '', version: 0, ...corpo });
    const r = novo
      ? await chamar(ponte, `${definicao.cadastro}.criar`, {
          ...corpo,
          ...(ACEITA_ID_NO_APP[definicao.cadastro] ? { id: crypto.randomUUID() } : {}),
        })
      : await chamar(ponte, `${definicao.cadastro}.editar`, {
          ...corpo,
          id: registro.id,
          version: registro.version,
        });
    setSalvando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoSalvar(nome);
  }

  const camposDaTela = new Set(
    definicao.campos.flatMap((c) => [
      chaveDe(c),
      c.nome,
      ...(c.alternativa ? [c.alternativa] : []),
    ]),
  );
  // Mensagens de campos que esta tela não mostra (o servidor pode recusar outros): aparecem no topo.
  const soltas = Object.entries(erro?.campos ?? {}).filter(([c, m]) => m && !camposDaTela.has(c));
  const campoComum = definicao.campos.filter((c) => !c.endereco);
  const campoDeEndereco = definicao.campos.filter((c) => c.endereco);

  function desenhar(c: CampoDaTela, primeiro: boolean) {
    const id = `cad-${chaveDe(c)}`;
    const valor = valores[chaveDe(c)] ?? '';
    const msg = mensagem(c);
    const comErro = msg ? { erro: msg } : {};
    const comAjuda = c.ajuda ? { ajuda: c.ajuda } : {};
    switch (c.tipo) {
      case 'selecao':
      case 'vendedor': {
        const opcoes =
          c.tipo === 'vendedor'
            ? listaDeVendedores.map((v) => ({ valor: v.id, rotulo: v.nome }))
            : (c.opcoes ?? []);
        const atualForaDaLista =
          c.tipo === 'vendedor' &&
          valor !== '' &&
          vendedores.dados !== null &&
          !opcoes.some((o) => o.valor === valor);
        return (
          <div key={id} className={`cs-campo${msg ? ' cs-campo--erro' : ''}`}>
            <label htmlFor={id}>{c.rotulo}</label>
            <select
              id={id}
              value={valor}
              autoFocus={primeiro}
              disabled={
                (c.tipo === 'vendedor' && vendedores.dados === null) ||
                (c.fixoDepoisDeDefinido === true && !novo && valorInicial(registro, c) !== '')
              }
              onChange={(e) => mudar(c, e.target.value)}
            >
              {c.tipo === 'vendedor' ? (
                <option value="">{TEXTOS.cadastros.semVendedor}</option>
              ) : null}
              {atualForaDaLista ? (
                <option value={valor}>{TEXTOS.cadastros.vendedorAtual}</option>
              ) : null}
              {opcoes.map((o) => (
                <option key={o.valor} value={o.valor}>
                  {o.rotulo}
                </option>
              ))}
            </select>
            {c.ajuda &&
            c.fixoDepoisDeDefinido === true &&
            !novo &&
            valorInicial(registro, c) !== '' ? (
              <span className="cs-campo__ajuda">{c.ajuda}</span>
            ) : null}
            {msg ? (
              <span role="alert" className="cs-campo__erro">
                {msg}
              </span>
            ) : null}
          </div>
        );
      }
      case 'sim-nao':
        return (
          <div key={id} className="cs-campo">
            <label htmlFor={id}>
              <input
                id={id}
                type="checkbox"
                checked={valor === 'sim'}
                autoFocus={primeiro}
                onChange={(e) => mudar(c, e.target.checked ? 'sim' : 'nao')}
              />{' '}
              {c.rotulo}
            </label>
          </div>
        );
      case 'area':
        return (
          <div key={id} className={`cs-campo${msg ? ' cs-campo--erro' : ''}`}>
            <label htmlFor={id}>{c.rotulo}</label>
            <textarea
              id={id}
              value={valor}
              rows={3}
              maxLength={c.max}
              autoFocus={primeiro}
              onChange={(e) => mudar(c, e.target.value)}
            />
            {msg ? (
              <span role="alert" className="cs-campo__erro">
                {msg}
              </span>
            ) : null}
          </div>
        );
      default:
        return (
          <Campo
            key={id}
            rotulo={c.rotulo}
            value={valor}
            autoFocus={primeiro}
            autoComplete="off"
            type={c.tipo === 'data' ? 'date' : c.tipo === 'numero' ? 'text' : 'text'}
            inputMode={
              c.tipo === 'numero' ? 'numeric' : c.tipo === 'percentual' ? 'decimal' : undefined
            }
            maxLength={c.max}
            onChange={(e) => mudar(c, e.target.value)}
            {...comAjuda}
            {...comErro}
          />
        );
    }
  }

  return (
    <form className="gestao-formulario" onSubmit={(e) => void enviar(e)} noValidate>
      <h2 className="gestao-subtitulo">
        {novo ? T.formulario.tituloNovo : T.formulario.tituloEditar}
      </h2>
      {erro && erro.tipo !== 'campos' ? (
        <AvisoDeErro erro={erro} aoAtualizar={aoAtualizar} />
      ) : null}
      {erro?.tipo === 'campos' && soltas.length > 0 ? (
        <Aviso tipo="erro">{soltas.map(([, m]) => m).join(' ')}</Aviso>
      ) : null}
      {vendedores.erro ? (
        <AvisoDeErro erro={vendedores.erro} aoTentarDeNovo={vendedores.recarregar} />
      ) : null}
      {campoComum.map((c, i) => desenhar(c, i === 0))}
      {campoDeEndereco.length > 0 ? (
        <fieldset className="gestao-grupo">
          <legend>{TEXTOS.cadastros.enderecoTitulo}</legend>
          {campoDeEndereco.map((c) => desenhar(c, false))}
        </fieldset>
      ) : null}
      <RodapeDeAcoes
        cancelar={<Botao onClick={aoCancelar}>{TEXTOS.formulario.cancelar}</Botao>}
        principal={
          <Botao type="submit" variante="primario" disabled={salvando}>
            {salvando ? TEXTOS.formulario.salvando : TEXTOS.formulario.salvar}
          </Botao>
        }
      />
    </form>
  );
}
