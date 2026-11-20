import { Aviso, Botao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { lerBytes } from '../../nucleo/arquivos';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { guardarResultadoDaImportacao, novoLoteDeImportacao } from '../../nucleo/importacoes';
import { chaveDoCodigo, digitoDoEan13Confere } from '../../nucleo/codigos';
import {
  TAMANHO_MAXIMO_DA_PLANILHA,
  analisarPlanilha,
  contarPrevia,
  lerPlanilha,
  montarPrevia,
  type LinhaDaPrevia,
  type ProdutoExistente,
} from '../../nucleo/planilha';
import { TEXTOS } from '../../textos';
import { lerOpcoes, lerProduto } from './dados';
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';

const T = TEXTOS.produtos.planilha;
/** Quantas linhas aparecem na tela de uma vez; as recusadas aparecem sempre. */
const LINHAS_NA_TELA = 200;
const CONSULTAS_AO_MESMO_TEMPO = 5;

type Resultado = {
  linha: LinhaDaPrevia;
  resultado: 'criado' | 'atualizado' | 'recusado';
  motivo?: string;
};

type Fase =
  | { fase: 'inicio' }
  | { fase: 'lendo'; etapa: string }
  | { fase: 'previa'; linhas: LinhaDaPrevia[] }
  | { fase: 'importando'; feitos: number; total: number }
  | { fase: 'relatorio'; resultados: Resultado[]; guardado: boolean };

export interface ImportacaoDePlanilhaProps {
  ponte: PonteDoCorona;
  /** A lista de produtos precisa ser lida de novo depois de uma importação. */
  aoMudar: () => void;
}

async function todasAsCategorias(ponte: PonteDoCorona) {
  const todas: { id: string; nome: string }[] = [];
  for (let pagina = 0; pagina < 50; pagina++) {
    const r = await chamar(ponte, 'categorias.listar', {
      pagina,
      tamanho: 100,
      situacao: 'TODOS',
    });
    if (!r.ok) return r;
    const itens = lerOpcoes(r.dados);
    todas.push(...itens);
    if (itens.length < 100) break;
  }
  return { ok: true as const, dados: todas };
}

/** Procura os códigos que já existem, poucos de cada vez. */
async function acharExistentes(ponte: PonteDoCorona, codigos: string[]) {
  const achados = new Map<string, ProdutoExistente>();
  let falha: ErroDaTela | null = null;
  let proximo = 0;
  const trabalhador = async () => {
    while (!falha && proximo < codigos.length) {
      const codigo = codigos[proximo++]!;
      const r = await chamar(ponte, 'produtos.porCodigo', { codigo });
      if (r.ok) {
        const p = lerProduto(r.dados);
        if (p) {
          achados.set(chaveDoCodigo(codigo), {
            id: p.id,
            version: p.version,
            nome: p.nome,
            referencia: p.referencia,
            ...(p.tamTon ? { tamTon: p.tamTon } : {}),
            ...(p.fornecedorId ? { fornecedorId: p.fornecedorId } : {}),
            estoqueMinimo: p.estoqueMinimo,
          });
        }
      } else if (r.erro.status !== 404) falha = r.erro;
    }
  };
  await Promise.all(Array.from({ length: CONSULTAS_AO_MESMO_TEMPO }, trabalhador));
  return falha
    ? { ok: false as const, erro: falha as ErroDaTela }
    : { ok: true as const, dados: achados };
}

/** Grava uma linha da prévia; o servidor decide de novo (código repetido, categoria que sumiu...). */
async function gravarLinha(ponte: PonteDoCorona, l: LinhaDaPrevia): Promise<Resultado> {
  if (l.situacao === 'recusar')
    return { linha: l, resultado: 'recusado', motivo: l.motivo ?? T.recusadaPeloServidor };
  const comum = {
    nome: l.nome,
    categoriaId: l.categoriaId,
    precoVenda: l.preco,
    precoCusto: l.custo,
    // Opcional: a planilha sem o valor não apaga o Tam./Ton. que o produto já tem.
    ...(l.tamTon ? { tamTon: l.tamTon } : {}),
  };
  const r =
    l.situacao === 'criar'
      ? await chamar(ponte, 'produtos.criar', {
          id: crypto.randomUUID(),
          ...comum,
          codigos: [
            {
              codigo: l.codigo,
              tipo: digitoDoEan13Confere(l.codigo) ? 'EAN13' : 'LOJA',
              principal: true,
            },
          ],
        })
      : await chamar(ponte, 'produtos.editar', {
          id: l.existente!.id,
          ...comum,
          ...(l.existente!.referencia ? { referencia: l.existente!.referencia } : {}),
          ...(!l.tamTon && l.existente!.tamTon ? { tamTon: l.existente!.tamTon } : {}),
          ...(l.existente!.fornecedorId ? { fornecedorId: l.existente!.fornecedorId } : {}),
          estoqueMinimo: l.existente!.estoqueMinimo ?? 0,
          version: l.existente!.version,
        });
  if (r.ok) return { linha: l, resultado: l.situacao === 'criar' ? 'criado' : 'atualizado' };
  const campo = Object.values(r.erro.campos)[0];
  return { linha: l, resultado: 'recusado', motivo: campo ?? r.erro.mensagem };
}

function ordenar<T extends { motivo?: string | undefined }>(
  itens: T[],
  recusado: (i: T) => boolean,
): T[] {
  return [...itens.filter(recusado), ...itens.filter((i) => !recusado(i))];
}

/** Importação em massa: escolher planilha → prévia linha a linha (nada gravado) → confirmar → relatório. */
export function ImportacaoDePlanilha({ ponte, aoMudar }: ImportacaoDePlanilhaProps) {
  const [estado, setEstado] = useState<Fase>({ fase: 'inicio' });
  const [mensagem, setMensagem] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const entrada = useRef<HTMLInputElement>(null);
  const nomeDoArquivo = useRef<string | undefined>(undefined);

  async function escolher(arquivo: File) {
    setMensagem(null);
    setErro(null);
    if (!/\.(xlsx|csv)$/i.test(arquivo.name)) return setMensagem(T.arquivoInvalido);
    if (arquivo.size > TAMANHO_MAXIMO_DA_PLANILHA) return setMensagem(T.arquivoGrande);
    nomeDoArquivo.current = arquivo.name;
    setEstado({ fase: 'lendo', etapa: T.lendo });
    let tabela;
    try {
      tabela = await lerPlanilha(arquivo.name, await lerBytes(arquivo));
    } catch {
      setEstado({ fase: 'inicio' });
      return setMensagem(T.naoLeu);
    }
    const cats = await todasAsCategorias(ponte);
    if (!cats.ok) {
      setEstado({ fase: 'inicio' });
      return setErro(cats.erro);
    }
    const lida = analisarPlanilha(tabela, cats.dados);
    if (lida.tipo === 'erro') {
      setEstado({ fase: 'inicio' });
      return setMensagem(lida.mensagem);
    }
    setEstado({ fase: 'lendo', etapa: T.conferindo });
    const boas = lida.linhas.filter((l) => !l.motivo).map((l) => l.codigo);
    const existentes = await acharExistentes(ponte, boas);
    if (!existentes.ok) {
      setEstado({ fase: 'inicio' });
      return setErro(existentes.erro);
    }
    setEstado({ fase: 'previa', linhas: montarPrevia(lida.linhas, existentes.dados) });
  }

  async function importar(linhas: LinhaDaPrevia[]) {
    const resultados: Resultado[] = [];
    setEstado({ fase: 'importando', feitos: 0, total: linhas.length });
    for (const l of linhas) {
      resultados.push(await gravarLinha(ponte, l));
      setEstado({ fase: 'importando', feitos: resultados.length, total: linhas.length });
    }
    // O resultado vai para o servidor (relatório "Resultado das importações"); se falhar, a importação já está feita.
    const guardado = await guardarResultadoDaImportacao(
      ponte,
      novoLoteDeImportacao(),
      'PLANILHA',
      nomeDoArquivo.current,
      resultados.map((r) => ({
        referencia: SOLTOS.planilha.referenciaDaLinha(r.linha.linha, r.linha.codigo),
        resultado:
          r.resultado === 'criado'
            ? 'CRIADO'
            : r.resultado === 'atualizado'
              ? 'ATUALIZADO'
              : 'RECUSADO',
        ...(r.resultado === 'recusado' && r.motivo ? { motivo: r.motivo } : {}),
      })),
    );
    setEstado({ fase: 'relatorio', resultados, guardado });
    aoMudar();
  }

  const situacaoNaTela = (l: LinhaDaPrevia) => T.situacoes[l.situacao];

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-planilha">
      <h2 id="titulo-planilha" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      {mensagem ? <Aviso tipo="erro">{mensagem}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}

      <input
        ref={entrada}
        type="file"
        hidden
        accept=".xlsx,.csv"
        aria-label={T.escolher}
        data-testid="escolher-planilha"
        onChange={(e) => {
          const arquivo = e.target.files?.[0];
          e.target.value = '';
          if (arquivo) void escolher(arquivo);
        }}
      />

      {estado.fase === 'inicio' ? (
        <Botao variante="primario" onClick={() => entrada.current?.click()}>
          {T.escolher}
        </Botao>
      ) : null}
      {estado.fase === 'lendo' ? <p role="status">{estado.etapa}</p> : null}

      {estado.fase === 'previa'
        ? (() => {
            const n = contarPrevia(estado.linhas);
            const mostradas = ordenar(estado.linhas, (l) => l.situacao === 'recusar').slice(
              0,
              Math.max(LINHAS_NA_TELA, n.recusar),
            );
            const importaveis = estado.linhas.filter((l) => l.situacao !== 'recusar');
            return (
              <>
                <Aviso tipo="informacao">{T.previa}</Aviso>
                <p className="gestao-texto">{T.resumo(n.criar, n.atualizar, n.recusar)}</p>
                {importaveis.length === 0 ? <Aviso tipo="atencao">{T.soRecusadas}</Aviso> : null}
                <Tabela
                  legenda={T.legendaPrevia}
                  colunas={[
                    { chave: 'linha', titulo: T.colunas.linha, numerica: true },
                    { chave: 'codigo', titulo: T.colunas.codigo },
                    { chave: 'nome', titulo: T.colunas.nome },
                    { chave: 'situacao', titulo: T.colunas.o_que },
                    { chave: 'motivo', titulo: T.colunas.motivo },
                  ]}
                  linhas={mostradas.map((l) => ({
                    linha: l.linha,
                    codigo: l.codigo,
                    nome: l.nome,
                    situacao: situacaoNaTela(l),
                    motivo: l.motivo ?? '',
                  }))}
                />
                <div className="gestao-acoes-da-linha">
                  <Botao onClick={() => setEstado({ fase: 'inicio' })}>
                    {TEXTOS.formulario.cancelar}
                  </Botao>
                  <Botao onClick={() => entrada.current?.click()}>{T.outra}</Botao>
                  <Botao
                    variante="primario"
                    disabled={importaveis.length === 0}
                    onClick={() => void importar(estado.linhas)}
                  >
                    {T.confirmar}
                  </Botao>
                </div>
              </>
            );
          })()
        : null}

      {estado.fase === 'importando' ? (
        <p role="status">{T.importando(estado.feitos, estado.total)}</p>
      ) : null}

      {estado.fase === 'relatorio'
        ? (() => {
            const c = estado.resultados.filter((r) => r.resultado === 'criado').length;
            const a = estado.resultados.filter((r) => r.resultado === 'atualizado').length;
            const recusados = estado.resultados.filter((r) => r.resultado === 'recusado');
            const mostradas = ordenar(estado.resultados, (r) => r.resultado === 'recusado').slice(
              0,
              Math.max(LINHAS_NA_TELA, recusados.length),
            );
            return (
              <>
                <Aviso tipo={recusados.length === 0 ? 'sucesso' : 'atencao'} titulo={T.relatorio}>
                  {T.relatorioResumo(c, a, recusados.length)}
                </Aviso>
                {estado.guardado ? null : <Aviso tipo="atencao">{T.naoGuardou}</Aviso>}
                <Tabela
                  legenda={T.legendaRelatorio}
                  colunas={[
                    { chave: 'linha', titulo: T.colunas.linha, numerica: true },
                    { chave: 'codigo', titulo: T.colunas.codigo },
                    { chave: 'nome', titulo: T.colunas.nome },
                    { chave: 'resultado', titulo: T.colunas.o_que },
                    { chave: 'motivo', titulo: T.colunas.motivo },
                  ]}
                  linhas={mostradas.map((r) => ({
                    linha: r.linha.linha,
                    codigo: r.linha.codigo,
                    nome: r.linha.nome,
                    resultado: T.resultadoLinha[r.resultado],
                    motivo: r.motivo ?? '',
                  }))}
                />
                <Botao variante="primario" onClick={() => setEstado({ fase: 'inicio' })}>
                  {T.novaImportacao}
                </Botao>
              </>
            );
          })()
        : null}
    </section>
  );
}
