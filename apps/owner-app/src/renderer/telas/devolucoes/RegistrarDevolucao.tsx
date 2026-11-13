import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { CorpoDaDevolucao } from '../../../shared/devolucoes';
import { MAXIMO_DO_TEXTO_DA_DEVOLUCAO } from '../../../shared/devolucoes';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { dataEHora } from '../sincronizacao/dados';
import { CampoDeEscolha, novoId, reais } from './Apoio';
import { lerVendasComAPeca, type VendaComAPeca } from './dados';

const T = TEXTOS_DE_DEVOLUCOES;
const R = T.registrar;

const MOTIVOS = ['ARREPENDIMENTO', 'DEFEITO', 'TAMANHO', 'OUTRO'] as const;
const FORMAS = ['DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE'] as const;
const ESTADOS = ['BOA', 'DEFEITO'] as const;

const opcoesDe = (nomes: Record<string, string>, chaves: readonly string[]) =>
  chaves.map((k) => ({ valor: k, texto: nomes[k] ?? k }));

/** Quantas peças voltam: número inteiro de 0 até o que ainda pode voltar; qualquer outra coisa é `null`. */
function quantasVoltam(digitado: string | undefined, maximo: number): number | null {
  const t = (digitado ?? '').trim();
  if (t === '') return 0;
  if (!/^\d{1,6}$/.test(t)) return null;
  const n = Number(t);
  return n <= maximo ? n : null;
}

/**
 * A dona registra a devolução de um cliente (ADR-0014, item 13): acha a venda pelo código da peça, escolhe quantas
 * peças voltam e em que estado, o motivo e como o dinheiro foi devolvido. A tela nunca manda valor: o servidor calcula.
 * A peça vai para a loja.
 */
export function RegistrarDevolucao({
  ponte,
  aoVoltar,
  aoRegistrar,
}: {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  /** Recebe o texto de "Devolução registrada." para a lista mostrar. */
  aoRegistrar: (texto: string) => void;
}) {
  const [codigo, setCodigo] = useState('');
  const [buscando, setBuscando] = useState(false);
  const [achadas, setAchadas] = useState<{ achouAPeca: boolean; vendas: VendaComAPeca[] } | null>(
    null,
  );
  const [venda, setVenda] = useState<VendaComAPeca | null>(null);
  const [quantidades, setQuantidades] = useState<Record<string, string>>({});
  const [estados, setEstados] = useState<Record<string, string>>({});
  const [motivo, setMotivo] = useState('');
  const [observacao, setObservacao] = useState('');
  const [forma, setForma] = useState('');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);
  // Um id por devolução: se o envio falhar no meio e a dona tentar de novo, o servidor reconhece a mesma devolução.
  const [idDaDevolucao] = useState(novoId);

  async function buscar() {
    if (trava.current || codigo.trim() === '') return;
    trava.current = true;
    setBuscando(true);
    setErro(null);
    setVenda(null);
    const r = await chamar(ponte, 'devolucoes.vendasComAPeca', { codigo: codigo.trim() });
    trava.current = false;
    setBuscando(false);
    if (!r.ok) {
      setErro(r.erro);
      setAchadas(null);
      return;
    }
    setAchadas(lerVendasComAPeca(r.dados));
  }

  function escolher(v: VendaComAPeca) {
    setVenda(v);
    setQuantidades({});
    setEstados({});
    setErro(null);
    setTentou(false);
  }

  const defeito = motivo === 'DEFEITO';
  const estadoDe = (itemVendaId: string) => (defeito ? 'DEFEITO' : (estados[itemVendaId] ?? 'BOA'));

  async function registrar() {
    if (!venda || trava.current) return;
    setTentou(true);
    const linhas = venda.itens.map((l) => ({
      l,
      n: quantasVoltam(quantidades[l.itemVendaId], l.podemVoltar),
    }));
    const escolhidas = linhas.filter((x) => x.n !== null && x.n > 0);
    const observacaoFalta = motivo === 'OUTRO' && observacao.trim() === '';
    if (
      motivo === '' ||
      forma === '' ||
      linhas.some((x) => x.n === null) ||
      escolhidas.length === 0 ||
      observacaoFalta
    ) {
      return;
    }
    const corpo: CorpoDaDevolucao = {
      id: idDaDevolucao,
      vendaId: venda.vendaId,
      motivo: motivo as CorpoDaDevolucao['motivo'],
      ...(observacao.trim() !== '' ? { observacao: observacao.trim() } : {}),
      formaReembolso: forma as CorpoDaDevolucao['formaReembolso'],
      itens: escolhidas.map((x) => ({
        produtoId: x.l.produtoId,
        quantidade: x.n as number,
        estado: estadoDe(x.l.itemVendaId) as CorpoDaDevolucao['itens'][number]['estado'],
      })),
    };
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'devolucoes.registrar', { corpo });
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(
        r.erro.codigo === 'GARANTIA_VENCIDA' ? { ...r.erro, mensagem: R.foraDoPrazo } : r.erro,
      );
      return;
    }
    aoRegistrar(r.jaRegistrado ? R.jaRegistrada : R.registrada);
  }

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{R.titulo}</h1>
      <p className="gestao-texto">{R.ajuda}</p>
      <div className="gestao-filtros">
        <Campo
          rotulo={R.codigo}
          value={codigo}
          autoComplete="off"
          maxLength={100}
          onChange={(e) => setCodigo(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              void buscar();
            }
          }}
        />
        <Botao
          variante="primario"
          disabled={buscando || codigo.trim() === ''}
          onClick={() => void buscar()}
        >
          {buscando ? R.buscando : R.buscar}
        </Botao>
        <Botao onClick={aoVoltar}>{T.voltar}</Botao>
      </div>
      {erro && !venda ? <AvisoDeErro erro={erro} /> : null}
      {achadas && !achadas.achouAPeca ? <Aviso tipo="atencao">{R.pecaNaoAchada}</Aviso> : null}
      {achadas && achadas.achouAPeca && achadas.vendas.length === 0 ? (
        <Aviso tipo="atencao">{R.semVendas}</Aviso>
      ) : null}
      {achadas && !venda && achadas.vendas.length > 0 ? (
        <section aria-labelledby="titulo-vendas-com-a-peca">
          <h2 id="titulo-vendas-com-a-peca" className="gestao-subtitulo">
            {R.vendasTitulo}
          </h2>
          <Tabela
            legenda={R.legendaDasVendas}
            colunas={[
              { chave: 'vendidaEm', titulo: R.colunasDasVendas.vendidaEm },
              { chave: 'cliente', titulo: R.colunasDasVendas.cliente },
              { chave: 'total', titulo: R.colunasDasVendas.total, numerica: true },
              { chave: 'garantia', titulo: R.colunasDasVendas.garantia },
              { chave: 'acoes', titulo: R.colunasDasVendas.acoes },
            ]}
            linhas={achadas.vendas.map((v) => ({
              vendidaEm: dataEHora(v.vendidaEm),
              cliente: v.clienteNome,
              total: reais(v.total),
              garantia: R.garantia(
                v.prazoArrependimentoAte ? dataParaTela(v.prazoArrependimentoAte) : '',
                v.prazoDefeitoAte ? dataParaTela(v.prazoDefeitoAte) : '',
              ),
              acoes: v.aPrazo ? (
                R.aPrazo
              ) : (
                <Botao aria-label={R.escolherDe(v.clienteNome)} onClick={() => escolher(v)}>
                  {R.escolher}
                </Botao>
              ),
            }))}
          />
        </section>
      ) : null}
      {venda ? (
        <section aria-labelledby="titulo-pecas-da-venda" className="gestao-formulario">
          <h2 id="titulo-pecas-da-venda" className="gestao-subtitulo">
            {R.pecasTitulo}
          </h2>
          <Tabela
            legenda={R.legendaDasPecas}
            colunas={[
              { chave: 'peca', titulo: R.colunasDasPecas.peca },
              { chave: 'vendidas', titulo: R.colunasDasPecas.vendidas, numerica: true },
              { chave: 'devolvidas', titulo: R.colunasDasPecas.devolvidas, numerica: true },
              { chave: 'podem', titulo: R.colunasDasPecas.podemVoltar, numerica: true },
              { chave: 'quantas', titulo: R.colunasDasPecas.quantas },
              { chave: 'estado', titulo: R.colunasDasPecas.estado },
            ]}
            linhas={venda.itens.map((l) => {
              const invalida =
                tentou && quantasVoltam(quantidades[l.itemVendaId], l.podemVoltar) === null;
              return {
                peca: l.nome,
                vendidas: l.vendidas,
                devolvidas: l.devolvidas,
                podem: l.podemVoltar,
                quantas:
                  l.podemVoltar > 0 ? (
                    <input
                      type="text"
                      inputMode="numeric"
                      size={6}
                      aria-label={R.quantasDe(l.nome)}
                      aria-invalid={invalida}
                      value={quantidades[l.itemVendaId] ?? ''}
                      onChange={(e) =>
                        setQuantidades({ ...quantidades, [l.itemVendaId]: e.target.value })
                      }
                    />
                  ) : (
                    ''
                  ),
                estado:
                  l.podemVoltar > 0 ? (
                    <select
                      aria-label={R.estadoDe(l.nome)}
                      value={estadoDe(l.itemVendaId)}
                      disabled={defeito}
                      onChange={(e) => setEstados({ ...estados, [l.itemVendaId]: e.target.value })}
                    >
                      {ESTADOS.map((s) => (
                        <option key={s} value={s}>
                          {T.estados[s]}
                        </option>
                      ))}
                    </select>
                  ) : (
                    ''
                  ),
              };
            })}
          />
          {tentou &&
          venda.itens.every(
            (l) => (quantasVoltam(quantidades[l.itemVendaId], l.podemVoltar) ?? 0) === 0,
          ) ? (
            <Aviso tipo="atencao">{R.nenhumaPeca}</Aviso>
          ) : null}
          <CampoDeEscolha
            id="registrar-motivo"
            rotulo={R.motivo}
            valor={motivo}
            aoMudar={setMotivo}
            opcoes={opcoesDe(T.motivos, MOTIVOS)}
            vazio={R.escolhaMotivo}
          />
          {tentou && motivo === '' ? <Aviso tipo="atencao">{R.escolhaMotivo}.</Aviso> : null}
          <Campo
            rotulo={R.observacao}
            value={observacao}
            autoComplete="off"
            maxLength={MAXIMO_DO_TEXTO_DA_DEVOLUCAO}
            onChange={(e) => setObservacao(e.target.value)}
            {...(tentou && motivo === 'OUTRO' && observacao.trim() === ''
              ? { erro: R.observacaoObrigatoria }
              : {})}
          />
          <CampoDeEscolha
            id="registrar-forma"
            rotulo={R.forma}
            valor={forma}
            aoMudar={setForma}
            opcoes={opcoesDe(T.formas, FORMAS)}
            vazio={R.escolhaForma}
          />
          {tentou && forma === '' ? <Aviso tipo="atencao">{R.escolhaForma}.</Aviso> : null}
          <Aviso tipo="informacao">{R.avisoDaLoja}</Aviso>
          {erro ? <AvisoDeErro erro={erro} /> : null}
          <div className="gestao-acoes-da-linha">
            <Botao variante="primario" disabled={ocupado} onClick={() => void registrar()}>
              {ocupado ? R.registrando : R.registrarBotao}
            </Botao>
            <Botao disabled={ocupado} onClick={() => setVenda(null)}>
              {R.outraVenda}
            </Botao>
          </div>
        </section>
      ) : null}
    </div>
  );
}
