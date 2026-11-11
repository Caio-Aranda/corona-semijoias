import { SaldoDaLoja } from '../../componentes/SaldoDaLoja';
import { Aviso, Botao, Campo, JanelaDeConfirmacao } from '@corona/ui';
import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { conferirListaDeCodigos, normalizarCodigo } from '../../nucleo/codigos';
import { useGuardaDeSaida } from '../../nucleo/guarda-de-saida';
import { TEXTOS } from '../../textos';
import { CamposDeCodigos, novaLinhaDeCodigo, type LinhaDeCodigo } from './CamposDeCodigos';
import { PainelDeFotos } from './PainelDeFotos';
import { soAtivosMaisAtual } from '../../nucleo/escolhas';
import { lerOpcoes, lerProduto, type Produto } from './dados';

const T = TEXTOS.produtos.ficha;

const emReaisDigitados = (v: string | null): string => (v ? v.replace('.', ',') : '');

interface Formulario {
  nome: string;
  referencia: string;
  categoriaId: string;
  fornecedorId: string;
  precoVendaDigitado: string;
  precoVenda: string | null;
  precoCustoDigitado: string;
  precoCusto: string | null;
  estoqueMinimo: string;
  codigos: LinhaDeCodigo[];
}

function formularioVazio(): Formulario {
  return {
    nome: '',
    referencia: '',
    categoriaId: '',
    fornecedorId: '',
    precoVendaDigitado: '',
    precoVenda: null,
    precoCustoDigitado: '',
    precoCusto: null,
    estoqueMinimo: '0',
    codigos: [novaLinhaDeCodigo({ principal: true })],
  };
}

function formularioDoProduto(p: Produto): Formulario {
  const ativos = p.codigos.filter((c) => c.ativo);
  const linhas = ativos.map((c) =>
    novaLinhaDeCodigo({
      id: c.id,
      codigo: c.codigo,
      tipo: c.tipo,
      fornecedorId: c.fornecedorId ?? '',
      principal: c.principal,
    }),
  );
  if (linhas.length > 0 && !linhas.some((l) => l.principal))
    linhas[0] = { ...linhas[0]!, principal: true };
  return {
    nome: p.nome,
    referencia: p.referencia,
    categoriaId: p.categoriaId,
    fornecedorId: p.fornecedorId ?? '',
    precoVendaDigitado: emReaisDigitados(p.precoVenda),
    precoVenda: p.precoVenda,
    precoCustoDigitado: emReaisDigitados(p.precoCusto),
    precoCusto: p.precoCusto,
    estoqueMinimo: String(p.estoqueMinimo),
    codigos: linhas.length > 0 ? linhas : [novaLinhaDeCodigo({ principal: true })],
  };
}

/** O que muda o cadastro: serve para saber se há algo por salvar (as chaves da tela não contam). */
const impressao = (f: Formulario): string =>
  JSON.stringify([
    f.nome,
    f.referencia,
    f.categoriaId,
    f.fornecedorId,
    f.precoVenda,
    f.precoCusto,
    f.estoqueMinimo,
    f.codigos.map((c) => [normalizarCodigo(c.codigo), c.tipo, c.fornecedorId, c.principal]),
  ]);

export interface FichaDoProdutoProps {
  ponte: PonteDoCorona;
  /** `null` = produto novo. */
  produtoId: string | null;
  aoVoltar: (mudou: boolean) => void;
  aoAbrirOutro: (id: string) => void;
}

/** Cadastro e ficha do produto: dados, custo só da dona, códigos e fotos. */
export function FichaDoProduto({ ponte, produtoId, aoVoltar, aoAbrirOutro }: FichaDoProdutoProps) {
  const [idAtual, setIdAtual] = useState<string | null>(produtoId);
  const [produto, setProduto] = useState<Produto | null>(null);
  const [form, setForm] = useState<Formulario>(formularioVazio);
  const [inicial, setInicial] = useState<string>(() => impressao(formularioVazio()));
  const [mudou, setMudou] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const [salvando, setSalvando] = useState(false);
  const [confirmando, setConfirmando] = useState<'desativar' | 'reativar' | null>(null);
  const [tentouSalvar, setTentouSalvar] = useState(false);

  const categorias = useBusca(
    () => chamar(ponte, 'categorias.listar', { pagina: 0, tamanho: 100, situacao: 'TODOS' }),
    'categorias-do-produto',
  );
  const fornecedores = useBusca(
    () => chamar(ponte, 'fornecedores.listar', { pagina: 0, tamanho: 100, situacao: 'TODOS' }),
    'fornecedores-do-produto',
  );
  // Só ativos nas escolhas; o que a peça já tem continua aparecendo, marcado como inativo.
  const todasAsCategorias = categorias.dados ? lerOpcoes(categorias.dados) : [];
  const listaDeCategorias = soAtivosMaisAtual(
    todasAsCategorias,
    form.categoriaId,
    TEXTOS.cadastros.categorias.marcaInativo,
  );
  const todosOsFornecedores = fornecedores.dados ? lerOpcoes(fornecedores.dados) : [];
  const listaDeFornecedores = soAtivosMaisAtual(
    todosOsFornecedores,
    form.fornecedorId,
    TEXTOS.cadastros.fornecedores.marcaInativo,
  );

  const carregar = useCallback(
    () => chamar(ponte, 'produtos.ver', { id: idAtual ?? '' }),
    [ponte, idAtual],
  );
  const leitura = useBusca(
    async () => (idAtual ? carregar() : { ok: true as const, dados: null }),
    `produto-${idAtual ?? 'novo'}`,
  );

  const aplicar = useCallback((p: Produto) => {
    const f = formularioDoProduto(p);
    setProduto(p);
    setForm(f);
    setInicial(impressao(f));
  }, []);

  useEffect(() => {
    if (!leitura.dados) return;
    const p = lerProduto(leitura.dados);
    if (p) aplicar(p);
  }, [leitura.dados, aplicar]);

  const haAlteracoes = useCallback(() => impressao(form) !== inicial, [form, inicial]);
  useGuardaDeSaida(haAlteracoes);

  const sujo = impressao(form) !== inicial;
  const mudar = (parte: Partial<Formulario>) => setForm((f) => ({ ...f, ...parte }));

  const errosDaLista = useMemo(() => conferirListaDeCodigos(form.codigos), [form.codigos]);
  const erroDoCodigo = tentouSalvar && errosDaLista.length > 0 ? errosDaLista.join(' ') : undefined;

  const campoDoServidor = (nome: string): string | undefined => erro?.campos[nome];
  const obrigatorio = (vazio: boolean): string | undefined =>
    tentouSalvar && vazio ? T.obrigatorio : undefined;

  const corpo = (): Record<string, unknown> => ({
    nome: form.nome.trim(),
    ...(form.referencia.trim() ? { referencia: form.referencia.trim() } : {}),
    categoriaId: form.categoriaId,
    ...(form.fornecedorId ? { fornecedorId: form.fornecedorId } : {}),
    precoVenda: form.precoVenda,
    precoCusto: form.precoCusto,
    estoqueMinimo: Number.parseInt(form.estoqueMinimo, 10) || 0,
  });

  const codigosParaGravar = () =>
    form.codigos
      .filter((c) => normalizarCodigo(c.codigo) !== '')
      .map((c) => ({
        ...(c.id ? { id: c.id } : {}),
        codigo: normalizarCodigo(c.codigo),
        tipo: c.tipo,
        ...(c.tipo === 'FORNECEDOR' && c.fornecedorId ? { fornecedorId: c.fornecedorId } : {}),
        principal: c.principal,
        ativo: true,
      }));

  async function salvar(e: FormEvent) {
    e.preventDefault();
    setTentouSalvar(true);
    setErro(null);
    setAviso(null);
    const faltaAlgo =
      form.nome.trim() === '' ||
      !form.categoriaId ||
      form.precoVenda === null ||
      form.precoCusto === null;
    if (faltaAlgo || errosDaLista.length > 0) return;
    setSalvando(true);
    try {
      if (!idAtual) {
        const id = crypto.randomUUID();
        const r = await chamar(ponte, 'produtos.criar', {
          id,
          ...corpo(),
          codigos: codigosParaGravar(),
        });
        if (!r.ok) return setErro(r.erro);
        const novo = lerProduto(r.dados);
        setMudou(true);
        setTentouSalvar(false);
        setIdAtual(novo?.id ?? id);
        if (novo) aplicar(novo);
        setAviso(T.feito.criado(novo?.nome ?? form.nome.trim()));
        return;
      }
      let atual = produto;
      const r = await chamar(ponte, 'produtos.editar', {
        id: idAtual,
        ...corpo(),
        version: atual?.version ?? 0,
      });
      if (!r.ok) return setErro(r.erro);
      atual = lerProduto(r.dados) ?? atual;
      setMudou(true);
      const codigosMudaram =
        JSON.stringify(
          form.codigos.map((c) => [
            normalizarCodigo(c.codigo),
            c.tipo,
            c.fornecedorId,
            c.principal,
          ]),
        ) !== JSON.stringify(inicial ? (JSON.parse(inicial) as unknown[])[7] : []);
      if (codigosMudaram) {
        const rc = await chamar(ponte, 'produtos.codigos.gravar', {
          id: idAtual,
          version: atual?.version ?? 0,
          codigos: codigosParaGravar(),
        });
        if (!rc.ok) {
          if (atual) setProduto(atual);
          return setErro(rc.erro);
        }
        atual = lerProduto(rc.dados) ?? atual;
      }
      setTentouSalvar(false);
      if (atual) aplicar(atual);
      setAviso(T.feito.salvo(atual?.nome ?? form.nome.trim()));
    } finally {
      setSalvando(false);
    }
  }

  async function mudarSituacao(acao: 'desativar' | 'reativar') {
    if (!idAtual) return;
    setConfirmando(null);
    setErro(null);
    setAviso(null);
    const r = await chamar(
      ponte,
      acao === 'desativar' ? 'produtos.inativar' : 'produtos.reativar',
      {
        id: idAtual,
      },
    );
    if (!r.ok) return setErro(r.erro);
    const p = lerProduto(r.dados);
    setMudou(true);
    if (p) {
      setProduto(p);
      setAviso(acao === 'desativar' ? T.feito.desativado(p.nome) : T.feito.reativado(p.nome));
    }
  }

  /** Depois de mexer nas fotos a versão do produto mudou: só a versão é relida, sem apagar o que foi digitado. */
  const aoMudarFotos = useCallback(() => {
    if (!idAtual) return;
    void carregar().then((r) => {
      if (!r.ok) return;
      const p = lerProduto(r.dados);
      if (p)
        setProduto((antes) =>
          antes ? { ...antes, version: p.version, fotoPrincipal: p.fotoPrincipal } : p,
        );
      setMudou(true);
    });
  }, [carregar, idAtual]);

  const titulo = idAtual ? form.nome || T.carregando : T.tituloNovo;
  const carregandoFicha = idAtual !== null && produto === null && !leitura.erro;

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{titulo}</h1>
        <Botao onClick={() => aoVoltar(mudou)}>{T.voltar}</Botao>
      </div>

      {leitura.erro && !produto ? (
        <AvisoDeErro
          erro={leitura.erro}
          aoTentarDeNovo={leitura.recarregar}
          aoAtualizar={leitura.recarregar}
        />
      ) : null}
      {carregandoFicha ? <p role="status">{T.carregando}</p> : null}
      {categorias.erro ? (
        <AvisoDeErro erro={categorias.erro} aoTentarDeNovo={categorias.recarregar} />
      ) : null}
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      {erro ? (
        <AvisoDeErro
          erro={erro}
          aoAtualizar={() => {
            setErro(null);
            leitura.recarregar();
          }}
        />
      ) : null}

      {!carregandoFicha && !(leitura.erro && !produto) ? (
        <form
          className="gestao-formulario gestao-formulario--larga"
          onSubmit={(e) => void salvar(e)}
          noValidate
        >
          {produto ? (
            <p className="gestao-texto">
              {T.situacaoAtual(produto.ativo)} {T.saldo(produto.estoqueAtual)}
            </p>
          ) : null}
          {produto && produto.estoqueAtual === 0 ? (
            <p className="gestao-texto">
              <SaldoDaLoja
                estoque={produto.estoqueAtual}
                emMaletas={produto.emMaletas}
                vendedores={produto.vendedoresEmMaletas}
              />
            </p>
          ) : null}

          <CamposDeCodigos
            ponte={ponte}
            linhas={form.codigos}
            aoMudar={(codigos) => mudar({ codigos })}
            fornecedores={listaDeFornecedores}
            produtoId={idAtual}
            aoAbrirProduto={aoAbrirOutro}
            focoAutomatico={!idAtual}
            {...((erroDoCodigo ?? campoDoServidor('codigos'))
              ? { erro: erroDoCodigo ?? campoDoServidor('codigos') }
              : {})}
          />

          <Campo
            rotulo={T.nome}
            value={form.nome}
            autoComplete="off"
            maxLength={200}
            onChange={(e) => mudar({ nome: e.target.value })}
            {...((obrigatorio(form.nome.trim() === '') ?? campoDoServidor('nome'))
              ? {
                  erro: obrigatorio(form.nome.trim() === '') ?? campoDoServidor('nome'),
                }
              : {})}
          />
          <Campo
            rotulo={T.referencia}
            value={form.referencia}
            autoComplete="off"
            maxLength={100}
            onChange={(e) => mudar({ referencia: e.target.value })}
            {...(campoDoServidor('referencia') ? { erro: campoDoServidor('referencia') } : {})}
          />

          <div className={`cs-campo${tentouSalvar && !form.categoriaId ? ' cs-campo--erro' : ''}`}>
            <label htmlFor="produto-categoria">{T.categoria}</label>
            <select
              id="produto-categoria"
              value={form.categoriaId}
              onChange={(e) => mudar({ categoriaId: e.target.value })}
            >
              <option value="">{T.escolhaCategoria}</option>
              {listaDeCategorias.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.nome}
                </option>
              ))}
            </select>
            {tentouSalvar && !form.categoriaId ? (
              <span role="alert" className="cs-campo__erro">
                {T.escolhaCategoriaAviso}
              </span>
            ) : null}
            {campoDoServidor('categoriaId') ? (
              <span role="alert" className="cs-campo__erro">
                {campoDoServidor('categoriaId')}
              </span>
            ) : null}
          </div>
          <div className="cs-campo">
            <label htmlFor="produto-fornecedor">{T.fornecedor}</label>
            <select
              id="produto-fornecedor"
              value={form.fornecedorId}
              onChange={(e) => mudar({ fornecedorId: e.target.value })}
            >
              <option value="">{T.semFornecedor}</option>
              {listaDeFornecedores.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.nome}
                </option>
              ))}
            </select>
          </div>

          <CampoDeDinheiro
            rotulo={T.precoVenda}
            valor={form.precoVendaDigitado}
            ajuda=""
            aoMudar={(digitado, valor) =>
              mudar({ precoVendaDigitado: digitado, precoVenda: valor })
            }
            {...((obrigatorio(form.precoVenda === null && form.precoVendaDigitado.trim() === '') ??
            campoDoServidor('precoVenda'))
              ? {
                  erro:
                    obrigatorio(
                      form.precoVenda === null && form.precoVendaDigitado.trim() === '',
                    ) ?? campoDoServidor('precoVenda'),
                }
              : {})}
          />
          <CampoDeDinheiro
            rotulo={T.precoCusto}
            valor={form.precoCustoDigitado}
            ajuda={T.custoAjuda}
            aoMudar={(digitado, valor) =>
              mudar({ precoCustoDigitado: digitado, precoCusto: valor })
            }
            {...((obrigatorio(form.precoCusto === null && form.precoCustoDigitado.trim() === '') ??
            campoDoServidor('precoCusto'))
              ? {
                  erro:
                    obrigatorio(
                      form.precoCusto === null && form.precoCustoDigitado.trim() === '',
                    ) ?? campoDoServidor('precoCusto'),
                }
              : {})}
          />
          {produto && produto.precoCusto === null ? (
            <p className="gestao-texto">{T.custoNaoMostrado}</p>
          ) : null}
          <p className="gestao-texto">{T.historicoDeCusto}</p>

          <Campo
            rotulo={T.estoqueMinimo}
            value={form.estoqueMinimo}
            inputMode="numeric"
            autoComplete="off"
            onChange={(e) => mudar({ estoqueMinimo: e.target.value.replace(/\D/g, '') })}
          />

          <div className="gestao-rodape-do-formulario">
            <Botao onClick={() => aoVoltar(mudou)}>{TEXTOS.formulario.cancelar}</Botao>
            <Botao
              type="submit"
              variante="primario"
              disabled={salvando || (idAtual !== null && !sujo)}
            >
              {salvando ? T.salvando : T.salvar}
            </Botao>
          </div>
        </form>
      ) : null}

      {produto ? (
        <div className="gestao-acoes-da-linha">
          {produto.ativo ? (
            <Botao variante="perigoso" disabled={sujo} onClick={() => setConfirmando('desativar')}>
              {T.desativar}
            </Botao>
          ) : (
            <Botao disabled={sujo} onClick={() => setConfirmando('reativar')}>
              {T.reativar}
            </Botao>
          )}
          {sujo ? <span className="gestao-texto">{T.salveAntes}</span> : null}
        </div>
      ) : null}

      {idAtual && produto ? (
        <PainelDeFotos
          ponte={ponte}
          produtoId={idAtual}
          nome={produto.nome}
          aoMudar={aoMudarFotos}
        />
      ) : (
        <section className="gestao-grupo">
          <h2 className="gestao-subtitulo">{TEXTOS.produtos.fotos.titulo}</h2>
          <p className="gestao-texto">{TEXTOS.produtos.fotos.soDepoisDeSalvar}</p>
        </section>
      )}

      {confirmando && produto ? (
        <JanelaDeConfirmacao
          titulo={
            confirmando === 'desativar'
              ? T.desativarConfirma.titulo(produto.nome)
              : T.reativarConfirma.titulo(produto.nome)
          }
          rotuloConfirmar={confirmando === 'desativar' ? T.desativar : T.reativar}
          rotuloCancelar={T.voltarConfirma}
          perigosa={confirmando === 'desativar'}
          aoCancelar={() => setConfirmando(null)}
          aoConfirmar={() => void mudarSituacao(confirmando)}
        >
          {confirmando === 'desativar' ? T.desativarConfirma.texto : T.reativarConfirma.texto}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
