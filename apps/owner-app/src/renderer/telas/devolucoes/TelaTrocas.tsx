import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { centavosDeTexto } from '../../nucleo/moedas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { dataEHora } from '../sincronizacao/dados';
import {
  CampoDeEscolha,
  novoId,
  reais,
  RodapeDaCotacao,
  useVendedores,
  ValorDoLancamentoNaTela,
} from './Apoio';
import { lerTrocas, resumoDasPecas, type Troca } from './dados';

const T = TEXTOS_DE_DEVOLUCOES;
const L = T.trocas;

/** O texto da diferença: a pagar, de crédito ou nenhuma (glossário, parte 6). */
function diferencaEmTexto(t: Troca) {
  const c = t.diferenca.real === null ? null : centavosDeTexto(t.diferenca.real);
  if (c === null) return '';
  if (c === 0n) return L.semDiferenca;
  const modulo = c < 0n ? t.diferenca.real?.replace('-', '') : t.diferenca.real;
  const valor = reais(modulo ?? null);
  return (
    <div>
      <div>{c > 0n ? L.diferencaAPagar(valor) : L.ficaDeCredito(valor)}</div>
      <ValorDoLancamentoNaTela
        valor={{
          real: modulo ?? null,
          dolar: t.diferenca.dolar?.replace('-', '') ?? null,
          guarani: t.diferenca.guarani?.replace('-', '') ?? null,
        }}
      />
    </div>
  );
}

/** Trocas dos vendedores: a dona vê e cancela (ADR-0014, item 11: "Cancelar troca" a qualquer hora, com motivo). */
export function TelaTrocas({ ponte }: { ponte: PonteDoCorona }) {
  const [situacao, setSituacao] = useState('ATIVA');
  const [vendedorId, setVendedorId] = useState('');
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () => chamar(ponte, 'trocas.listar', { situacao, vendedorId }),
    `trocas-${situacao}-${vendedorId}`,
  );
  const [cancelando, setCancelando] = useState<{ troca: Troca; cancelamentoId: string } | null>(
    null,
  );
  const [feito, setFeito] = useState<string | null>(null);
  const trocas = busca.dados ? lerTrocas(busca.dados) : [];

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{L.titulo}</h1>
      <p className="gestao-texto">{L.ajuda}</p>
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="trocas-situacao"
          rotulo={T.situacao}
          valor={situacao}
          aoMudar={(v) => {
            setSituacao(v);
            setFeito(null);
          }}
          opcoes={L.situacoes}
        />
        <CampoDeEscolha
          id="trocas-vendedor"
          rotulo={T.vendedor}
          valor={vendedorId}
          aoMudar={setVendedorId}
          opcoes={vendedores.opcoes}
          vazio={T.todosOsVendedores}
        />
      </div>
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={L.legenda}
        textoVazio={L.vazio}
        colunas={[
          { chave: 'quando', titulo: L.colunas.trocadaEm },
          { chave: 'vendedor', titulo: L.colunas.vendedor },
          { chave: 'cliente', titulo: L.colunas.cliente },
          { chave: 'voltaram', titulo: L.colunas.voltaram },
          { chave: 'sairam', titulo: L.colunas.sairam },
          { chave: 'diferenca', titulo: L.colunas.diferenca },
          { chave: 'situacao', titulo: L.colunas.situacao },
          { chave: 'acoes', titulo: L.colunas.acoes },
        ]}
        linhas={trocas.map((t) => ({
          quando: dataEHora(t.trocadaEm),
          vendedor: vendedores.nomeDe(t.vendedorId),
          cliente: t.clienteNome,
          voltaram: resumoDasPecas(t.voltaram),
          sairam: resumoDasPecas(t.sairam),
          diferenca: diferencaEmTexto(t),
          situacao: L.situacaoDaTroca[t.situacao] ?? '',
          acoes:
            t.situacao === 'ATIVA' ? (
              <Botao
                aria-label={L.cancelarDe(t.clienteNome)}
                onClick={() => setCancelando({ troca: t, cancelamentoId: novoId() })}
              >
                {L.cancelar}
              </Botao>
            ) : (
              ''
            ),
        }))}
      />
      <RodapeDaCotacao />
      {cancelando ? (
        <PedirMotivo
          titulo={L.confirmarCancelar.titulo}
          texto={L.confirmarCancelar.texto}
          rotuloDoMotivo={L.confirmarCancelar.rotuloDoMotivo}
          avisoDoMotivo={L.confirmarCancelar.avisoDoMotivo}
          rotuloConfirmar={L.cancelar}
          rotuloCancelar={T.voltar}
          executar={(motivo) =>
            chamar(ponte, 'trocas.cancelar', {
              id: cancelando.troca.id,
              cancelamentoId: cancelando.cancelamentoId,
              motivo,
            })
          }
          aoFeito={() => {
            setCancelando(null);
            setFeito(L.cancelada);
            busca.recarregar();
          }}
          aoCancelar={() => setCancelando(null)}
        />
      ) : null}
    </div>
  );
}
