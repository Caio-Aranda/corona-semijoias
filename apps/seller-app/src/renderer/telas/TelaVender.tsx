import { Aviso, Botao, Campo, JanelaDeConfirmacao, PainelDePecaLida, Tabela } from '@corona/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { Cliente, CreditosDoCliente, LinhaDaMaleta, Maleta, Venda } from '../../shared/dados';
import {
  FORMAS_DE_PAGAMENTO,
  type FormaDePagamento,
  type PagamentoParaEnviar,
} from '../../shared/operacoes';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao, useFoto } from '../chamadas';
import { centavosDeTexto, formatarReais, lerReaisDigitados } from '../moedas';
import { TEXTOS } from '../textos';
import { detalhesDaPeca } from './detalhes-da-peca';
import { BuscaPorNome } from './BuscaPorNome';
import { FormularioDeCliente } from './FormularioDeCliente';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaVenderProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoVerVendas: () => void;
}

interface LinhaDoCarrinho {
  linha: LinhaDaMaleta;
  quantidade: number;
  /** O que a pessoa digitou no campo de desconto (vazio = sem desconto). */
  desconto: string;
}

type Forma = FormaDePagamento | 'PRAZO';

/** Centavos como texto decimal com ponto, o formato que a API entende ("50.00"). */
const textoDeCentavos = (c: bigint): string =>
  `${c / 100n}.${(c % 100n).toString().padStart(2, '0')}`;

/** Desconto digitado → centavos; vazio é zero; formato errado é `null`. */
function descontoEmCentavos(digitado: string): bigint | null {
  if (!digitado.trim()) return 0n;
  const texto = lerReaisDigitados(digitado);
  return texto === null ? null : centavosDeTexto(texto);
}

/**
 * Venda (§7.2): ler o código da peça, escolher o cliente, dizer como pagou e concluir. O app manda peça, quantidade,
 * desconto e pagamentos; preço e totais são do servidor. O total da tela é só conferência.
 */
export function TelaVender({ ponte, aoVoltar, aoVerVendas }: TelaVenderProps) {
  const T = TEXTOS.venda;
  const { cotacao, aviso: avisoDaCotacao, carregada } = useCotacao(ponte);
  const [maletas, setMaletas] = useState<Maleta[] | null>(null);
  // Vender só depois de conferir (decisão de 11/11/2026): a maleta ainda não conferida não aparece aqui.
  const [haParaConferir, setHaParaConferir] = useState(false);
  const [erroInicial, setErroInicial] = useState<string | null>(null);
  const [maletaId, setMaletaId] = useState<string>('');
  const [carrinho, setCarrinho] = useState<LinhaDoCarrinho[]>([]);
  const [codigo, setCodigo] = useState('');
  const [lendo, setLendo] = useState(false);
  const [ultima, setUltima] = useState<LinhaDaMaleta | null>(null);
  const [resultado, setResultado] = useState<
    { tipo: 'sucesso' | 'erro'; texto: string } | undefined
  >();
  const [cliente, setCliente] = useState<Cliente | null>(null);
  const [cadastrando, setCadastrando] = useState(false);
  const [busca, setBusca] = useState('');
  const [clientes, setClientes] = useState<Cliente[]>([]);
  const [forma, setForma] = useState<Forma>('DINHEIRO');
  const [valorPago, setValorPago] = useState('');
  const [problema, setProblema] = useState<string | null>(null);
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [feita, setFeita] = useState<Venda | null>(null);
  // Crédito do cliente (tarefa 6.8): só os créditos em aberto dele com este vendedor.
  const [creditos, setCreditos] = useState<CreditosDoCliente | null>(null);
  const [usandoCredito, setUsandoCredito] = useState(false);
  const [valorCredito, setValorCredito] = useState('');
  const idsDoCredito = useRef(new Map<string, string>());
  // Reenviar a mesma venda (queda de internet) usa os mesmos ids: o servidor não vende duas vezes.
  const idDaVenda = useRef(novoId());
  const idDoPagamento = useRef(novoId());
  const foto = useFoto(ponte, ultima?.produto.fotoHash, 'grande');

  useEffect(() => {
    let ativo = true;
    void chamar<Maleta[]>(ponte, { operacao: 'maletas.listar' }).then((r) => {
      if (!ativo) return;
      if (!r.ok) {
        setErroInicial(r.mensagem);
        return;
      }
      const conferidas = r.dados.filter((m) => m.status === 'CONFERIDA');
      setHaParaConferir(r.dados.some((m) => m.status === 'ENVIADA'));
      setMaletas(conferidas);
      const primeira = conferidas[0];
      if (primeira) setMaletaId(primeira.id);
    });
    return () => {
      ativo = false;
    };
  }, [ponte]);

  // A busca de cliente espera a pessoa parar de digitar.
  useEffect(() => {
    let ativo = true;
    const espera = setTimeout(() => {
      void chamar<Cliente[]>(ponte, {
        operacao: 'clientes.listar',
        ...(busca.trim() ? { busca: busca.trim() } : {}),
      }).then((r) => {
        if (ativo && r.ok) setClientes(r.dados);
      });
    }, 250);
    return () => {
      ativo = false;
      clearTimeout(espera);
    };
  }, [ponte, busca]);

  // O crédito do cliente escolhido (só no computador; nunca o de outro vendedor).
  useEffect(() => {
    setCreditos(null);
    setUsandoCredito(false);
    setValorCredito('');
    if (!cliente) return;
    let ativo = true;
    void chamar<CreditosDoCliente>(ponte, {
      operacao: 'creditos.doCliente',
      clienteId: cliente.id,
    }).then((r) => {
      if (ativo && r.ok && Array.isArray(r.dados?.creditos)) setCreditos(r.dados);
    });
    return () => {
      ativo = false;
    };
  }, [ponte, cliente]);

  const maleta = maletas?.find((m) => m.id === maletaId) ?? null;

  const subtotal = carrinho.reduce(
    (s, l) => s + (centavosDeTexto(l.linha.precoUnitario) ?? 0n) * BigInt(l.quantidade),
    0n,
  );
  const descontos = carrinho.map((l) => descontoEmCentavos(l.desconto));
  const descontoInvalido = descontos.some((d) => d === null);
  const desconto = descontos.reduce<bigint>((s, d) => s + (d ?? 0n), 0n);
  const total = subtotal - desconto;
  const saldoDoCredito = creditos ? (centavosDeTexto(creditos.total) ?? 0n) : 0n;
  let creditoCentavos: bigint | null = 0n;
  if (usandoCredito) {
    const digitado = lerReaisDigitados(valorCredito);
    creditoCentavos = digitado === null ? null : centavosDeTexto(digitado);
  }
  const resto = total - (creditoCentavos ?? 0n);
  let pagoCentavos: bigint | null;
  if (forma === 'PRAZO') pagoCentavos = 0n;
  else if (valorPago.trim() === '') pagoCentavos = resto < 0n ? 0n : resto;
  else {
    const digitado = lerReaisDigitados(valorPago);
    pagoCentavos = digitado === null ? null : centavosDeTexto(digitado);
  }

  async function ler(evento: FormEvent) {
    evento.preventDefault();
    const lido = codigo.trim();
    if (!lido || lendo || !maleta) return;
    setLendo(true);
    const r = await chamar<LinhaDaMaleta>(ponte, {
      operacao: 'maletas.peca',
      id: maleta.id,
      codigo: lido,
    });
    setLendo(false);
    setCodigo('');
    if (!r.ok) {
      setResultado({ tipo: 'erro', texto: r.mensagem });
      return;
    }
    aceitar(r.dados);
  }

  /** A peça lida pelo código ou escolhida pelo nome entra na venda do mesmo jeito. */
  function aceitar(linha: LinhaDaMaleta) {
    const jaNoCarrinho =
      carrinho.find((c) => c.linha.produto.id === linha.produto.id)?.quantidade ?? 0;
    setUltima(linha);
    if (jaNoCarrinho + 1 > linha.saldo) {
      setResultado({ tipo: 'erro', texto: T.acabou });
      return;
    }
    setResultado({ tipo: 'sucesso', texto: `${linha.produto.nome}: ${jaNoCarrinho + 1}` });
    setCarrinho((atual) => {
      const existe = atual.some((c) => c.linha.produto.id === linha.produto.id);
      return existe
        ? atual.map((c) =>
            c.linha.produto.id === linha.produto.id
              ? { ...c, linha, quantidade: c.quantidade + 1 }
              : c,
          )
        : [...atual, { linha, quantidade: 1, desconto: '' }];
    });
  }

  function mudarQuantidade(produtoId: string, delta: number) {
    setCarrinho((atual) =>
      atual
        .map((c) => {
          if (c.linha.produto.id !== produtoId) return c;
          const nova = c.quantidade + delta;
          return nova > c.linha.saldo ? c : { ...c, quantidade: nova };
        })
        .filter((c) => c.quantidade > 0),
    );
  }

  function validar(): string | null {
    if (carrinho.length === 0) return T.faltaPeca;
    if (!cliente) return T.faltaCliente;
    if (descontoInvalido) return T.descontoInvalido;
    if (creditoCentavos === null || (usandoCredito && creditoCentavos <= 0n))
      return T.creditoInvalido;
    if (creditoCentavos > saldoDoCredito) return T.creditoAcimaDoSaldo;
    if (pagoCentavos === null) return T.valorPagoInvalido;
    if (pagoCentavos + creditoCentavos > total) return T.pagoMaiorQueTotal;
    return null;
  }

  function pedirConfirmacao() {
    const falta = validar();
    setProblema(falta);
    if (!falta) setConfirmando(true);
  }

  async function concluir() {
    if (!maleta || !cliente || pagoCentavos === null || creditoCentavos === null) return;
    setEnviando(true);
    // O valor do crédito sai dos créditos do cliente na ordem (cada um até o saldo dele); o resto na forma de sempre.
    const pagamentos: PagamentoParaEnviar[] = [];
    let falta = creditoCentavos;
    for (const c of creditos?.creditos ?? []) {
      if (falta <= 0n) break;
      const saldo = centavosDeTexto(c.saldo) ?? 0n;
      const usado = saldo < falta ? saldo : falta;
      if (usado <= 0n) continue;
      let id = idsDoCredito.current.get(c.id);
      if (!id) {
        id = novoId();
        idsDoCredito.current.set(c.id, id);
      }
      pagamentos.push({ id, valor: textoDeCentavos(usado), forma: 'CREDITO', creditoId: c.id });
      falta -= usado;
    }
    if (forma !== 'PRAZO' && pagoCentavos > 0n)
      pagamentos.push({ id: idDoPagamento.current, valor: textoDeCentavos(pagoCentavos), forma });
    const r = await chamar<Venda>(ponte, {
      operacao: 'vendas.vender',
      venda: {
        id: idDaVenda.current,
        remessaId: maleta.id,
        clienteId: cliente.id,
        itens: carrinho.map((c, i) => ({
          produtoId: c.linha.produto.id,
          quantidade: c.quantidade,
          ...((descontos[i] ?? 0n) > 0n ? { desconto: textoDeCentavos(descontos[i] ?? 0n) } : {}),
        })),
        pagamentos,
        totalConferido: textoDeCentavos(total),
      },
    });
    setEnviando(false);
    setConfirmando(false);
    if (!r.ok) {
      // A venda não foi gravada (ou não se sabe): a mesma venda tenta de novo com os mesmos ids.
      setProblema(r.mensagem);
      return;
    }
    setFeita(r.dados);
  }

  function novaVenda() {
    idDaVenda.current = novoId();
    idDoPagamento.current = novoId();
    idsDoCredito.current = new Map();
    setFeita(null);
    setCarrinho([]);
    setUltima(null);
    setResultado(undefined);
    setProblema(null);
    setValorPago('');
    setForma('DINHEIRO');
    setCliente(null);
    // A maleta mudou (saldo): busca de novo.
    void chamar<Maleta[]>(ponte, { operacao: 'maletas.listar' }).then((r) => {
      if (r.ok) setMaletas(r.dados);
    });
  }

  if (erroInicial) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <Aviso tipo="erro">{erroInicial}</Aviso>
      </PaginaDoVendedor>
    );
  }
  if (maletas === null) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <p className="vendas-texto" aria-busy="true">
          {T.textoCarregando}
        </p>
      </PaginaDoVendedor>
    );
  }
  if (maletas.length === 0) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <Aviso tipo="informacao">{haParaConferir ? T.conferirAntes : T.semMaleta}</Aviso>
      </PaginaDoVendedor>
    );
  }

  if (feita) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <Aviso tipo="sucesso" titulo={T.registrada}>
          {`${feita.clienteNome}.`}
        </Aviso>
        <p className="vendas-texto">
          {T.totalDoServidor}: <Dinheiro reais={feita.total} cotacao={cotacao} />
        </p>
        <p className="vendas-texto">
          {TEXTOS.vendas.pago}: <Dinheiro reais={feita.pago} cotacao={cotacao} />
          {' · '}
          {T.aReceber}: <Dinheiro reais={feita.aReceber} cotacao={cotacao} />
        </p>
        <Botao variante="primario" grande onClick={novaVenda}>
          {T.novaVenda}
        </Botao>
        <Botao onClick={aoVerVendas}>{T.verVendas}</Botao>
      </PaginaDoVendedor>
    );
  }

  const aReceber = pagoCentavos === null ? null : resto - pagoCentavos;

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      {problema ? <Aviso tipo="erro">{problema}</Aviso> : null}
      {carregada ? (
        <p className="vendas-texto vendas-texto--pequeno">
          {cotacao && avisoDaCotacao ? avisoDaCotacao : T.cotacaoSem}
        </p>
      ) : null}

      {maletas.length > 1 ? (
        <div className="cs-campo">
          <label htmlFor="maleta-da-venda">{T.maleta}</label>
          <select
            id="maleta-da-venda"
            value={maletaId}
            onChange={(e) => {
              setMaletaId(e.target.value);
              setCarrinho([]);
              setUltima(null);
              setResultado(undefined);
            }}
          >
            {maletas.map((m) => (
              <option key={m.id} value={m.id}>
                {`${m.numero ?? ''} · ${m.pecasEmAberto} peças`}
              </option>
            ))}
          </select>
        </div>
      ) : null}

      <form className="vendas-leitor" onSubmit={(e) => void ler(e)}>
        <Campo
          rotulo={T.lerCodigo}
          value={codigo}
          onChange={(e) => setCodigo(e.target.value)}
          autoFocus
          autoComplete="off"
          spellCheck={false}
        />
        <Botao type="submit" variante="primario" disabled={lendo || !codigo.trim()}>
          Ler o código
        </Botao>
      </form>
      {ultima ? (
        <PainelDePecaLida
          nome={ultima.produto.nome}
          codigo={ultima.produto.codigo ?? ''}
          preco={<Dinheiro reais={ultima.precoUnitario} cotacao={cotacao} />}
          fotoUrl={foto}
          detalhes={detalhesDaPeca(ultima.produto, ultima.saldo)}
          {...(resultado ? { resultado } : {})}
        />
      ) : resultado ? (
        <Aviso tipo="erro">{resultado.texto}</Aviso>
      ) : null}
      {maleta ? <BuscaPorNome ponte={ponte} maletaId={maleta.id} aoEscolher={aceitar} /> : null}

      <Tabela
        legenda={T.legendaCarrinho}
        textoVazio={T.carrinhoVazio}
        colunas={[
          { chave: 'peca', titulo: T.peca },
          { chave: 'qtd', titulo: T.qtd },
          { chave: 'preco', titulo: T.preco, numerica: true },
          { chave: 'desconto', titulo: T.desconto },
          { chave: 'total', titulo: T.total, numerica: true },
        ]}
        linhas={carrinho.map((c, i) => {
          const d = descontos[i];
          const linhaTotal =
            (centavosDeTexto(c.linha.precoUnitario) ?? 0n) * BigInt(c.quantidade) - (d ?? 0n);
          return {
            peca: c.linha.produto.nome,
            qtd: (
              <span className="vendas-quantidade">
                <Botao
                  aria-label={`${T.menos}: ${c.linha.produto.nome}`}
                  onClick={() => mudarQuantidade(c.linha.produto.id, -1)}
                >
                  −
                </Botao>
                <strong>{c.quantidade}</strong>
                <Botao
                  aria-label={`${T.mais}: ${c.linha.produto.nome}`}
                  onClick={() => mudarQuantidade(c.linha.produto.id, 1)}
                >
                  +
                </Botao>
              </span>
            ),
            preco: <Dinheiro reais={c.linha.precoUnitario} cotacao={cotacao} />,
            desconto: (
              <input
                className="vendas-desconto"
                aria-label={`${T.desconto}: ${c.linha.produto.nome}`}
                inputMode="decimal"
                value={c.desconto}
                aria-invalid={d === null ? true : undefined}
                onChange={(e) =>
                  setCarrinho((atual) =>
                    atual.map((x) =>
                      x.linha.produto.id === c.linha.produto.id
                        ? { ...x, desconto: e.target.value }
                        : x,
                    ),
                  )
                }
              />
            ),
            total: (
              <Dinheiro
                reais={textoDeCentavos(linhaTotal < 0n ? 0n : linhaTotal)}
                cotacao={cotacao}
              />
            ),
          };
        })}
      />
      <p className="vendas-total">
        {T.total}: <Dinheiro reais={textoDeCentavos(total < 0n ? 0n : total)} cotacao={cotacao} />
      </p>

      <section className="vendas-cartao vendas-cartao--larga" aria-label={T.cliente}>
        <h2 className="vendas-subtitulo">{T.cliente}</h2>
        {cliente ? (
          <p className="vendas-texto">
            {T.clienteEscolhido} <strong>{cliente.nome}</strong>{' '}
            <Botao onClick={() => setCliente(null)}>{T.trocarCliente}</Botao>
          </p>
        ) : cadastrando ? (
          <FormularioDeCliente
            ponte={ponte}
            aoCancelar={() => setCadastrando(false)}
            aoSalvar={(novo) => {
              setCliente(novo);
              setCadastrando(false);
            }}
          />
        ) : (
          <>
            <Campo
              rotulo={T.buscarCliente}
              value={busca}
              onChange={(e) => setBusca(e.target.value)}
              autoComplete="off"
            />
            {clientes.length === 0 ? <p className="vendas-texto">{T.nenhumCliente}</p> : null}
            <ul className="vendas-lista">
              {clientes.slice(0, 8).map((c) => (
                <li key={c.id}>
                  <span>{c.nome}</span>
                  <Botao aria-label={`${T.escolher}: ${c.nome}`} onClick={() => setCliente(c)}>
                    {T.escolher}
                  </Botao>
                </li>
              ))}
            </ul>
            <Botao onClick={() => setCadastrando(true)}>{T.cadastrarCliente}</Botao>
          </>
        )}
      </section>

      {cliente && saldoDoCredito > 0n ? (
        <section className="vendas-cartao vendas-cartao--larga" aria-label={T.usarCredito}>
          <p className="vendas-texto">{T.creditoEmAberto(formatarReais(saldoDoCredito))}</p>
          {usandoCredito ? (
            <Campo
              rotulo={T.valorDoCredito}
              value={valorCredito}
              onChange={(e) => setValorCredito(e.target.value)}
              inputMode="decimal"
              autoComplete="off"
            />
          ) : (
            <Botao
              onClick={() => {
                // O menor entre o crédito e o total; a pessoa pode diminuir, nunca passar do crédito (dúvida V9).
                const sugestao = saldoDoCredito < total ? saldoDoCredito : total < 0n ? 0n : total;
                setValorCredito(formatarReais(sugestao).replace('R$ ', ''));
                setUsandoCredito(true);
              }}
            >
              {T.usarCredito}
            </Botao>
          )}
        </section>
      ) : null}

      <fieldset className="vendas-pagamento">
        <legend>{T.pagamento}</legend>
        {FORMAS_DE_PAGAMENTO.map((f) => (
          <label key={f}>
            <input type="radio" name="forma" checked={forma === f} onChange={() => setForma(f)} />{' '}
            {T.formas[f]}
          </label>
        ))}
        <label>
          <input
            type="radio"
            name="forma"
            checked={forma === 'PRAZO'}
            onChange={() => setForma('PRAZO')}
          />{' '}
          {T.aPrazo}
        </label>
        {forma !== 'PRAZO' ? (
          <Campo
            rotulo={T.valorPago}
            value={valorPago}
            placeholder={formatarReais(resto < 0n ? 0n : resto).replace('R$ ', '')}
            onChange={(e) => setValorPago(e.target.value)}
            inputMode="decimal"
            autoComplete="off"
          />
        ) : null}
        {aReceber !== null && aReceber > 0n ? (
          <p className="vendas-texto">
            {T.aReceber}: <Dinheiro reais={textoDeCentavos(aReceber)} cotacao={cotacao} />
          </p>
        ) : null}
      </fieldset>

      <Botao variante="primario" grande onClick={pedirConfirmacao}>
        {T.concluir}
      </Botao>

      {confirmando && cliente ? (
        <JanelaDeConfirmacao
          titulo={T.janelaTitulo}
          rotuloConfirmar={T.concluir}
          ocupado={enviando}
          aoConfirmar={() => void concluir()}
          aoCancelar={() => setConfirmando(false)}
        >
          <p>{T.janelaTexto(cliente.nome)}</p>
          <p>
            {T.total}: <Dinheiro reais={textoDeCentavos(total)} cotacao={cotacao} />
          </p>
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}
