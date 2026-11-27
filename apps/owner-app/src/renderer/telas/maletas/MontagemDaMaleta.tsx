import { SaldoDaLoja } from '../../componentes/SaldoDaLoja';
import { TEXTOS } from '../../textos';
import { Aviso, Botao, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import { MAXIMO_DA_QUANTIDADE_NA_MALETA } from '../../../shared/maletas';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { centavosDeTexto, formatarReais } from '../../nucleo/moedas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { avisoDaMaleta, ehAvisoDeEstoque } from '../../idioma/erros-es';
import { codigoPrincipal, type Produto } from '../produtos/dados';
import { Dinheiro } from './Dinheiro';
import { LeitorDaMaleta } from './LeitorDaMaleta';
import { lerMaleta, lerSituacaoDoLimite, sobraDoLimite, type Maleta } from './dados';

const T = TEXTOS_DE_MALETAS;

export interface MontagemDaMaletaProps {
  ponte: PonteDoCorona;
  inicial: Maleta;
  /** Volta para a lista (o rascunho continua guardado no servidor). */
  aoVoltar: () => void;
  /** O servidor enviou (ou já tinha enviado) a maleta. */
  aoEnviar: (maleta: Maleta, jaEstavaEnviada: boolean) => void;
}

interface PecaLida {
  produto: Produto;
  resultado: { tipo: 'sucesso' | 'erro'; texto: string };
}

/** Quantidade digitada: inteiro de 1 em diante, até o máximo que o servidor aceita. */
function lerQuantidade(digitada: string): number | null {
  if (!/^\d{1,6}$/.test(digitada.trim())) return null;
  const n = Number(digitada.trim());
  return n >= 1 && n <= MAXIMO_DA_QUANTIDADE_NA_MALETA ? n : null;
}

/**
 * Montagem da maleta pelo leitor: cada leitura grava na hora (a maleta em montagem fica guardada
 * no servidor), e o servidor devolve preço e totais. Alterações são feitas uma de cada vez, para a
 * versão que o servidor espera nunca ficar para trás.
 */
export function MontagemDaMaleta({ ponte, inicial, aoVoltar, aoEnviar }: MontagemDaMaletaProps) {
  const [maleta, setMaleta] = useState<Maleta>(inicial);
  const atual = useRef<Maleta>(inicial);
  const fila = useRef<Promise<void>>(Promise.resolve());
  const [lida, setLida] = useState<PecaLida | null>(null);
  const [naoAchou, setNaoAchou] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [salvando, setSalvando] = useState(0);
  const [digitadas, setDigitadas] = useState<Record<string, string>>({});
  const [quantidadeRuim, setQuantidadeRuim] = useState<string | null>(null);
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [semPecas, setSemPecas] = useState(false);
  const trava = useRef(false);

  const limite = useBusca(
    () => chamar(ponte, 'maletas.emAberto', { vendedorId: inicial.vendedorId }),
    `limite-${inicial.vendedorId}`,
  );

  const guardar = (nova: Maleta) => {
    atual.current = nova;
    setMaleta(nova);
  };

  /** Põe a chamada na fila e devolve se deu certo. */
  function seguir(operacao: () => Promise<boolean>): Promise<boolean> {
    setSalvando((n) => n + 1);
    const resultado = fila.current.then(operacao, operacao);
    fila.current = resultado.then(
      () => undefined,
      () => undefined,
    );
    return resultado.finally(() => setSalvando((n) => n - 1));
  }

  function aplicar(r: Awaited<ReturnType<typeof chamar>>): boolean {
    if (!r.ok) {
      setErro(r.erro);
      return false;
    }
    const nova = lerMaleta(r.dados);
    if (!nova) {
      setErro({ tipo: 'outro', mensagem: T.naoAbriu, campos: {} });
      return false;
    }
    setErro(null);
    guardar(nova);
    return true;
  }

  function colocar(produtoId: string, quantidade: number): Promise<boolean> {
    return seguir(async () =>
      aplicar(
        await chamar(ponte, 'maletas.colocarPeca', {
          id: atual.current.id,
          produtoId,
          quantidade,
          version: atual.current.version,
        }),
      ),
    );
  }

  function tirar(produtoId: string): Promise<boolean> {
    return seguir(async () =>
      aplicar(
        await chamar(ponte, 'maletas.tirarPeca', {
          id: atual.current.id,
          produtoId,
          version: atual.current.version,
        }),
      ),
    );
  }

  async function atualizarDaApi() {
    const r = await chamar(ponte, 'maletas.ver', { id: atual.current.id });
    aplicar(r);
  }

  /** A peça lida entra na maleta (soma 1), ou aparece o motivo de não entrar. */
  async function aoAchar(produto: Produto) {
    setNaoAchou(null);
    setSemPecas(false);
    const jaNaMaleta = atual.current.itens.find((i) => i.produtoId === produto.id)?.quantidade ?? 0;
    const recusa = (texto: string) => setLida({ produto, resultado: { tipo: 'erro', texto } });
    if (!produto.ativo) return recusa(T.leitor.desativada(produto.nome));
    if (produto.estoqueAtual <= 0) {
      // Sem estoque na loja: se a peça está na maleta de alguém, diz de quem (item 13).
      return recusa(
        produto.emMaletas > 0
          ? TEXTOS.saldoDaLoja.semEstoqueNaMontagem(produto.vendedoresEmMaletas.map((v) => v.nome))
          : T.leitor.semEstoque(produto.nome),
      );
    }
    if (jaNaMaleta + 1 > produto.estoqueAtual) {
      return recusa(T.leitor.passaDoEstoque(produto.nome, produto.estoqueAtual));
    }
    const novaQuantidade = jaNaMaleta + 1;
    const ok = await colocar(produto.id, novaQuantidade);
    if (ok) {
      setLida({
        produto,
        resultado: {
          tipo: 'sucesso',
          texto:
            jaNaMaleta === 0
              ? T.leitor.entrou(produto.nome)
              : T.leitor.somou(produto.nome, novaQuantidade),
        },
      });
    } else {
      setLida(null);
    }
  }

  async function confirmarQuantidade(produtoId: string) {
    const digitada = digitadas[produtoId];
    const item = atual.current.itens.find((i) => i.produtoId === produtoId);
    if (digitada === undefined || !item) return;
    const n = lerQuantidade(digitada);
    if (n === null) {
      setQuantidadeRuim(produtoId);
      return;
    }
    setQuantidadeRuim(null);
    if (n !== item.quantidade) await colocar(produtoId, n);
    setDigitadas((d) => Object.fromEntries(Object.entries(d).filter(([k]) => k !== produtoId)));
  }

  async function enviar() {
    if (trava.current) return;
    trava.current = true;
    setEnviando(true);
    await fila.current;
    const r = await chamar(ponte, 'maletas.enviar', { id: atual.current.id });
    trava.current = false;
    setEnviando(false);
    setConfirmando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    const enviada = lerMaleta(r.dados);
    if (enviada) aoEnviar(enviada, r.jaRegistrado === true);
  }

  function pedirEnvio() {
    if (maleta.itens.length === 0) {
      setSemPecas(true);
      return;
    }
    setSemPecas(false);
    setConfirmando(true);
  }

  const estaMaleta = centavosDeTexto(maleta.totalValor) ?? 0n;
  const situacaoDoLimite = limite.dados ? lerSituacaoDoLimite(limite.dados) : null;
  const sobra = situacaoDoLimite ? sobraDoLimite(situacaoDoLimite, estaMaleta) : null;
  const L = T.limite;

  return (
    <div>
      <p className="gestao-texto">
        {T.nova.vendedor}: <strong>{maleta.vendedorNome}</strong>
        {[
          T.nova.nomeDaSemana(String(maleta.semanaNumero)),
          maleta.pracaNome,
          T.nova.tipos[maleta.tipo] ?? maleta.tipo,
        ]
          .filter((parte) => parte)
          .map((parte) => ` · ${parte}`)
          .join('')}
      </p>
      {maleta.avisos.map((a) => (
        <Aviso
          key={a}
          tipo="atencao"
          titulo={ehAvisoDeEstoque(a) ? T.avisoDeEstoque : T.avisoDeRestricao}
        >
          {avisoDaMaleta(a)}
        </Aviso>
      ))}

      <LeitorDaMaleta
        ponte={ponte}
        aoAchar={aoAchar}
        aoNaoAchar={(codigo) => {
          setLida(null);
          setNaoAchou(codigo);
        }}
        desativado={enviando}
      />
      {naoAchou ? (
        <Aviso tipo="erro" titulo={T.leitor.naoAchou}>
          {T.leitor.naoAchouTexto(naoAchou)}
        </Aviso>
      ) : null}

      {lida ? (
        <section className="gestao-grupo gestao-peca-lida" aria-labelledby="titulo-peca-da-maleta">
          <h2 id="titulo-peca-da-maleta" className="gestao-subtitulo">
            {T.peca.titulo}
          </h2>
          <div className="gestao-consulta">
            <ImagemDoProduto
              ponte={ponte}
              hash={lida.produto.fotoPrincipal?.hash}
              tamanho="grande"
              nome={lida.produto.nome}
            />
            <div>
              <dl className="gestao-resumo">
                <div>
                  <dt>{T.peca.nome}</dt>
                  <dd>{lida.produto.nome}</dd>
                </div>
                <div>
                  <dt>{T.peca.codigo}</dt>
                  <dd>{codigoPrincipal(lida.produto)}</dd>
                </div>
                <div>
                  <dt>{T.peca.estoque}</dt>
                  <dd>
                    <SaldoDaLoja
                      estoque={lida.produto.estoqueAtual}
                      emMaletas={lida.produto.emMaletas}
                      vendedores={lida.produto.vendedoresEmMaletas}
                    />
                  </dd>
                </div>
                <div>
                  <dt>{T.peca.nestaMaleta}</dt>
                  <dd>
                    {maleta.itens.find((i) => i.produtoId === lida.produto.id)?.quantidade ?? 0}
                  </dd>
                </div>
                <div>
                  <dt>{T.peca.preco}</dt>
                  <dd>
                    <Dinheiro valor={lida.produto.precoVenda} />
                  </dd>
                </div>
              </dl>
              <Aviso tipo={lida.resultado.tipo === 'erro' ? 'erro' : 'sucesso'}>
                {lida.resultado.texto}
              </Aviso>
            </div>
          </div>
        </section>
      ) : null}

      <section className="gestao-grupo" aria-label={T.itens.legenda}>
        {erro ? <AvisoDeErro erro={erro} aoAtualizar={() => void atualizarDaApi()} /> : null}
        <Tabela
          legenda={T.itens.legenda}
          textoVazio={T.itens.vazia}
          colunas={[
            { chave: 'foto', titulo: T.itens.colunas.foto },
            { chave: 'nome', titulo: T.itens.colunas.nome },
            { chave: 'codigo', titulo: T.itens.colunas.codigo },
            { chave: 'quantidade', titulo: T.itens.colunas.quantidade, numerica: true },
            { chave: 'preco', titulo: T.itens.colunas.preco, numerica: true },
            { chave: 'subtotal', titulo: T.itens.colunas.subtotal, numerica: true },
            { chave: 'acoes', titulo: T.itens.colunas.acoes },
          ]}
          linhas={maleta.itens.map((i) => ({
            foto: (
              <ImagemDoProduto ponte={ponte} hash={i.fotoHash} tamanho="miniatura" nome={i.nome} />
            ),
            nome: i.nome,
            codigo: i.codigo,
            quantidade: (
              <form
                className="gestao-correcao-de-codigo"
                onSubmit={(e) => {
                  e.preventDefault();
                  void confirmarQuantidade(i.produtoId);
                }}
              >
                <input
                  className="gestao-campo-da-tabela"
                  aria-label={T.itens.quantidadeDe(i.nome)}
                  inputMode="numeric"
                  autoComplete="off"
                  value={digitadas[i.produtoId] ?? String(i.quantidade)}
                  aria-invalid={quantidadeRuim === i.produtoId ? true : undefined}
                  onChange={(e) => setDigitadas((d) => ({ ...d, [i.produtoId]: e.target.value }))}
                />
                {digitadas[i.produtoId] !== undefined &&
                digitadas[i.produtoId] !== String(i.quantidade) ? (
                  <Botao type="submit" aria-label={T.itens.atualizarDe(i.nome)}>
                    {T.itens.atualizar}
                  </Botao>
                ) : null}
                {quantidadeRuim === i.produtoId ? (
                  <span role="alert" className="cs-campo__erro">
                    {T.itens.quantidadeInvalida}
                  </span>
                ) : null}
              </form>
            ),
            preco: <Dinheiro valor={i.precoUnitario} />,
            subtotal: <Dinheiro valor={i.total} />,
            acoes: (
              <Botao aria-label={T.itens.tirarDe(i.nome)} onClick={() => void tirar(i.produtoId)}>
                {T.itens.tirar}
              </Botao>
            ),
          }))}
        />
        {salvando > 0 ? <p role="status">{T.itens.salvando}</p> : null}

        <div className="gestao-consulta">
          <section aria-labelledby="titulo-totais-da-maleta">
            <h3 id="titulo-totais-da-maleta" className="gestao-subtitulo">
              {T.totais.titulo}
            </h3>
            <dl className="gestao-resumo" aria-live="polite">
              <div>
                <dt>{T.totais.pecas}</dt>
                <dd>{maleta.totalPecas}</dd>
              </div>
              <div>
                <dt>{T.totais.valor}</dt>
                <dd>
                  <Dinheiro valor={maleta.totalValor} />
                </dd>
              </div>
            </dl>
          </section>
          <section aria-labelledby="titulo-limite-da-maleta">
            <h3 id="titulo-limite-da-maleta" className="gestao-subtitulo">
              {L.titulo}
            </h3>
            {limite.carregando && !situacaoDoLimite ? <p role="status">{L.carregando}</p> : null}
            {limite.erro && !situacaoDoLimite ? (
              <AvisoDeErro erro={limite.erro} aoTentarDeNovo={limite.recarregar} />
            ) : null}
            {situacaoDoLimite?.semLimite ? <p className="gestao-texto">{L.semLimite}</p> : null}
            {situacaoDoLimite && !situacaoDoLimite.definido ? (
              <Aviso tipo="atencao">{L.naoDefinido}</Aviso>
            ) : null}
            {situacaoDoLimite && situacaoDoLimite.limite !== null ? (
              <dl className="gestao-resumo">
                <div>
                  <dt>{L.limite}</dt>
                  <dd>
                    {situacaoDoLimite.limite === null ? (
                      situacaoDoLimite.semLimite ? (
                        L.semLimite
                      ) : (
                        L.naoDefinido
                      )
                    ) : (
                      <Dinheiro valor={situacaoDoLimite.limite} />
                    )}
                  </dd>
                </div>
                <div>
                  <dt>{L.jaComEle}</dt>
                  <dd>
                    <Dinheiro valor={situacaoDoLimite.jaComEle} />
                  </dd>
                </div>
                <div>
                  <dt>{L.estaMaleta}</dt>
                  <dd>
                    <Dinheiro valor={estaMaleta} />
                  </dd>
                </div>
                {sobra !== null ? (
                  <div>
                    <dt>{L.sobra}</dt>
                    <dd>
                      <Dinheiro valor={sobra} />
                    </dd>
                  </div>
                ) : null}
              </dl>
            ) : null}
            {sobra !== null && sobra < 0n ? (
              <Aviso tipo="atencao">{L.passou(formatarReais(-sobra))}</Aviso>
            ) : null}
          </section>
        </div>

        {semPecas ? <Aviso tipo="atencao">{T.vazia}</Aviso> : null}
        <div className="gestao-acoes-da-linha">
          <Botao onClick={aoVoltar}>{T.voltar}</Botao>
          <Botao variante="primario" onClick={pedirEnvio} disabled={enviando || salvando > 0}>
            {T.enviar}
          </Botao>
        </div>
      </section>

      {confirmando ? (
        <JanelaDeConfirmacao
          titulo={T.confirmacao.titulo}
          rotuloConfirmar={enviando ? T.confirmacao.enviando : T.confirmacao.enviar}
          rotuloCancelar={T.confirmacao.voltar}
          ocupado={enviando}
          aoCancelar={() => setConfirmando(false)}
          aoConfirmar={() => void enviar()}
        >
          {T.confirmacao.texto(
            maleta.vendedorNome,
            T.pecas(maleta.totalPecas),
            formatarReais(estaMaleta),
          )}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
