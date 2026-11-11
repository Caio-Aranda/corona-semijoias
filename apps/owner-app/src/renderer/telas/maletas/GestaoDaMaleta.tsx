import { Aviso, Botao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { imprimirNotaDaMaleta, NOTA_DA_MALETA_PRONTA } from '../../nucleo/nota-da-maleta';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { dataNaTela } from '../cadastros/definicoes';
import { Dinheiro } from './Dinheiro';
import { PedirMotivo } from './PedirMotivo';
import {
  lerMaleta,
  lerSaldoDaMaleta,
  type Maleta,
  type MovimentoDaMaleta,
  type SaldoDaMaleta,
} from './dados';

const T = TEXTOS_DE_MALETAS;
const F = T.ficha;

export interface GestaoDaMaletaProps {
  ponte: PonteDoCorona;
  id: string;
  aoVoltar: () => void;
  aoRepor: (maleta: Maleta) => void;
  aoDevolver: (maleta: Maleta, saldo: SaldoDaMaleta) => void;
}

/** Ficha da maleta enviada: peça a peça, movimentos em ordem e as ações (repor, devolver, cancelar). */
export function GestaoDaMaleta({ ponte, id, aoVoltar, aoRepor, aoDevolver }: GestaoDaMaletaProps) {
  const [versao, setVersao] = useState(0);
  const busca = useBusca(async () => {
    const [cabecalho, saldo] = await Promise.all([
      chamar(ponte, 'maletas.ver', { id }),
      chamar(ponte, 'maletas.saldo', { id }),
    ]);
    if (!cabecalho.ok) return cabecalho;
    if (!saldo.ok) return saldo;
    const maleta = lerMaleta(cabecalho.dados);
    const lido = lerSaldoDaMaleta(saldo.dados);
    if (!maleta || !lido) {
      return {
        ok: false as const,
        erro: { tipo: 'outro' as const, mensagem: T.naoAbriu, campos: {} },
      };
    }
    return { ok: true as const, dados: { maleta, saldo: lido } };
  }, `gestao-${id}-${versao}`);
  const [cancelando, setCancelando] = useState(false);
  const [desfazendo, setDesfazendo] = useState<MovimentoDaMaleta | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  // O mesmo id vale para o mesmo movimento e motivo (reenvio seguro); motivo novo = id novo.
  const estorno = useRef({ chave: '', id: '' });
  const recarregar = () => setVersao((v) => v + 1);

  if (busca.erro) {
    return (
      <div>
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
        <Botao onClick={aoVoltar}>{T.gerir.voltarAMaletas}</Botao>
      </div>
    );
  }
  if (!busca.dados) return <p role="status">{T.carregando}</p>;
  const { maleta, saldo } = busca.dados;
  const enviada = maleta.status === 'ENVIADA';
  const cancelada = maleta.status === 'CANCELADA';
  const E = T.enviada;
  const nomeDoProduto = (produtoId: string) =>
    saldo.linhas.find((l) => l.produtoId === produtoId)?.nome ?? '';

  function oQueFoi(m: MovimentoDaMaleta): string {
    if (m.estornaId) return F.movimentos.desfeita;
    if (m.tipo === 'DEVOLUCAO_DONA') return F.movimentos.devolvida;
    return F.movimentos.outro;
  }

  return (
    <div>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      {cancelada ? (
        <Aviso tipo="atencao">
          {F.canceladaAviso}
          {saldo.motivoDoCancelamento
            ? ` ${F.motivoDoCancelamento(saldo.motivoDoCancelamento)}`
            : ''}
        </Aviso>
      ) : null}
      <section className="gestao-grupo" aria-labelledby="titulo-gestao-maleta">
        <h2 id="titulo-gestao-maleta" className="gestao-subtitulo">
          {E.tituloDaFicha}
        </h2>
        <dl className="gestao-resumo">
          <div>
            <dt>{E.ficha.vendedor}</dt>
            <dd>{maleta.vendedorNome}</dd>
          </div>
          <div>
            <dt>{E.ficha.semana}</dt>
            <dd>{maleta.semanaNumero}</dd>
          </div>
          <div>
            <dt>{E.ficha.praca}</dt>
            <dd>{maleta.pracaNome}</dd>
          </div>
          <div>
            <dt>{E.ficha.tipo}</dt>
            <dd>{T.nova.tipos[maleta.tipo] ?? maleta.tipo}</dd>
          </div>
          <div>
            <dt>{E.ficha.situacao}</dt>
            <dd>{E.ficha.situacoes[maleta.status] ?? maleta.status}</dd>
          </div>
          {maleta.enviadaEm ? (
            <div>
              <dt>{E.ficha.enviadaEm}</dt>
              <dd>{dataNaTela(maleta.enviadaEm.slice(0, 10))}</dd>
            </div>
          ) : null}
        </dl>
        <Tabela
          legenda={F.pecasLegenda}
          textoVazio={F.semPecas}
          colunas={[
            { chave: 'foto', titulo: F.colunas.foto },
            { chave: 'nome', titulo: F.colunas.nome },
            { chave: 'codigo', titulo: F.colunas.codigo },
            { chave: 'enviada', titulo: F.colunas.enviada, numerica: true },
            { chave: 'devolvida', titulo: F.colunas.devolvida, numerica: true },
            { chave: 'saldo', titulo: F.colunas.saldo, numerica: true },
            { chave: 'preco', titulo: F.colunas.preco, numerica: true },
            { chave: 'valor', titulo: F.colunas.valor, numerica: true },
          ]}
          linhas={saldo.linhas.map((l) => ({
            foto: (
              <ImagemDoProduto ponte={ponte} hash={l.fotoHash} tamanho="miniatura" nome={l.nome} />
            ),
            nome: l.nome,
            codigo: l.codigo,
            enviada: l.enviada,
            devolvida: l.devolvida,
            saldo: l.saldo,
            preco: <Dinheiro valor={l.precoUnitario} />,
            valor: <Dinheiro valor={l.valorEmAberto} />,
          }))}
        />
        <p className="gestao-total-da-compra">
          {F.totais(saldo.pecasEmAberto)} · {F.valorEmAberto}:{' '}
          <Dinheiro valor={saldo.valorEmAberto} />
        </p>
        <h3 className="gestao-subtitulo">{F.movimentos.titulo}</h3>
        <Tabela
          legenda={F.movimentos.legenda}
          textoVazio={F.movimentos.vazio}
          colunas={[
            { chave: 'quando', titulo: F.movimentos.colunas.quando },
            { chave: 'peca', titulo: F.movimentos.colunas.peca },
            { chave: 'quantidade', titulo: F.movimentos.colunas.quantidade, numerica: true },
            { chave: 'oQueFoi', titulo: F.movimentos.colunas.oQueFoi },
            { chave: 'acoes', titulo: F.movimentos.colunas.acoes },
          ]}
          linhas={saldo.movimentos.map((m) => {
            const peca = m.produtoNome || nomeDoProduto(m.produtoId);
            const podeDesfazer = enviada && m.tipo === 'DEVOLUCAO_DONA' && !m.estornaId;
            return {
              quando: m.em ? dataNaTela(m.em.slice(0, 10)) : '',
              peca,
              quantidade: Math.abs(m.quantidade),
              oQueFoi: oQueFoi(m),
              acoes: m.desfeito ? (
                F.movimentos.jaDesfeita
              ) : podeDesfazer ? (
                <Botao aria-label={F.desfazerDe(peca)} onClick={() => setDesfazendo(m)}>
                  {F.desfazer}
                </Botao>
              ) : (
                ''
              ),
            };
          })}
        />
        <div className="gestao-acoes-da-linha">
          {enviada ? (
            <>
              <Botao variante="primario" onClick={() => aoDevolver(maleta, saldo)}>
                {F.devolver}
              </Botao>
              <Botao onClick={() => aoRepor(maleta)}>{F.repor}</Botao>
              <Botao onClick={() => setCancelando(true)}>{F.cancelar}</Botao>
            </>
          ) : null}
          {NOTA_DA_MALETA_PRONTA && !cancelada ? (
            <Botao onClick={() => void imprimirNotaDaMaleta(ponte, maleta.id)}>{E.imprimir}</Botao>
          ) : null}
          <Botao onClick={aoVoltar}>{T.gerir.voltarAMaletas}</Botao>
        </div>
      </section>
      {cancelando ? (
        <PedirMotivo
          titulo={T.cancelar.titulo}
          texto={<p className="gestao-texto">{T.cancelar.textoEnviada}</p>}
          rotuloDoMotivo={T.cancelar.motivo}
          avisoDoMotivo={T.cancelar.motivoAviso}
          rotuloConfirmar={T.cancelar.confirmar}
          rotuloCancelar={T.cancelar.voltar}
          dicaNoErro={T.cancelar.sugestao}
          executar={(motivo) => chamar(ponte, 'maletas.cancelar', { id, motivo })}
          aoFeito={() => {
            setCancelando(false);
            setAviso(T.cancelar.feito);
            recarregar();
          }}
          aoCancelar={() => setCancelando(false)}
        />
      ) : null}
      {desfazendo ? (
        <PedirMotivo
          titulo={T.desfazer.titulo}
          texto={
            <p className="gestao-texto">
              {T.desfazer.texto(
                desfazendo.produtoNome || nomeDoProduto(desfazendo.produtoId),
                Math.abs(desfazendo.quantidade),
              )}
            </p>
          }
          rotuloDoMotivo={T.desfazer.motivo}
          avisoDoMotivo={T.desfazer.motivoAviso}
          rotuloConfirmar={T.desfazer.confirmar}
          rotuloCancelar={T.desfazer.voltar}
          executar={(motivo) => {
            // Mesmo movimento e mesmo motivo = mesmo pedido; qualquer mudança = pedido novo.
            const chave = `${desfazendo.id}|${motivo}`;
            if (estorno.current.chave !== chave) {
              estorno.current = { chave, id: crypto.randomUUID() };
            }
            return chamar(ponte, 'maletas.desfazerDevolucao', {
              id,
              devolucaoId: desfazendo.id,
              estornoId: estorno.current.id,
              motivo,
            });
          }}
          aoFeito={() => {
            setDesfazendo(null);
            setAviso(T.desfazer.feito);
            recarregar();
          }}
          aoCancelar={() => setDesfazendo(null)}
        />
      ) : null}
    </div>
  );
}
