import { Aviso, Botao, JanelaDeConfirmacao, Tabela, ValorTresMoedas } from '@corona/ui';
import { useMemo, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import {
  adicionarOuSomar,
  centavosParaApi,
  centavosParaCampo,
  itensDoPedido,
  lerQuantidade,
  problemasDasLinhas,
  separarErrosDoServidor,
  totalDaCompra,
  totalDaLinha,
  totalDePecas,
  type LinhaDaCompra,
} from '../../nucleo/compra';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { useCotacao } from '../../nucleo/cotacao';
import { useGuardaDeSaida } from '../../nucleo/guarda-de-saida';
import { centavosDeTexto, formatarReais, tresMoedas } from '../../nucleo/moedas';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { codigoPrincipal, type Produto } from '../produtos/dados';
import { CabecalhoDaCompra, hojeEmTexto, type DadosDoCabecalho } from './CabecalhoDaCompra';
import { CadastroRapido } from './CadastroRapido';
import { DetalheDaCompra } from './DetalheDaCompra';
import { LeitorDaCompra, ID_DO_CAMPO_DO_LEITOR } from './LeitorDaCompra';
import { lerCompra, type CompraGravada } from './dados';

const T = TEXTOS_DE_COMPRAS.receber;

export interface ReceberPelaLeituraProps {
  ponte: PonteDoCorona;
  /** Leva para a lista de compras. */
  aoVerLista: () => void;
}

const custoSugerido = (p: Produto): string => {
  const c = p.precoCusto === null ? null : centavosDeTexto(p.precoCusto);
  return c === null || c === 0n ? '' : centavosParaCampo(c);
};

/** Receber compra: leitor (ou nome), linhas com foto, quantidade e custo, total e confirmação. */
export function ReceberPelaLeitura({ ponte, aoVerLista }: ReceberPelaLeituraProps) {
  const { taxas } = useCotacao();
  const [cabecalho, setCabecalho] = useState<DadosDoCabecalho>({
    fornecedorId: '',
    data: hojeEmTexto(),
    numero: '',
    observacoes: '',
  });
  const [linhas, setLinhas] = useState<LinhaDaCompra[]>([]);
  const linhasAtuais = useRef<LinhaDaCompra[]>([]);
  const [rapido, setRapido] = useState<string | null>(null);
  const [tentou, setTentou] = useState(false);
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [gravada, setGravada] = useState<CompraGravada | null>(null);
  const [jaRegistrada, setJaRegistrada] = useState(false);
  // O identificador nasce ao abrir a tela e só muda depois de gravar: reenviar depois de uma falha
  // de rede não duplica a compra.
  const idDaCompra = useRef(crypto.randomUUID());
  const trava = useRef(false);

  useGuardaDeSaida(() => linhasAtuais.current.length > 0 && gravada === null);

  const mudarLinhas = (novas: LinhaDaCompra[]) => {
    linhasAtuais.current = novas;
    setLinhas(novas);
  };

  const errosDoServidor = useMemo(() => separarErrosDoServidor(erro?.campos ?? {}), [erro]);

  function adicionar(produto: Produto): string {
    const nova: LinhaDaCompra = {
      produtoId: produto.id,
      nome: produto.nome,
      codigo: codigoPrincipal(produto),
      fotoHash: produto.fotoPrincipal?.hash ?? null,
      quantidade: '1',
      custo: custoSugerido(produto),
    };
    const jaTinha = linhasAtuais.current.some((l) => l.produtoId === produto.id);
    const novas = adicionarOuSomar(linhasAtuais.current, nova);
    mudarLinhas(novas);
    setErro(null);
    if (!jaTinha) return T.leitor.entrou(produto.nome);
    const q = lerQuantidade(novas.find((l) => l.produtoId === produto.id)?.quantidade ?? '') ?? 1;
    return T.leitor.somou(produto.nome, q);
  }

  function mudarLinha(produtoId: string, parcial: Partial<LinhaDaCompra>) {
    mudarLinhas(
      linhasAtuais.current.map((l) => (l.produtoId === produtoId ? { ...l, ...parcial } : l)),
    );
    setErro(null);
  }

  const problemas = problemasDasLinhas(linhas, {
    quantidade: T.quantidadeInvalida,
    custo: T.custoInvalido,
  });
  const algumaLinhaRuim = problemas.some((p) => p.quantidade || p.custo);
  const cabecalhoRuim = !cabecalho.fornecedorId || !cabecalho.data;
  const totalEmCentavos = totalDaCompra(linhas);
  const totalNaTela = tresMoedas(centavosParaApi(totalEmCentavos), taxas);

  function pedirConfirmacao() {
    setTentou(true);
    if (linhas.length === 0 || algumaLinhaRuim || cabecalhoRuim) return;
    setConfirmando(true);
  }

  async function enviar() {
    if (trava.current) return;
    const itens = itensDoPedido(linhasAtuais.current);
    if (!itens) return;
    trava.current = true;
    setEnviando(true);
    setErro(null);
    const r = await chamar(ponte, 'compras.receber', {
      id: idDaCompra.current,
      fornecedorId: cabecalho.fornecedorId,
      dataCompra: cabecalho.data,
      ...(cabecalho.numero.trim() ? { numero: cabecalho.numero.trim() } : {}),
      ...(cabecalho.observacoes.trim() ? { observacoes: cabecalho.observacoes.trim() } : {}),
      itens,
    });
    trava.current = false;
    setEnviando(false);
    setConfirmando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const compra = lerCompra(r.dados);
    if (compra) {
      setGravada(compra);
      setJaRegistrada(r.jaRegistrado === true);
      mudarLinhas([]);
    }
  }

  function recomecar() {
    idDaCompra.current = crypto.randomUUID();
    mudarLinhas([]);
    setGravada(null);
    setErro(null);
    setTentou(false);
    setCabecalho({ fornecedorId: '', data: hojeEmTexto(), numero: '', observacoes: '' });
    setTimeout(() => document.getElementById(ID_DO_CAMPO_DO_LEITOR)?.focus(), 0);
  }

  if (gravada) {
    const pecas = T.pecas(gravada.resumo.pecas);
    const total = tresMoedas(gravada.resumo.total, null)?.real ?? gravada.resumo.total;
    return (
      <div>
        <Aviso tipo="sucesso" titulo={T.gravada}>
          {T.gravadaTexto(pecas, total)}
        </Aviso>
        {jaRegistrada ? <Aviso tipo="atencao">{T.jaRegistrada}</Aviso> : null}
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" onClick={recomecar}>
            {T.receberOutra}
          </Botao>
          <Botao onClick={aoVerLista}>{T.verNaLista}</Botao>
        </div>
        <DetalheDaCompra ponte={ponte} compraId={gravada.resumo.id} inicial={gravada} />
      </div>
    );
  }

  const linhasDaTabela = linhas.map((l, i) => {
    const p = problemas[i] ?? {};
    const doServidor = errosDoServidor.porItem.get(i) ?? [];
    const total = totalDaLinha(l);
    const mostrarProblema = tentou;
    return {
      foto: <ImagemDoProduto ponte={ponte} hash={l.fotoHash} tamanho="miniatura" nome={l.nome} />,
      nome: (
        <>
          {l.nome}
          {doServidor.map((m) => (
            <span key={m} role="alert" className="cs-campo__erro gestao-erro-da-linha">
              {m}
            </span>
          ))}
        </>
      ),
      codigo: l.codigo,
      quantidade: (
        <>
          <input
            className="gestao-campo-da-tabela"
            aria-label={T.quantidadeDe(l.nome)}
            inputMode="numeric"
            autoComplete="off"
            value={l.quantidade}
            aria-invalid={mostrarProblema && p.quantidade ? true : undefined}
            onChange={(e) => mudarLinha(l.produtoId, { quantidade: e.target.value })}
          />
          {mostrarProblema && p.quantidade ? (
            <span role="alert" className="cs-campo__erro gestao-erro-da-linha">
              {p.quantidade}
            </span>
          ) : null}
        </>
      ),
      custo: (
        <>
          <input
            className="gestao-campo-da-tabela"
            aria-label={T.custoDe(l.nome)}
            inputMode="decimal"
            autoComplete="off"
            value={l.custo}
            aria-invalid={mostrarProblema && p.custo ? true : undefined}
            onChange={(e) => mudarLinha(l.produtoId, { custo: e.target.value })}
          />
          {mostrarProblema && p.custo ? (
            <span role="alert" className="cs-campo__erro gestao-erro-da-linha">
              {p.custo}
            </span>
          ) : null}
        </>
      ),
      total: total === null ? '—' : formatarReais(total),
      acoes: (
        <Botao
          aria-label={T.tirarDe(l.nome)}
          onClick={() =>
            mudarLinhas(linhasAtuais.current.filter((x) => x.produtoId !== l.produtoId))
          }
        >
          {T.tirar}
        </Botao>
      ),
    };
  });

  return (
    <div>
      <p className="gestao-texto">{T.ajuda}</p>
      <CabecalhoDaCompra
        ponte={ponte}
        valores={cabecalho}
        tentou={tentou}
        erros={errosDoServidor.gerais}
        aoMudar={(p) => {
          setCabecalho((c) => ({ ...c, ...p }));
          setErro(null);
        }}
      />

      {rapido !== null ? (
        <CadastroRapido
          ponte={ponte}
          codigo={rapido}
          fornecedorId={cabecalho.fornecedorId}
          aoCancelar={() => setRapido(null)}
          aoCadastrar={(produto) => {
            setRapido(null);
            adicionar(produto);
          }}
        />
      ) : (
        <LeitorDaCompra ponte={ponte} aoAchar={adicionar} aoNaoAchar={setRapido} />
      )}

      <section className="gestao-grupo" aria-label={T.legenda}>
        {erro && erro.tipo !== 'campos' ? <AvisoDeErro erro={erro} /> : null}
        {erro && erro.tipo === 'campos' ? (
          <Aviso tipo="erro" titulo={T.cabecalhoComErro}>
            {erro.mensagem}
          </Aviso>
        ) : null}
        <Tabela
          legenda={T.legenda}
          textoVazio={T.vazia}
          colunas={[
            { chave: 'foto', titulo: T.colunas.foto },
            { chave: 'nome', titulo: T.colunas.nome },
            { chave: 'codigo', titulo: T.colunas.codigo },
            { chave: 'quantidade', titulo: T.colunas.quantidade, numerica: true },
            { chave: 'custo', titulo: T.colunas.custo, numerica: true },
            { chave: 'total', titulo: T.colunas.total, numerica: true },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={linhasDaTabela}
        />
        <p className="gestao-total-da-compra" aria-live="polite">
          {T.totalDaCompra}:{' '}
          {totalNaTela ? (
            <ValorTresMoedas
              real={totalNaTela.real}
              {...(totalNaTela.dolar ? { dolar: totalNaTela.dolar } : {})}
              {...(totalNaTela.guarani ? { guarani: totalNaTela.guarani } : {})}
            />
          ) : null}{' '}
          ({T.pecas(totalDePecas(linhas))})
        </p>
        {tentou && linhas.length === 0 ? <Aviso tipo="atencao">{T.semPecas}</Aviso> : null}
        {tentou && linhas.length > 0 && (algumaLinhaRuim || cabecalhoRuim) ? (
          <Aviso tipo="atencao">{T.faltaAlgo}</Aviso>
        ) : null}
        <div className="gestao-acoes-da-linha">
          <Botao
            onClick={() => {
              mudarLinhas([]);
              setErro(null);
              setTentou(false);
            }}
            disabled={linhas.length === 0}
          >
            {T.limpar}
          </Botao>
          <Botao variante="primario" onClick={pedirConfirmacao} disabled={enviando}>
            {T.confirmar}
          </Botao>
        </div>
      </section>

      {confirmando ? (
        <JanelaDeConfirmacao
          titulo={T.confirmacao.titulo}
          rotuloConfirmar={enviando ? T.confirmando : T.confirmar}
          rotuloCancelar={T.confirmacao.voltar}
          ocupado={enviando}
          aoCancelar={() => setConfirmando(false)}
          aoConfirmar={() => void enviar()}
        >
          {T.confirmacao.texto(T.pecas(totalDePecas(linhas)), formatarReais(totalEmCentavos))}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
