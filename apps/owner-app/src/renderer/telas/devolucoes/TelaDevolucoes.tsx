import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { dataEHora } from '../sincronizacao/dados';
import {
  CampoDeEscolha,
  ConfirmarAcao,
  novoId,
  reais,
  RodapeDaCotacao,
  useVendedores,
  ValorDoLancamentoNaTela,
} from './Apoio';
import { lerDevolucoes, resumoDasPecas, type Devolucao } from './dados';
import { RegistrarDevolucao } from './RegistrarDevolucao';

const T = TEXTOS_DE_DEVOLUCOES;
const R = T.revisar;
const L = T.lista;

type Acao =
  | { tipo: 'manter' | 'naoAceitar'; devolucao: Devolucao }
  | { tipo: 'cancelar'; devolucao: Devolucao; cancelamentoId: string };

/**
 * Devoluções para revisar (Manter ou Não aceitar; ADR-0014, item 5) e a lista de todas as devoluções, onde a dona
 * também registra uma devolução e cancela uma (ADR-0014, itens 11 e 13).
 */
export function TelaDevolucoes({
  ponte,
  modo,
}: {
  ponte: PonteDoCorona;
  modo: 'revisar' | 'todas';
}) {
  const revisando = modo === 'revisar';
  const [situacao, setSituacao] = useState(revisando ? 'EM_REVISAO' : 'ACEITA');
  const [vendedorId, setVendedorId] = useState('');
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () => chamar(ponte, 'devolucoes.listar', { situacao, vendedorId }),
    `devolucoes-${modo}-${situacao}-${vendedorId}`,
  );
  const [acao, setAcao] = useState<Acao | null>(null);
  const [registrando, setRegistrando] = useState(false);
  const [feito, setFeito] = useState<string | null>(null);
  const devolucoes = busca.dados ? lerDevolucoes(busca.dados) : [];

  function terminou(texto: string) {
    setAcao(null);
    setFeito(texto);
    busca.recarregar();
  }

  if (registrando) {
    return (
      <RegistrarDevolucao
        ponte={ponte}
        aoVoltar={() => setRegistrando(false)}
        aoRegistrar={(texto) => {
          setRegistrando(false);
          setFeito(texto);
          busca.recarregar();
        }}
      />
    );
  }

  const colunas = revisando
    ? [
        { chave: 'quando', titulo: R.colunas.devolvidaEm },
        { chave: 'vendedor', titulo: R.colunas.vendedor },
        { chave: 'cliente', titulo: R.colunas.cliente },
        { chave: 'valor', titulo: R.colunas.valor, numerica: true },
        { chave: 'porQue', titulo: R.colunas.porQue },
        { chave: 'acoes', titulo: R.colunas.acoes },
      ]
    : [
        { chave: 'quando', titulo: L.colunas.devolvidaEm },
        { chave: 'vendedor', titulo: L.colunas.vendedor },
        { chave: 'cliente', titulo: L.colunas.cliente },
        { chave: 'pecas', titulo: L.colunas.pecas },
        { chave: 'motivo', titulo: L.colunas.motivo },
        { chave: 'valor', titulo: L.colunas.valor, numerica: true },
        { chave: 'voltou', titulo: L.colunas.voltou },
        { chave: 'situacao', titulo: L.colunas.situacao },
        { chave: 'acoes', titulo: L.colunas.acoes },
      ];

  function acoesDe(d: Devolucao) {
    const quem = vendedores.nomeDe(d.vendedorId);
    if (revisando) {
      return (
        <div className="gestao-acoes-da-linha">
          <Botao
            aria-label={R.manterDe(quem)}
            onClick={() => setAcao({ tipo: 'manter', devolucao: d })}
          >
            {R.manter}
          </Botao>
          <Botao
            aria-label={R.naoAceitarDe(quem)}
            onClick={() => setAcao({ tipo: 'naoAceitar', devolucao: d })}
          >
            {R.naoAceitar}
          </Botao>
        </div>
      );
    }
    if (d.situacao !== 'ACEITA' && d.situacao !== 'EM_REVISAO') return '';
    return (
      <Botao
        aria-label={L.cancelarDe(d.clienteNome)}
        onClick={() => setAcao({ tipo: 'cancelar', devolucao: d, cancelamentoId: novoId() })}
      >
        {L.cancelar}
      </Botao>
    );
  }

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{revisando ? R.titulo : L.titulo}</h1>
      <p className="gestao-texto">{revisando ? R.ajuda : L.ajuda}</p>
      {!revisando ? (
        <div className="gestao-filtros">
          <CampoDeEscolha
            id="devolucoes-situacao"
            rotulo={T.situacao}
            valor={situacao}
            aoMudar={(v) => {
              setSituacao(v);
              setFeito(null);
            }}
            opcoes={L.situacoes}
          />
          <CampoDeEscolha
            id="devolucoes-vendedor"
            rotulo={T.vendedor}
            valor={vendedorId}
            aoMudar={setVendedorId}
            opcoes={vendedores.opcoes}
            vazio={T.todosOsVendedores}
          />
          <Botao variante="primario" onClick={() => setRegistrando(true)}>
            {L.registrar}
          </Botao>
        </div>
      ) : null}
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={revisando ? R.legenda : L.legenda}
        textoVazio={revisando ? R.vazio : L.vazio}
        colunas={colunas}
        linhas={devolucoes.map((d) => ({
          quando: dataEHora(d.devolvidaEm),
          vendedor: vendedores.nomeDe(d.vendedorId),
          cliente: d.clienteNome,
          pecas: resumoDasPecas(d.itens),
          motivo: T.motivos[d.motivo] ?? '',
          valor: <ValorDoLancamentoNaTela valor={d.valorDevolvido} />,
          porQue: d.motivosRevisao
            .map((m) => R.porQue[m] ?? '')
            .filter(Boolean)
            .join('; '),
          voltou: (
            <>
              {d.valorReembolsado && Number(d.valorReembolsado) > 0 ? (
                <div>{L.emDinheiro(reais(d.valorReembolsado))}</div>
              ) : null}
              {d.valorEmCredito && Number(d.valorEmCredito) > 0 ? (
                <div>{L.emCredito(reais(d.valorEmCredito))}</div>
              ) : null}
            </>
          ),
          situacao: L.situacaoDaDevolucao[d.situacao] ?? '',
          acoes: acoesDe(d),
        }))}
      />
      <RodapeDaCotacao />
      {acao?.tipo === 'manter' ? (
        <ConfirmarAcao
          titulo={R.confirmarManter.titulo}
          rotuloConfirmar={R.manter}
          executar={() => chamar(ponte, 'devolucoes.manter', { id: acao.devolucao.id })}
          aoFeito={() => terminou(R.mantida)}
          aoCancelar={() => setAcao(null)}
        >
          <p>{R.confirmarManter.texto}</p>
        </ConfirmarAcao>
      ) : null}
      {acao?.tipo === 'naoAceitar' ? (
        <PedirMotivo
          titulo={R.confirmarNaoAceitar.titulo}
          texto={R.confirmarNaoAceitar.texto}
          rotuloDoMotivo={R.confirmarNaoAceitar.rotuloDoMotivo}
          avisoDoMotivo={R.confirmarNaoAceitar.avisoDoMotivo}
          rotuloConfirmar={R.naoAceitar}
          rotuloCancelar={T.voltar}
          executar={(motivo) =>
            chamar(ponte, 'devolucoes.naoAceitar', { id: acao.devolucao.id, motivo })
          }
          aoFeito={() => terminou(R.naoAceita)}
          aoCancelar={() => setAcao(null)}
        />
      ) : null}
      {acao?.tipo === 'cancelar' ? (
        <PedirMotivo
          titulo={L.confirmarCancelar.titulo}
          texto={L.confirmarCancelar.texto}
          rotuloDoMotivo={L.confirmarCancelar.rotuloDoMotivo}
          avisoDoMotivo={L.confirmarCancelar.avisoDoMotivo}
          rotuloConfirmar={L.cancelar}
          rotuloCancelar={T.voltar}
          executar={(motivo) =>
            chamar(ponte, 'devolucoes.cancelar', {
              id: acao.devolucao.id,
              cancelamentoId: acao.cancelamentoId,
              motivo,
            })
          }
          aoFeito={() => terminou(L.cancelada)}
          aoCancelar={() => setAcao(null)}
        />
      ) : null}
    </div>
  );
}
