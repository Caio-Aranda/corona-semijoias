import { Aviso, Botao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import {
  FOTOS_POR_LOTE,
  TAMANHO_MAXIMO_DA_FOTO,
  extensaoDeFotoAceita,
} from '../../../shared/produtos';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { lerBytes } from '../../nucleo/arquivos';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';

const T = TEXTOS.produtos.importacaoDeFotos;

type Situacao = keyof typeof T.situacoes;

interface Associada {
  nome: string;
  produtoNome: string;
}
interface ResultadoDoArquivo {
  nome: string;
  situacao: Situacao;
  mensagem: string;
}
interface Previa {
  associadas: Associada[];
  semProduto: { nome: string; mensagem: string }[];
  ambiguas: { codigo: string; nomes: string[]; mensagem: string }[];
  semFoto: { total: number; itens: string[] };
}

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const lista = (v: unknown): Record<string, unknown>[] => (Array.isArray(v) ? v.map(objeto) : []);
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');

function lerPrevia(bruto: unknown): Previa {
  const o = objeto(bruto);
  const sf = objeto(o.produtosSemFoto);
  return {
    associadas: lista(o.associadas).map((a) => ({
      nome: texto(a.nome),
      produtoNome: texto(a.produtoNome),
    })),
    semProduto: lista(o.semProduto).map((a) => ({
      nome: texto(a.nome),
      mensagem: texto(a.mensagem),
    })),
    ambiguas: lista(o.ambiguas).map((a) => ({
      codigo: texto(a.codigo),
      nomes: Array.isArray(a.nomes)
        ? a.nomes.filter((n): n is string => typeof n === 'string')
        : [],
      mensagem: texto(a.mensagem),
    })),
    semFoto: {
      total: typeof sf.total === 'number' ? sf.total : 0,
      itens: lista(sf.itens).map((i) => texto(i.nome)),
    },
  };
}

function lerResultados(bruto: unknown): ResultadoDoArquivo[] {
  return lista(objeto(bruto).resultados).map((r) => ({
    nome: texto(r.nome),
    situacao: (texto(r.situacao) in T.situacoes ? texto(r.situacao) : 'RECUSADA') as Situacao,
    mensagem: texto(r.mensagem),
  }));
}

type Fase =
  | { fase: 'inicio' }
  | { fase: 'conferindo' }
  | { fase: 'previa'; arquivos: File[]; ignorados: number; previa: Previa }
  | { fase: 'enviando'; feitas: number; total: number }
  | { fase: 'relatorio'; resultados: ResultadoDoArquivo[] };

export interface ImportacaoDeFotosProps {
  ponte: PonteDoCorona;
  aoMudar: () => void;
}

/** Fotos em massa, nomeadas pelo código do produto: prévia (só os nomes) → confirmar → relatório. */
export function ImportacaoDeFotos({ ponte, aoMudar }: ImportacaoDeFotosProps) {
  const [estado, setEstado] = useState<Fase>({ fase: 'inicio' });
  const [mensagem, setMensagem] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const entrada = useRef<HTMLInputElement>(null);
  const pasta = useRef<HTMLInputElement>(null);

  async function escolher(escolhidos: File[]) {
    setMensagem(null);
    setErro(null);
    const boas = escolhidos.filter(
      (f) => f.size > 0 && f.size <= TAMANHO_MAXIMO_DA_FOTO && extensaoDeFotoAceita(f.name),
    );
    if (boas.length === 0) return setMensagem(T.semFotoValida);
    setEstado({ fase: 'conferindo' });
    const r = await chamar(ponte, 'produtos.importacao.previa', { nomes: boas.map((f) => f.name) });
    if (!r.ok) {
      setEstado({ fase: 'inicio' });
      return setErro(r.erro);
    }
    setEstado({
      fase: 'previa',
      arquivos: boas,
      ignorados: escolhidos.length - boas.length,
      previa: lerPrevia(r.dados),
    });
  }

  async function enviar(arquivos: File[], previa: Previa) {
    // Só segue o que achou o produto; nome repetido não vai (entre lotes o servidor não veria a repetição).
    const nomesBons = new Set(previa.associadas.map((a) => a.nome));
    const nomesRepetidos = new Set(previa.ambiguas.flatMap((a) => a.nomes));
    const aEnviar = arquivos.filter((f) => nomesBons.has(f.name) && !nomesRepetidos.has(f.name));
    const resultados: ResultadoDoArquivo[] = [
      ...previa.semProduto.map((s) => ({
        nome: s.nome,
        situacao: 'SEM_PRODUTO' as const,
        mensagem: s.mensagem,
      })),
      ...previa.ambiguas.flatMap((a) =>
        a.nomes.map((nome) => ({ nome, situacao: 'AMBIGUA' as const, mensagem: a.mensagem })),
      ),
    ];
    setEstado({ fase: 'enviando', feitas: 0, total: aEnviar.length });
    for (let i = 0; i < aEnviar.length; i += FOTOS_POR_LOTE) {
      const lote = aEnviar.slice(i, i + FOTOS_POR_LOTE);
      const pedido = await Promise.all(
        lote.map(async (f) => ({ nome: f.name, bytes: await lerBytes(f) })),
      );
      const r = await chamar(ponte, 'produtos.importacao.confirmar', { arquivos: pedido });
      if (r.ok) resultados.push(...lerResultados(r.dados));
      else {
        resultados.push(
          ...lote.map((f) => ({
            nome: f.name,
            situacao: 'RECUSADA' as const,
            mensagem: r.erro.mensagem,
          })),
        );
      }
      setEstado({
        fase: 'enviando',
        feitas: Math.min(i + FOTOS_POR_LOTE, aEnviar.length),
        total: aEnviar.length,
      });
    }
    setEstado({ fase: 'relatorio', resultados });
    aoMudar();
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-importar-fotos">
      <h2 id="titulo-importar-fotos" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      {mensagem ? <Aviso tipo="erro">{mensagem}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}

      <input
        ref={entrada}
        type="file"
        hidden
        multiple
        accept="image/jpeg,image/png,image/webp"
        aria-label={T.escolher}
        data-testid="escolher-fotos"
        onChange={(e) => {
          const arquivos = Array.from(e.target.files ?? []);
          e.target.value = '';
          if (arquivos.length > 0) void escolher(arquivos);
        }}
      />
      <input
        ref={pasta}
        type="file"
        hidden
        multiple
        aria-label={T.escolherPasta}
        data-testid="escolher-pasta"
        {...({ webkitdirectory: '' } as Record<string, string>)}
        onChange={(e) => {
          const arquivos = Array.from(e.target.files ?? []);
          e.target.value = '';
          if (arquivos.length > 0) void escolher(arquivos);
        }}
      />

      {estado.fase === 'inicio' ? (
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" onClick={() => entrada.current?.click()}>
            {T.escolher}
          </Botao>
          <Botao onClick={() => pasta.current?.click()}>{T.escolherPasta}</Botao>
        </div>
      ) : null}
      {estado.fase === 'conferindo' ? <p role="status">{T.conferindo}</p> : null}

      {estado.fase === 'previa'
        ? (() => {
            const p = estado.previa;
            const repetidos = new Set(p.ambiguas.flatMap((a) => a.nomes));
            const vaiGravar = p.associadas.filter((a) => !repetidos.has(a.nome));
            return (
              <>
                <Aviso tipo="informacao">{T.previa}</Aviso>
                <p className="gestao-texto">
                  {T.resumo(vaiGravar.length, p.semProduto.length, repetidos.size, p.semFoto.total)}
                </p>
                {estado.ignorados > 0 ? (
                  <Aviso tipo="atencao">{T.ignorados(estado.ignorados)}</Aviso>
                ) : null}
                <Tabela
                  legenda={T.legenda}
                  colunas={[
                    { chave: 'arquivo', titulo: T.colunas.arquivo },
                    { chave: 'produto', titulo: T.colunas.produto },
                    { chave: 'situacao', titulo: T.colunas.situacao },
                  ]}
                  linhas={[
                    ...p.semProduto.map((s) => ({
                      arquivo: s.nome,
                      produto: '',
                      situacao: T.semProduto,
                    })),
                    ...[...repetidos].map((nome) => ({
                      arquivo: nome,
                      produto: '',
                      situacao: T.ambigua,
                    })),
                    ...vaiGravar.map((a) => ({
                      arquivo: a.nome,
                      produto: a.produtoNome,
                      situacao: T.vaiGravar,
                    })),
                  ]}
                />
                {p.semFoto.total > 0 ? (
                  <details>
                    <summary>{T.produtosSemFoto}</summary>
                    <ul>
                      {p.semFoto.itens.map((n, i) => (
                        <li key={i}>{n}</li>
                      ))}
                    </ul>
                    {p.semFoto.total > p.semFoto.itens.length ? (
                      <p>{T.maisSemFoto(p.semFoto.total - p.semFoto.itens.length)}</p>
                    ) : null}
                  </details>
                ) : null}
                <div className="gestao-acoes-da-linha">
                  <Botao onClick={() => setEstado({ fase: 'inicio' })}>
                    {TEXTOS.formulario.cancelar}
                  </Botao>
                  <Botao
                    variante="primario"
                    disabled={vaiGravar.length === 0}
                    onClick={() => void enviar(estado.arquivos, p)}
                  >
                    {T.confirmar}
                  </Botao>
                </div>
              </>
            );
          })()
        : null}

      {estado.fase === 'enviando' ? (
        <p role="status">{T.enviando(estado.feitas, estado.total)}</p>
      ) : null}

      {estado.fase === 'relatorio'
        ? (() => {
            const conta = (s: Situacao) => estado.resultados.filter((r) => r.situacao === s).length;
            const naoGravadas = estado.resultados.length - conta('GRAVADA') - conta('JA_EXISTIA');
            return (
              <>
                <Aviso tipo={naoGravadas === 0 ? 'sucesso' : 'atencao'} titulo={T.relatorio}>
                  {T.relatorioResumo(conta('GRAVADA'), conta('JA_EXISTIA'), naoGravadas)}
                </Aviso>
                <Tabela
                  legenda={T.legendaRelatorio}
                  colunas={[
                    { chave: 'arquivo', titulo: T.colunasRelatorio.arquivo },
                    { chave: 'situacao', titulo: T.colunasRelatorio.situacao },
                    { chave: 'motivo', titulo: T.colunasRelatorio.motivo },
                  ]}
                  linhas={estado.resultados.map((r) => ({
                    arquivo: r.nome,
                    situacao: T.situacoes[r.situacao],
                    motivo: r.mensagem,
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
