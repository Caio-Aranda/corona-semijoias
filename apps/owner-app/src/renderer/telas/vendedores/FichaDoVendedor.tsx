import { Aviso, Botao, JanelaDeConfirmacao } from '@corona/ui';
import { useCallback, useMemo, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { useGuardaDeSaida } from '../../nucleo/guarda-de-saida';
import { TEXTOS } from '../../textos';
import { lerPaginaDeUsuarios } from '../usuarios/dados';
import { AbaDaFicha } from './AbaDaFicha';
import { AbaDocumentos } from './AbaDocumentos';
import { AbaReferencias } from './AbaReferencias';
import { ABAS_DA_FICHA, CAMPOS_DO_CADASTRO, type ChaveDaAba } from './campos';
import { lerLista, lerVendedor, texto, type Registro, type Vendedor } from './dados';
import { FormularioDeCampos, type OpcaoDaLista } from './FormularioDeCampos';

const T = TEXTOS.vendedores;

/** Marca o inativo no nome ("Centro (inativa)"); o formulário só mostra o inativo que já estava escolhido. */
const comMarcaDeInativo = (rotulo: string, r: Registro, marca: string): string =>
  r.ativo === false ? `${rotulo} ${marca}` : rotulo;

export interface FichaDoVendedorProps {
  ponte: PonteDoCorona;
  /** `null`: vendedor novo (só a aba Cadastro, até salvar). */
  vendedorId: string | null;
  /** Volta para a lista; `mudou` diz se a lista precisa ser buscada de novo. */
  aoVoltar: (mudou: boolean) => void;
}

/** Ficha do vendedor: o cadastro e as abas, cada uma gravada sozinha. */
export function FichaDoVendedor({ ponte, vendedorId, aoVoltar }: FichaDoVendedorProps) {
  const lido = useBusca(
    () =>
      vendedorId
        ? chamar(ponte, 'vendedores.ver', { id: vendedorId })
        : Promise.resolve({ ok: true as const, dados: null }),
    `ficha|${vendedorId ?? 'novo'}`,
  );
  const [atual, setAtual] = useState<Vendedor | null>(null);
  const [aba, setAba] = useState<ChaveDaAba>('cadastro');
  const [visitadas, setVisitadas] = useState<ReadonlySet<ChaveDaAba>>(new Set(['cadastro']));
  const [sujas, setSujas] = useState<ReadonlySet<string>>(new Set());
  const sujasRef = useRef<ReadonlySet<string>>(sujas);
  const [saindo, setSaindo] = useState(false);
  const [mudouAlgo, setMudouAlgo] = useState(false);
  const [mudandoSituacao, setMudandoSituacao] = useState(false);
  const [executando, setExecutando] = useState(false);
  const [feito, setFeito] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);

  const vendedor: Vendedor | null = atual ?? (lido.dados ? lerVendedor(lido.dados) : null);
  const novo = vendedorId === null && vendedor === null;

  const pracas = useBusca(
    () => chamarTodasAsPaginas(ponte, 'pracas.listar', { situacao: 'TODOS' }),
    'pracas',
  );
  const restricoes = useBusca(
    () => chamarTodasAsPaginas(ponte, 'restricoes.listar', { situacao: 'TODOS' }),
    'restricoes',
  );
  const listas = useMemo<Record<string, readonly OpcaoDaLista[] | null>>(() => {
    const itens = (bruto: unknown): Registro[] =>
      typeof bruto === 'object' && bruto !== null && 'itens' in bruto ? lerLista(bruto.itens) : [];
    return {
      pracas: pracas.dados
        ? itens(pracas.dados).flatMap((p) =>
            p.id
              ? [
                  {
                    valor: p.id,
                    rotulo: comMarcaDeInativo(
                      texto(p, 'nome'),
                      p,
                      TEXTOS.cadastros.pracas.marcaInativo,
                    ),
                    ativo: p.ativo !== false,
                  },
                ]
              : [],
          )
        : null,
      restricoes: restricoes.dados
        ? itens(restricoes.dados).flatMap((r) =>
            r.id
              ? [
                  {
                    valor: r.id,
                    rotulo: comMarcaDeInativo(
                      `${texto(r, 'codigo')} - ${texto(r, 'descricao')}`,
                      r,
                      TEXTOS.cadastros.restricoes.marcaInativo,
                    ),
                    ativo: r.ativo !== false,
                  },
                ]
              : [],
          )
        : null,
    };
  }, [pracas.dados, restricoes.dados]);

  const acesso = useBusca(
    () =>
      vendedor
        ? chamarTodasAsPaginas(ponte, 'usuarios.listar', { perfil: 'VENDEDOR' })
        : Promise.resolve({ ok: true as const, dados: null }),
    `acesso|${vendedor?.id ?? 'nenhum'}`,
  );
  const usuarioLigado =
    vendedor && acesso.dados
      ? (lerPaginaDeUsuarios(acesso.dados).itens.find((u) => u.vendedorId === vendedor.id) ?? null)
      : null;

  const marcar = useCallback((qual: string, sujo: boolean) => {
    const anterior = sujasRef.current;
    if (anterior.has(qual) === sujo) return;
    const proximo = new Set(anterior);
    if (sujo) proximo.add(qual);
    else proximo.delete(qual);
    sujasRef.current = proximo;
    setSujas(proximo);
  }, []);
  const marcadores = useMemo(
    () =>
      Object.fromEntries(
        ABAS_DA_FICHA.map((a) => [a, (sujo: boolean) => marcar(a, sujo)]),
      ) as Record<ChaveDaAba, (sujo: boolean) => void>,
    [marcar],
  );
  const haAlteracoes = useCallback(() => sujasRef.current.size > 0, []);
  useGuardaDeSaida(haAlteracoes);

  function irParaAba(nova: ChaveDaAba) {
    setAba(nova);
    setVisitadas((v) => new Set(v).add(nova));
  }

  function voltar() {
    if (sujas.size > 0) setSaindo(true);
    else aoVoltar(mudouAlgo);
  }

  async function mudarSituacao() {
    if (!vendedor || executando) return;
    setExecutando(true);
    const r = await chamar(ponte, vendedor.ativo ? 'vendedores.inativar' : 'vendedores.reativar', {
      id: vendedor.id,
    });
    setExecutando(false);
    setMudandoSituacao(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const novoEstado = lerVendedor(r.dados);
    if (novoEstado) setAtual(novoEstado);
    setErro(null);
    setMudouAlgo(true);
    setFeito(vendedor.ativo ? T.feito.desativado(vendedor.nome) : T.feito.reativado(vendedor.nome));
  }

  if (vendedorId && vendedor === null) {
    return (
      <div className="gestao-tela">
        <Botao onClick={() => aoVoltar(false)}>{T.ficha.voltar}</Botao>
        {lido.erro ? (
          <AvisoDeErro erro={lido.erro} aoTentarDeNovo={lido.recarregar} />
        ) : (
          <p className="gestao-texto" aria-busy="true">
            {T.ficha.carregando}
          </p>
        )}
      </div>
    );
  }

  const cadastroSujo = sujas.has('cadastro');
  const registroDoCadastro: Registro = vendedor ?? { version: 0 };

  function painel(qual: ChaveDaAba) {
    if (!visitadas.has(qual)) return null;
    if (qual === 'cadastro') {
      return (
        <FormularioDeCampos
          key={vendedor?.version ?? 'novo'}
          idBase="cad"
          campos={CAMPOS_DO_CADASTRO}
          registro={registroDoCadastro}
          listas={listas}
          salvar={(corpo, version) =>
            vendedor
              ? chamar(ponte, 'vendedores.editar', { ...corpo, id: vendedor.id, version })
              : chamar(ponte, 'vendedores.criar', corpo)
          }
          aoSalvo={(resposta) => {
            const salvo = lerVendedor(resposta);
            if (salvo) setAtual(salvo);
            setMudouAlgo(true);
            setErro(null);
            setFeito(
              vendedor ? T.feito.editado(salvo?.nome ?? '') : T.feito.criado(salvo?.nome ?? ''),
            );
          }}
          aoAtualizar={() => {
            setAtual(null);
            lido.recarregar();
          }}
          aoMudarSujo={marcadores.cadastro}
          autoFocus={novo}
          rodapeExtra={
            vendedor ? (
              <>
                <Botao disabled={cadastroSujo} onClick={() => setMudandoSituacao(true)}>
                  {vendedor.ativo ? T.confirmar.desativar : T.confirmar.reativar}
                </Botao>
                {cadastroSujo ? <span className="gestao-texto">{T.ficha.salveAntes}</span> : null}
              </>
            ) : null
          }
        />
      );
    }
    if (!vendedor) return null;
    if (qual === 'referencias') {
      return (
        <AbaReferencias ponte={ponte} vendedorId={vendedor.id} aoMudarSujo={marcadores[qual]} />
      );
    }
    if (qual === 'documentos') return <AbaDocumentos ponte={ponte} vendedorId={vendedor.id} />;
    return (
      <AbaDaFicha
        ponte={ponte}
        vendedorId={vendedor.id}
        aba={qual}
        aoMudarSujo={marcadores[qual]}
      />
    );
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{vendedor ? vendedor.nome : T.ficha.tituloNovo}</h1>
        <Botao onClick={voltar}>{T.ficha.voltar}</Botao>
      </div>
      {vendedor ? (
        <p className="gestao-texto">
          {T.ficha.codigo(vendedor.codigo)} ·{' '}
          <strong>{vendedor.ativo ? T.situacao.ativo : T.situacao.desativado}</strong>
        </p>
      ) : (
        <p className="gestao-texto">{T.ficha.primeiroSalve}</p>
      )}
      {vendedor ? (
        <p className="gestao-texto" aria-live="polite">
          <strong>{T.ficha.acesso.titulo}:</strong>{' '}
          {acesso.erro
            ? T.ficha.acesso.erro
            : acesso.dados === null
              ? '...'
              : usuarioLigado
                ? T.ficha.acesso.com(usuarioLigado.login, usuarioLigado.ativo)
                : T.ficha.acesso.sem}
        </p>
      ) : null}

      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} aoAtualizar={() => lido.recarregar()} /> : null}
      {pracas.erro || restricoes.erro ? (
        <AvisoDeErro
          erro={(pracas.erro ?? restricoes.erro)!}
          aoTentarDeNovo={() => {
            pracas.recarregar();
            restricoes.recarregar();
          }}
        />
      ) : null}

      <div role="tablist" aria-label={T.ficha.abas} className="gestao-abas">
        {ABAS_DA_FICHA.map((qual) => {
          const bloqueada = qual !== 'cadastro' && !vendedor;
          return (
            <button
              key={qual}
              type="button"
              role="tab"
              id={`aba-${qual}`}
              aria-selected={aba === qual}
              aria-controls={`painel-${qual}`}
              disabled={bloqueada}
              className={`gestao-aba-botao${aba === qual ? ' gestao-aba-botao--atual' : ''}`}
              onClick={() => irParaAba(qual)}
            >
              {T.abas[qual]}
              {sujas.has(qual) ? ' •' : ''}
            </button>
          );
        })}
      </div>
      {ABAS_DA_FICHA.map((qual) => (
        <div
          key={qual}
          role="tabpanel"
          id={`painel-${qual}`}
          aria-labelledby={`aba-${qual}`}
          hidden={aba !== qual}
        >
          {painel(qual)}
        </div>
      ))}

      {mudandoSituacao && vendedor ? (
        <JanelaDeConfirmacao
          titulo={
            vendedor.ativo
              ? T.confirmar.desativarTitulo(vendedor.nome)
              : T.confirmar.reativarTitulo(vendedor.nome)
          }
          rotuloConfirmar={vendedor.ativo ? T.confirmar.desativar : T.confirmar.reativar}
          rotuloCancelar={T.confirmar.voltar}
          perigosa={vendedor.ativo}
          ocupado={executando}
          aoCancelar={() => setMudandoSituacao(false)}
          aoConfirmar={() => void mudarSituacao()}
        >
          {vendedor.ativo
            ? T.confirmar.desativarTexto(vendedor.nome)
            : T.confirmar.reativarTexto(vendedor.nome)}
        </JanelaDeConfirmacao>
      ) : null}
      {saindo ? (
        <JanelaDeConfirmacao
          titulo={T.sair.titulo}
          rotuloConfirmar={T.sair.sair}
          rotuloCancelar={T.sair.continuar}
          perigosa
          aoCancelar={() => setSaindo(false)}
          aoConfirmar={() => aoVoltar(mudouAlgo)}
        >
          {T.sair.texto}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
