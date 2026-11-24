import { Aviso, Botao, Campo, EstadoDaLista, Paginacao, Tabela, ValorTresMoedas } from '@corona/ui';
import { hojeEmBrasilia } from '../../nucleo/datas';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { ConsultaDeRelatorio, NomeDoRelatorio } from '../../../shared/relatorios';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { quantidadeComSinal } from '../../nucleo/estoque';
import { lerPaginaDeUsuarios } from '../usuarios/dados';
import { chamar, useBusca, type Retorno } from '../../nucleo/chamadas';
import { useCotacao } from '../../nucleo/cotacao';
import { entregarArquivo } from '../../nucleo/arquivos';
import { gerarCsv } from '../../nucleo/exportacao/csv';
import {
  comoTexto,
  dataEHoraParaTela,
  montarGrade,
  somarConvertido,
  type ColunaDoRelatorio,
  type PedidoDeExportacao,
} from '../../nucleo/exportacao/grade';
import { gerarPdf } from '../../nucleo/exportacao/pdf';
import { gerarXlsx } from '../../nucleo/exportacao/xlsx';
import {
  centavosDeTexto,
  dataParaTela,
  formatarDolar,
  formatarGuarani,
  tresMoedas,
} from '../../nucleo/moedas';
import {
  RELATORIOS,
  lerRelatorio,
  prepararLinha,
  type AcaoDaLinha,
  type DefinicaoDeRelatorio,
  type Opcao,
  type Opcoes,
  type RelatorioLido,
  type TotalLido,
} from '../../nucleo/relatorios';
import { TEXTOS_RELATORIOS as T } from '../../textos-relatorios';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';
import { localeAtual } from '../../idioma/idioma';

const MALETAS = TEXTOS_DE_MALETAS.relatorios;

const TAMANHO = 20;
const TAMANHO_DA_BUSCA_COMPLETA = 100;
/** Acima disto o arquivo não é montado de uma vez (100 páginas de 100). */
const MAXIMO_DE_LINHAS_NO_ARQUIVO = 10_000;

interface Filtros {
  busca: string;
  situacao: string;
  categoriaId: string;
  fornecedorId: string;
  pracaId: string;
  vendedorId: string;
  restricaoId: string;
  usuarioId: string;
  semanaId: string;
  de: string;
  ate: string;
  mes: string;
  /** Filtros próprios do relatório (tarefa 1.13), pelo nome na API. */
  extras: Record<string, string>;
}

const SEM_FILTROS: Filtros = {
  busca: '',
  situacao: '',
  categoriaId: '',
  fornecedorId: '',
  pracaId: '',
  vendedorId: '',
  restricaoId: '',
  usuarioId: '',
  semanaId: '',
  de: '',
  ate: '',
  mes: '',
  extras: {},
};

function mesDeHoje(): string {
  const partes = new Intl.DateTimeFormat(localeAtual(), {
    timeZone: 'America/Sao_Paulo',
    month: 'numeric',
  }).format(new Date());
  return partes;
}

/** O filtro que a linha aberta fixa (a compra, a contagem ou a peça), pelo nome na API. */
export interface FiltroFixo {
  filtro: AcaoDaLinha['filtro'];
  valor: string;
  /** Em palavras: "Compra de 10/11/2026 — Ouro Ltda". */
  texto: string;
}

function consultaDe(
  def: DefinicaoDeRelatorio,
  f: Filtros,
  fixo: FiltroFixo | null,
): Omit<ConsultaDeRelatorio, 'pagina' | 'tamanho'> {
  const c: Omit<ConsultaDeRelatorio, 'pagina' | 'tamanho'> = {};
  if (fixo) c[fixo.filtro] = fixo.valor;
  if (def.filtros.includes('usuario') && f.usuarioId) c.usuarioId = f.usuarioId;
  if (def.filtros.includes('busca') && f.busca) c.busca = f.busca;
  if (def.filtros.includes('situacao') && f.situacao) {
    c.situacao = f.situacao as ConsultaDeRelatorio['situacao'] & string;
  }
  if (def.filtros.includes('categoria') && f.categoriaId) c.categoriaId = f.categoriaId;
  if (def.filtros.includes('fornecedor') && f.fornecedorId) c.fornecedorId = f.fornecedorId;
  if (def.filtros.includes('praca') && f.pracaId) c.pracaId = f.pracaId;
  if (def.filtros.includes('vendedor') && f.vendedorId) c.vendedorId = f.vendedorId;
  if (def.filtros.includes('restricao') && f.restricaoId) c.restricaoId = f.restricaoId;
  if (def.filtros.includes('semana') && f.semanaId) c.semanaId = f.semanaId;
  if (def.filtros.includes('periodo')) {
    if (f.de) c.de = f.de;
    if (f.ate) c.ate = f.ate;
  }
  if (def.filtros.includes('mes')) c.mes = Number(f.mes || mesDeHoje());
  for (const e of def.extras ?? []) {
    const bruto = (f.extras[e.chave] ?? '').trim().replace(',', '.');
    if (bruto === '') continue;
    // Valor digitado pela metade (por exemplo "12.") ainda não vira filtro.
    if (e.tipo === 'valor' && !VALOR_EM_REAIS.test(bruto)) continue;
    if (e.tipo === 'inteiro') {
      // Só número inteiro; o que está pela metade ou fora de 1 a 3650 ainda não vira filtro.
      if (e.chave === 'diasDeAtraso') {
        // Prazo da maleta: 0 a 365 dias (o servidor usa 7 quando vem vazio).
        if (/^\d{1,3}$/.test(bruto) && Number(bruto) <= 365) c.diasDeAtraso = Number(bruto);
        continue;
      }
      if (!/^\d{1,4}$/.test(bruto) || Number(bruto) < 1 || Number(bruto) > 3650) continue;
      if (e.chave === 'dias') c.dias = Number(bruto);
      continue;
    }
    c[e.chave as Exclude<typeof e.chave, 'dias' | 'diasDeAtraso'>] = bruto;
  }
  return c;
}

const VALOR_EM_REAIS = /^\d{1,12}(\.\d{1,2})?$/;

const SITUACOES_DE_PRODUTO = [
  { valor: '', rotulo: T.filtros.ativos },
  { valor: 'INATIVOS', rotulo: T.filtros.inativos },
  { valor: 'TODOS', rotulo: T.filtros.todos },
];

function nomeDaOpcao(lista: Opcao[], id: string): string {
  return lista.find((o) => o.id === id)?.nome ?? '';
}

/** Os filtros usados, em palavras, para o cabeçalho dos arquivos. */
function filtrosEmPalavras(
  def: DefinicaoDeRelatorio,
  f: Filtros,
  o: Opcoes,
  fixo: FiltroFixo | null,
): string[] {
  const x: string[] = [];
  if (fixo) x.push(fixo.texto);
  const tem = (nome: DefinicaoDeRelatorio['filtros'][number]) => def.filtros.includes(nome);
  if (tem('mes'))
    x.push(`${T.filtros.mes}: ${T.filtros.meses[Number(f.mes || mesDeHoje()) - 1] ?? ''}`);
  if (tem('busca') && f.busca) x.push(`${T.filtros.busca}: ${f.busca}`);
  if (tem('situacao')) {
    const opcao = (def.situacoes ?? SITUACOES_DE_PRODUTO).find((s) => s.valor === f.situacao);
    x.push(`${T.filtros.situacao}: ${opcao?.rotulo ?? T.filtros.ativos}`);
  }
  if (tem('usuario') && f.usuarioId) {
    x.push(`${T.filtros.usuario}: ${nomeDaOpcao(o.usuarios, f.usuarioId)}`);
  }
  if (tem('categoria') && f.categoriaId) {
    x.push(`${T.filtros.categoria}: ${nomeDaOpcao(o.categorias, f.categoriaId)}`);
  }
  if (tem('fornecedor') && f.fornecedorId) {
    x.push(`${T.filtros.fornecedor}: ${nomeDaOpcao(o.fornecedores, f.fornecedorId)}`);
  }
  if (tem('praca') && f.pracaId) x.push(`${T.filtros.praca}: ${nomeDaOpcao(o.pracas, f.pracaId)}`);
  if (tem('vendedor') && f.vendedorId) {
    x.push(`${T.filtros.vendedor}: ${nomeDaOpcao(o.vendedores, f.vendedorId)}`);
  }
  if (tem('restricao') && f.restricaoId) {
    x.push(`${T.filtros.restricao}: ${nomeDaOpcao(o.restricoes, f.restricaoId)}`);
  }
  if (tem('semana') && f.semanaId) {
    x.push(`${MALETAS.semana}: ${nomeDaOpcao(o.semanas, f.semanaId)}`);
  }
  if (tem('periodo') && (f.de || f.ate)) {
    x.push(
      SOLTOS.arquivo.periodo(
        f.de ? dataParaTela(f.de) : SOLTOS.arquivo.comeco,
        f.ate ? dataParaTela(f.ate) : SOLTOS.arquivo.hoje,
      ),
    );
  }
  for (const e of def.extras ?? []) {
    const v = (f.extras[e.chave] ?? '').trim();
    if (!v && e.tipo === 'inteiro' && e.padrao !== undefined) {
      x.push(`${e.rotulo}: ${e.padrao}`);
      continue;
    }
    if (!v) continue;
    const texto = e.tipo === 'escolha' ? (e.opcoes.find((o) => o.valor === v)?.rotulo ?? v) : v;
    x.push(`${e.rotulo}: ${texto}`);
  }
  return x;
}

function listaDe(bruto: unknown, nome: (i: Record<string, unknown>) => string): Opcao[] {
  const itens = (bruto as { itens?: unknown } | null)?.itens;
  if (!Array.isArray(itens)) return [];
  return itens
    .filter((i): i is Record<string, unknown> => typeof i === 'object' && i !== null)
    .filter((i) => typeof i.id === 'string')
    .map((i) => ({ id: comoTexto(i.id), nome: nome(i) }));
}

const OPCOES_VAZIAS: Opcoes = {
  usuarios: [],
  categorias: [],
  fornecedores: [],
  pracas: [],
  restricoes: [],
  vendedores: [],
  semanas: [],
};

/** Carrega as listas dos filtros (só as que o relatório usa). */
async function carregarOpcoes(
  ponte: PonteDoCorona,
  def: DefinicaoDeRelatorio,
): Promise<Retorno<Opcoes>> {
  const tem = (f: DefinicaoDeRelatorio['filtros'][number]) => def.filtros.includes(f);
  const pedir = (op: Parameters<typeof chamar>[1], ativo: boolean) =>
    ativo
      ? chamar(ponte, op, { pagina: 0, tamanho: 100, situacao: 'TODOS' })
      : Promise.resolve(null);
  const [cat, forn, praca, restr, vend, usu, sem] = await Promise.all([
    pedir('categorias.listar', tem('categoria') || def.nome === 'sem-foto'),
    pedir('fornecedores.listar', tem('fornecedor') || def.nome === 'sem-foto'),
    pedir('pracas.listar', tem('praca')),
    pedir('restricoes.listar', tem('restricao')),
    tem('vendedor') ? chamar(ponte, 'vendedores.escolha') : Promise.resolve(null),
    tem('usuario')
      ? chamar(ponte, 'usuarios.listar', { pagina: 0, tamanho: 100 })
      : Promise.resolve(null),
    pedir('semanas.listar', tem('semana')),
  ]);
  for (const r of [cat, forn, praca, restr, vend, usu, sem]) {
    if (r && !r.ok) return r;
  }
  const dados = (r: Retorno<unknown> | null) => (r && r.ok ? r.dados : null);
  return {
    ok: true,
    dados: {
      categorias: listaDe(dados(cat), (i) => comoTexto(i.nome)),
      fornecedores: listaDe(dados(forn), (i) => comoTexto(i.razaoSocial)),
      pracas: listaDe(dados(praca), (i) => comoTexto(i.nome)),
      restricoes: listaDe(
        dados(restr),
        (i) => `${comoTexto(i.codigo)} - ${comoTexto(i.descricao)}`,
      ),
      vendedores: listaDe(dados(vend), (i) => comoTexto(i.nome)),
      semanas: listaDe(dados(sem), (i) => `${MALETAS.semana} ${comoTexto(i.numero)}`),
      usuarios:
        usu && usu.ok
          ? lerPaginaDeUsuarios(usu.dados).itens.map((u) => ({ id: u.id, nome: u.nome }))
          : [],
    },
  };
}

function valorNaTela(
  coluna: ColunaDoRelatorio,
  valor: unknown,
  cotacao: ReturnType<typeof useCotacao>,
  ponte: PonteDoCorona,
  linha: Record<string, unknown>,
): ReactNode {
  // A foto nunca trava: sem foto (ou com falha) aparece o marcador "Sem foto".
  if (coluna.tipo === 'foto') {
    return (
      <ImagemDoProduto
        ponte={ponte}
        hash={typeof valor === 'string' && valor !== '' ? valor : null}
        tamanho="miniatura"
        nome={comoTexto(linha.nome)}
      />
    );
  }
  const v = comoTexto(valor);
  if (v === '') return '';
  switch (coluna.tipo) {
    case 'sinal':
      return typeof valor === 'number' ? quantidadeComSinal(valor) : v;
    case 'dinheiro': {
      if (coluna.soReal) {
        const so = tresMoedas(v, null);
        return so ? <ValorTresMoedas real={so.real} /> : '';
      }
      if (coluna.convertido) {
        // Maletas: Dólar e Guarani da cotação do dia do envio, já calculados pelo servidor.
        const so = tresMoedas(v, null);
        const d = centavosDeTexto(comoTexto(linha[coluna.convertido.dolar]));
        const g = centavosDeTexto(comoTexto(linha[coluna.convertido.guarani]));
        return so ? (
          <ValorTresMoedas
            real={so.real}
            {...(d !== null ? { dolar: formatarDolar(d) } : {})}
            {...(g !== null ? { guarani: formatarGuarani(g / 100n) } : {})}
          />
        ) : (
          ''
        );
      }
      const m = tresMoedas(v, cotacao.taxas);
      return m ? (
        <ValorTresMoedas
          real={m.real}
          {...(m.dolar ? { dolar: m.dolar } : {})}
          {...(m.guarani ? { guarani: m.guarani } : {})}
        />
      ) : (
        ''
      );
    }
    case 'percentual':
      return `${v.replace('.', ',')}%`;
    case 'data':
      return dataParaTela(v);
    case 'dataHora':
      return dataEHoraParaTela(v);
    case 'simNao':
      if (coluna.titulo === SOLTOS.arquivo.situacao)
        return valor === true ? T.situacaoDoProduto.ativo : T.situacaoDoProduto.inativo;
      return valor === true ? SOLTOS.arquivo.sim : SOLTOS.arquivo.nao;
    default:
      return v;
  }
}

export interface TelaDeRelatorioProps {
  ponte: PonteDoCorona;
  relatorio: NomeDoRelatorio;
  /** Quem está gerando (vai no cabeçalho dos arquivos). */
  usuario: string | null;
  /** Relatório aberto a partir de uma linha: um filtro fixo e o caminho de volta. */
  fixo?: FiltroFixo;
  aoVoltar?: () => void;
  /** Conteúdo da tela que abriu o relatório (ex.: o Fechamento da maleta), logo abaixo da explicação. */
  aposOTitulo?: ReactNode;
  /** Relatórios de maleta: abre a ficha da maleta da linha (na tela Maletas). */
  aoAbrirMaleta?: (id: string) => void;
  /** Relatório aberto a partir do painel da semana: já começa com estes filtros. */
  filtrosIniciais?: Partial<Pick<Filtros, 'semanaId' | 'vendedorId' | 'pracaId' | 'de' | 'ate'>>;
}

/**
 * Um relatório de cadastro (tarefa 1.12, §12): filtros, tabela, totais no rodapé com "Ver os registros"
 * e exportação em CSV, Excel e PDF. O relatório inteiro é montado pelo servidor; aqui só se mostra.
 */
export function TelaDeRelatorio({
  ponte,
  relatorio,
  usuario,
  fixo,
  aoVoltar,
  aposOTitulo,
  aoAbrirMaleta,
  filtrosIniciais,
}: TelaDeRelatorioProps) {
  const def = RELATORIOS[relatorio];
  const cotacao = useCotacao();
  const filtroFixo = fixo ?? null;
  // Linha aberta (compra → peças; contagem → diferenças; peça → histórico de custo).
  const [aberto, setAberto] = useState<{ relatorio: NomeDoRelatorio; fixo: FiltroFixo } | null>(
    null,
  );
  const [inicio] = useState<Filtros>(() => ({ ...SEM_FILTROS, ...filtrosIniciais }));
  const [rascunho, setRascunho] = useState<Filtros>(inicio);
  const [filtros, setFiltros] = useState<Filtros>(inicio);
  const [pagina, setPagina] = useState(0);
  const [recorte, setRecorte] = useState<string | null>(null);
  const [exportando, setExportando] = useState(false);
  const [avisoDeArquivo, setAvisoDeArquivo] = useState<{
    tipo: 'sucesso' | 'erro';
    texto: string;
  } | null>(null);

  const periodoInvertido = Boolean(rascunho.de && rascunho.ate && rascunho.ate < rascunho.de);

  // A busca por texto espera a pessoa parar de digitar; os outros filtros valem na hora.
  useEffect(() => {
    if (periodoInvertido) return;
    const espera = rascunho.busca === filtros.busca ? 0 : 300;
    const relogio = setTimeout(() => {
      setFiltros(rascunho);
      setPagina(0);
      setRecorte(null);
    }, espera);
    return () => clearTimeout(relogio);
  }, [rascunho, filtros.busca, periodoInvertido]);

  const opcoes = useBusca(() => carregarOpcoes(ponte, def), `opcoes|${relatorio}`);
  const listas = opcoes.dados ?? OPCOES_VAZIAS;

  // Relatório em partes (Fechamento da maleta): uma aba por parte; a escolha só muda o que aparece na tela, os arquivos levam todas.
  const [abaEscolhida, setAbaEscolhida] = useState(0);
  const aba = def.abas ? (def.abas[abaEscolhida] ?? def.abas[0]) : undefined;
  const mostraATabela = aba ? aba.comTabela : true;

  const consulta = useMemo(() => consultaDe(def, filtros, filtroFixo), [def, filtros, filtroFixo]);
  const chaveDosFiltros = `${relatorio}|${JSON.stringify(consulta)}`;

  const lista = useBusca(
    () =>
      chamar<unknown>(ponte, 'relatorios.cadastros', {
        relatorio,
        ...consulta,
        ...(recorte ? { recorte } : {}),
        pagina,
        tamanho: TAMANHO,
      }),
    `${chaveDosFiltros}|${recorte ?? ''}|${pagina}`,
  );
  const dados: RelatorioLido | null = lista.dados ? lerRelatorio(lista.dados, relatorio) : null;

  // O rodapé do relatório inteiro (sem o recorte): guardado para não sumir enquanto se vê um detalhamento.
  const [rodape, setRodape] = useState<{ chave: string; totais: TotalLido[] } | null>(null);
  useEffect(() => {
    if (dados && !recorte) setRodape({ chave: chaveDosFiltros, totais: dados.totais });
  }, [lista.dados]);
  const totais = rodape?.chave === chaveDosFiltros ? rodape.totais : (dados?.totais ?? []);
  // Com abas, cada uma mostra só os totais dela (as chaves que não estão em nenhuma ficam na primeira).
  const chavesDasAbas = (def.abas ?? []).flatMap((a) => a.totais);
  const totaisDaAba = !aba
    ? totais
    : totais.filter(
        (t) =>
          aba.totais.includes(t.chave) ||
          (aba.id === def.abas?.[0]?.id && !chavesDasAbas.includes(t.chave)),
      );
  const rotuloDoRecorte = totais.find((t) => t.chave === recorte)?.rotulo;

  // Todas as linhas do relatório (sem o recorte): servem ao arquivo e ao total em Dólar e Guarani,
  // que é a soma das linhas já convertidas.
  const todasAsLinhas = useRef<{
    chave: string;
    promessa: Promise<Retorno<Record<string, unknown>[]>>;
  } | null>(null);
  function carregarTodas(): Promise<Retorno<Record<string, unknown>[]>> {
    if (todasAsLinhas.current?.chave === chaveDosFiltros) return todasAsLinhas.current.promessa;
    const promessa = (async (): Promise<Retorno<Record<string, unknown>[]>> => {
      const linhas: Record<string, unknown>[] = [];
      for (let p = 0; p < MAXIMO_DE_LINHAS_NO_ARQUIVO / TAMANHO_DA_BUSCA_COMPLETA; p++) {
        const r = await chamar<unknown>(ponte, 'relatorios.cadastros', {
          relatorio,
          ...consulta,
          pagina: p,
          tamanho: TAMANHO_DA_BUSCA_COMPLETA,
        });
        if (!r.ok) return r;
        const lido = lerRelatorio(r.dados, relatorio);
        linhas.push(...lido.itens);
        if (linhas.length >= lido.total || lido.itens.length === 0) {
          return { ok: true, dados: linhas.map((l) => prepararLinha(relatorio, l, listas)) };
        }
      }
      return { ok: false, erro: { tipo: 'outro', mensagem: T.exportar.muitasLinhas, campos: {} } };
    })();
    todasAsLinhas.current = { chave: chaveDosFiltros, promessa };
    return promessa;
  }

  // Nas maletas o servidor já manda os totais convertidos; não há soma local.
  const jaConvertido = def.colunas.some((c) => c.convertido);
  const temValor = Object.keys(def.totaisDeValor).length > 0 && !jaConvertido;
  const [linhasParaSomar, setLinhasParaSomar] = useState<{
    chave: string;
    linhas: Record<string, unknown>[];
  } | null>(null);
  useEffect(() => {
    if (!temValor || !dados || !cotacao.taxas) return;
    let vale = true;
    void carregarTodas().then((r) => {
      if (vale && r.ok) setLinhasParaSomar({ chave: chaveDosFiltros, linhas: r.dados });
    });
    return () => {
      vale = false;
    };
  }, [chaveDosFiltros, temValor, dados !== null, cotacao.taxas]);

  async function exportar(formato: 'csv' | 'xlsx' | 'pdf') {
    if (exportando) return;
    setExportando(true);
    setAvisoDeArquivo(null);
    try {
      const r = await carregarTodas();
      if (!r.ok) {
        setAvisoDeArquivo({ tipo: 'erro', texto: r.erro.mensagem });
        return;
      }
      if (r.dados.length === 0) {
        setAvisoDeArquivo({ tipo: 'erro', texto: T.exportar.semLinhas });
        return;
      }
      const agora = new Date();
      const pedido: PedidoDeExportacao = {
        loja: T.loja,
        titulo: def.titulo,
        filtros: filtrosEmPalavras(def, filtros, listas, filtroFixo),
        geradoEm: dataEHoraParaTela(agora),
        geradoPor: usuario ?? SOLTOS.arquivo.dona,
        linhaDaCotacao: jaConvertido
          ? (def.notaDaCotacao ?? MALETAS.cotacaoDoEnvio)
          : cotacao.taxas
            ? `${cotacao.linha} ${T.cotacao.deHoje}`
            : null,
        dataDaCotacao: cotacao.data,
        cotacao: cotacao.taxas,
        colunas: def.colunas,
        linhas: r.dados,
        totais: totais.map((t) => ({ ...t })),
        totaisDeValor: def.totaisDeValor,
        ...(jaConvertido
          ? {
              totaisConvertidos: Object.fromEntries(
                totais
                  .filter((t) => t.valorEmDolar !== null && t.valorEmGuarani !== null)
                  .map((t) => [t.chave, { dolar: t.valorEmDolar!, guarani: t.valorEmGuarani! }]),
              ),
            }
          : {}),
        ...(def.linhasDoTotal ? { linhasDoTotal: def.linhasDoTotal } : {}),
        ...(def.abas
          ? { secoes: def.abas.map((a) => ({ titulo: a.rotulo, totais: a.totais })) }
          : {}),
      };
      const grade = montarGrade(pedido);
      const base = `${def.nome}-${hojeEmBrasilia(agora)}`;
      if (formato === 'csv') {
        entregarArquivo(
          `${base}.csv`,
          'text/csv;charset=utf-8',
          new TextEncoder().encode(gerarCsv(grade)),
        );
        setAvisoDeArquivo({ tipo: 'sucesso', texto: T.exportar.pronto(`${base}.csv`) });
      } else if (formato === 'xlsx') {
        entregarArquivo(
          `${base}.xlsx`,
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          gerarXlsx(grade),
        );
        setAvisoDeArquivo({ tipo: 'sucesso', texto: T.exportar.pronto(`${base}.xlsx`) });
      } else {
        entregarArquivo(`${base}.pdf`, 'application/pdf', gerarPdf(grade));
        setAvisoDeArquivo({ tipo: 'sucesso', texto: T.exportar.pronto(`${base}.pdf`) });
      }
    } catch {
      setAvisoDeArquivo({ tipo: 'erro', texto: T.exportar.erro });
    } finally {
      setExportando(false);
    }
  }

  function mudarExtra(chave: string, valor: string) {
    setRascunho((r) => ({ ...r, extras: { ...r.extras, [chave]: valor } }));
  }

  function mudar(campo: Exclude<keyof Filtros, 'extras'>, valor: string) {
    setRascunho((r) => ({ ...r, [campo]: valor }));
  }

  function seletor(
    id: string,
    rotulo: string,
    campo: Exclude<keyof Filtros, 'extras'>,
    itens: Opcao[],
    qualquer: string,
  ) {
    return (
      <div className="cs-campo">
        <label htmlFor={id}>{rotulo}</label>
        <select id={id} value={rascunho[campo]} onChange={(e) => mudar(campo, e.target.value)}>
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

  const filtrando = JSON.stringify(rascunho) !== JSON.stringify(SEM_FILTROS);
  const colunasDaTabela = def.colunas.map((c) => ({
    chave: c.chave,
    titulo: c.titulo,
    numerica: c.tipo === 'inteiro' || c.tipo === 'dinheiro' || c.tipo === 'percentual',
  }));
  const acoes = def.acoes ?? [];
  if (acoes.length > 0 || def.fichaDaMaleta)
    colunasDaTabela.push({
      chave: '_abrir',
      titulo: T.estoqueECompras.acoes.coluna,
      numerica: false,
    });
  const linhasDaTabela = (dados?.itens ?? []).map((l) => {
    const linha = prepararLinha(relatorio, l, listas);
    const celulas: Record<string, ReactNode> = Object.fromEntries(
      def.colunas.map((c) => [c.chave, valorNaTela(c, linha[c.chave], cotacao, ponte, linha)]),
    );
    if (acoes.length > 0 || def.fichaDaMaleta) {
      const idDaMaleta = def.fichaDaMaleta ? comoTexto(linha[def.fichaDaMaleta]) : '';
      celulas._abrir = (
        <span className="gestao-acoes-da-linha">
          {idDaMaleta && aoAbrirMaleta ? (
            <Botao
              aria-label={`${MALETAS.verAMaleta}: ${comoTexto(linha.numero) || comoTexto(linha.vendedor)}`}
              onClick={() => aoAbrirMaleta(idDaMaleta)}
            >
              {MALETAS.verAMaleta}
            </Botao>
          ) : null}
          {acoes
            .filter((a) => (a.quando ? a.quando(linha) : true))
            .map((a) => {
              const valor = comoTexto(linha[a.campo]);
              if (valor === '') return null;
              const titulo = a.titulo(linha);
              return (
                <Botao
                  key={a.rotulo}
                  aria-label={`${a.rotulo}: ${titulo}`}
                  onClick={() =>
                    setAberto({
                      relatorio: a.abre,
                      fixo: { filtro: a.filtro, valor, texto: titulo },
                    })
                  }
                >
                  {a.rotulo}
                </Botao>
              );
            })}
        </span>
      );
    }
    return celulas;
  });

  function totalNaTela(t: TotalLido): ReactNode {
    if (t.valorEmReais === null) {
      return t.pecas !== null ? T.totais.pecas(t.pecas) : String(t.quantidade);
    }
    const campo = def.totaisDeValor[t.chave];
    const m = tresMoedas(t.valorEmReais, null);
    const soma =
      campo && linhasParaSomar?.chave === chaveDosFiltros
        ? somarConvertido(
            linhasParaSomar.linhas,
            campo,
            cotacao.taxas,
            def.linhasDoTotal?.[t.chave],
          )
        : null;
    const dServidor = t.valorEmDolar !== null ? centavosDeTexto(t.valorEmDolar) : null;
    const gServidor = t.valorEmGuarani !== null ? centavosDeTexto(t.valorEmGuarani) : null;
    const dolar =
      dServidor !== null
        ? formatarDolar(dServidor)
        : soma
          ? formatarDolar(soma.dolarEmCentavos)
          : undefined;
    const guarani =
      gServidor !== null
        ? formatarGuarani(gServidor / 100n)
        : soma
          ? formatarGuarani(soma.guarani)
          : undefined;
    return (
      <>
        {t.pecas !== null ? `${T.totais.pecas(t.pecas)} · ` : null}
        <ValorTresMoedas
          real={m?.real ?? ''}
          {...(dolar ? { dolar } : {})}
          {...(guarani ? { guarani } : {})}
        />
      </>
    );
  }

  if (aberto) {
    return (
      <TelaDeRelatorio
        ponte={ponte}
        relatorio={aberto.relatorio}
        usuario={usuario}
        fixo={aberto.fixo}
        aoVoltar={() => setAberto(null)}
        {...(aoAbrirMaleta ? { aoAbrirMaleta } : {})}
      />
    );
  }

  return (
    <div className="gestao-tela">
      {aoVoltar ? (
        <div>
          <Botao onClick={aoVoltar}>{T.estoqueECompras.acoes.voltar}</Botao>
        </div>
      ) : null}
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{def.titulo}</h1>
      </div>
      <p className="gestao-texto">{def.explicacao}</p>
      {filtroFixo ? (
        <Aviso tipo="informacao">{T.estoqueECompras.acoes.filtroFixo(filtroFixo.texto)}</Aviso>
      ) : null}
      {def.nota ? <Aviso tipo="informacao">{def.nota}</Aviso> : null}
      {aposOTitulo}

      {def.abas ? (
        <div role="tablist" aria-label={def.titulo} className="gestao-abas">
          {def.abas.map((a, i) => (
            <button
              key={a.id}
              type="button"
              role="tab"
              id={`aba-relatorio-${a.id}`}
              aria-selected={aba?.id === a.id}
              aria-controls="painel-relatorio"
              className={`gestao-aba-botao${aba?.id === a.id ? ' gestao-aba-botao--atual' : ''}`}
              onClick={() => setAbaEscolhida(i)}
            >
              {a.rotulo}
            </button>
          ))}
        </div>
      ) : null}

      <form
        className="gestao-filtros"
        role="search"
        aria-label={T.rotuloDosFiltros}
        onSubmit={(e) => {
          e.preventDefault();
          if (!periodoInvertido) {
            setFiltros(rascunho);
            setPagina(0);
            setRecorte(null);
          }
        }}
      >
        {def.filtros.includes('mes') ? (
          <div className="cs-campo">
            <label htmlFor="filtro-mes">{T.filtros.mes}</label>
            <select
              id="filtro-mes"
              value={rascunho.mes || mesDeHoje()}
              onChange={(e) => mudar('mes', e.target.value)}
            >
              {T.filtros.meses.map((nome, i) => (
                <option key={nome} value={String(i + 1)}>
                  {nome}
                </option>
              ))}
            </select>
          </div>
        ) : null}
        {def.filtros.includes('busca') ? (
          <Campo
            rotulo={def.buscaRotulo}
            type="search"
            value={rascunho.busca}
            autoComplete="off"
            onChange={(e) => mudar('busca', e.target.value)}
          />
        ) : null}
        {def.filtros.includes('situacao') ? (
          <div className="cs-campo">
            <label htmlFor="filtro-situacao">{T.filtros.situacao}</label>
            <select
              id="filtro-situacao"
              value={rascunho.situacao}
              onChange={(e) => mudar('situacao', e.target.value)}
            >
              {(def.situacoes ?? SITUACOES_DE_PRODUTO)
                .filter((o) => !(def.semOpcaoTodos && o.valor === 'TODOS'))
                .map((o) => (
                  <option key={o.valor} value={o.valor}>
                    {o.rotulo}
                  </option>
                ))}
            </select>
          </div>
        ) : null}
        {def.filtros.includes('categoria')
          ? seletor(
              'filtro-categoria',
              T.filtros.categoria,
              'categoriaId',
              listas.categorias,
              T.filtros.qualquer,
            )
          : null}
        {def.filtros.includes('fornecedor')
          ? seletor(
              'filtro-fornecedor',
              T.filtros.fornecedor,
              'fornecedorId',
              listas.fornecedores,
              T.filtros.qualquerUm,
            )
          : null}
        {def.filtros.includes('semana')
          ? seletor(
              'filtro-semana',
              MALETAS.semana,
              'semanaId',
              listas.semanas,
              MALETAS.qualquerSemana,
            )
          : null}
        {def.filtros.includes('praca')
          ? seletor('filtro-praca', T.filtros.praca, 'pracaId', listas.pracas, T.filtros.qualquer)
          : null}
        {def.filtros.includes('vendedor')
          ? seletor(
              'filtro-vendedor',
              T.filtros.vendedor,
              'vendedorId',
              listas.vendedores,
              T.filtros.qualquerUm,
            )
          : null}
        {def.filtros.includes('restricao')
          ? seletor(
              'filtro-restricao',
              T.filtros.restricao,
              'restricaoId',
              listas.restricoes,
              T.filtros.qualquer,
            )
          : null}
        {def.filtros.includes('usuario')
          ? seletor(
              'filtro-usuario',
              T.filtros.usuario,
              'usuarioId',
              listas.usuarios,
              T.filtros.qualquerUm,
            )
          : null}
        {def.filtros.includes('periodo') ? (
          <>
            <Campo
              rotulo={T.filtros.de}
              type="date"
              value={rascunho.de}
              onChange={(e) => mudar('de', e.target.value)}
            />
            <Campo
              rotulo={T.filtros.ate}
              type="date"
              value={rascunho.ate}
              {...(periodoInvertido ? { erro: T.filtros.periodoInvertido } : {})}
              onChange={(e) => mudar('ate', e.target.value)}
            />
          </>
        ) : null}
        {(def.extras ?? []).map((e) =>
          e.tipo === 'escolha' ? (
            <div className="cs-campo" key={e.chave}>
              <label htmlFor={`filtro-${e.chave}`}>{e.rotulo}</label>
              <select
                id={`filtro-${e.chave}`}
                value={rascunho.extras[e.chave] ?? ''}
                onChange={(ev) => mudarExtra(e.chave, ev.target.value)}
              >
                <option value="">{e.qualquer}</option>
                {e.opcoes.map((o) => (
                  <option key={o.valor} value={o.valor}>
                    {o.rotulo}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <Campo
              key={e.chave}
              id={`filtro-${e.chave}`}
              rotulo={e.rotulo}
              {...(e.tipo === 'inteiro' && e.padrao !== undefined
                ? { placeholder: String(e.padrao) }
                : {})}
              inputMode={e.tipo === 'inteiro' ? 'numeric' : 'decimal'}
              autoComplete="off"
              value={rascunho.extras[e.chave] ?? ''}
              onChange={(ev) => mudarExtra(e.chave, ev.target.value)}
            />
          ),
        )}
        {filtrando ? (
          <Botao
            onClick={() => {
              setRascunho(SEM_FILTROS);
              setFiltros(SEM_FILTROS);
              setPagina(0);
              setRecorte(null);
            }}
          >
            {T.filtros.limpar}
          </Botao>
        ) : null}
      </form>

      {opcoes.erro && opcoes.dados === null ? (
        <AvisoDeErro erro={opcoes.erro} aoTentarDeNovo={opcoes.recarregar} />
      ) : null}

      <EstadoDaLista
        carregando={lista.carregando && dados === null}
        erro={lista.erro && dados === null ? lista.erro.mensagem : null}
        textoCarregando={T.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        {lista.erro && dados !== null ? (
          <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} />
        ) : null}
        {mostraATabela ? (
          <>
            {recorte ? (
              <Aviso tipo="informacao">{T.totais.mostrando(rotuloDoRecorte ?? '')}</Aviso>
            ) : null}
            <Tabela
              legenda={def.titulo}
              colunas={colunasDaTabela}
              linhas={linhasDaTabela}
              textoVazio={def.vazio}
            />
            {dados ? (
              <Paginacao
                pagina={dados.pagina}
                tamanho={dados.tamanho}
                total={dados.total}
                nomeDoItem={[def.itens.um, def.itens.varios]}
                aoMudar={setPagina}
              />
            ) : null}
          </>
        ) : null}
      </EstadoDaLista>

      {totaisDaAba.length > 0 ? (
        <section
          className="gestao-totais"
          id="painel-relatorio"
          role={def.abas ? 'tabpanel' : undefined}
          aria-label={aba ? aba.rotulo : T.totais.titulo}
        >
          <h2 className="gestao-subtitulo">{aba ? aba.rotulo : T.totais.titulo}</h2>
          <ul>
            {totaisDaAba.map((t) => (
              <li key={t.chave}>
                <span className="gestao-totais__rotulo">{t.rotulo}</span>
                <span className="gestao-totais__valor">{totalNaTela(t)}</span>
                {mostraATabela ? (
                  <Botao
                    onClick={() => {
                      setRecorte(t.chave === 'todos' ? null : t.chave);
                      setPagina(0);
                    }}
                    aria-label={`${T.totais.verRegistros}: ${t.rotulo}`}
                  >
                    {T.totais.verRegistros}
                  </Botao>
                ) : null}
              </li>
            ))}
          </ul>
          {recorte ? (
            <Botao
              onClick={() => {
                setRecorte(null);
                setPagina(0);
              }}
            >
              {T.totais.voltarAoRelatorio}
            </Botao>
          ) : null}
          {temValor && cotacao.taxas && linhasParaSomar?.chave !== chaveDosFiltros ? (
            <p className="gestao-texto">{T.totais.calculando}</p>
          ) : null}
        </section>
      ) : null}

      <section className="gestao-exportar" aria-label={T.exportar.titulo}>
        <h2 className="gestao-subtitulo">{T.exportar.titulo}</h2>
        <div className="gestao-acoes-da-linha">
          <Botao disabled={exportando} onClick={() => void exportar('pdf')}>
            {T.exportar.pdf}
          </Botao>
          <Botao disabled={exportando} onClick={() => void exportar('xlsx')}>
            {T.exportar.excel}
          </Botao>
          <Botao disabled={exportando} onClick={() => void exportar('csv')}>
            {T.exportar.csv}
          </Botao>
        </div>
        {exportando ? <p className="gestao-texto">{T.exportar.preparando}</p> : null}
        {avisoDeArquivo ? <Aviso tipo={avisoDeArquivo.tipo}>{avisoDeArquivo.texto}</Aviso> : null}
      </section>
    </div>
  );
}
