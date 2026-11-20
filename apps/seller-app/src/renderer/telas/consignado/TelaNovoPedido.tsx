import { Aviso, Botao, RodapeDeAcoes } from '@corona/ui';
import { useEffect, useState } from 'react';
import type {
  ClienteDaLista,
  DetalheDaNota,
  PedidoEmMontagem,
  ResultadoDoBipeNaTela,
} from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao } from '../../chamadas';
import { Campo, JanelaDeConfirmacao } from '../../componentes-do-vendedor';
import { TEXTOS } from '../../textos';
import { FotoDoCliente, FotoDoProduto, reais, useConsulta } from './apoio';
import { LeitorDeCodigo } from './componentes';
import { BotaoDeRelatorio } from './Relatorio';
import { PaginaDoVendedor } from '../PaginaDoVendedor';

export interface TelaNovoPedidoProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoAbrirPedido: (notaId: string) => void;
}

/**
 * Novo Pedido (§23.6): o cliente, a maleta, a Dt. Cobrança e a leitura do código de cada peça (soma 1 na mesma linha, ou
 * linha nova). O pedido em montagem fica guardado neste computador (sobrevive a fechar o programa) e só vira nota ao Finalizar.
 */
export function TelaNovoPedido({ ponte, aoVoltar, aoAbrirPedido }: TelaNovoPedidoProps) {
  const T = TEXTOS.consignado.pedido;
  const cotacao = useCotacao(ponte);
  const {
    dados: pedido,
    erro: erroDaConsulta,
    recarregar,
  } = useConsulta<PedidoEmMontagem>(ponte, { operacao: 'consignado.pedidoVer' }, []);
  const [atual, setAtual] = useState<PedidoEmMontagem | null>(null);
  const [mensagem, setMensagem] = useState<{
    tipo: 'sucesso' | 'atencao' | 'erro';
    texto: string;
  } | null>(null);
  const [buscaDoCliente, setBuscaDoCliente] = useState('');
  const [clientes, setClientes] = useState<ClienteDaLista[]>([]);
  const [trocandoCliente, setTrocandoCliente] = useState(false);
  const [confirmando, setConfirmando] = useState(false);
  const [descartando, setDescartando] = useState(false);
  const [pronto, setPronto] = useState<DetalheDaNota | null>(null);

  useEffect(() => {
    if (pedido) setAtual(pedido);
  }, [pedido]);

  useEffect(() => {
    if (atual?.clienteId && !trocandoCliente) return;
    let ativo = true;
    const espera = setTimeout(() => {
      void chamar<ClienteDaLista[]>(ponte, {
        operacao: 'consignado.clientes',
        ...(buscaDoCliente.trim() ? { busca: buscaDoCliente.trim() } : {}),
      }).then((r) => {
        if (ativo && r.ok) setClientes(r.dados);
      });
    }, 200);
    return () => {
      ativo = false;
      clearTimeout(espera);
    };
  }, [ponte, buscaDoCliente, atual?.clienteId, trocandoCliente]);

  async function definir(parte: {
    clienteId?: string | null;
    remessaId?: string | null;
    dataCobranca?: string;
  }): Promise<void> {
    const r = await chamar<PedidoEmMontagem>(ponte, {
      operacao: 'consignado.pedidoDefinir',
      ...parte,
    });
    if (r.ok) {
      setAtual(r.dados);
      setMensagem(null);
    } else setMensagem({ tipo: 'erro', texto: r.mensagem });
  }

  async function ler(codigo: string): Promise<void> {
    const r = await chamar<ResultadoDoBipeNaTela>(ponte, {
      operacao: 'consignado.pedidoBipar',
      codigo,
    });
    if (!r.ok) {
      setMensagem({ tipo: 'erro', texto: r.mensagem });
      return;
    }
    switch (r.dados.resultado) {
      case 'ADICIONADA':
        setAtual(r.dados.pedido);
        setMensagem({ tipo: 'sucesso', texto: T.lida(r.dados.pedido.itens.at(-1)?.nome ?? '') });
        break;
      case 'FORA_DA_MALETA':
        setMensagem({ tipo: 'atencao', texto: T.foraDaMaleta });
        break;
      case 'EM_OUTRA_MALETA':
        setMensagem({ tipo: 'atencao', texto: T.emOutraMaleta(r.dados.maleta) });
        break;
      case 'SEM_SALDO':
        setMensagem({ tipo: 'atencao', texto: T.semSaldo(r.dados.disponivel) });
        break;
    }
  }

  async function diminuir(itemId: string, quantidade: number): Promise<void> {
    const r = await chamar<PedidoEmMontagem>(ponte, {
      operacao: 'consignado.pedidoQuantidade',
      itemId,
      quantidade,
    });
    if (r.ok) setAtual(r.dados);
    else setMensagem({ tipo: 'erro', texto: r.mensagem });
  }

  async function finalizar(): Promise<void> {
    const r = await chamar<{ notaId: string; nota: DetalheDaNota }>(ponte, {
      operacao: 'consignado.pedidoFinalizar',
      id: novoId(),
    });
    setConfirmando(false);
    if (r.ok) setPronto(r.dados.nota);
    else setMensagem({ tipo: 'erro', texto: r.mensagem });
  }

  async function descartar(): Promise<void> {
    await chamar(ponte, { operacao: 'consignado.pedidoDescartar' });
    setDescartando(false);
    setBuscaDoCliente('');
    setTrocandoCliente(false);
    await recarregar();
  }

  if (pronto) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <Aviso tipo="sucesso">{T.pronto(pronto.numero)}</Aviso>
        <BotaoDeRelatorio
          ponte={ponte}
          pedido={{ tipo: 'NOTA_DE_SAIDA', notaId: pronto.id }}
          rotulo={TEXTOS.consignado.relatorios.abrir.notaDeSaida}
          variante="primario"
        />
        <RodapeDeAcoes
          cancelar={<Botao onClick={() => aoAbrirPedido(pronto.id)}>{T.verNota}</Botao>}
          principal={
            <Botao
              variante="primario"
              onClick={() => {
                setPronto(null);
                setAtual(null);
                void recarregar();
              }}
            >
              {T.novoOutro}
            </Botao>
          }
        />
      </PaginaDoVendedor>
    );
  }

  const escolheuCliente = Boolean(atual?.clienteId) && !trocandoCliente;
  const dataValida = atual ? /^\d{4}-\d{2}-\d{2}$/.test(atual.dataCobranca) : false;
  const podeFinalizar = Boolean(atual && atual.clienteId && atual.itens.length > 0 && dataValida);

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      {erroDaConsulta ? <Aviso tipo="erro">{erroDaConsulta}</Aviso> : null}
      {atual ? (
        <>
          <section className="vendas-cartao vendas-cartao--larga" aria-label={T.cliente}>
            <h2 className="vendas-subtitulo">{T.cliente}</h2>
            {escolheuCliente ? (
              <>
                <p className="vendas-texto">
                  <strong>{atual.cliente}</strong>
                </p>
                {centavosPositivos(atual.pendenciaDoCliente) ? (
                  <Aviso tipo="atencao">{T.deve(reais(atual.pendenciaDoCliente))}</Aviso>
                ) : null}
                <Botao onClick={() => setTrocandoCliente(true)}>{T.trocarCliente}</Botao>
              </>
            ) : (
              <>
                <Campo
                  rotulo={T.buscarCliente}
                  value={buscaDoCliente}
                  autoComplete="off"
                  onChange={(e) => setBuscaDoCliente(e.target.value)}
                />
                {clientes.length === 0 ? (
                  <p className="vendas-texto">{T.semClientes}</p>
                ) : (
                  <ul className="vendas-lista consignado-clientes-lista">
                    {clientes.map((c) => (
                      <li key={c.id}>
                        <FotoDoCliente ponte={ponte} hash={c.fotoHash} rotulo={c.nome} pequena />
                        <span>{c.nome}</span>
                        <Botao
                          onClick={() => {
                            setTrocandoCliente(false);
                            void definir({ clienteId: c.id });
                          }}
                        >
                          {T.escolherCliente}
                        </Botao>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </section>

          <section className="consignado-linha-de-campos">
            <Campo
              rotulo={T.cobranca}
              type="date"
              value={atual.dataCobranca}
              onChange={(e) => void definir({ dataCobranca: e.target.value })}
            />
            <div className="cs-campo">
              <label htmlFor="consignado-maleta">{T.maleta}</label>
              <select
                id="consignado-maleta"
                value={atual.remessaId ?? ''}
                onChange={(e) => void definir({ remessaId: e.target.value || null })}
              >
                <option value="">{T.escolherMaleta}</option>
                {atual.maletas.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.codigo}
                  </option>
                ))}
              </select>
            </div>
          </section>
          {atual.maletas.length === 0 ? <Aviso tipo="atencao">{T.semMaletas}</Aviso> : null}

          <LeitorDeCodigo
            rotulo={T.lerCodigo}
            botao={T.lerAgora}
            aoLer={ler}
            desabilitado={!atual.remessaId}
          />
          {mensagem ? <Aviso tipo={mensagem.tipo}>{mensagem.texto}</Aviso> : null}

          <table className="cs-tabela consignado-tabela">
            <caption>{T.legenda}</caption>
            <thead>
              <tr>
                <th scope="col">{T.colunas.ref}</th>
                <th scope="col">{T.colunas.produto}</th>
                <th scope="col">{T.colunas.tamTon}</th>
                <th scope="col">{T.colunas.maleta}</th>
                <th scope="col" className="cs-numero">
                  {T.colunas.qtd}
                </th>
                <th scope="col" className="cs-numero">
                  {T.colunas.preco}
                </th>
                <th scope="col" className="cs-numero">
                  {T.colunas.subTotal}
                </th>
                <th scope="col">{T.colunas.acoes}</th>
              </tr>
            </thead>
            <tbody>
              {atual.itens.length === 0 ? (
                <tr>
                  <td colSpan={8}>{T.semPecas}</td>
                </tr>
              ) : (
                atual.itens.map((i) => (
                  <tr key={i.id}>
                    <td>{i.codigo ?? ''}</td>
                    <td>
                      <span className="consignado-peca">
                        <FotoDoProduto ponte={ponte} hash={i.fotoHash} nome={i.nome} />
                        {i.nome}
                      </span>
                    </td>
                    <td>{i.tamTon ?? ''}</td>
                    <td>{i.maleta}</td>
                    <td className="cs-numero">{i.quantidade}</td>
                    <td className="cs-numero">{reais(i.precoUnitario)}</td>
                    <td className="cs-numero">{reais(i.subTotal)}</td>
                    <td>
                      <Botao
                        aria-label={T.tirarUmaDe(i.nome)}
                        onClick={() => void diminuir(i.id, i.quantidade - 1)}
                      >
                        {T.menos}
                      </Botao>{' '}
                      <Botao
                        variante="perigoso"
                        aria-label={T.removerDe(i.nome)}
                        onClick={() => void diminuir(i.id, 0)}
                      >
                        {T.remover}
                      </Botao>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>

          <p className="vendas-total">
            {T.totalItens}: <strong>{atual.totalDeItens}</strong> · {T.totalDx}:{' '}
            <Dinheiro reais={atual.totalDx} cotacao={cotacao.cotacao} />
          </p>

          <RodapeDeAcoes
            cancelar={
              <Botao variante="perigoso" onClick={() => setDescartando(true)}>
                {T.descartar}
              </Botao>
            }
            principal={
              <Botao
                variante="primario"
                grande
                disabled={!podeFinalizar}
                onClick={() => setConfirmando(true)}
              >
                {T.finalizar}
              </Botao>
            }
          />
        </>
      ) : null}

      {confirmando && atual ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarTitulo}
          rotuloConfirmar={T.confirmarBotao}
          aoConfirmar={() => void finalizar()}
          aoCancelar={() => setConfirmando(false)}
        >
          {T.confirmarTexto(atual.totalDeItens, reais(atual.totalDx))}
        </JanelaDeConfirmacao>
      ) : null}
      {descartando ? (
        <JanelaDeConfirmacao
          titulo={T.descartarTitulo}
          rotuloConfirmar={T.descartar}
          perigosa
          aoConfirmar={() => void descartar()}
          aoCancelar={() => setDescartando(false)}
        >
          {T.descartarTexto}
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}

function centavosPositivos(texto: string): boolean {
  return /[1-9]/.test(texto.replace('.', ''));
}
