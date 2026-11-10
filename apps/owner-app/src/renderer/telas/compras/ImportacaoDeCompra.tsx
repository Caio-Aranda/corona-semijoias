import { Aviso, Botao, Tabela } from '@corona/ui';
import { useMemo, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { entregarArquivo, lerBytes } from '../../nucleo/arquivos';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { centavosParaApi, separarErrosDoServidor } from '../../nucleo/compra';
import { chaveDoCodigo, normalizarCodigo } from '../../nucleo/codigos';
import { useGuardaDeSaida } from '../../nucleo/guarda-de-saida';
import { centavosDeTexto, formatarReais } from '../../nucleo/moedas';
import { TAMANHO_MAXIMO_DA_PLANILHA, lerPlanilha } from '../../nucleo/planilha';
import {
  analisarPlanilhaDeCompra,
  contarPreviaDeCompra,
  modeloDaPlanilha,
  montarPreviaDeCompra,
  podeGravarAPrevia,
  type LinhaLidaDaCompra,
  type PecaDaPrevia,
} from '../../nucleo/planilha-compra';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { lerProduto, type Produto } from '../produtos/dados';
import { CabecalhoDaCompra, hojeEmTexto, type DadosDoCabecalho } from './CabecalhoDaCompra';
import { CadastroRapido } from './CadastroRapido';
import { DetalheDaCompra } from './DetalheDaCompra';
import { lerCompra, type CompraGravada } from './dados';

const T = TEXTOS_DE_COMPRAS.planilha;
const CONSULTAS_AO_MESMO_TEMPO = 5;

type Fase =
  | { fase: 'inicio' }
  | { fase: 'lendo'; etapa: string }
  | { fase: 'previa' }
  | { fase: 'gravando' }
  | { fase: 'resultado'; compra: CompraGravada };

export interface ImportacaoDeCompraProps {
  ponte: PonteDoCorona;
}

/** Procura os códigos, poucos de cada vez. Código que não existe fica de fora do mapa. */
async function procurarCodigos(ponte: PonteDoCorona, codigos: string[]) {
  const achadas = new Map<string, PecaDaPrevia>();
  let falha: ErroDaTela | null = null;
  let proximo = 0;
  const trabalhador = async () => {
    while (!falha && proximo < codigos.length) {
      const codigo = codigos[proximo++]!;
      const r = await chamar(ponte, 'produtos.porCodigo', { codigo });
      if (r.ok) {
        const p = lerProduto(r.dados);
        if (p) achadas.set(chaveDoCodigo(codigo), { id: p.id, nome: p.nome });
      } else if (r.erro.status !== 404) falha = r.erro;
    }
  };
  await Promise.all(Array.from({ length: CONSULTAS_AO_MESMO_TEMPO }, trabalhador));
  return falha
    ? { ok: false as const, erro: falha as ErroDaTela }
    : { ok: true as const, dados: achadas };
}

/**
 * Importar a compra por planilha: escolher → prévia linha a linha (nada gravado) → corrigir o que
 * falta → confirmar → compra gravada de uma vez. Um problema no servidor não grava nada.
 */
export function ImportacaoDeCompra({ ponte }: ImportacaoDeCompraProps) {
  const [cabecalho, setCabecalho] = useState<DadosDoCabecalho>({
    fornecedorId: '',
    data: hojeEmTexto(),
    numero: '',
    observacoes: '',
  });
  const [fase, setFase] = useState<Fase>({ fase: 'inicio' });
  const [lidas, setLidas] = useState<LinhaLidaDaCompra[]>([]);
  const [achadas, setAchadas] = useState<Map<string, PecaDaPrevia>>(new Map());
  const [mensagem, setMensagem] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [tentou, setTentou] = useState(false);
  const [rapido, setRapido] = useState<LinhaLidaDaCompra | null>(null);
  const [corrigindo, setCorrigindo] = useState<{ linha: number; texto: string } | null>(null);
  const entrada = useRef<HTMLInputElement>(null);
  // Um identificador por planilha escolhida: reenviar depois de uma falha de rede não duplica.
  const idDaCompra = useRef(crypto.randomUUID());
  const trava = useRef(false);

  useGuardaDeSaida(() => fase.fase === 'previa' || fase.fase === 'gravando');

  const previa = useMemo(() => montarPreviaDeCompra(lidas, achadas), [lidas, achadas]);
  const contagem = contarPreviaDeCompra(previa);
  const errosDoServidor = useMemo(() => separarErrosDoServidor(erro?.campos ?? {}), [erro]);

  async function escolher(arquivo: File) {
    setMensagem(null);
    setErro(null);
    if (!/\.(xlsx|csv)$/i.test(arquivo.name)) return setMensagem(T.arquivoInvalido);
    if (arquivo.size > TAMANHO_MAXIMO_DA_PLANILHA) return setMensagem(T.arquivoGrande);
    setFase({ fase: 'lendo', etapa: T.lendo });
    let tabela;
    try {
      tabela = await lerPlanilha(arquivo.name, await lerBytes(arquivo));
    } catch {
      setFase({ fase: 'inicio' });
      return setMensagem(T.naoLeu);
    }
    const lida = analisarPlanilhaDeCompra(tabela);
    if (lida.tipo === 'erro') {
      setFase({ fase: 'inicio' });
      return setMensagem(lida.mensagem);
    }
    setFase({ fase: 'lendo', etapa: T.conferindo });
    const procuradas = await procurarCodigos(
      ponte,
      lida.linhas.filter((l) => !l.motivo).map((l) => l.codigo),
    );
    if (!procuradas.ok) {
      setFase({ fase: 'inicio' });
      return setErro(procuradas.erro);
    }
    idDaCompra.current = crypto.randomUUID();
    setTentou(false);
    setRapido(null);
    setCorrigindo(null);
    setLidas(lida.linhas);
    setAchadas(procuradas.dados);
    setFase({ fase: 'previa' });
  }

  function peca(produto: Produto, codigo: string) {
    setAchadas((a) =>
      new Map(a).set(chaveDoCodigo(codigo), { id: produto.id, nome: produto.nome }),
    );
  }

  async function aplicarCorrecao(linha: number, texto: string) {
    const codigo = normalizarCodigo(texto);
    if (codigo === '') return;
    setErro(null);
    const r = await chamar(ponte, 'produtos.porCodigo', { codigo });
    if (r.ok) {
      const p = lerProduto(r.dados);
      if (p) peca(p, codigo);
    } else if (r.erro.status !== 404) {
      return setErro(r.erro);
    }
    setLidas((ls) => ls.map((l) => (l.linha === linha ? { ...l, codigo } : l)));
    setCorrigindo(null);
  }

  async function gravar() {
    setTentou(true);
    if (trava.current || !podeGravarAPrevia(previa)) return;
    if (!cabecalho.fornecedorId || !cabecalho.data) return;
    trava.current = true;
    setFase({ fase: 'gravando' });
    setErro(null);
    const r = await chamar(ponte, 'compras.receber', {
      id: idDaCompra.current,
      fornecedorId: cabecalho.fornecedorId,
      dataCompra: cabecalho.data,
      ...(cabecalho.numero.trim() ? { numero: cabecalho.numero.trim() } : {}),
      ...(cabecalho.observacoes.trim() ? { observacoes: cabecalho.observacoes.trim() } : {}),
      itens: previa.map((l) => ({
        produtoId: l.peca!.id,
        quantidade: l.quantidade!,
        custoUnitario: centavosParaApi(l.custo!),
      })),
    });
    trava.current = false;
    if (!r.ok) {
      setErro(r.erro);
      setFase({ fase: 'previa' });
      return;
    }
    const compra = lerCompra(r.dados);
    if (compra) setFase({ fase: 'resultado', compra });
    else setFase({ fase: 'previa' });
  }

  function baixarModelo() {
    entregarArquivo(T.modeloNome, 'text/csv', modeloDaPlanilha());
  }

  const situacaoNaTela = (s: 'ok' | 'desconhecido' | 'erro') => T.situacoes[s];

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-planilha-compra">
      <h2 id="titulo-planilha-compra" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      {mensagem ? <Aviso tipo="erro">{mensagem}</Aviso> : null}
      {erro && erro.tipo !== 'campos' ? <AvisoDeErro erro={erro} /> : null}
      {erro && erro.tipo === 'campos' ? <Aviso tipo="erro">{T.recusada}</Aviso> : null}

      <input
        ref={entrada}
        type="file"
        hidden
        accept=".xlsx,.csv"
        aria-label={T.escolher}
        data-testid="escolher-planilha-de-compra"
        onChange={(e) => {
          const arquivo = e.target.files?.[0];
          e.target.value = '';
          if (arquivo) void escolher(arquivo);
        }}
      />

      {fase.fase === 'inicio' ? (
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" onClick={() => entrada.current?.click()}>
            {T.escolher}
          </Botao>
          <Botao onClick={baixarModelo}>{T.modelo}</Botao>
        </div>
      ) : null}
      {fase.fase === 'lendo' ? <p role="status">{fase.etapa}</p> : null}
      {fase.fase === 'gravando' ? <p role="status">{T.gravando}</p> : null}

      {fase.fase === 'previa' && rapido ? (
        <CadastroRapido
          ponte={ponte}
          codigo={rapido.codigo}
          fornecedorId={cabecalho.fornecedorId}
          {...(rapido.custo !== undefined
            ? { custoInicial: centavosParaApi(rapido.custo).replace('.', ',') }
            : {})}
          aoCancelar={() => setRapido(null)}
          aoCadastrar={(produto) => {
            peca(produto, rapido.codigo);
            setRapido(null);
          }}
        />
      ) : null}

      {fase.fase === 'previa' && !rapido ? (
        <>
          <CabecalhoDaCompra
            idBase="planilha-compra"
            ponte={ponte}
            valores={cabecalho}
            tentou={tentou}
            erros={errosDoServidor.gerais}
            aoMudar={(p) => {
              setCabecalho((c) => ({ ...c, ...p }));
              setErro(null);
            }}
          />
          <Aviso tipo="informacao">{T.previa}</Aviso>
          <p className="gestao-texto">
            {T.resumo(contagem.ok, contagem.desconhecido, contagem.erro)}
          </p>
          {!podeGravarAPrevia(previa) ? (
            <Aviso tipo="atencao">
              {contagem.ok === 0
                ? T.soComProblema
                : T.desconhecidasAviso(contagem.desconhecido + contagem.erro)}
            </Aviso>
          ) : null}
          <Tabela
            legenda={T.legendaPrevia}
            colunas={[
              { chave: 'linha', titulo: T.colunas.linha, numerica: true },
              { chave: 'codigo', titulo: T.colunas.codigo },
              { chave: 'nome', titulo: T.colunas.nome },
              { chave: 'quantidade', titulo: T.colunas.quantidade, numerica: true },
              { chave: 'custo', titulo: T.colunas.custo, numerica: true },
              { chave: 'situacao', titulo: T.colunas.situacao },
              { chave: 'motivo', titulo: T.colunas.motivo },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={previa.map((l) => {
              const doServidor = l.situacao === 'ok' ? errosDoServidor.porItem : null;
              const indiceNoEnvio = previa.indexOf(l);
              const msgsDoServidor = doServidor?.get(indiceNoEnvio) ?? [];
              return {
                linha: l.linha,
                codigo: l.codigo,
                nome: l.peca?.nome ?? '',
                quantidade: l.quantidade ?? l.quantidadeLida,
                custo: l.custo === undefined ? '' : formatarReais(l.custo),
                situacao: situacaoNaTela(l.situacao),
                motivo: (
                  <>
                    {l.motivo ?? ''}
                    {msgsDoServidor.map((m) => (
                      <span key={m} role="alert" className="cs-campo__erro gestao-erro-da-linha">
                        {m}
                      </span>
                    ))}
                  </>
                ),
                acoes:
                  l.situacao === 'ok' ? (
                    <Botao
                      aria-label={`${T.tirarLinha} ${l.linha}`}
                      onClick={() => setLidas((ls) => ls.filter((x) => x.linha !== l.linha))}
                    >
                      {T.tirarLinha}
                    </Botao>
                  ) : (
                    <>
                      {l.situacao === 'desconhecido' ? (
                        <Botao aria-label={T.cadastrarDe(l.codigo)} onClick={() => setRapido(l)}>
                          {T.cadastrar}
                        </Botao>
                      ) : null}
                      {l.situacao === 'desconhecido' || l.motivo ? (
                        corrigindo?.linha === l.linha ? (
                          <span className="gestao-correcao-de-codigo">
                            <input
                              className="gestao-campo-da-tabela"
                              aria-label={T.corrigirDe(l.linha)}
                              value={corrigindo.texto}
                              autoComplete="off"
                              autoFocus
                              onChange={(e) =>
                                setCorrigindo({ linha: l.linha, texto: e.target.value })
                              }
                              onKeyDown={(e) => {
                                if (e.key === 'Enter') {
                                  e.preventDefault();
                                  void aplicarCorrecao(l.linha, corrigindo.texto);
                                }
                              }}
                            />
                            <Botao onClick={() => void aplicarCorrecao(l.linha, corrigindo.texto)}>
                              {T.aplicarCorrecao}
                            </Botao>
                          </span>
                        ) : (
                          <Botao
                            aria-label={`${T.corrigir} ${l.linha}`}
                            onClick={() => setCorrigindo({ linha: l.linha, texto: l.codigo })}
                          >
                            {T.corrigir}
                          </Botao>
                        )
                      ) : null}
                      <Botao
                        aria-label={`${T.tirarLinha} ${l.linha}`}
                        onClick={() => setLidas((ls) => ls.filter((x) => x.linha !== l.linha))}
                      >
                        {T.tirarLinha}
                      </Botao>
                    </>
                  ),
              };
            })}
          />
          <div className="gestao-acoes-da-linha">
            <Botao onClick={() => setFase({ fase: 'inicio' })}>
              {TEXTOS_DE_COMPRAS.receber.limpar}
            </Botao>
            <Botao onClick={() => entrada.current?.click()}>{T.outra}</Botao>
            <Botao
              variante="primario"
              disabled={!podeGravarAPrevia(previa)}
              onClick={() => void gravar()}
            >
              {T.confirmar}
            </Botao>
          </div>
        </>
      ) : null}

      {fase.fase === 'resultado' ? (
        <>
          <Aviso tipo="sucesso" titulo={T.resultado}>
            {T.resultadoTexto(
              fase.compra.itens.length,
              TEXTOS_DE_COMPRAS.receber.pecas(fase.compra.resumo.pecas),
              formatarReais(centavosDeTexto(fase.compra.resumo.total) ?? 0n),
            )}
          </Aviso>
          <div className="gestao-acoes-da-linha">
            <Botao
              variante="primario"
              onClick={() => {
                setFase({ fase: 'inicio' });
                setLidas([]);
                setCabecalho({
                  fornecedorId: '',
                  data: hojeEmTexto(),
                  numero: '',
                  observacoes: '',
                });
              }}
            >
              {T.novaImportacao}
            </Botao>
          </div>
          <DetalheDaCompra ponte={ponte} compraId={fase.compra.resumo.id} inicial={fase.compra} />
        </>
      ) : null}
    </section>
  );
}
