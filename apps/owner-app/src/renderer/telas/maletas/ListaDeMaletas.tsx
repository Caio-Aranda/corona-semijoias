import { Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type Retorno } from '../../nucleo/chamadas';
import { formatarReais } from '../../nucleo/moedas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { dataNaTela } from '../cadastros/definicoes';
import { BotaoImprimirNota } from './BotaoImprimirNota';
import { Dinheiro } from './Dinheiro';
import {
  lerEmAberto,
  lerPaginaDeMaletas,
  lerSemanasParaEscolher,
  lerVendedoresParaEscolher,
  type EmAbertoDoVendedor,
  type Maleta,
} from './dados';

const T = TEXTOS_DE_MALETAS;
const G = T.gerir;

const SITUACOES_DO_FILTRO = ['RASCUNHO', 'ENVIADA', 'CONFERIDA', 'CANCELADA'] as const;

export interface ListaDeMaletasProps {
  ponte: PonteDoCorona;
  /** Chave que muda quando a lista precisa ser buscada de novo. */
  chave: string;
  aoAbrir: (maleta: Maleta) => void;
  aoRepor: (vendedorId: string, vendedorNome: string) => void;
}

type Aba = 'todas' | 'porVendedor';

/** Maletas → Maletas: lista com filtros e a visão "Por representante" (quanto tem em aberto e quanto sobra). */
export function ListaDeMaletas({ ponte, chave, aoAbrir, aoRepor }: ListaDeMaletasProps) {
  const [aba, setAba] = useState<Aba>('todas');
  return (
    <div>
      <div className="gestao-acoes-da-linha" role="group" aria-label={G.titulo}>
        <Botao
          variante={aba === 'todas' ? 'primario' : 'secundario'}
          aria-pressed={aba === 'todas'}
          onClick={() => setAba('todas')}
        >
          {G.abas.todas}
        </Botao>
        <Botao
          variante={aba === 'porVendedor' ? 'primario' : 'secundario'}
          aria-pressed={aba === 'porVendedor'}
          onClick={() => setAba('porVendedor')}
        >
          {G.abas.porVendedor}
        </Botao>
      </div>
      {aba === 'todas' ? (
        <TodasAsMaletas ponte={ponte} chave={chave} aoAbrir={aoAbrir} />
      ) : (
        <PorVendedor ponte={ponte} chave={chave} aoRepor={aoRepor} />
      )}
    </div>
  );
}

function TodasAsMaletas({
  ponte,
  chave,
  aoAbrir,
}: {
  ponte: PonteDoCorona;
  chave: string;
  aoAbrir: (maleta: Maleta) => void;
}) {
  const [vendedorId, setVendedorId] = useState('');
  const [semanaId, setSemanaId] = useState('');
  const [situacao, setSituacao] = useState('');
  const vendedores = useBusca(() => chamar(ponte, 'vendedores.escolha'), 'lista-vendedores');
  const semanas = useBusca(() => chamarTodasAsPaginas(ponte, 'semanas.listar'), 'lista-semanas');
  const busca = useBusca(
    () =>
      chamarTodasAsPaginas(ponte, 'maletas.listar', {
        ...(vendedorId ? { vendedorId } : {}),
        ...(semanaId ? { semanaId } : {}),
        ...(situacao ? { status: situacao } : {}),
      }),
    `lista-${chave}-${vendedorId}-${semanaId}-${situacao}`,
  );
  const maletas = busca.dados ? lerPaginaDeMaletas(busca.dados) : [];
  const L = G.lista;

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-lista-de-maletas">
      <h2 id="titulo-lista-de-maletas" className="gestao-subtitulo">
        {G.abas.todas}
      </h2>
      <div className="gestao-filtros">
        <div className="cs-campo">
          <label htmlFor="filtro-maleta-vendedor">{G.filtros.vendedor}</label>
          <select
            id="filtro-maleta-vendedor"
            value={vendedorId}
            onChange={(e) => setVendedorId(e.target.value)}
          >
            <option value="">{G.filtros.todosVendedores}</option>
            {(vendedores.dados ? lerVendedoresParaEscolher(vendedores.dados) : []).map((v) => (
              <option key={v.id} value={v.id}>
                {v.nome}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-maleta-semana">{G.filtros.semana}</label>
          <select
            id="filtro-maleta-semana"
            value={semanaId}
            onChange={(e) => setSemanaId(e.target.value)}
          >
            <option value="">{G.filtros.todasSemanas}</option>
            {(semanas.dados ? lerSemanasParaEscolher(semanas.dados) : []).map((s) => (
              <option key={s.id} value={s.id}>
                {T.nova.nomeDaSemana(s.numero)}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-maleta-situacao">{G.filtros.situacao}</label>
          <select
            id="filtro-maleta-situacao"
            value={situacao}
            onChange={(e) => setSituacao(e.target.value)}
          >
            <option value="">{G.filtros.todasSituacoes}</option>
            {SITUACOES_DO_FILTRO.map((s) => (
              <option key={s} value={s}>
                {T.enviada.ficha.situacoes[s]}
              </option>
            ))}
          </select>
        </div>
      </div>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={L.legenda}
        textoVazio={L.vazia}
        colunas={[
          { chave: 'codigo', titulo: L.colunas.codigo },
          { chave: 'vendedor', titulo: L.colunas.vendedor },
          { chave: 'semana', titulo: L.colunas.semana },
          { chave: 'tipo', titulo: L.colunas.tipo },
          { chave: 'situacao', titulo: L.colunas.situacao },
          { chave: 'pecas', titulo: L.colunas.pecas, numerica: true },
          { chave: 'valor', titulo: L.colunas.valor, numerica: true },
          { chave: 'enviada', titulo: L.colunas.enviada },
          { chave: 'acoes', titulo: L.colunas.acoes },
        ]}
        linhas={maletas.map((m) => ({
          codigo: m.codigo,
          vendedor: m.vendedorNome,
          semana: String(m.semanaNumero),
          tipo: T.nova.tipos[m.tipo] ?? m.tipo,
          situacao: T.enviada.ficha.situacoes[m.status] ?? m.status,
          pecas: m.totalPecas,
          valor: <Dinheiro valor={m.totalValor} />,
          enviada: m.enviadaEm ? dataNaTela(m.enviadaEm.slice(0, 10)) : L.semData,
          acoes:
            m.status === 'RASCUNHO' ? (
              <Botao aria-label={L.continuarDe(m.vendedorNome)} onClick={() => aoAbrir(m)}>
                {L.continuar}
              </Botao>
            ) : (
              <>
                <Botao
                  aria-label={L.abrirDe(m.vendedorNome, m.semanaNumero)}
                  onClick={() => aoAbrir(m)}
                >
                  {L.abrir}
                </Botao>
                {m.enviadaEm ? (
                  <BotaoImprimirNota ponte={ponte} maletaId={m.id} vendedorNome={m.vendedorNome} />
                ) : null}
              </>
            ),
        }))}
      />
    </section>
  );
}

async function buscarEmAbertoDeTodos(ponte: PonteDoCorona): Promise<Retorno<EmAbertoDoVendedor[]>> {
  const lista = await chamar(ponte, 'vendedores.escolha');
  if (!lista.ok) return lista;
  const respostas = await Promise.all(
    lerVendedoresParaEscolher(lista.dados).map(async (v) => {
      const r = await chamar(ponte, 'maletas.emAberto', { vendedorId: v.id });
      return r.ok
        ? {
            ok: true as const,
            dados: { ...lerEmAberto(r.dados), vendedorNome: v.nome, vendedorId: v.id },
          }
        : r;
    }),
  );
  const falha = respostas.find((r) => !r.ok);
  if (falha && !falha.ok) return falha;
  return {
    ok: true,
    dados: respostas.flatMap((r) => (r.ok ? [r.dados] : [])),
  };
}

function PorVendedor({
  ponte,
  chave,
  aoRepor,
}: {
  ponte: PonteDoCorona;
  chave: string;
  aoRepor: (vendedorId: string, vendedorNome: string) => void;
}) {
  const P = G.porVendedor;
  const busca = useBusca(() => buscarEmAbertoDeTodos(ponte), `por-vendedor-${chave}`);
  return (
    <section className="gestao-grupo" aria-labelledby="titulo-por-vendedor">
      <h2 id="titulo-por-vendedor" className="gestao-subtitulo">
        {G.abas.porVendedor}
      </h2>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {busca.carregando && !busca.dados ? <p role="status">{P.carregando}</p> : null}
      {busca.dados ? (
        <Tabela
          legenda={P.legenda}
          textoVazio={P.vazia}
          colunas={[
            { chave: 'vendedor', titulo: P.colunas.vendedor },
            { chave: 'maletas', titulo: P.colunas.maletas, numerica: true },
            { chave: 'emAberto', titulo: P.colunas.emAberto, numerica: true },
            { chave: 'limite', titulo: P.colunas.limite, numerica: true },
            { chave: 'sobra', titulo: P.colunas.sobra, numerica: true },
            { chave: 'acoes', titulo: P.colunas.acoes },
          ]}
          linhas={busca.dados.map((v) => ({
            vendedor: v.vendedorNome,
            // Maleta com tudo devolvido (saldo zero) continua enviada, mas não conta aqui.
            maletas: v.maletas.filter((m) => m.pecasEmAberto > 0).length,
            emAberto: <Dinheiro valor={v.valorEmAberto} />,
            limite:
              v.limite !== null ? (
                <Dinheiro valor={v.limite} />
              ) : v.semLimite ? (
                P.semLimite
              ) : (
                P.naoDefinido
              ),
            sobra:
              v.sobra === null ? (
                '—'
              ) : v.sobra < 0n ? (
                P.passou(formatarReais(-v.sobra))
              ) : (
                <Dinheiro valor={v.sobra} />
              ),
            acoes: (
              <Botao
                aria-label={P.reporDe(v.vendedorNome)}
                onClick={() => aoRepor(v.vendedorId, v.vendedorNome)}
              >
                {P.repor}
              </Botao>
            ),
          }))}
        />
      ) : null}
    </section>
  );
}
