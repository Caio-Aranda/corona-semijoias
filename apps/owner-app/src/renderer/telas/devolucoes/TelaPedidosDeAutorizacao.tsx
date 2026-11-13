import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { dataEHora } from '../sincronizacao/dados';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { CampoDeEscolha, ConfirmarAcao, RodapeDaCotacao, ValorDoLancamentoNaTela } from './Apoio';
import { lerAutorizacoes, resumoDasPecas, type Autorizacao } from './dados';

const T = TEXTOS_DE_DEVOLUCOES.autorizar;
const MOTIVOS = TEXTOS_DE_DEVOLUCOES.motivos;

/** Pedidos de autorização de devolução: a dona aprova o reembolso (vale 7 dias) ou nega com motivo (ADR-0014, item 6). */
export function TelaPedidosDeAutorizacao({ ponte }: { ponte: PonteDoCorona }) {
  const [situacao, setSituacao] = useState('PEDIDA');
  const busca = useBusca(
    () => chamar(ponte, 'devolucoes.autorizacoes', { situacao }),
    `autorizacoes-${situacao}`,
  );
  const [aprovando, setAprovando] = useState<Autorizacao | null>(null);
  const [negando, setNegando] = useState<Autorizacao | null>(null);
  const [feito, setFeito] = useState<string | null>(null);
  const pedidos = busca.dados ? lerAutorizacoes(busca.dados) : [];

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="autorizacoes-situacao"
          rotulo={TEXTOS_DE_DEVOLUCOES.situacao}
          valor={situacao}
          aoMudar={(v) => {
            setSituacao(v);
            setFeito(null);
          }}
          opcoes={T.situacoes}
        />
      </div>
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazio}
        colunas={[
          { chave: 'pedidoEm', titulo: T.colunas.pedidoEm },
          { chave: 'vendedor', titulo: T.colunas.vendedor },
          { chave: 'cliente', titulo: T.colunas.cliente },
          { chave: 'pecas', titulo: T.colunas.pecas },
          { chave: 'valor', titulo: T.colunas.valor, numerica: true },
          { chave: 'porQue', titulo: T.colunas.porQue },
          { chave: 'motivo', titulo: T.colunas.motivo },
          { chave: 'garantia', titulo: T.colunas.garantiaAte },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={pedidos.map((p) => ({
          pedidoEm: dataEHora(p.pedidaEm),
          vendedor: p.vendedorNome,
          cliente: p.clienteNome,
          pecas: resumoDasPecas(p.itens),
          valor: <ValorDoLancamentoNaTela valor={p.valorPedido} />,
          porQue: T.porQue[p.tipo] ?? '',
          motivo: MOTIVOS[p.motivo] ?? '',
          garantia: p.prazoDaGarantiaAte ? dataParaTela(p.prazoDaGarantiaAte) : '',
          acoes:
            p.situacao === 'PEDIDA' ? (
              <div className="gestao-acoes-da-linha">
                <Botao aria-label={T.aprovarDe(p.vendedorNome)} onClick={() => setAprovando(p)}>
                  {T.aprovar}
                </Botao>
                <Botao aria-label={T.negarDe(p.vendedorNome)} onClick={() => setNegando(p)}>
                  {T.negar}
                </Botao>
              </div>
            ) : p.situacao === 'APROVADA' && p.validaAte ? (
              T.valeAte(dataParaTela(p.validaAte))
            ) : (
              ''
            ),
        }))}
      />
      <RodapeDaCotacao />
      {aprovando ? (
        <ConfirmarAcao
          titulo={T.confirmarAprovar.titulo}
          rotuloConfirmar={T.aprovar}
          executar={() => chamar(ponte, 'devolucoes.aprovarAutorizacao', { id: aprovando.id })}
          aoFeito={(dados) => {
            const ate = lerAutorizacoes([dados])[0]?.validaAte;
            setFeito(T.aprovado(ate ? dataParaTela(ate) : ''));
            setAprovando(null);
            busca.recarregar();
          }}
          aoCancelar={() => setAprovando(null)}
        >
          <p>{T.confirmarAprovar.texto}</p>
        </ConfirmarAcao>
      ) : null}
      {negando ? (
        <PedirMotivo
          titulo={T.confirmarNegar.titulo}
          texto={T.confirmarNegar.texto}
          rotuloDoMotivo={T.confirmarNegar.rotuloDoMotivo}
          avisoDoMotivo={T.confirmarNegar.avisoDoMotivo}
          rotuloConfirmar={T.negar}
          rotuloCancelar={TEXTOS_DE_DEVOLUCOES.voltar}
          executar={(motivo) =>
            chamar(ponte, 'devolucoes.negarAutorizacao', { id: negando.id, motivo })
          }
          aoFeito={() => {
            setNegando(null);
            setFeito(T.negado);
            busca.recarregar();
          }}
          aoCancelar={() => setNegando(null)}
        />
      ) : null}
    </div>
  );
}
