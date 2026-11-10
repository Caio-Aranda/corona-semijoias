import { Aviso, Botao, Campo, EstadoDaLista, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { tresMoedas, dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { lerCompra, type CompraGravada } from './dados';

const T = TEXTOS_DE_COMPRAS.detalhe;
const reais = (valor: string): string => tresMoedas(valor, null)?.real ?? valor;

export interface DetalheDaCompraProps {
  ponte: PonteDoCorona;
  compraId: string;
  /** A compra que acabou de ser gravada, para não buscar de novo. */
  inicial?: CompraGravada;
  /** Se vier, aparece o botão de voltar para a lista. */
  aoVoltar?: () => void;
  /** A compra mudou (foi desfeita): a lista precisa ser lida de novo. */
  aoMudar?: () => void;
}

/** Uma compra gravada: dados, peças com foto e a opção de desfazer. */
export function DetalheDaCompra({
  ponte,
  compraId,
  inicial,
  aoVoltar,
  aoMudar,
}: DetalheDaCompraProps) {
  const busca = useBusca(
    () =>
      inicial
        ? Promise.resolve({ ok: true as const, dados: inicial })
        : chamar(ponte, 'compras.ver', { id: compraId }),
    `compra|${compraId}`,
  );
  const [atual, setAtual] = useState<CompraGravada | null>(null);
  const compra = atual ?? inicial ?? lerCompra(busca.dados) ?? null;

  const [desfazendo, setDesfazendo] = useState(false);
  const [motivo, setMotivo] = useState('');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [feito, setFeito] = useState(false);
  // O mesmo identificador em toda tentativa: reenviar depois de uma falha de rede não desfaz duas vezes.
  const pedidoId = useRef(crypto.randomUUID());
  const trava = useRef(false);

  async function desfazer() {
    if (trava.current) return;
    if (motivo.trim() === '') {
      setTentou(true);
      return;
    }
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'compras.desfazer', {
      id: compraId,
      pedidoId: pedidoId.current,
      motivo: motivo.trim(),
    });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const nova = lerCompra(r.dados);
    if (nova) setAtual(nova);
    setDesfazendo(false);
    setFeito(true);
    aoMudar?.();
  }

  const dados = compra
    ? {
        linhas: compra.itens.map((i) => ({
          foto: (
            <ImagemDoProduto ponte={ponte} hash={i.fotoHash} tamanho="miniatura" nome={i.nome} />
          ),
          nome: i.nome,
          codigo: i.codigo,
          quantidade: i.quantidade,
          custo: reais(i.custoUnitario),
          total: reais(i.total),
        })),
      }
    : null;

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">
          {compra
            ? T.titulo(dataParaTela(compra.resumo.dataCompra))
            : TEXTOS_DE_COMPRAS.lista.titulo}
        </h1>
        {aoVoltar ? <Botao onClick={aoVoltar}>{T.voltar}</Botao> : null}
      </div>
      <EstadoDaLista
        carregando={busca.carregando && compra === null}
        erro={
          busca.erro && compra === null
            ? busca.erro.status === 404
              ? T.naoEncontrada
              : busca.erro.mensagem
            : null
        }
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        {compra && dados ? (
          <>
            {feito ? <Aviso tipo="sucesso">{T.desfeitaAgora}</Aviso> : null}
            {compra.resumo.situacao === 'ESTORNADA' ? (
              <Aviso tipo="atencao">
                {T.desfeita(
                  compra.desfeitaEm ? dataParaTela(compra.desfeitaEm.slice(0, 10)) : '—',
                  compra.motivoDeDesfeita,
                )}
              </Aviso>
            ) : null}
            <dl className="gestao-resumo">
              <div>
                <dt>{T.fornecedor}</dt>
                <dd>{compra.resumo.fornecedorNome}</dd>
              </div>
              <div>
                <dt>{T.data}</dt>
                <dd>{dataParaTela(compra.resumo.dataCompra)}</dd>
              </div>
              {compra.resumo.numero ? (
                <div>
                  <dt>{T.numero}</dt>
                  <dd>{compra.resumo.numero}</dd>
                </div>
              ) : null}
              <div>
                <dt>{T.pecas}</dt>
                <dd>{compra.resumo.pecas}</dd>
              </div>
              <div>
                <dt>{T.total}</dt>
                <dd>{reais(compra.resumo.total)}</dd>
              </div>
              {compra.usuarioNome ? (
                <div>
                  <dt>{T.registradaPor}</dt>
                  <dd>{compra.usuarioNome}</dd>
                </div>
              ) : null}
              {compra.observacoes ? (
                <div>
                  <dt>{T.observacoes}</dt>
                  <dd>{compra.observacoes}</dd>
                </div>
              ) : null}
            </dl>
            <Tabela
              legenda={T.legenda}
              colunas={[
                { chave: 'foto', titulo: T.colunas.foto },
                { chave: 'nome', titulo: T.colunas.nome },
                { chave: 'codigo', titulo: T.colunas.codigo },
                { chave: 'quantidade', titulo: T.colunas.quantidade, numerica: true },
                { chave: 'custo', titulo: T.colunas.custo, numerica: true },
                { chave: 'total', titulo: T.colunas.total, numerica: true },
              ]}
              linhas={dados.linhas}
            />
            {compra.resumo.situacao === 'REGISTRADA' ? (
              <div className="gestao-acoes-da-linha">
                <Botao onClick={() => setDesfazendo(true)}>{T.desfazer}</Botao>
              </div>
            ) : null}
          </>
        ) : null}
      </EstadoDaLista>
      {desfazendo ? (
        <JanelaDeConfirmacao
          titulo={T.desfazerConfirma.titulo}
          rotuloConfirmar={T.desfazerConfirma.confirmar}
          rotuloCancelar={T.desfazerConfirma.voltar}
          perigosa
          ocupado={ocupado}
          aoCancelar={() => {
            setDesfazendo(false);
            setErro(null);
          }}
          aoConfirmar={() => void desfazer()}
        >
          <>
            {T.desfazerConfirma.texto}
            <Campo
              rotulo={T.desfazerConfirma.motivo}
              value={motivo}
              maxLength={500}
              autoComplete="off"
              onChange={(e) => setMotivo(e.target.value)}
              {...(tentou && motivo.trim() === '' ? { erro: T.desfazerConfirma.motivoAviso } : {})}
            />
            {erro ? <AvisoDeErro erro={erro} /> : null}
          </>
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
