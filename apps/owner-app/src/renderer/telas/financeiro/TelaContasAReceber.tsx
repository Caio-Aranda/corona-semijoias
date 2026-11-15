import { Aviso, Tabela } from '@corona/ui';
import { useState } from 'react';
import {
  NIVEIS_DA_CONTA_A_RECEBER,
  SITUACOES_DA_CONTA_A_RECEBER,
} from '../../../shared/financeiro';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { RodapeDaCotacao } from '../../nucleo/cotacao';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha, useVendedores } from '../devolucoes/Apoio';
import { ValorDoDia } from './Valor';
import { lerContasAReceber } from './recebimentos';

const T = TEXTOS_DE_FINANCEIRO.contasAReceber;

/** Financeiro → Contas a receber: o que os clientes devem aos vendedores e o que os vendedores devem à loja (só consulta). */
export function TelaContasAReceber({ ponte }: { ponte: PonteDoCorona }) {
  const [vendedorId, setVendedorId] = useState('');
  const [situacao, setSituacao] = useState('');
  const [nivel, setNivel] = useState('');
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () =>
      chamar(ponte, 'contasAReceber.listar', {
        ...(vendedorId ? { vendedorId } : {}),
        ...(situacao ? { situacao } : {}),
        ...(nivel ? { nivel } : {}),
      }),
    `contas-a-receber-${vendedorId}-${situacao}-${nivel}`,
  );
  const contas = busca.dados ? lerContasAReceber(busca.dados) : null;

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="filtro-receber-vendedor"
          rotulo={T.filtros.vendedor}
          valor={vendedorId}
          aoMudar={setVendedorId}
          opcoes={vendedores.opcoes}
          vazio={T.filtros.todosVendedores}
        />
        <CampoDeEscolha
          id="filtro-receber-nivel"
          rotulo={T.filtros.nivel}
          valor={nivel}
          aoMudar={setNivel}
          opcoes={NIVEIS_DA_CONTA_A_RECEBER.map((n) => ({ valor: n, texto: T.niveis[n] ?? n }))}
          vazio={T.filtros.todosNiveis}
        />
        <CampoDeEscolha
          id="filtro-receber-situacao"
          rotulo={T.filtros.situacao}
          valor={situacao}
          aoMudar={setSituacao}
          opcoes={SITUACOES_DA_CONTA_A_RECEBER.map((s) => ({
            valor: s,
            texto: T.situacoes[s] ?? s,
          }))}
          vazio={T.filtros.todasSituacoes}
        />
      </div>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {busca.dados && !contas ? <Aviso tipo="erro">{T.naoLeu}</Aviso> : null}
      {contas ? (
        <>
          <Tabela
            legenda={T.legenda}
            textoVazio={T.vazia}
            colunas={[
              { chave: 'vendedor', titulo: T.colunas.vendedor },
              { chave: 'cliente', titulo: T.colunas.cliente },
              { chave: 'nivel', titulo: T.colunas.nivel },
              { chave: 'parcela', titulo: T.colunas.parcela },
              { chave: 'vencimento', titulo: T.colunas.vencimento },
              { chave: 'valor', titulo: T.colunas.valor, numerica: true },
              { chave: 'jaPago', titulo: T.colunas.jaPago, numerica: true },
              { chave: 'emAberto', titulo: T.colunas.emAberto, numerica: true },
              { chave: 'situacao', titulo: T.colunas.situacao },
            ]}
            linhas={contas.map((c) => ({
              vendedor: c.vendedorNome,
              cliente: c.clienteNome || T.semCliente,
              nivel: T.niveis[c.nivel] ?? c.nivel,
              parcela: T.parcela(c.numero, c.totalDeParcelas),
              vencimento: dataParaTela(c.vencimento),
              valor: <ValorDoDia valor={c.valor} />,
              jaPago: <ValorDoDia valor={c.valorPago} />,
              emAberto: <ValorDoDia valor={c.emAberto} />,
              situacao: c.vencida ? T.vencida : (T.situacoes[c.status] ?? c.status),
            }))}
          />
          <RodapeDaCotacao />
        </>
      ) : null}
    </div>
  );
}
