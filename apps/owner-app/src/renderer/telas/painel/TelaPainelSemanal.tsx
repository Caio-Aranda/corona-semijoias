import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState, type ReactNode } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { entregarArquivo } from '../../nucleo/arquivos';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { gerarCsv } from '../../nucleo/exportacao/csv';
import { montarGrade, dataEHoraParaTela } from '../../nucleo/exportacao/grade';
import { gerarPdf } from '../../nucleo/exportacao/pdf';
import { gerarXlsx } from '../../nucleo/exportacao/xlsx';
import { dataParaTela } from '../../nucleo/moedas';
import { useTelasDoGestao } from '../../nucleo/telas-do-gestao';
import {
  dolarNaTela,
  guaraniNaTela,
  lerPainel,
  pedidoDeExportacao,
  registrosDaOrigem,
  reaisNaTela,
  type CartaoDoPainel,
  type LinhaDoPainel,
  type Moedas,
  type PainelLido,
  type RegistrosParaAbrir,
} from '../../nucleo/painel-semanal';
import { TEXTOS_DO_PAINEL as T } from '../../textos-painel';
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';

export interface TelaPainelSemanalProps {
  ponte: PonteDoCorona;
  /** Quem está gerando (vai no cabeçalho dos arquivos). */
  usuario: string | null;
  aoAbrirMaleta: (id: string) => void;
  aoAbrirAcerto: (id: string) => void;
  aoAbrirRegistros: (pedido: RegistrosParaAbrir) => void;
}

interface Opcao {
  id: string;
  nome: string;
}

interface Filtros {
  semana: string;
  vendedor: string;
  praca: string;
  situacao: string;
}

const SEM_FILTROS: Filtros = { semana: '', vendedor: '', praca: '', situacao: '' };

const ATALHOS: { rotulo: string; rota: string }[] = [
  { rotulo: T.atalhos.novaMaleta, rota: 'maletas/montar' },
  { rotulo: T.atalhos.acerto, rota: 'acertos/lista' },
  { rotulo: T.atalhos.reposicao, rota: 'maletas/lista' },
  { rotulo: T.atalhos.troca, rota: 'devolucoes/trocas' },
  { rotulo: T.atalhos.vendedores, rota: 'pessoas/vendedores' },
  { rotulo: T.atalhos.estoque, rota: 'estoque/posicao' },
];

function listaDe(bruto: unknown, nome: (i: Record<string, unknown>) => string): Opcao[] {
  const itens = (bruto as { itens?: unknown } | null)?.itens;
  if (!Array.isArray(itens)) return [];
  return itens.flatMap((i: unknown) => {
    const o = (typeof i === 'object' && i !== null ? i : {}) as Record<string, unknown>;
    return typeof o.id === 'string' ? [{ id: o.id, nome: nome(o) }] : [];
  });
}

const textoDe = (v: unknown): string =>
  typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '';

async function carregarListas(ponte: PonteDoCorona) {
  const pagina = { pagina: 0, tamanho: 100, situacao: 'TODOS' };
  const [semanas, vendedores, pracas] = await Promise.all([
    chamar<unknown>(ponte, 'semanas.listar', pagina),
    chamar<unknown>(ponte, 'vendedores.escolha'),
    chamar<unknown>(ponte, 'pracas.listar', pagina),
  ]);
  const dados = (r: { ok: boolean; dados?: unknown }) => (r.ok ? r.dados : null);
  return {
    ok: true as const,
    dados: {
      semanas: listaDe(dados(semanas), (i) => `${T.filtros.semana} ${textoDe(i.numero)}`),
      vendedores: listaDe(dados(vendedores), (i) => textoDe(i.nome)),
      pracas: listaDe(dados(pracas), (i) => textoDe(i.nome)),
    },
  };
}

/** Real em destaque; Dólar e Guarani logo abaixo, pela cotação do dia de cada lançamento. */
function Valor({ m, semCotacao = false }: { m: Moedas | null | undefined; semCotacao?: boolean }) {
  if (!m) return <span>-</span>;
  return (
    <span className="gestao-painel__valor">
      <strong>{reaisNaTela(m.real)}</strong>
      {m.dolar !== null && m.guarani !== null ? (
        <span className="gestao-painel__outras-moedas">
          {dolarNaTela(m.dolar)} · {guaraniNaTela(m.guarani)}
        </span>
      ) : semCotacao ? (
        <span className="gestao-painel__outras-moedas">{T.semCotacaoLinha}</span>
      ) : null}
    </span>
  );
}

function Cartao({
  c,
  aoAbrirRegistros,
}: {
  c: CartaoDoPainel;
  aoAbrirRegistros: (pedido: RegistrosParaAbrir) => void;
}) {
  const id = `cartao-${c.chave}`;
  return (
    <section className="gestao-painel__cartao" aria-labelledby={id}>
      <h3 id={id} className="gestao-subtitulo">
        {c.titulo}
      </h3>
      {c.semCotacao ? <Aviso tipo="atencao">{T.previaSemCotacao}</Aviso> : null}
      <dl className="gestao-painel__itens">
        {c.itens.map((i) => (
          <div key={i.rotulo} className="gestao-painel__item">
            <dt>{i.rotulo}</dt>
            <dd>
              {i.quantidade !== undefined ? i.quantidade : <Valor m={i.valor} />}
              {i.quantidade !== undefined && i.valor !== undefined ? <Valor m={i.valor} /> : null}
            </dd>
          </div>
        ))}
      </dl>
      <div className="gestao-acoes-da-linha">
        {c.origens.map((o) => {
          const registros = registrosDaOrigem(o.origem);
          return registros ? (
            <Botao
              key={o.rotulo}
              aria-label={T.verRegistrosDe(o.rotulo)}
              onClick={() => aoAbrirRegistros(registros)}
            >
              {`${T.verRegistros}: ${o.rotulo}`}
            </Botao>
          ) : null;
        })}
      </div>
    </section>
  );
}

function Situacao({ l }: { l: LinhaDoPainel }) {
  return (
    <span>
      {T.situacoes[l.situacao] ?? l.situacao}
      {l.previa ? <span className="gestao-painel__previa"> · {T.previa}</span> : null}
    </span>
  );
}

function linhaDaTabela(
  l: LinhaDoPainel,
  aoAbrirMaleta: (id: string) => void,
  aoAbrirAcerto: (id: string) => void,
): Record<string, ReactNode> {
  const pecas = (n: number) => <span className="gestao-painel__pecas">{T.pecas(n)}</span>;
  return {
    vendedor: (
      <span>
        <strong>{l.vendedor}</strong>
        <span className="gestao-acoes-da-linha">
          {l.acertoId ? (
            <Botao
              aria-label={T.abrirAcerto(l.vendedor)}
              onClick={() => aoAbrirAcerto(l.acertoId!)}
            >
              {T.verAcerto}
            </Botao>
          ) : null}
          {l.maletas.map((m) => (
            <Botao
              key={m.id}
              aria-label={T.abrirMaleta(m.numero, l.vendedor)}
              onClick={() => aoAbrirMaleta(m.id)}
            >
              {T.maleta(m.numero)}
            </Botao>
          ))}
        </span>
      </span>
    ),
    praca: l.praca,
    situacao: <Situacao l={l} />,
    enviado: (
      <span>
        <Valor m={l.enviado} semCotacao={l.semCotacao} />
        {pecas(l.enviadoPecas)}
      </span>
    ),
    devolvido: (
      <span>
        <Valor m={l.devolvido} semCotacao={l.semCotacao} />
        {pecas(l.devolvidoPecas)}
        {l.emFaltaPecas > 0 ? (
          <span className="gestao-painel__pecas">{T.faltando(l.emFaltaPecas)}</span>
        ) : null}
      </span>
    ),
    vendido: (
      <span>
        <Valor m={l.vendidoLiquido} semCotacao={l.semCotacao} />
        {pecas(l.vendidasPecas)}
      </span>
    ),
    comissao: (
      <span>
        <Valor m={l.comissao} semCotacao={l.semCotacao} />
        {l.percentualComissao ? (
          <span className="gestao-painel__pecas">{T.comissaoDe(l.percentualComissao)}</span>
        ) : null}
      </span>
    ),
    liquido: <Valor m={l.liquido} semCotacao={l.semCotacao} />,
    cotacao: l.dataDaCotacao ? T.dataDaCotacao(dataParaTela(l.dataDaCotacao)) : '',
  };
}

const COLUNAS = [
  { chave: 'vendedor', titulo: T.colunas.vendedor },
  { chave: 'praca', titulo: T.colunas.praca },
  { chave: 'situacao', titulo: T.colunas.situacao },
  { chave: 'enviado', titulo: T.colunas.enviado, numerica: true },
  { chave: 'devolvido', titulo: T.colunas.devolvido, numerica: true },
  { chave: 'vendido', titulo: T.colunas.vendido, numerica: true },
  { chave: 'comissao', titulo: T.colunas.comissao, numerica: true },
  { chave: 'liquido', titulo: T.colunas.liquido, numerica: true },
  { chave: 'cotacao', titulo: SOLTOS.arquivo.cotacao },
] as const;

/** Início → Painel da semana (tarefa 8.8, §2 e §19): atalhos, filtros, abas Maletas e Totais. Só mostra o que o servidor calculou. */
export function TelaPainelSemanal({
  ponte,
  usuario,
  aoAbrirMaleta,
  aoAbrirAcerto,
  aoAbrirRegistros,
}: TelaPainelSemanalProps) {
  // O botão "Acerto" é do acerto antigo da §7.4: some enquanto ele está escondido (ADR-0016, item 15).
  const { acertoDoVendedorVisivel } = useTelasDoGestao();
  const atalhos = ATALHOS.filter((a) => a.rota !== 'acertos/lista' || acertoDoVendedorVisivel);
  const [filtros, setFiltros] = useState<Filtros>(SEM_FILTROS);
  const [aba, setAba] = useState<'maletas' | 'totais'>('maletas');
  const [exportando, setExportando] = useState(false);
  const [aviso, setAviso] = useState<{ tipo: 'sucesso' | 'erro'; texto: string } | null>(null);

  const listas = useBusca(() => carregarListas(ponte), 'painel-listas');
  const opcoes = listas.dados ?? { semanas: [], vendedores: [], pracas: [] };

  const chave = `painel|${JSON.stringify(filtros)}`;
  const busca = useBusca(
    () =>
      chamar<unknown>(ponte, 'painel.semanal', {
        ...(filtros.semana ? { semana: filtros.semana } : {}),
        ...(filtros.vendedor ? { vendedor: filtros.vendedor } : {}),
        ...(filtros.praca ? { praca: filtros.praca } : {}),
        ...(filtros.situacao ? { situacao: filtros.situacao } : {}),
      }),
    chave,
  );
  const painel: PainelLido | null = busca.dados ? lerPainel(busca.dados) : null;

  function mudar(campo: keyof Filtros, valor: string) {
    setAviso(null);
    setFiltros((f) => ({ ...f, [campo]: valor }));
  }

  function seletor(
    id: string,
    rotulo: string,
    campo: keyof Filtros,
    itens: Opcao[],
    qualquer: string,
  ) {
    return (
      <div className="cs-campo">
        <label htmlFor={id}>{rotulo}</label>
        <select id={id} value={filtros[campo]} onChange={(e) => mudar(campo, e.target.value)}>
          <option value="">{qualquer}</option>
          {itens.map((o) => (
            <option key={o.id} value={o.id}>
              {o.nome}
            </option>
          ))}
        </select>
      </div>
    );
  }

  function filtrosEmPalavras(): string[] {
    const nome = (itens: Opcao[], id: string) => itens.find((o) => o.id === id)?.nome ?? id;
    const x: string[] = [];
    if (painel?.semana) {
      x.push(
        `${T.filtros.semana}: ${painel.semana.numero} (${dataParaTela(painel.semana.inicio)} a ${dataParaTela(painel.semana.fim)})`,
      );
    }
    if (filtros.vendedor)
      x.push(`${T.filtros.vendedor}: ${nome(opcoes.vendedores, filtros.vendedor)}`);
    if (filtros.praca) x.push(`${T.filtros.praca}: ${nome(opcoes.pracas, filtros.praca)}`);
    if (filtros.situacao) {
      x.push(`${T.filtros.situacao}: ${T.situacoes[filtros.situacao] ?? filtros.situacao}`);
    }
    return x;
  }

  function exportar(formato: 'csv' | 'xlsx' | 'pdf') {
    if (exportando || !painel) return;
    setAviso(null);
    if (painel.linhas.length === 0) {
      setAviso({ tipo: 'erro', texto: T.exportar.semLinhas });
      return;
    }
    setExportando(true);
    try {
      const agora = new Date();
      const grade = montarGrade(
        pedidoDeExportacao(painel, {
          filtrosEmPalavras: filtrosEmPalavras(),
          geradoEm: dataEHoraParaTela(agora),
          geradoPor: usuario ?? SOLTOS.arquivo.dona,
        }),
      );
      const base = `${T.arquivo}-${agora.toISOString().slice(0, 10)}`;
      if (formato === 'csv') {
        entregarArquivo(
          `${base}.csv`,
          'text/csv;charset=utf-8',
          new TextEncoder().encode(gerarCsv(grade)),
        );
      } else if (formato === 'xlsx') {
        entregarArquivo(
          `${base}.xlsx`,
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          gerarXlsx(grade),
        );
      } else {
        entregarArquivo(`${base}.pdf`, 'application/pdf', gerarPdf(grade));
      }
      setAviso({ tipo: 'sucesso', texto: T.exportar.pronto(`${base}.${formato}`) });
    } catch {
      setAviso({ tipo: 'erro', texto: T.exportar.erro });
    } finally {
      setExportando(false);
    }
  }

  const filtrando = JSON.stringify(filtros) !== JSON.stringify(SEM_FILTROS);

  return (
    <div className="gestao-tela gestao-painel">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>

      <nav className="gestao-botoes" aria-label={T.atalhos.rotulo}>
        {atalhos.map((a) => (
          <Botao
            key={a.rotulo}
            onClick={() => {
              window.location.hash = `/${a.rota}`;
            }}
          >
            {a.rotulo}
          </Botao>
        ))}
      </nav>

      <form
        className="gestao-filtros"
        aria-label={T.filtros.rotulo}
        onSubmit={(e) => e.preventDefault()}
      >
        {seletor(
          'painel-semana',
          T.filtros.semana,
          'semana',
          opcoes.semanas,
          T.filtros.semanaAtual,
        )}
        {seletor(
          'painel-vendedor',
          T.filtros.vendedor,
          'vendedor',
          opcoes.vendedores,
          T.filtros.qualquer,
        )}
        {seletor('painel-praca', T.filtros.praca, 'praca', opcoes.pracas, T.filtros.qualquer)}
        <div className="cs-campo">
          <label htmlFor="painel-situacao">{T.filtros.situacao}</label>
          <select
            id="painel-situacao"
            value={filtros.situacao}
            onChange={(e) => mudar('situacao', e.target.value)}
          >
            <option value="">{T.filtros.qualquer}</option>
            {Object.entries(T.situacoes).map(([valor, nome]) => (
              <option key={valor} value={valor}>
                {nome}
              </option>
            ))}
          </select>
        </div>
        {filtrando ? (
          <Botao onClick={() => setFiltros(SEM_FILTROS)}>{T.filtros.limpar}</Botao>
        ) : null}
        <Botao onClick={busca.recarregar}>{T.atualizar}</Botao>
      </form>

      {busca.erro && !painel ? (
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
      ) : null}
      {busca.carregando && !painel ? <p className="gestao-texto">{T.carregando}</p> : null}

      {painel ? (
        <>
          {painel.semana ? (
            <p className="gestao-subtitulo" aria-live="polite">
              {T.semana(
                painel.semana.numero,
                dataParaTela(painel.semana.inicio),
                dataParaTela(painel.semana.fim),
              )}
            </p>
          ) : null}
          {painel.linhas.some((l) => l.previa) ? (
            <Aviso tipo="informacao">
              {painel.avisoDaPrevia ?? T.previaAjuda}
              {painel.previaIncluiSemanasAnteriores ? ` ${T.previaAnteriores}` : ''}
            </Aviso>
          ) : null}

          <div role="tablist" aria-label={T.abas.rotulo} className="gestao-abas">
            {(['maletas', 'totais'] as const).map((qual) => (
              <button
                key={qual}
                type="button"
                role="tab"
                id={`aba-painel-${qual}`}
                aria-selected={aba === qual}
                aria-controls={`painel-${qual}`}
                className={`gestao-aba-botao${aba === qual ? ' gestao-aba-botao--atual' : ''}`}
                onClick={() => setAba(qual)}
              >
                {T.abas[qual]}
              </button>
            ))}
          </div>

          {aba === 'maletas' ? (
            <div role="tabpanel" id="painel-maletas" aria-labelledby="aba-painel-maletas">
              <Tabela
                legenda={T.abas.maletas}
                colunas={COLUNAS}
                linhas={painel.linhas.map((l) => linhaDaTabela(l, aoAbrirMaleta, aoAbrirAcerto))}
                textoVazio={T.vazio}
              />
            </div>
          ) : (
            <div
              role="tabpanel"
              id="painel-totais"
              aria-labelledby="aba-painel-totais"
              className="gestao-painel__cartoes"
            >
              {painel.cartoes.map((c) => (
                <Cartao key={c.chave} c={c} aoAbrirRegistros={aoAbrirRegistros} />
              ))}
            </div>
          )}

          <section className="gestao-exportar" aria-label={T.exportar.titulo}>
            <h2 className="gestao-subtitulo">{T.exportar.titulo}</h2>
            <div className="gestao-acoes-da-linha">
              <Botao disabled={exportando} onClick={() => exportar('pdf')}>
                {T.exportar.pdf}
              </Botao>
              <Botao disabled={exportando} onClick={() => exportar('xlsx')}>
                {T.exportar.excel}
              </Botao>
              <Botao disabled={exportando} onClick={() => exportar('csv')}>
                {T.exportar.csv}
              </Botao>
            </div>
            {aviso ? (
              <Aviso tipo={aviso.tipo === 'erro' ? 'erro' : 'sucesso'}>{aviso.texto}</Aviso>
            ) : null}
          </section>
        </>
      ) : null}
    </div>
  );
}
