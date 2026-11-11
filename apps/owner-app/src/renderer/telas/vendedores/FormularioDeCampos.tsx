import { Aviso, Botao, Campo } from '@corona/ui';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import type { ErroDaTela, Retorno } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { montarCorpo, valoresIniciais, type CampoDaFicha, type Registro } from './dados';

const T = TEXTOS.vendedores;

export interface OpcaoDaLista {
  valor: string;
  rotulo: string;
  /** Inativo some das escolhas, menos quando é o que já está escolhido. */
  ativo?: boolean;
}

export interface FormularioDeCamposProps {
  /** Texto que identifica o formulário nos `id` dos campos. */
  idBase: string;
  campos: readonly CampoDaFicha[];
  /** O que veio da API (ou um registro vazio, com versão 0, quando é cadastro novo). */
  registro: Registro;
  /** Listas de opções que vêm de fora (praças, restrições), pelo nome em `campo.lista`. */
  listas?: Record<string, readonly OpcaoDaLista[] | null>;
  salvar: (corpo: Record<string, unknown>, version: number) => Promise<Retorno<unknown>>;
  aoSalvo: (resposta: unknown) => void;
  /** Outra pessoa alterou: busca de novo. */
  aoAtualizar: () => void;
  aoMudarSujo: (sujo: boolean) => void;
  rotuloSalvar?: string;
  /** Algo para mostrar ao lado do botão de salvar (por exemplo, desativar). */
  rodapeExtra?: React.ReactNode;
  autoFocus?: boolean;
}

/**
 * Formulário genérico da ficha: cada aba é uma lista de campos. Avisa a ficha quando há alteração
 * não salva, mostra o erro de cada campo no próprio campo (o texto vem do servidor) e trata o
 * "outra pessoa alterou" com o botão Atualizar.
 */
export function FormularioDeCampos({
  idBase,
  campos,
  registro,
  listas = {},
  salvar,
  aoSalvo,
  aoAtualizar,
  aoMudarSujo,
  rotuloSalvar = T.ficha.salvar,
  rodapeExtra,
  autoFocus = true,
}: FormularioDeCamposProps) {
  const inicial = useMemo(() => valoresIniciais(campos, registro), [campos, registro]);
  const [salvos, setSalvos] = useState(inicial);
  const [valores, setValores] = useState(inicial);
  const [salvando, setSalvando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [locais, setLocais] = useState<Record<string, string>>({});

  const sujo = JSON.stringify(valores) !== JSON.stringify(salvos);
  // A função de aviso pode mudar a cada desenho da tela; o aviso só deve sair quando `sujo` muda.
  const avisar = useRef(aoMudarSujo);
  avisar.current = aoMudarSujo;
  useEffect(() => {
    avisar.current(sujo);
  }, [sujo]);
  useEffect(() => () => avisar.current(false), []);

  function mudar(nome: string, valor: string) {
    setValores((v) => {
      const novo = { ...v, [nome]: valor };
      // Marcar "Sem limite" esvazia o campo de valor do limite.
      for (const c of campos) if (c.desabilitaSe === nome && valor === 'sim') novo[c.nome] = '';
      return novo;
    });
    setLocais((l) => ({ ...l, [nome]: '' }));
    setErro((e) => (e ? { ...e, campos: { ...e.campos, [nome]: '' } } : e));
  }

  const mensagem = (nome: string): string | undefined =>
    locais[nome] || erro?.campos[nome] || undefined;

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    if (salvando) return;
    const { corpo, erros } = montarCorpo(campos, valores);
    setLocais(erros);
    if (Object.values(erros).some(Boolean)) return;
    setSalvando(true);
    setErro(null);
    const r = await salvar(corpo, registro.version);
    setSalvando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    setSalvos(valores);
    aoSalvo(r.dados);
  }

  const conhecidos = new Set(campos.map((c) => c.nome));
  // Mensagens de campos que esta aba não mostra: aparecem no topo.
  const soltas = Object.entries(erro?.campos ?? {}).filter(([c, m]) => m && !conhecidos.has(c));

  function desenhar(c: CampoDaFicha, primeiro: boolean) {
    const id = `${idBase}-${c.nome}`;
    const rotulo = c.rotulo ?? T.rotulos[c.nome] ?? c.nome;
    const valor = valores[c.nome] ?? '';
    const msg = mensagem(c.nome);
    const foco = primeiro && autoFocus;
    const comErro = msg ? { erro: msg } : {};
    const comAjuda = c.ajuda ? { ajuda: c.ajuda } : {};
    switch (c.tipo) {
      case 'dinheiro':
        return (
          <CampoDeDinheiro
            key={id}
            id={id}
            rotulo={rotulo}
            valor={valor}
            aoMudar={(digitado) => mudar(c.nome, digitado)}
            autoFocus={foco}
            desabilitado={c.desabilitaSe !== undefined && valores[c.desabilitaSe] === 'sim'}
            {...(msg ? { erro: msg } : {})}
            {...(c.ajuda ? { ajuda: c.ajuda } : {})}
          />
        );
      case 'selecao': {
        const lista = c.lista ? listas[c.lista] : null;
        const opcoes = (c.lista ? (lista ?? []) : (c.opcoes ?? [])).filter(
          (o) => !('ativo' in o) || o.ativo !== false || o.valor === valor,
        );
        const foraDaLista =
          c.lista !== undefined &&
          lista !== null &&
          lista !== undefined &&
          valor !== '' &&
          !opcoes.some((o) => o.valor === valor);
        return (
          <div key={id} className={`cs-campo${msg ? ' cs-campo--erro' : ''}`}>
            <label htmlFor={id}>{rotulo}</label>
            <select
              id={id}
              value={valor}
              autoFocus={foco}
              disabled={c.lista !== undefined && !lista}
              onChange={(e) => mudar(c.nome, e.target.value)}
            >
              <option value="">{c.semOpcao ?? ''}</option>
              {foraDaLista ? <option value={valor}>{T.opcaoAntiga}</option> : null}
              {opcoes.map((o) => (
                <option key={o.valor} value={o.valor}>
                  {o.rotulo}
                </option>
              ))}
            </select>
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
                autoFocus={foco}
                onChange={(e) => mudar(c.nome, e.target.checked ? 'sim' : 'nao')}
              />{' '}
              {rotulo}
            </label>
          </div>
        );
      case 'area':
        return (
          <div key={id} className={`cs-campo${msg ? ' cs-campo--erro' : ''}`}>
            <label htmlFor={id}>{rotulo}</label>
            <textarea
              id={id}
              value={valor}
              rows={6}
              maxLength={c.max}
              autoFocus={foco}
              onChange={(e) => mudar(c.nome, e.target.value)}
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
            id={id}
            rotulo={rotulo}
            value={valor}
            autoFocus={foco}
            autoComplete="off"
            type={c.tipo === 'data' ? 'date' : 'text'}
            maxLength={c.max}
            onChange={(e) => mudar(c.nome, e.target.value)}
            {...comAjuda}
            {...comErro}
          />
        );
    }
  }

  return (
    <form
      className="gestao-formulario gestao-formulario--larga"
      onSubmit={(e) => void enviar(e)}
      noValidate
    >
      {erro && erro.tipo !== 'campos' ? (
        <AvisoDeErro erro={erro} aoAtualizar={aoAtualizar} />
      ) : null}
      {erro?.tipo === 'campos' && soltas.length > 0 ? (
        <Aviso tipo="erro">{soltas.map(([, m]) => m).join(' ')}</Aviso>
      ) : null}
      {campos.map((c, i) => desenhar(c, i === 0))}
      <div className="gestao-rodape-do-formulario">
        <Botao type="submit" variante="primario" disabled={salvando}>
          {salvando ? T.ficha.salvando : rotuloSalvar}
        </Botao>
        {rodapeExtra}
      </div>
    </form>
  );
}
