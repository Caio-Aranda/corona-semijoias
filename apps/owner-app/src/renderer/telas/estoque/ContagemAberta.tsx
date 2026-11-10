import { Aviso, Botao, Campo, EstadoDaLista } from '@corona/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { conferirAjuste, quantidadeComSinal } from '../../nucleo/estoque';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { CancelarContagem, FecharContagem } from './EncerrarContagem';
import { ItensDaContagem } from './ItensDaContagem';
import {
  dataEHoraParaTela,
  lerContagem,
  lerLeitura,
  type Contagem,
  type ItemDaContagem,
  type ResumoDaContagem,
} from './dados';

const T = TEXTOS_DE_ESTOQUE.contando;
const E = TEXTOS_DE_ESTOQUE.encerrada;
export const ID_DO_CAMPO_DA_CONTAGEM = 'contagem-leitor';

export interface ContagemAbertaProps {
  ponte: PonteDoCorona;
  contagemId: string;
  aoVoltar: () => void;
}

/** Uma contagem: em andamento (leitor, peça lida e lista) ou encerrada (só leitura). */
export function ContagemAberta({ ponte, contagemId, aoVoltar }: ContagemAbertaProps) {
  const [recarga, setRecarga] = useState(0);
  const busca = useBusca(
    () => chamar(ponte, 'contagens.ver', { id: contagemId }),
    `contagem|${contagemId}|${recarga}`,
  );
  const contagem = busca.dados ? lerContagem(busca.dados) : null;
  const [mensagemFinal, setMensagemFinal] = useState<string | null>(null);

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao onClick={aoVoltar}>{T.voltarLista}</Botao>
      </div>
      <EstadoDaLista
        carregando={busca.carregando && contagem === null}
        erro={
          busca.erro && contagem === null
            ? busca.erro.status === 404
              ? T.naoEncontrada
              : busca.erro.mensagem
            : null
        }
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        {mensagemFinal ? <Aviso tipo="sucesso">{mensagemFinal}</Aviso> : null}
        {contagem?.situacao === 'EM_ANDAMENTO' ? (
          <Contando
            ponte={ponte}
            contagem={contagem}
            aoEncerrar={(mensagem) => {
              setMensagemFinal(mensagem);
              setRecarga((r) => r + 1);
            }}
          />
        ) : null}
        {contagem && contagem.situacao !== 'EM_ANDAMENTO' ? (
          <Encerrada ponte={ponte} contagem={contagem} />
        ) : null}
      </EstadoDaLista>
    </div>
  );
}

function Encerrada({ ponte, contagem }: { ponte: PonteDoCorona; contagem: Contagem }) {
  const fechada = contagem.situacao === 'FECHADO';
  return (
    <>
      <Aviso tipo="informacao">
        {fechada
          ? E.fechada(dataEHoraParaTela(contagem.fechadoEm), contagem.fechadoPorNome)
          : E.cancelada(dataEHoraParaTela(contagem.canceladoEm))}
      </Aviso>
      {contagem.motivoDoCancelamento ? (
        <p className="gestao-texto">{E.motivo(contagem.motivoDoCancelamento)}</p>
      ) : null}
      {fechada ? (
        <p className="gestao-texto">
          {E.ajustes(contagem.ajustes)} {contagem.zerouNaoLidos ? E.zerou : E.nZerou}
        </p>
      ) : null}
      <ItensDaContagem ponte={ponte} contagemId={contagem.id} versao={0} aoCorrigir={null} />
    </>
  );
}

function Contando({
  ponte,
  contagem,
  aoEncerrar,
}: {
  ponte: PonteDoCorona;
  contagem: Contagem;
  aoEncerrar: (mensagem: string) => void;
}) {
  const [texto, setTexto] = useState('');
  const [lendo, setLendo] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [mensagem, setMensagem] = useState<string | null>(null);
  const [ultimo, setUltimo] = useState<ItemDaContagem | null>(null);
  const [resumo, setResumo] = useState<ResumoDaContagem>(contagem.resumo);
  const [versao, setVersao] = useState(0);
  const [fechando, setFechando] = useState(false);
  const [cancelando, setCancelando] = useState(false);
  const trava = useRef(false);
  // Uma leitura que falhou por falta de internet é repetida com o mesmo identificador: não soma duas vezes.
  const pendente = useRef<{ codigo: string; id: string } | null>(null);

  // O foco volta ao campo do leitor depois de cada leitura e quando as janelas fecham.
  useEffect(() => {
    if (!lendo && !fechando && !cancelando) {
      document.getElementById(ID_DO_CAMPO_DA_CONTAGEM)?.focus();
    }
  }, [lendo, fechando, cancelando, mensagem, erro]);

  async function ler(e: FormEvent) {
    e.preventDefault();
    const codigo = texto.trim();
    if (codigo === '' || trava.current) return;
    trava.current = true;
    setLendo(true);
    setErro(null);
    setMensagem(null);
    const id = pendente.current?.codigo === codigo ? pendente.current.id : crypto.randomUUID();
    pendente.current = { codigo, id };
    const r = await chamar(ponte, 'contagens.ler', { id: contagem.id, leituraId: id, codigo });
    if (r.ok) {
      const leitura = lerLeitura(r.dados);
      pendente.current = null;
      if (leitura) {
        setUltimo(leitura.item);
        setResumo(leitura.resumo);
        setMensagem(T.leitor.somou(leitura.item.peca.nome, leitura.item.contado ?? 0));
        setVersao((v) => v + 1);
      }
      setTexto('');
    } else {
      // Recusa do servidor (código desconhecido, peça de fora): a próxima leitura é outra.
      if (r.erro.tipo !== 'sem-conexao') pendente.current = null;
      setErro(r.erro);
    }
    trava.current = false;
    setLendo(false);
  }

  function mudouContado(item: ItemDaContagem, resumoNovo: ResumoDaContagem, frase: string) {
    setUltimo(item);
    setResumo(resumoNovo);
    setMensagem(frase);
    setVersao((v) => v + 1);
  }

  return (
    <>
      <p className="gestao-texto">
        {T.resumo.lidas(resumo.lidos, resumo.totalDePecas)} ·{' '}
        {T.resumo.comDiferenca(resumo.comDiferenca)}
        {resumo.faltam > 0 ? ` · ${T.resumo.faltam(resumo.faltam)}` : ''}
        {resumo.sobram > 0 ? ` · ${T.resumo.sobram(resumo.sobram)}` : ''}
      </p>
      <section className="gestao-grupo" aria-labelledby="titulo-leitor-contagem">
        <h2 id="titulo-leitor-contagem" className="gestao-subtitulo">
          {T.leitor.ler}
        </h2>
        <form className="gestao-filtros" onSubmit={(e) => void ler(e)}>
          <Campo
            id={ID_DO_CAMPO_DA_CONTAGEM}
            rotulo={T.leitor.campo}
            ajuda={T.leitor.ajuda}
            value={texto}
            autoComplete="off"
            spellCheck={false}
            autoFocus
            disabled={fechando || cancelando}
            onChange={(e) => setTexto(e.target.value)}
          />
          <Botao type="submit" variante="primario" disabled={lendo}>
            {T.leitor.ler}
          </Botao>
        </form>
        {lendo ? <p role="status">{T.leitor.lendo}</p> : null}
        {mensagem ? (
          <p className="gestao-texto" role="status">
            {mensagem}
          </p>
        ) : null}
        {erro ? <AvisoDeErro erro={erro} aoTentarDeNovo={() => setErro(null)} /> : null}
      </section>
      {ultimo ? (
        <PecaLida
          key={ultimo.peca.id}
          ponte={ponte}
          contagemId={contagem.id}
          item={ultimo}
          aoMudar={mudouContado}
        />
      ) : null}
      <ItensDaContagem
        ponte={ponte}
        contagemId={contagem.id}
        versao={versao}
        aoCorrigir={setUltimo}
      />
      <div className="gestao-acoes-da-linha">
        <Botao onClick={() => setCancelando(true)}>{T.cancelar}</Botao>
        <Botao variante="primario" onClick={() => setFechando(true)}>
          {T.fechar}
        </Botao>
      </div>
      {fechando ? (
        <FecharContagem
          ponte={ponte}
          contagemId={contagem.id}
          aoFechar={aoEncerrar}
          aoContinuar={() => setFechando(false)}
        />
      ) : null}
      {cancelando ? (
        <CancelarContagem
          ponte={ponte}
          contagemId={contagem.id}
          aoCancelar={aoEncerrar}
          aoVoltar={() => setCancelando(false)}
        />
      ) : null}
    </>
  );
}

interface PecaLidaProps {
  ponte: PonteDoCorona;
  contagemId: string;
  item: ItemDaContagem;
  aoMudar: (item: ItemDaContagem, resumo: ResumoDaContagem, frase: string) => void;
}

/** A última peça lida, com a foto grande para conferir, e o campo para corrigir a quantidade contada. */
function PecaLida({ ponte, contagemId, item, aoMudar }: PecaLidaProps) {
  const P = T.peca;
  const [valor, setValor] = useState(String(item.contado ?? 0));
  const [erro, setErro] = useState<string | null>(null);
  const [erroDoServidor, setErroDoServidor] = useState<ErroDaTela | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const trava = useRef(false);
  const leituraId = useRef(crypto.randomUUID());
  const ultimoValor = useRef('');

  useEffect(() => {
    setValor(String(item.contado ?? 0));
  }, [item.contado]);

  async function definir(e: FormEvent) {
    e.preventDefault();
    if (trava.current) return;
    // Só números inteiros, zero vale. Reaproveita a regra do "contei" (sem comparar com o estoque).
    const conferido = conferirAjuste('CONTEI', valor, -1);
    if (!conferido.ok || !('novaQuantidade' in conferido.valor)) {
      setErro(P.quantidadeInvalida);
      return;
    }
    const contado = conferido.valor.novaQuantidade;
    setErro(null);
    setErroDoServidor(null);
    if (ultimoValor.current !== '' && ultimoValor.current !== valor.trim()) {
      leituraId.current = crypto.randomUUID();
    }
    ultimoValor.current = valor.trim();
    trava.current = true;
    setOcupado(true);
    const r = await chamar(ponte, 'contagens.corrigir', {
      id: contagemId,
      leituraId: leituraId.current,
      produtoId: item.peca.id,
      contado,
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErroDoServidor(r.erro);
      return;
    }
    const leitura = lerLeitura(r.dados);
    if (leitura) aoMudar(leitura.item, leitura.resumo, P.definida(item.peca.nome, contado));
    leituraId.current = crypto.randomUUID();
    ultimoValor.current = '';
  }

  return (
    <section className="gestao-grupo gestao-peca-lida" aria-labelledby="titulo-peca-lida">
      <h2 id="titulo-peca-lida" className="gestao-subtitulo">
        {P.titulo}
      </h2>
      <div className="gestao-consulta">
        <ImagemDoProduto
          ponte={ponte}
          hash={item.peca.fotoHash}
          tamanho="grande"
          nome={item.peca.nome}
        />
        <dl className="gestao-resumo">
          <div>
            <dt>{TEXTOS_DE_ESTOQUE.comuns.peca}</dt>
            <dd>{item.peca.nome}</dd>
          </div>
          <div>
            <dt>{TEXTOS_DE_ESTOQUE.comuns.codigo}</dt>
            <dd>{item.peca.codigo}</dd>
          </div>
          <div>
            <dt>{P.esperado}</dt>
            <dd>{item.esperado}</dd>
          </div>
          <div>
            <dt>{P.contado}</dt>
            <dd>{item.contado ?? 0}</dd>
          </div>
          <div>
            <dt>{P.diferenca}</dt>
            <dd>{quantidadeComSinal(item.diferenca ?? -item.esperado)}</dd>
          </div>
        </dl>
      </div>
      <form className="gestao-filtros" onSubmit={(e) => void definir(e)}>
        <Campo
          rotulo={P.corrigir}
          ajuda={P.corrigirAjuda}
          value={valor}
          inputMode="numeric"
          autoComplete="off"
          onChange={(e) => setValor(e.target.value)}
          {...(erro ? { erro } : {})}
        />
        <Botao type="submit" disabled={ocupado}>
          {P.definir}
        </Botao>
      </form>
      {erroDoServidor ? <AvisoDeErro erro={erroDoServidor} /> : null}
    </section>
  );
}
