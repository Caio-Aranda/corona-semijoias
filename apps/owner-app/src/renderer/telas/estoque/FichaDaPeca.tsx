import { Aviso, Botao, Campo, EstadoDaLista, JanelaDeConfirmacao, Paginacao } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { conferirMinimo, estaAcabando } from '../../nucleo/estoque';
import { tresMoedas } from '../../nucleo/moedas';
import { TEXTOS_DE_ESTOQUE } from '../../textos-estoque';
import { lerProduto } from '../produtos/dados';
import { DesfazerLancamento } from './DesfazerLancamento';
import { LancarNoEstoque, type ModoDoLancamento } from './LancarNoEstoque';
import { TabelaDeLancamentos } from './TabelaDeLancamentos';
import { lerFicha, type Lancamento } from './dados';

const T = TEXTOS_DE_ESTOQUE.ficha;
const TAMANHO = 10;

export interface FichaDaPecaProps {
  ponte: PonteDoCorona;
  pecaId: string;
  aoVoltar: () => void;
}

/** Ficha de uma peça no estoque: foto, quantidade, mínimo, custo (só da dona) e o histórico. */
export function FichaDaPeca({ ponte, pecaId, aoVoltar }: FichaDaPecaProps) {
  const [pagina, setPagina] = useState(0);
  const [recarga, setRecarga] = useState(0);
  const ficha = useBusca(
    () => chamar(ponte, 'estoque.ficha', { id: pecaId, pagina, tamanho: TAMANHO }),
    `ficha|${pecaId}|${pagina}|${recarga}`,
  );
  // O custo é da dona: vem do cadastro da peça e só aparece nesta tela.
  const produto = useBusca(
    () => chamar(ponte, 'produtos.ver', { id: pecaId }),
    `custo|${pecaId}|${recarga}`,
  );
  const dados = ficha.dados ? lerFicha(ficha.dados) : null;
  const cadastro = produto.dados ? lerProduto(produto.dados) : null;
  const custo = cadastro?.precoCusto ?? null;
  const versao = cadastro?.version ?? null;

  const [lancando, setLancando] = useState<ModoDoLancamento | null>(null);
  const [desfazendo, setDesfazendo] = useState<Lancamento | null>(null);
  const [mudandoMinimo, setMudandoMinimo] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);

  const atualizar = (mensagem: string) => {
    setAviso(mensagem);
    setLancando(null);
    setDesfazendo(null);
    setMudandoMinimo(false);
    setRecarga((r) => r + 1);
  };

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">
          {dados ? dados.peca.nome : TEXTOS_DE_ESTOQUE.posicao.titulo}
        </h1>
        <Botao onClick={aoVoltar}>{TEXTOS_DE_ESTOQUE.comuns.voltar}</Botao>
      </div>
      <EstadoDaLista
        carregando={ficha.carregando && dados === null}
        erro={
          ficha.erro && dados === null
            ? ficha.erro.status === 404
              ? T.naoEncontrada
              : ficha.erro.mensagem
            : null
        }
        textoCarregando={T.carregando}
        aoTentarDeNovo={ficha.recarregar}
      >
        {dados ? (
          <>
            {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
            <div className="gestao-consulta">
              <ImagemDoProduto
                ponte={ponte}
                hash={dados.peca.fotoHash}
                tamanho="grande"
                nome={dados.peca.nome}
              />
              <dl className="gestao-resumo">
                <div>
                  <dt>{TEXTOS_DE_ESTOQUE.comuns.codigo}</dt>
                  <dd>{dados.peca.codigo}</dd>
                </div>
                <div>
                  <dt>{T.noEstoque}</dt>
                  <dd>{TEXTOS_DE_ESTOQUE.comuns.pecas(dados.saldo)}</dd>
                </div>
                <div>
                  <dt>{T.minimo}</dt>
                  <dd>
                    {dados.peca.minimo}{' '}
                    <Botao onClick={() => setMudandoMinimo(true)}>{T.mudarMinimo}</Botao>
                  </dd>
                </div>
                {custo ? (
                  <div>
                    <dt>{T.custo}</dt>
                    <dd>
                      {tresMoedas(custo, null)?.real ?? custo} <small>{T.custoAjuda}</small>
                    </dd>
                  </div>
                ) : null}
              </dl>
            </div>
            {estaAcabando(dados.saldo, dados.peca.minimo) ? (
              <Aviso tipo="atencao">{T.acabando}</Aviso>
            ) : null}
            <div className="gestao-acoes-da-linha">
              <Botao variante="primario" onClick={() => setLancando('ENTRADA')}>
                {T.entrada}
              </Botao>
              <Botao onClick={() => setLancando('SAIDA')}>{T.saida}</Botao>
              <Botao onClick={() => setLancando('AVARIA')}>{T.perda}</Botao>
              <Botao onClick={() => setLancando('AJUSTE')}>{T.ajustar}</Botao>
            </div>
            <h2 className="gestao-subtitulo">{T.historico}</h2>
            <TabelaDeLancamentos
              ponte={ponte}
              lancamentos={dados.historico.itens}
              mostrarPeca={false}
              textoVazio={TEXTOS_DE_ESTOQUE.historico.vazio}
              aoDesfazer={setDesfazendo}
            />
            <Paginacao
              pagina={dados.historico.pagina}
              tamanho={dados.historico.tamanho}
              total={dados.historico.total}
              nomeDoItem={TEXTOS_DE_ESTOQUE.historico.itens}
              aoMudar={setPagina}
            />
          </>
        ) : null}
      </EstadoDaLista>
      {lancando && dados ? (
        <LancarNoEstoque
          key={lancando}
          ponte={ponte}
          modo={lancando}
          pecaId={pecaId}
          pecaNome={dados.peca.nome}
          saldo={dados.saldo}
          aoFeito={() => atualizar(T.foiAtualizado)}
          aoCancelar={() => setLancando(null)}
        />
      ) : null}
      {desfazendo ? (
        <DesfazerLancamento
          key={desfazendo.id}
          ponte={ponte}
          lancamentoId={desfazendo.id}
          aoFeito={() => atualizar(T.foiAtualizado)}
          aoCancelar={() => setDesfazendo(null)}
        />
      ) : null}
      {mudandoMinimo && dados && versao !== null ? (
        <MudarMinimo
          ponte={ponte}
          pecaId={pecaId}
          version={versao ?? 0}
          atual={dados.peca.minimo}
          aoFeito={() => atualizar(T.minimoAtualizado)}
          aoCancelar={() => setMudandoMinimo(false)}
        />
      ) : null}
    </div>
  );
}

interface MudarMinimoProps {
  ponte: PonteDoCorona;
  pecaId: string;
  /** Versão do cadastro que a tela leu; se mudou, o servidor recusa e a pessoa atualiza. */
  version: number;
  atual: number;
  aoFeito: () => void;
  aoCancelar: () => void;
}

/** Muda só o estoque mínimo, pela rota própria (não reenvia o cadastro). */
function MudarMinimo({ ponte, pecaId, version, atual, aoFeito, aoCancelar }: MudarMinimoProps) {
  const [texto, setTexto] = useState(String(atual));
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);
  const conferido = conferirMinimo(texto);
  const M = TEXTOS_DE_ESTOQUE.minimo;

  async function salvar() {
    if (trava.current) return;
    if (!conferido.ok) {
      setTentou(true);
      return;
    }
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'produtos.estoqueMinimo', {
      id: pecaId,
      version,
      estoqueMinimo: conferido.valor,
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    aoFeito();
  }

  return (
    <JanelaDeConfirmacao
      titulo={M.titulo}
      rotuloConfirmar={M.salvar}
      rotuloCancelar={M.cancelar}
      ocupado={ocupado}
      aoCancelar={aoCancelar}
      aoConfirmar={() => void salvar()}
    >
      <>
        {M.texto}
        <Campo
          rotulo={M.campo}
          value={texto}
          inputMode="numeric"
          autoComplete="off"
          autoFocus
          onChange={(e) => setTexto(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              void salvar();
            }
          }}
          {...(tentou && !conferido.ok ? { erro: conferido.erro } : {})}
        />
        {erro ? <AvisoDeErro erro={erro} aoAtualizar={() => setErro(null)} /> : null}
      </>
    </JanelaDeConfirmacao>
  );
}
