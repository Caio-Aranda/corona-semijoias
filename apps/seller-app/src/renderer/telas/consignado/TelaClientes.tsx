import { Aviso, Botao, RodapeDeAcoes } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { ClienteCompleto, ClienteDaLista } from '../../../shared/dados-do-consignado';
import {
  FORMAS_DO_CONSIGNADO,
  TIPOS_DE_DOCUMENTO,
  type CampoDoClienteDoConsignado,
  type FormaDoConsignado,
  type FormaPaga,
  type TipoDeDocumento,
} from '../../../shared/operacoes-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { centavosDeTexto } from '../../moedas';
import { chamar, Dinheiro, novoId, useCotacao } from '../../chamadas';
import { Campo, EstadoDaLista, JanelaDeConfirmacao } from '../../componentes-do-vendedor';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import {
  classeDaSituacao,
  dia,
  dinheiroDigitado,
  FotoDoCliente,
  porcentagemDigitada,
  reais,
  situacaoNaTela,
  useConsulta,
} from './apoio';
import { diaEmBrasilia } from '../../../shared/garantia';
import { RelatoriosDoCliente, useRelatorioDaTela, ValorDoRelatorio } from './Relatorio';
import type { SituacaoDoCliente } from '../../../shared/relatorios-do-consignado';
import { CapturaDeFoto } from './CapturaDeFoto';

export interface TelaClientesProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoAbrirPedido: (notaId: string) => void;
  /** Abre direto a ficha de um cliente (vindo da pesquisa). */
  clienteInicial?: string | null;
}

type Modo =
  | { tipo: 'lista' }
  | { tipo: 'novo' }
  | { tipo: 'ficha'; id: string }
  | { tipo: 'editar'; id: string };

/** Clientes (§23.5 e §23.8): a lista, o cadastro com fotos, a ficha com pendência, maletas, histórico e Lançar pagamento. */
export function TelaClientes({
  ponte,
  aoVoltar,
  aoAbrirPedido,
  clienteInicial = null,
}: TelaClientesProps) {
  const T = TEXTOS.consignado.clientes;
  const [modo, setModo] = useState<Modo>(
    clienteInicial ? { tipo: 'ficha', id: clienteInicial } : { tipo: 'lista' },
  );
  const [busca, setBusca] = useState('');
  const [aviso, setAviso] = useState<string | null>(null);
  const {
    dados: clientes,
    erro,
    recarregar,
  } = useConsulta<ClienteDaLista[]>(
    ponte,
    { operacao: 'consignado.clientes', ...(busca.trim() ? { busca: busca.trim() } : {}) },
    [],
  );

  if (modo.tipo === 'novo' || modo.tipo === 'editar') {
    return (
      <PaginaDoVendedor
        ponte={ponte}
        titulo={
          modo.tipo === 'novo'
            ? TEXTOS.consignado.cadastro.tituloNovo
            : TEXTOS.consignado.cadastro.tituloEditar
        }
        aoVoltar={() =>
          setModo(modo.tipo === 'editar' ? { tipo: 'ficha', id: modo.id } : { tipo: 'lista' })
        }
      >
        <FormularioDoCliente
          ponte={ponte}
          {...(modo.tipo === 'editar' ? { editarId: modo.id } : {})}
          aoCancelar={() =>
            setModo(modo.tipo === 'editar' ? { tipo: 'ficha', id: modo.id } : { tipo: 'lista' })
          }
          aoSalvar={(c) => {
            setAviso(modo.tipo === 'novo' ? T.salvo : T.atualizado);
            setModo({ tipo: 'ficha', id: c.id });
            void recarregar();
          }}
        />
      </PaginaDoVendedor>
    );
  }
  if (modo.tipo === 'ficha') {
    return (
      <FichaDoCliente
        ponte={ponte}
        id={modo.id}
        aviso={aviso}
        aoVoltar={() => {
          setAviso(null);
          setModo({ tipo: 'lista' });
          void recarregar();
        }}
        aoEditar={() => setModo({ tipo: 'editar', id: modo.id })}
        aoAbrirPedido={aoAbrirPedido}
      />
    );
  }
  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <div className="consignado-linha-de-campos">
        <Campo
          rotulo={T.buscar}
          value={busca}
          autoComplete="off"
          onChange={(e) => setBusca(e.target.value)}
        />
        <Botao variante="primario" onClick={() => setModo({ tipo: 'novo' })}>
          {T.novo}
        </Botao>
      </div>
      <EstadoDaLista
        carregando={clientes === null && !erro}
        erro={erro}
        textoCarregando={TEXTOS.consignado.carregando}
        aoTentarDeNovo={() => void recarregar()}
      >
        <table className="cs-tabela consignado-tabela">
          <caption>{T.legenda}</caption>
          <thead>
            <tr>
              <th scope="col">{T.colunas.cliente}</th>
              <th scope="col">{T.colunas.telefone}</th>
              <th scope="col">{T.colunas.cidade}</th>
              <th scope="col" className="cs-numero">
                {T.colunas.pendencia}
              </th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {(clientes ?? []).length === 0 ? (
              <tr>
                <td colSpan={5}>{T.nenhum}</td>
              </tr>
            ) : (
              (clientes ?? []).map((c) => (
                <tr key={c.id}>
                  <td>
                    <span className="consignado-peca">
                      <FotoDoCliente ponte={ponte} hash={c.fotoHash} rotulo={c.nome} pequena />
                      {c.nome}
                    </span>
                  </td>
                  <td>{c.telefone ?? ''}</td>
                  <td>{c.cidade ?? ''}</td>
                  <td className="cs-numero">
                    {/[1-9]/.test(c.pendencia) ? reais(c.pendencia) : T.naoDeve}
                  </td>
                  <td>
                    <Botao onClick={() => setModo({ tipo: 'ficha', id: c.id })}>{T.abrir}</Botao>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </EstadoDaLista>
    </PaginaDoVendedor>
  );
}

// --- ficha ---

function FichaDoCliente({
  ponte,
  id,
  aviso,
  aoVoltar,
  aoEditar,
  aoAbrirPedido,
}: {
  ponte: PonteDoCorona;
  id: string;
  aviso: string | null;
  aoVoltar: () => void;
  aoEditar: () => void;
  aoAbrirPedido: (notaId: string) => void;
}) {
  const T = TEXTOS.consignado.clientes;
  const cotacao = useCotacao(ponte);
  const {
    dados: c,
    erro,
    recarregar,
  } = useConsulta<ClienteCompleto>(ponte, { operacao: 'consignado.cliente', id }, [id]);
  const situacao = useRelatorioDaTela<SituacaoDoCliente>(
    ponte,
    { tipo: 'SITUACAO_DO_CLIENTE', clienteId: id },
    [c],
  );
  const [lancando, setLancando] = useState(false);
  const [estornando, setEstornando] = useState<string | null>(null);
  const [mensagem, setMensagem] = useState<{ tipo: 'sucesso' | 'erro'; texto: string } | null>(
    aviso ? { tipo: 'sucesso', texto: aviso } : null,
  );

  return (
    <PaginaDoVendedor ponte={ponte} titulo={c?.nome ?? T.titulo} aoVoltar={aoVoltar}>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      {mensagem ? <Aviso tipo={mensagem.tipo}>{mensagem.texto}</Aviso> : null}
      {c ? (
        <>
          <section className="vendas-cartao vendas-cartao--larga" aria-label={T.sobreOCliente}>
            <h2 className="vendas-subtitulo">{T.sobreOCliente}</h2>
            <p className="vendas-texto">
              {[c.telefone, c.whatsapp].filter(Boolean).join(' · ')}
              <br />
              {[
                c.enderecoLogradouro,
                c.enderecoNumero,
                c.enderecoBairro,
                c.enderecoCidade,
                c.enderecoUf,
              ]
                .filter(Boolean)
                .join(', ')}
              <br />
              {c.documento ? `${TEXTOS.consignado.cadastro.documento}: ${c.documento}` : ''}
              <br />
              {TEXTOS.consignado.cadastro.comissao}: {c.comissaoPercentual.replace('.', ',')}
            </p>
            <div className="consignado-fotos">
              <figure>
                <FotoDoCliente ponte={ponte} hash={c.fotoHash} rotulo={T.fotoCliente} />
                <figcaption>{T.fotoCliente}</figcaption>
              </figure>
              <figure>
                <FotoDoCliente
                  ponte={ponte}
                  hash={c.fotoDocumentoFrenteHash}
                  rotulo={T.fotoFrente}
                />
                <figcaption>{T.fotoFrente}</figcaption>
              </figure>
              <figure>
                <FotoDoCliente ponte={ponte} hash={c.fotoDocumentoVersoHash} rotulo={T.fotoVerso} />
                <figcaption>{T.fotoVerso}</figcaption>
              </figure>
            </div>
            {c.fotosEsperandoEnvio > 0 ? (
              <p className="vendas-texto vendas-texto--pequeno">{T.fotoEsperando}</p>
            ) : null}
            <Botao onClick={aoEditar}>{T.editar}</Botao>
          </section>

          <RelatoriosDoCliente ponte={ponte} clienteId={c.id} />

          <section aria-label={T.pendenciaTitulo}>
            <h2 className="vendas-subtitulo">{T.pendenciaTitulo}</h2>
            <p className="vendas-total">
              {/[1-9]/.test(c.pendencia) ? (
                <>
                  {T.deve}:{' '}
                  {situacao?.deve ? (
                    <ValorDoRelatorio v={situacao.deve} />
                  ) : (
                    <Dinheiro reais={c.pendencia} cotacao={cotacao.cotacao} />
                  )}
                </>
              ) : (
                T.naoDeve
              )}
            </p>
            {(centavosDeTexto(c.sobraAAplicar) ?? 0n) > 0n ? (
              <p className="vendas-texto vendas-texto--pequeno">
                {T.sobraAAplicar(reais(c.sobraAAplicar))}
              </p>
            ) : null}
            {c.debitos.length > 0 ? (
              <table className="cs-tabela">
                <caption className="consignado-so-leitor">{T.pendenciaTitulo}</caption>
                <thead>
                  <tr>
                    <th scope="col">{T.debitosColunas.nota}</th>
                    <th scope="col">{T.debitosColunas.data}</th>
                    <th scope="col" className="cs-numero">
                      {T.debitosColunas.valor}
                    </th>
                    <th scope="col" className="cs-numero">
                      {T.debitosColunas.deve}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {c.debitos.map((d) => (
                    <tr key={d.acertoId} className="consignado-linha consignado-linha--pendente">
                      <td>{d.numeroDaNota}</td>
                      <td>{dia(d.acertadoEm)}</td>
                      <td className="cs-numero">{reais(d.valor)}</td>
                      <td className="cs-numero">{reais(d.saldo)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : null}
            <Botao
              variante="primario"
              disabled={!/[1-9]/.test(c.pendencia)}
              onClick={() => setLancando(true)}
            >
              {T.lancar}
            </Botao>
          </section>

          <section aria-label={T.maletasAtuais}>
            <h2 className="vendas-subtitulo">{T.maletasAtuais}</h2>
            {c.maletasAtuais.length === 0 ? (
              <p className="vendas-texto">{T.nenhumaMaleta}</p>
            ) : (
              <ul className="vendas-lista">
                {c.maletasAtuais.map((m) => (
                  <li key={m.remessaId}>{T.maletaPecas(m.maleta, m.pecas)}</li>
                ))}
              </ul>
            )}
          </section>

          <section aria-label={T.historico}>
            <h2 className="vendas-subtitulo">{T.historico}</h2>
            {c.pedidos.length === 0 ? (
              <p className="vendas-texto">{T.semHistorico}</p>
            ) : (
              <table className="cs-tabela consignado-tabela">
                <caption className="consignado-so-leitor">{T.historico}</caption>
                <thead>
                  <tr>
                    <th scope="col">{T.historicoColunas.nota}</th>
                    <th scope="col">{T.historicoColunas.cobranca}</th>
                    <th scope="col" className="cs-numero">
                      {T.historicoColunas.dx}
                    </th>
                    <th scope="col" className="cs-numero">
                      {T.historicoColunas.vd}
                    </th>
                    <th scope="col" className="cs-numero">
                      {T.historicoColunas.liquido}
                    </th>
                    <th scope="col">{T.historicoColunas.situacao}</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {c.pedidos.map((p) => (
                    <tr key={p.notaId} className={classeDaSituacao(p.situacao)}>
                      <td className="consignado-nota">{p.numero}</td>
                      <td>{dia(p.dataCobranca)}</td>
                      <td className="cs-numero">{reais(p.valorDx)}</td>
                      <td className="cs-numero">{reais(p.valorVd)}</td>
                      <td className="cs-numero">{reais(p.valorLiquido)}</td>
                      <td>{situacaoNaTela(p.situacao)}</td>
                      <td>
                        <Botao onClick={() => aoAbrirPedido(p.notaId)}>{T.abrirPedido}</Botao>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section aria-label={T.pagamentos}>
            <h2 className="vendas-subtitulo">{T.pagamentos}</h2>
            {c.pagamentos.length === 0 ? (
              <p className="vendas-texto">{T.semPagamentos}</p>
            ) : (
              <table className="cs-tabela">
                <caption className="consignado-so-leitor">{T.pagamentos}</caption>
                <thead>
                  <tr>
                    <th scope="col">{T.pagamentosColunas.recibo}</th>
                    <th scope="col">{T.pagamentosColunas.data}</th>
                    <th scope="col">{T.pagamentosColunas.forma}</th>
                    <th scope="col" className="cs-numero">
                      {T.pagamentosColunas.total}
                    </th>
                    <th scope="col">{T.pagamentosColunas.acao}</th>
                  </tr>
                </thead>
                <tbody>
                  {c.pagamentos.map((p) => (
                    <tr key={p.reciboId}>
                      <td className="consignado-nota">{p.numero}</td>
                      <td>{dia(p.pagoEm)}</td>
                      <td>
                        {p.formas
                          .map((f) => TEXTOS.consignado.formas[f.forma] ?? f.forma)
                          .join(', ')}
                      </td>
                      <td className="cs-numero">{reais(p.total)}</td>
                      <td>
                        {p.estornado ? (
                          T.estornadoTexto
                        ) : p.podeEstornar ? (
                          <Botao variante="perigoso" onClick={() => setEstornando(p.reciboId)}>
                            {T.estornarPagamento}
                          </Botao>
                        ) : p.doAcerto ? (
                          T.doAcerto
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      ) : null}

      {lancando && c ? (
        <LancarPagamento
          ponte={ponte}
          cliente={c}
          aoFechar={() => setLancando(false)}
          aoLancar={(numero) => {
            setLancando(false);
            setMensagem({ tipo: 'sucesso', texto: T.lancado(numero) });
            void recarregar();
          }}
        />
      ) : null}
      {estornando ? (
        <EstornarPagamento
          ponte={ponte}
          reciboId={estornando}
          aoFechar={() => setEstornando(null)}
          aoEstornar={() => {
            setEstornando(null);
            setMensagem({ tipo: 'sucesso', texto: T.pagamentoEstornado });
            void recarregar();
          }}
        />
      ) : null}
    </PaginaDoVendedor>
  );
}

function LancarPagamento({
  ponte,
  cliente,
  aoFechar,
  aoLancar,
}: {
  ponte: PonteDoCorona;
  cliente: ClienteCompleto;
  aoFechar: () => void;
  aoLancar: (numeroDoRecibo: string) => void;
}) {
  const T = TEXTOS.consignado.clientes;
  const [valores, setValores] = useState<Record<FormaDoConsignado, string>>({
    DINHEIRO: '',
    PIX: '',
    DEBITO: '',
    CREDITO: '',
  });
  const hoje = diaEmBrasilia(new Date());
  const [dataDoPagamento, setDataDoPagamento] = useState(hoje);
  const [erro, setErro] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const dataInvalida = !/^\d{4}-\d{2}-\d{2}$/.test(dataDoPagamento) || dataDoPagamento > hoje;
  const formas: FormaPaga[] = [];
  let invalido = false;
  for (const f of FORMAS_DO_CONSIGNADO) {
    const t = valores[f].trim();
    if (t === '') continue;
    const v = dinheiroDigitado(t);
    if (v === null || Number(v) <= 0) invalido = true;
    else formas.push({ forma: f, valor: v });
  }
  // Em centavos inteiros (BigInt): dinheiro nunca passa por número fracionário.
  const total = formas.reduce((s, f) => s + (centavosDeTexto(f.valor) ?? 0n), 0n);
  const devido = centavosDeTexto(cliente.pendencia) ?? 0n;
  const totalEmTexto = `${total / 100n}.${(total % 100n).toString().padStart(2, '0')}`;

  async function lancar(): Promise<void> {
    setOcupado(true);
    const r = await chamar<{ cliente: ClienteCompleto | null; sequencia: number }>(ponte, {
      operacao: 'consignado.pagamento',
      pagamento: { id: novoId(), clienteId: cliente.id, formas, data: dataDoPagamento },
    });
    setOcupado(false);
    if (!r.ok) {
      setErro(r.mensagem);
      return;
    }
    const recibo = r.dados.cliente?.pagamentos.find((p) => !p.estornado && !p.doAcerto);
    aoLancar(recibo?.numero ?? '');
  }

  return (
    <JanelaDeConfirmacao
      titulo={T.lancarTitulo}
      rotuloConfirmar={T.lancarBotao}
      ocupado={ocupado || invalido || dataInvalida || formas.length === 0 || total > devido}
      aoConfirmar={() => void lancar()}
      aoCancelar={aoFechar}
    >
      <p>{T.lancarAjuda}</p>
      <p>
        <strong>
          {T.deve}: {reais(cliente.pendencia)}
        </strong>
      </p>
      <Campo
        rotulo={T.lancarData}
        ajuda={T.lancarDataAjuda}
        type="date"
        max={hoje}
        value={dataDoPagamento}
        onChange={(e) => setDataDoPagamento(e.target.value)}
        {...(dataInvalida ? { erro: T.lancarDataAjuda } : {})}
      />
      {FORMAS_DO_CONSIGNADO.map((f) => (
        <Campo
          key={f}
          rotulo={TEXTOS.consignado.formas[f] ?? f}
          inputMode="decimal"
          placeholder="0,00"
          value={valores[f]}
          {...(valores[f].trim() !== '' && dinheiroDigitado(valores[f]) === null
            ? { erro: TEXTOS.consignado.cobranca.valorInvalido }
            : {})}
          onChange={(e) => setValores((v) => ({ ...v, [f]: e.target.value }))}
        />
      ))}
      <p>
        {T.lancarTotal}: <strong>{reais(totalEmTexto)}</strong>
      </p>
      {total > devido ? <Aviso tipo="erro">{TEXTOS.consignado.cobranca.pagoAcima}</Aviso> : null}
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
    </JanelaDeConfirmacao>
  );
}

function EstornarPagamento({
  ponte,
  reciboId,
  aoFechar,
  aoEstornar,
}: {
  ponte: PonteDoCorona;
  reciboId: string;
  aoFechar: () => void;
  aoEstornar: () => void;
}) {
  const T = TEXTOS.consignado.clientes;
  const [motivo, setMotivo] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);
  return (
    <JanelaDeConfirmacao
      titulo={T.estornarPagamentoTitulo}
      rotuloConfirmar={T.estornarPagamentoBotao}
      perigosa
      ocupado={ocupado || motivo.trim() === ''}
      aoConfirmar={() => {
        setOcupado(true);
        void chamar(ponte, {
          operacao: 'consignado.estornarRecibo',
          id: novoId(),
          reciboId,
          motivo: motivo.trim(),
        }).then((r) => {
          setOcupado(false);
          if (r.ok) aoEstornar();
          else setErro(r.mensagem);
        });
      }}
      aoCancelar={aoFechar}
    >
      <p>{T.estornarPagamentoTexto}</p>
      <Campo rotulo={T.estornarMotivo} value={motivo} onChange={(e) => setMotivo(e.target.value)} />
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
    </JanelaDeConfirmacao>
  );
}

// --- cadastro e mudança do cadastro ---

const VAZIO = {
  nome: '',
  telefone: '',
  whatsapp: '',
  enderecoLogradouro: '',
  enderecoNumero: '',
  enderecoComplemento: '',
  enderecoBairro: '',
  enderecoCidade: '',
  enderecoUf: '',
  enderecoCep: '',
  documentoTipo: 'CPF' as TipoDeDocumento,
  documento: '',
  indicadoPorClienteId: '',
  indicadoPorNome: '',
  comoConheceu: '',
  comissao: '30',
  observacoes: '',
};
type Campos = typeof VAZIO;

function FormularioDoCliente({
  ponte,
  editarId,
  aoCancelar,
  aoSalvar,
}: {
  ponte: PonteDoCorona;
  editarId?: string;
  aoCancelar: () => void;
  aoSalvar: (c: ClienteCompleto) => void;
}) {
  const T = TEXTOS.consignado.cadastro;
  const [id] = useState(() => editarId ?? novoId());
  const { dados: existente } = useConsulta<ClienteCompleto>(
    ponte,
    editarId ? { operacao: 'consignado.cliente', id: editarId } : null,
    [editarId],
  );
  const { dados: todos } = useConsulta<ClienteDaLista[]>(
    ponte,
    { operacao: 'consignado.clientes' },
    [],
  );
  const [v, setV] = useState<Campos>(VAZIO);
  const [fotos, setFotos] = useState<{
    cliente: string | null;
    frente: string | null;
    verso: string | null;
  }>({
    cliente: null,
    frente: null,
    verso: null,
  });
  const [erro, setErro] = useState<string | null>(null);
  const [repetido, setRepetido] = useState<string | null>(null);
  const [ocupado, setOcupado] = useState(false);

  useEffect(() => {
    if (!existente) return;
    setV({
      nome: existente.nome,
      telefone: existente.telefone ?? '',
      whatsapp: existente.whatsapp ?? '',
      enderecoLogradouro: existente.enderecoLogradouro ?? '',
      enderecoNumero: existente.enderecoNumero ?? '',
      enderecoComplemento: existente.enderecoComplemento ?? '',
      enderecoBairro: existente.enderecoBairro ?? '',
      enderecoCidade: existente.enderecoCidade ?? '',
      enderecoUf: existente.enderecoUf ?? '',
      enderecoCep: existente.enderecoCep ?? '',
      documentoTipo: (existente.documentoTipo as TipoDeDocumento | null) ?? 'CPF',
      documento: existente.documento ?? '',
      indicadoPorClienteId: existente.indicadoPorClienteId ?? '',
      indicadoPorNome: existente.indicadoPorNome ?? '',
      comoConheceu: existente.comoConheceu ?? '',
      comissao: existente.comissaoPercentual.replace(/\.00$/, '').replace('.', ','),
      observacoes: existente.observacoes ?? '',
    });
  }, [existente]);

  // O mesmo documento em outro cliente: avisa, não barra (§23.5).
  useEffect(() => {
    const doc = v.documento.trim();
    if (doc.length < 4) {
      setRepetido(null);
      return;
    }
    let ativo = true;
    const espera = setTimeout(() => {
      void chamar<ClienteDaLista[]>(ponte, { operacao: 'consignado.clientes', busca: doc }).then(
        (r) => {
          if (!ativo || !r.ok) return;
          const outro = r.dados.find((c) => c.id !== id);
          setRepetido(outro ? outro.nome : null);
        },
      );
    }, 400);
    return () => {
      ativo = false;
      clearTimeout(espera);
    };
  }, [ponte, v.documento, id]);

  const set = (campo: keyof Campos) => (e: { target: { value: string } }) =>
    setV((x) => ({ ...x, [campo]: e.target.value }));
  const comissao = porcentagemDigitada(v.comissao || '30');
  const faltaObrigatorio =
    !v.nome.trim() ||
    !v.enderecoLogradouro.trim() ||
    !v.enderecoNumero.trim() ||
    !v.enderecoBairro.trim() ||
    !v.enderecoCidade.trim() ||
    !v.enderecoUf.trim() ||
    !(v.telefone.trim() || v.whatsapp.trim());
  const faltaFoto = !editarId && !(fotos.cliente && fotos.frente && fotos.verso);

  async function salvar(): Promise<void> {
    setErro(null);
    if (faltaObrigatorio) {
      setErro(TEXTOS.consignado.cadastro.obrigatorios);
      return;
    }
    if (comissao === null) {
      setErro(TEXTOS.consignado.cobranca.comissaoInvalida);
      return;
    }
    if (faltaFoto) {
      setErro(T.faltaFoto);
      return;
    }
    setOcupado(true);
    if (!editarId) {
      const r = await chamar<ClienteCompleto>(ponte, {
        operacao: 'consignado.clienteCadastrar',
        cliente: {
          id,
          nome: v.nome,
          telefone: v.telefone,
          ...(v.whatsapp.trim() ? { whatsapp: v.whatsapp } : {}),
          enderecoLogradouro: v.enderecoLogradouro,
          enderecoNumero: v.enderecoNumero,
          ...(v.enderecoComplemento.trim() ? { enderecoComplemento: v.enderecoComplemento } : {}),
          enderecoBairro: v.enderecoBairro,
          enderecoCidade: v.enderecoCidade,
          enderecoUf: v.enderecoUf,
          ...(v.enderecoCep.trim() ? { enderecoCep: v.enderecoCep } : {}),
          ...(v.documento.trim() ? { documento: v.documento, documentoTipo: v.documentoTipo } : {}),
          ...(v.indicadoPorClienteId ? { indicadoPorClienteId: v.indicadoPorClienteId } : {}),
          ...(!v.indicadoPorClienteId && v.indicadoPorNome.trim()
            ? { indicadoPorNome: v.indicadoPorNome }
            : {}),
          ...(v.comoConheceu.trim() ? { comoConheceu: v.comoConheceu } : {}),
          comissaoPercentual: comissao,
          ...(v.observacoes.trim() ? { observacoes: v.observacoes } : {}),
          fotoHash: fotos.cliente as string,
          fotoDocumentoFrenteHash: fotos.frente as string,
          fotoDocumentoVersoHash: fotos.verso as string,
        },
      });
      setOcupado(false);
      if (r.ok) aoSalvar(r.dados);
      else setErro(r.mensagem);
      return;
    }
    const campos: Partial<Record<CampoDoClienteDoConsignado, string>> = {
      nome: v.nome,
      telefone: v.telefone,
      whatsapp: v.whatsapp,
      enderecoLogradouro: v.enderecoLogradouro,
      enderecoNumero: v.enderecoNumero,
      enderecoComplemento: v.enderecoComplemento,
      enderecoBairro: v.enderecoBairro,
      enderecoCidade: v.enderecoCidade,
      enderecoUf: v.enderecoUf,
      enderecoCep: v.enderecoCep,
      documento: v.documento,
      ...(v.documento.trim() ? { documentoTipo: v.documentoTipo } : {}),
      indicadoPorNome: v.indicadoPorClienteId ? '' : v.indicadoPorNome,
      comoConheceu: v.comoConheceu,
      comissaoPercentual: comissao,
      observacoes: v.observacoes,
    };
    const r = await chamar<ClienteCompleto>(ponte, {
      operacao: 'consignado.clienteAlterar',
      id: editarId,
      campos,
    });
    if (r.ok) {
      for (const [tipo, hash] of [
        ['cliente', fotos.cliente],
        ['documento-frente', fotos.frente],
        ['documento-verso', fotos.verso],
      ] as const) {
        if (hash)
          await chamar(ponte, {
            operacao: 'consignado.clienteTrocarFoto',
            id: editarId,
            tipo,
            hash,
          });
      }
    }
    setOcupado(false);
    if (r.ok) aoSalvar(r.dados);
    else setErro(r.mensagem);
  }

  return (
    <form
      className="consignado-formulario"
      onSubmit={(e) => {
        e.preventDefault();
        void salvar();
      }}
    >
      <p className="vendas-texto vendas-texto--pequeno">{T.obrigatorios}</p>
      <Campo rotulo={`${T.nome} *`} value={v.nome} onChange={set('nome')} />
      <Campo
        rotulo={`${T.telefone} *`}
        value={v.telefone}
        inputMode="tel"
        onChange={set('telefone')}
      />
      <Campo rotulo={T.whatsapp} value={v.whatsapp} inputMode="tel" onChange={set('whatsapp')} />
      <Campo
        rotulo={`${T.enderecoLogradouro} *`}
        value={v.enderecoLogradouro}
        onChange={set('enderecoLogradouro')}
      />
      <Campo
        rotulo={`${T.enderecoNumero} *`}
        value={v.enderecoNumero}
        onChange={set('enderecoNumero')}
      />
      <Campo
        rotulo={T.enderecoComplemento}
        value={v.enderecoComplemento}
        onChange={set('enderecoComplemento')}
      />
      <Campo
        rotulo={`${T.enderecoBairro} *`}
        value={v.enderecoBairro}
        onChange={set('enderecoBairro')}
      />
      <Campo
        rotulo={`${T.enderecoCidade} *`}
        value={v.enderecoCidade}
        onChange={set('enderecoCidade')}
      />
      <Campo rotulo={`${T.enderecoUf} *`} value={v.enderecoUf} onChange={set('enderecoUf')} />
      <Campo rotulo={T.enderecoCep} value={v.enderecoCep} onChange={set('enderecoCep')} />
      <div className="cs-campo">
        <label htmlFor="cliente-documento-tipo">{T.documentoTipo}</label>
        <select id="cliente-documento-tipo" value={v.documentoTipo} onChange={set('documentoTipo')}>
          {TIPOS_DE_DOCUMENTO.map((t) => (
            <option key={t} value={t}>
              {T.tiposDeDocumento[t] ?? t}
            </option>
          ))}
        </select>
      </div>
      <Campo
        rotulo={T.documento}
        ajuda={T.documentoAjuda}
        value={v.documento}
        onChange={set('documento')}
      />
      {repetido ? <Aviso tipo="atencao">{T.documentoRepetido(repetido)}</Aviso> : null}
      <fieldset className="consignado-indicado">
        <legend>{T.indicadoPor}</legend>
        <p className="vendas-texto vendas-texto--pequeno">{T.indicadoPorAjuda}</p>
        <div className="cs-campo">
          <label htmlFor="cliente-indicado">{T.indicadoPorCliente}</label>
          <select
            id="cliente-indicado"
            value={v.indicadoPorClienteId}
            onChange={set('indicadoPorClienteId')}
            disabled={Boolean(editarId)}
          >
            <option value="">{T.nenhumIndicado}</option>
            {(todos ?? [])
              .filter((c) => c.id !== id)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.nome}
                </option>
              ))}
          </select>
        </div>
        <Campo
          rotulo={T.indicadoPorNome}
          value={v.indicadoPorNome}
          disabled={Boolean(v.indicadoPorClienteId)}
          onChange={set('indicadoPorNome')}
        />
      </fieldset>
      <Campo rotulo={T.comoConheceu} value={v.comoConheceu} onChange={set('comoConheceu')} />
      <Campo
        rotulo={`${T.comissao} *`}
        ajuda={T.comissaoAjuda}
        inputMode="decimal"
        value={v.comissao}
        onChange={set('comissao')}
      />
      <Campo rotulo={T.observacoes} value={v.observacoes} onChange={set('observacoes')} />

      <CapturaDeFoto
        ponte={ponte}
        rotulo={`${T.fotoCliente} *`}
        hash={fotos.cliente ?? existente?.fotoHash ?? null}
        aoGuardar={(h) => setFotos((f) => ({ ...f, cliente: h }))}
      />
      <CapturaDeFoto
        ponte={ponte}
        rotulo={`${T.fotoFrente} *`}
        hash={fotos.frente ?? existente?.fotoDocumentoFrenteHash ?? null}
        aoGuardar={(h) => setFotos((f) => ({ ...f, frente: h }))}
      />
      <CapturaDeFoto
        ponte={ponte}
        rotulo={`${T.fotoVerso} *`}
        hash={fotos.verso ?? existente?.fotoDocumentoVersoHash ?? null}
        aoGuardar={(h) => setFotos((f) => ({ ...f, verso: h }))}
      />

      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      <RodapeDeAcoes
        cancelar={<Botao onClick={aoCancelar}>{T.cancelar}</Botao>}
        principal={
          <Botao type="submit" variante="primario" grande disabled={ocupado}>
            {T.salvar}
          </Botao>
        }
      />
    </form>
  );
}
