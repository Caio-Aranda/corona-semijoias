import { Aviso, Botao, Campo, EstadoDaLista, Tabela } from '@corona/ui';
import { useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { dataNaTela } from '../cadastros/definicoes';
import { centavosDeTexto, formatarGuarani, formatarReais } from '../../nucleo/moedas';
import { reais } from '../devolucoes/Apoio';
import { dataEHora } from '../sincronizacao/dados';
import {
  lerDetalheDaNota,
  lerNotas,
  lerRepresentantes,
  type DetalheDaNotaLido,
  type MetaLida,
  type NotaLida,
} from './dados';

const T = TEXTOS_DE_CONSIGNADO.notas;
const SITUACOES = ['EM_ABERTO', 'PENDENTE', 'FECHADO', 'REMARCADO'] as const;

export interface FiltroInicialDasNotas {
  vendedorId?: string;
  semanaId?: string;
}

const guarani = (v: number | null): string => (v === null ? '' : formatarGuarani(BigInt(v)));

/** "Bateu" ou "Não bateu"; vazio quando a comissão não foi decidida pela meta (acerto antigo ou nota sem acerto). */
export function resultadoDaMeta(m: MetaLida): string {
  return m.bateu === null ? '' : m.bateu ? T.bateu : T.naoBateu;
}

/** Texto da situação da nota na tela. */
export function situacaoDaNota(situacao: string): string {
  return T.situacoes[situacao] ?? situacao;
}

/** Consignado → Notas dos clientes: lista com filtros e o detalhe de cada nota (só leitura). */
export function TelaNotasDoConsignado({
  ponte,
  filtroInicial,
  notaInicial,
}: {
  ponte: PonteDoCorona;
  filtroInicial?: FiltroInicialDasNotas | null;
  /** Vem da ficha do cliente: já abre esta nota. */
  notaInicial?: string | null;
}) {
  const [aberta, setAberta] = useState<string | null>(notaInicial ?? null);
  const [vendedorId, setVendedorId] = useState(filtroInicial?.vendedorId ?? '');
  const [situacao, setSituacao] = useState('');
  const [texto, setTexto] = useState('');
  const [textoAplicado, setTextoAplicado] = useState('');
  const semanaId = filtroInicial?.semanaId ?? '';

  const representantes = useBusca(
    () => chamar(ponte, 'vendedores.escolha'),
    'consignado-representantes',
  );
  const busca = useBusca(
    () =>
      chamar(ponte, 'consignado.notas', {
        ...(vendedorId ? { vendedorId } : {}),
        ...(semanaId ? { semanaId } : {}),
        ...(situacao ? { situacao } : {}),
        ...(textoAplicado ? { texto: textoAplicado } : {}),
      }),
    `consignado-notas-${vendedorId}-${semanaId}-${situacao}-${textoAplicado}`,
  );

  if (aberta) {
    return <DetalheDaNota ponte={ponte} id={aberta} aoVoltar={() => setAberta(null)} />;
  }
  const notas = busca.dados ? lerNotas(busca.dados) : [];
  const nomes = lerRepresentantes(representantes.dados);

  function buscar(e: FormEvent) {
    e.preventDefault();
    setTextoAplicado(texto.trim());
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <form
        className="gestao-filtros"
        role="search"
        aria-label={T.filtros.rotulo}
        onSubmit={buscar}
      >
        <div className="cs-campo">
          <label htmlFor="notas-representante">{T.filtros.representante}</label>
          <select
            id="notas-representante"
            value={vendedorId}
            onChange={(e) => setVendedorId(e.target.value)}
          >
            <option value="">{T.filtros.todos}</option>
            {nomes.map((v) => (
              <option key={v.id} value={v.id}>
                {v.nome}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="notas-situacao">{T.filtros.situacao}</label>
          <select
            id="notas-situacao"
            value={situacao}
            onChange={(e) => setSituacao(e.target.value)}
          >
            <option value="">{T.filtros.todasAsSituacoes}</option>
            {SITUACOES.map((s) => (
              <option key={s} value={s}>
                {situacaoDaNota(s)}
              </option>
            ))}
          </select>
        </div>
        <Campo
          id="notas-texto"
          rotulo={T.filtros.texto}
          value={texto}
          autoComplete="off"
          onChange={(e) => setTexto(e.target.value)}
        />
        <Botao type="submit">{TEXTOS.lista.buscar}</Botao>
      </form>
      <EstadoDaLista
        carregando={busca.carregando && !busca.dados}
        erro={busca.erro?.mensagem ?? null}
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        <Tabela
          legenda={T.legenda}
          colunas={[
            { chave: 'nota', titulo: T.colunas.nota },
            { chave: 'cliente', titulo: T.colunas.cliente },
            { chave: 'saida', titulo: T.colunas.saida },
            { chave: 'cobranca', titulo: T.colunas.cobranca },
            { chave: 'itens', titulo: T.colunas.itens, numerica: true },
            { chave: 'valor', titulo: T.colunas.valor, numerica: true },
            { chave: 'vdGuarani', titulo: T.colunas.vdGuarani, numerica: true },
            { chave: 'meta', titulo: T.colunas.meta },
            { chave: 'situacao', titulo: T.colunas.situacao },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={notas.map((n) => ({
            nota: n.numero,
            cliente: n.cliente,
            saida: dataEHora(n.saidaEm).split(' ')[0] ?? '',
            cobranca: n.dataCobranca ? dataNaTela(n.dataCobranca) : '',
            itens: n.itensDx,
            valor: reais(n.valorDx),
            vdGuarani: guarani(n.meta.vdGuarani),
            meta: resultadoDaMeta(n.meta),
            situacao: situacaoDaNota(n.situacao),
            acoes: (
              <Botao aria-label={T.verDaNota(n.numero)} onClick={() => setAberta(n.id)}>
                {T.ver}
              </Botao>
            ),
          }))}
          textoVazio={T.vazio}
        />
        {notas.length >= 200 ? <Aviso tipo="informacao">{T.limite}</Aviso> : null}
      </EstadoDaLista>
    </div>
  );
}

function DetalheDaNota({
  ponte,
  id,
  aoVoltar,
}: {
  ponte: PonteDoCorona;
  id: string;
  aoVoltar: () => void;
}) {
  const busca = useBusca(() => chamar(ponte, 'consignado.nota', { id }), `consignado-nota-${id}`);
  const d: DetalheDaNotaLido | null = busca.dados ? lerDetalheDaNota(busca.dados) : null;
  const D = T.detalhe;

  return (
    <div className="gestao-tela">
      <div>
        <Botao onClick={aoVoltar}>{D.voltar}</Botao>
      </div>
      <EstadoDaLista
        carregando={busca.carregando && !busca.dados}
        erro={busca.erro?.mensagem ?? null}
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        {d ? <ConteudoDaNota d={d} /> : null}
      </EstadoDaLista>
    </div>
  );
}

function ConteudoDaNota({ d }: { d: DetalheDaNotaLido }) {
  const D = T.detalhe;
  const n: NotaLida = d.nota;
  return (
    <>
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{D.titulo(n.numero)}</h1>
      </div>
      <dl className="gestao-ficha">
        <dt>{D.cliente}</dt>
        <dd>{n.cliente}</dd>
        <dt>{D.saida}</dt>
        <dd>{dataEHora(n.saidaEm)}</dd>
        <dt>{D.cobranca}</dt>
        <dd>{n.dataCobranca ? dataNaTela(n.dataCobranca) : ''}</dd>
        <dt>{D.situacao}</dt>
        <dd>{situacaoDaNota(n.situacao)}</dd>
      </dl>

      <h2 className="gestao-subtitulo">{D.pecas}</h2>
      <Tabela
        legenda={D.pecas}
        colunas={[
          { chave: 'peca', titulo: D.colunasPecas.peca },
          { chave: 'quantidade', titulo: D.colunasPecas.quantidade, numerica: true },
          { chave: 'preco', titulo: D.colunasPecas.preco, numerica: true },
          { chave: 'subtotal', titulo: D.colunasPecas.subtotal, numerica: true },
        ]}
        linhas={d.linhas.map((l) => ({
          peca: l.produto,
          quantidade: l.dx,
          preco: reais(l.precoUnitario),
          subtotal: formatarReais((centavosDeTexto(l.precoUnitario) ?? 0n) * BigInt(l.dx)),
        }))}
      />

      <h2 className="gestao-subtitulo">{D.acertos}</h2>
      {d.acertos.length === 0 ? <p className="gestao-texto">{D.semAcerto}</p> : null}
      {d.acertos.map((a) => (
        <section key={a.id} className="gestao-grupo" aria-label={D.acerto(a.semana)}>
          <h3 className="gestao-subtitulo">
            {D.acerto(a.semana)}
            {a.acertadoEm ? `, ${dataEHora(a.acertadoEm)}` : ''}
          </h3>
          {a.estornado ? <Aviso tipo="atencao">{D.estornado(a.motivoDoEstorno)}</Aviso> : null}
          <dl className="gestao-ficha">
            <dt>{D.dx}</dt>
            <dd>{reais(a.valorDx)}</dd>
            <dt>{D.dv}</dt>
            <dd>{reais(a.valorDv)}</dd>
            <dt>{D.vd}</dt>
            <dd>{reais(a.valorVd)}</dd>
            {a.meta.regra === 'META' ? (
              <>
                <dt>{D.vdGuarani}</dt>
                <dd>{guarani(a.meta.vdGuarani)}</dd>
                <dt>{D.metaDoAcerto}</dt>
                <dd>{guarani(a.meta.metaGuarani)}</dd>
                <dt>{D.resultadoDaMeta}</dt>
                <dd>{resultadoDaMeta(a.meta)}</dd>
              </>
            ) : null}
            <dt>{D.comissao(a.comissaoPercentual)}</dt>
            <dd>{reais(a.comissao)}</dd>
            <dt>{D.liquido}</dt>
            <dd>{reais(a.bruto)}</dd>
            <dt>{D.pendenciasAnteriores}</dt>
            <dd>{reais(a.pendenciasAnteriores)}</dd>
            <dt>{D.totalAReceber}</dt>
            <dd>{reais(a.totalAReceber)}</dd>
            <dt>{D.totalPago}</dt>
            <dd>{reais(a.totalPago)}</dd>
            <dt>{D.ficouDevendo}</dt>
            <dd>{reais(a.ficouDevendo)}</dd>
            <dt>{D.saldoDoDebito}</dt>
            <dd>{reais(a.saldoDoDebito)}</dd>
          </dl>
          <p className="gestao-texto">{D.itens(a.itensDx, a.itensDv, a.itensVd)}</p>
        </section>
      ))}

      <h2 className="gestao-subtitulo">{D.recibos}</h2>
      {d.recibos.length === 0 ? <p className="gestao-texto">{D.semRecibo}</p> : null}
      {d.recibos.map((r) => (
        <section key={r.id} className="gestao-grupo" aria-label={D.recibo(r.semana, r.sequencia)}>
          <h3 className="gestao-subtitulo">
            {D.recibo(r.semana, r.sequencia)}
            {r.pagoEm ? `, ${dataEHora(r.pagoEm)}` : ''}: {reais(r.total)}
          </h3>
          {r.estornado ? <Aviso tipo="atencao">{D.reciboEstornado}</Aviso> : null}
          <ul>
            {r.formas.map((f, i) => (
              <li key={i}>
                {D.formas[f.forma] ?? f.forma}: {reais(f.valor)}
              </li>
            ))}
          </ul>
          {Number(r.aAplicar) > 0 ? (
            <p className="gestao-texto">{D.aAplicar(reais(r.aAplicar))}</p>
          ) : null}
        </section>
      ))}
    </>
  );
}
