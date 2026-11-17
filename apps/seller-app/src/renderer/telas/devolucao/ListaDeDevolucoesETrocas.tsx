import { Aviso, Botao } from '@corona/ui';
import { Campo, EstadoDaLista, JanelaDeConfirmacao, Tabela } from '../../componentes-do-vendedor';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AutorizacoesDoVendedor, DevolucaoOuTroca } from '../../../shared/dados';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, Dinheiro, novoId } from '../../chamadas';
import { AvisoDosDados } from '../../IndicadorDeEnvio';
import { centavosDeTexto, dataParaTela } from '../../moedas';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { diaEMes, reais, textoDeCentavos } from './apoio';

export interface ListaDeDevolucoesETrocasProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
}

/**
 * Minhas devoluções e trocas (tarefa 6.8): o filtro de período de Minhas vendas, a situação de cada uma ("Aguardando
 * envio" enquanto está só no computador; "Para revisão da dona" quando a dona vai olhar), o cancelar só no mesmo dia e
 * os pedidos de autorização. Cancelar cria um lançamento novo: nada é apagado.
 */
export function ListaDeDevolucoesETrocas({ ponte, aoVoltar }: ListaDeDevolucoesETrocasProps) {
  const T = TEXTOS.devolucao;
  const [de, setDe] = useState('');
  const [ate, setAte] = useState('');
  const [lista, setLista] = useState<DevolucaoOuTroca[] | null>(null);
  const [pedidos, setPedidos] = useState<AutorizacoesDoVendedor['pedidos']>([]);
  const [erro, setErro] = useState<string | null>(null);
  const [cancelando, setCancelando] = useState<DevolucaoOuTroca | null>(null);
  const [motivo, setMotivo] = useState('');
  const [erroDoMotivo, setErroDoMotivo] = useState<string | undefined>();
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<{ tipo: 'sucesso' | 'erro'; texto: string } | null>(null);
  // O mesmo cancelamento reenviado tem o mesmo id (uma janela aberta, um id).
  const idDoCancelamento = useRef(novoId());

  const carregar = useCallback(async () => {
    setErro(null);
    const [r, a] = await Promise.all([
      chamar<DevolucaoOuTroca[]>(ponte, {
        operacao: 'devolucoes.listar',
        ...(de ? { de } : {}),
        ...(ate ? { ate } : {}),
      }),
      chamar<AutorizacoesDoVendedor>(ponte, { operacao: 'autorizacoes.listar' }),
    ]);
    if (r.ok) setLista(r.dados);
    else setErro(r.mensagem);
    if (a.ok && Array.isArray(a.dados.pedidos)) setPedidos(a.dados.pedidos);
  }, [ponte, de, ate]);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  async function cancelar() {
    if (!cancelando) return;
    const ehTroca = cancelando.tipo === 'TROCA';
    if (!motivo.trim()) {
      setErroDoMotivo(ehTroca ? T.faltaMotivoTroca : T.faltaMotivoDevolucao);
      return;
    }
    setErroDoMotivo(undefined);
    setEnviando(true);
    const r = await chamar(ponte, {
      operacao: ehTroca ? 'trocas.cancelar' : 'devolucoes.cancelar',
      id: cancelando.id,
      cancelamentoId: idDoCancelamento.current,
      motivo: motivo.trim(),
    });
    setEnviando(false);
    setCancelando(null);
    setMotivo('');
    idDoCancelamento.current = novoId();
    setAviso(
      r.ok
        ? { tipo: 'sucesso', texto: ehTroca ? T.trocaCancelada : T.devolucaoCancelada }
        : { tipo: 'erro', texto: r.mensagem },
    );
    void carregar();
  }

  const situacao = (l: DevolucaoOuTroca) => (
    <>
      <span>{T.situacoes[l.situacao] ?? ''}</span>
      {l.situacao === 'EM_REVISAO' ? (
        <>
          <br />
          <small>{T.donaVaiOlhar}</small>
        </>
      ) : null}
    </>
  );

  const comoVoltou = (l: DevolucaoOuTroca) => {
    const partes: string[] = [];
    if ((centavosDeTexto(l.abatido) ?? 0n) !== 0n) partes.push(T.abatidoLinha(reais(l.abatido)));
    if ((centavosDeTexto(l.emDinheiro) ?? 0n) !== 0n)
      partes.push(T.emDinheiroLinha(reais(l.emDinheiro)));
    if ((centavosDeTexto(l.emCredito) ?? 0n) !== 0n)
      partes.push(T.emCreditoLinha(reais(l.emCredito)));
    const diferenca = l.diferenca === null ? null : (centavosDeTexto(l.diferenca) ?? 0n);
    return (
      <>
        {partes.map((p) => (
          <span key={p} className="vendas-linha">
            {p}
          </span>
        ))}
        {diferenca === null ? null : (
          <span className="vendas-linha">
            {diferenca > 0n
              ? T.diferencaAPagar(reais(l.diferenca ?? '0.00'))
              : diferenca < 0n
                ? T.ficaDeCredito(reais(textoDeCentavos(-diferenca)))
                : T.semDiferenca}
          </span>
        )}
      </>
    );
  };

  const acoes = (l: DevolucaoOuTroca) => {
    if (l.podeCancelar)
      return (
        <Botao
          variante="perigoso"
          onClick={() => {
            setAviso(null);
            setErroDoMotivo(undefined);
            setCancelando(l);
          }}
        >
          {l.tipo === 'TROCA' ? T.cancelarTroca : T.cancelarDevolucao}
        </Botao>
      );
    return ['AGUARDANDO_ENVIO', 'ACEITA', 'EM_REVISAO', 'FEITA'].includes(l.situacao) ? (
      <small>{T.soNoMesmoDia}</small>
    ) : null;
  };

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.minhas} aoVoltar={aoVoltar}>
      <AvisoDosDados ponte={ponte} />
      {aviso ? <Aviso tipo={aviso.tipo}>{aviso.texto}</Aviso> : null}
      <form
        className="vendas-leitor"
        onSubmit={(e) => {
          e.preventDefault();
          void carregar();
        }}
      >
        <Campo rotulo={T.de} type="date" value={de} onChange={(e) => setDe(e.target.value)} />
        <Campo rotulo={T.ate} type="date" value={ate} onChange={(e) => setAte(e.target.value)} />
        <Botao type="submit">{T.ver}</Botao>
      </form>
      <EstadoDaLista
        carregando={lista === null && erro === null}
        erro={erro}
        textoCarregando={T.textoCarregando}
        aoTentarDeNovo={() => void carregar()}
        rotuloTentarDeNovo={TEXTOS.tentarDeNovo}
      >
        {lista !== null ? (
          <Tabela
            legenda={T.minhas}
            textoVazio={T.vazia}
            colunas={[
              { chave: 'dia', titulo: T.colunasDaLista.dia },
              { chave: 'cliente', titulo: T.colunasDaLista.cliente },
              { chave: 'tipo', titulo: T.colunasDaLista.oQueFoi },
              { chave: 'pecas', titulo: T.colunasDaLista.pecas, numerica: true },
              { chave: 'valor', titulo: T.colunasDaLista.valor, numerica: true },
              { chave: 'como', titulo: T.colunasDaLista.comoVoltou },
              { chave: 'situacao', titulo: T.colunasDaLista.situacao },
              { chave: 'acoes', titulo: T.colunasDaLista.acoes },
            ]}
            linhas={lista.map((l) => ({
              dia: dataParaTela(l.em),
              cliente: l.clienteNome,
              tipo: T.tipos[l.tipo] ?? '',
              pecas: l.pecas,
              valor: (
                <Dinheiro
                  reais={l.valor}
                  cotacao={
                    l.cotacao ? { dolar: l.cotacao.dolar, guarani: l.cotacao.guarani } : null
                  }
                />
              ),
              como: comoVoltou(l),
              situacao: situacao(l),
              acoes: acoes(l),
            }))}
          />
        ) : null}
        <p className="vendas-texto vendas-texto--pequeno">{T.cotacaoDeCadaLancamento}</p>
      </EstadoDaLista>

      <h2 className="vendas-subtitulo">{T.pedidos}</h2>
      <Tabela
        legenda={T.pedidos}
        textoVazio={T.semPedidos}
        colunas={[
          { chave: 'em', titulo: T.colunasDosPedidos.em },
          { chave: 'cliente', titulo: T.colunasDosPedidos.cliente },
          { chave: 'pecas', titulo: T.colunasDosPedidos.pecas, numerica: true },
          { chave: 'situacao', titulo: T.colunasDosPedidos.situacao },
        ]}
        linhas={pedidos.map((p) => ({
          em: dataParaTela(p.pedidaEm),
          cliente: p.clienteNome,
          pecas: p.pecas,
          situacao:
            p.situacao === 'APROVADA'
              ? T.aprovadoAte(diaEMes(p.validaAte ?? ''))
              : (T.situacoesDoPedido[p.situacao] ?? ''),
        }))}
      />

      {cancelando ? (
        <JanelaDeConfirmacao
          titulo={cancelando.tipo === 'TROCA' ? T.cancelarTrocaTitulo : T.cancelarDevolucaoTitulo}
          rotuloConfirmar={cancelando.tipo === 'TROCA' ? T.cancelarTroca : T.cancelarDevolucao}
          rotuloCancelar={TEXTOS.voltar}
          perigosa
          ocupado={enviando}
          aoConfirmar={() => void cancelar()}
          aoCancelar={() => setCancelando(null)}
        >
          <p>{cancelando.tipo === 'TROCA' ? T.cancelarTrocaTexto : T.cancelarDevolucaoTexto}</p>
          <Campo
            rotulo={T.motivoDoCancelamento}
            value={motivo}
            maxLength={400}
            onChange={(e) => setMotivo(e.target.value)}
            {...(erroDoMotivo ? { erro: erroDoMotivo } : {})}
            autoComplete="off"
            autoFocus
          />
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}
