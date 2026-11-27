import { Aviso, Botao, EstadoDaLista, JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState } from 'react';
import { MAXIMO_DO_MOTIVO, type DecisaoDaFalta } from '../../../shared/consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import {
  chamar,
  chamarTodasAsPaginas,
  useBusca,
  type ErroDaTela,
  type Retorno,
} from '../../nucleo/chamadas';
import { centavosDeTexto, formatarReais } from '../../nucleo/moedas';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { dataEHora } from '../sincronizacao/dados';
import { LeitorDaMaleta } from '../maletas/LeitorDaMaleta';
import { lerPaginaDeMaletas, type Maleta } from '../maletas/dados';
import type { Produto } from '../produtos/dados';
import { TelaDeRelatorio } from '../relatorios/TelaDeRelatorio';
import { lerLeituraDoFechamento, type LeituraDoFechamentoLida } from './dados';

const T = TEXTOS_DE_CONSIGNADO.fechamento;
interface AvisoDaTela {
  tipo: 'sucesso' | 'atencao';
  texto: string;
}

const STATUS_DAS_MALETAS = ['ENVIADA', 'CONFERIDA', 'FECHADA'] as const;

/** Um código novo para cada leitura: reenviar a mesma leitura (queda da internet) nunca soma duas vezes. */
function novoId(): string {
  return crypto.randomUUID();
}

export interface TelaFechamentoDaMaletaProps {
  ponte: PonteDoCorona;
  usuario: string | null;
}

/**
 * Maletas → Fechamento da maleta (§23.10, ADR-0016 item 14). Quando a maleta volta, a dona lê o código de cada peça que
 * chegou; o relatório mostra enviadas, devolvidas pelos clientes, vendidas, ainda com clientes (e com quem), lidas na volta e a
 * diferença, com o valor vendido, a comissão dos clientes, o recebido e as pendências. O PDF, o Excel e o CSV saem da mesma
 * leitura. Concluir devolve ao estoque o que foi lido e fecha a maleta; a diferença nunca é corrigida sozinha.
 */
export function TelaFechamentoDaMaleta({ ponte, usuario }: TelaFechamentoDaMaletaProps) {
  const [maleta, setMaleta] = useState<Maleta | null>(null);
  if (maleta) {
    return (
      <FechamentoAberto
        key={maleta.id}
        ponte={ponte}
        usuario={usuario}
        maleta={maleta}
        aoVoltar={() => setMaleta(null)}
      />
    );
  }
  return <EscolherMaleta ponte={ponte} aoEscolher={setMaleta} />;
}

function EscolherMaleta({
  ponte,
  aoEscolher,
}: {
  ponte: PonteDoCorona;
  aoEscolher: (maleta: Maleta) => void;
}) {
  const [escolhida, setEscolhida] = useState('');
  const busca = useBusca<Maleta[]>(async (): Promise<Retorno<Maleta[]>> => {
    const respostas = await Promise.all(
      STATUS_DAS_MALETAS.map((status) => chamarTodasAsPaginas(ponte, 'maletas.listar', { status })),
    );
    for (const r of respostas) if (!r.ok) return { ok: false, erro: r.erro };
    const todas = respostas.flatMap((r) => (r.ok ? lerPaginaDeMaletas(r.dados) : []));
    todas.sort((a, b) => b.enviadaEm.localeCompare(a.enviadaEm));
    return { ok: true, dados: todas };
  }, 'fechamento-maletas');
  const maletas = busca.dados ?? [];

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <EstadoDaLista
        carregando={busca.carregando && !busca.dados}
        erro={busca.erro?.mensagem ?? null}
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        {maletas.length === 0 ? (
          <Aviso tipo="informacao">{T.nenhuma}</Aviso>
        ) : (
          <div className="gestao-formulario">
            <div className="cs-campo">
              <label htmlFor="fechamento-maleta">{T.escolher}</label>
              <select
                id="fechamento-maleta"
                value={escolhida}
                onChange={(e) => setEscolhida(e.target.value)}
              >
                <option value="">{T.escolha}</option>
                {maletas.map((m) => (
                  <option key={m.id} value={m.id}>
                    {T.nome(m.codigo, m.vendedorNome, m.semanaNumero, dataCurta(m.enviadaEm))}
                    {m.status === 'FECHADA' ? T.fechadaNaLista : ''}
                  </option>
                ))}
              </select>
            </div>
            <div className="gestao-acoes-da-linha">
              <Botao
                variante="primario"
                disabled={!escolhida}
                onClick={() => {
                  const m = maletas.find((x) => x.id === escolhida);
                  if (m) aoEscolher(m);
                }}
              >
                {T.abrir}
              </Botao>
            </div>
          </div>
        )}
      </EstadoDaLista>
    </div>
  );
}

/** "2026-11-17T15:30:00Z" → "17/11/2026" (hora de Brasília). */
function dataCurta(instante: string): string {
  return dataEHora(instante).split(' ')[0] ?? '';
}

function FechamentoAberto({
  ponte,
  usuario,
  maleta,
  aoVoltar,
}: {
  ponte: PonteDoCorona;
  usuario: string | null;
  maleta: Maleta;
  aoVoltar: () => void;
}) {
  // Muda a cada leitura e ao concluir: o relatório (que vem do servidor) é buscado de novo.
  const [versao, setVersao] = useState(0);
  const [concluiu, setConcluiu] = useState(false);
  // O relatório é refeito a cada leitura; o aviso da última leitura fica aqui para não sumir junto.
  const [aviso, setAviso] = useState<AvisoDaTela | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const leitura = useBusca<LeituraDoFechamentoLida | 'nao-comecou'>(async () => {
    const r = await chamar(ponte, 'fechamento.ler', { id: maleta.id });
    if (r.ok) {
      const l = lerLeituraDoFechamento(r.dados);
      return l ? { ok: true, dados: l } : { ok: true, dados: 'nao-comecou' };
    }
    // O servidor responde "não encontrado" quando o fechamento ainda não foi começado.
    if (r.erro.status === 404) return { ok: true, dados: 'nao-comecou' };
    return r;
  }, `fechamento-${maleta.id}-${versao}`);

  const titulo = T.maletaDoFechamento(maleta.codigo, maleta.vendedorNome);
  const dados = leitura.dados;

  if (!dados) {
    return (
      <div className="gestao-tela">
        <div>
          <Botao onClick={aoVoltar}>{T.voltarParaLista}</Botao>
        </div>
        <EstadoDaLista
          carregando={leitura.carregando}
          erro={leitura.erro?.mensagem ?? null}
          textoCarregando={T.carregando}
          aoTentarDeNovo={leitura.recarregar}
        />
      </div>
    );
  }
  if (dados === 'nao-comecou') {
    return (
      <ComecarOFechamento
        ponte={ponte}
        titulo={titulo}
        maletaId={maleta.id}
        aoVoltar={aoVoltar}
        aoComecar={() => setVersao((v) => v + 1)}
      />
    );
  }

  return (
    <TelaDeRelatorio
      key={`${maleta.id}-${versao}`}
      ponte={ponte}
      relatorio="maleta-fechamento"
      usuario={usuario}
      fixo={{ filtro: 'remessaId', valor: maleta.id, texto: titulo }}
      aoVoltar={aoVoltar}
      aposOTitulo={
        <ControlesDoFechamento
          ponte={ponte}
          maletaId={maleta.id}
          leitura={dados}
          concluiuAgora={concluiu}
          aviso={aviso}
          erro={erro}
          aoAvisar={(a, e) => {
            setAviso(a);
            setErro(e);
          }}
          aoMudar={() => setVersao((v) => v + 1)}
          aoConcluir={() => {
            setConcluiu(true);
            setVersao((v) => v + 1);
          }}
        />
      }
    />
  );
}

function ComecarOFechamento({
  ponte,
  titulo,
  maletaId,
  aoVoltar,
  aoComecar,
}: {
  ponte: PonteDoCorona;
  titulo: string;
  maletaId: string;
  aoVoltar: () => void;
  aoComecar: () => void;
}) {
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);
  async function comecar() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'fechamento.iniciar', { id: maletaId });
    trava.current = false;
    setOcupado(false);
    if (r.ok) aoComecar();
    else setErro(r.erro);
  }
  return (
    <div className="gestao-tela">
      <div>
        <Botao onClick={aoVoltar}>{T.voltarParaLista}</Botao>
      </div>
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{titulo}</p>
      <Aviso tipo="informacao">
        <strong>{T.comecarTitulo}.</strong> {T.comecarTexto}
      </Aviso>
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <div className="gestao-acoes-da-linha">
        <Botao variante="primario" disabled={ocupado} onClick={() => void comecar()}>
          {ocupado ? T.comecando : T.comecar}
        </Botao>
      </div>
    </div>
  );
}

type Escolha = { id: string; decisao: DecisaoDaFalta | ''; motivo: string };

function ControlesDoFechamento({
  ponte,
  maletaId,
  leitura,
  concluiuAgora,
  aviso,
  erro,
  aoAvisar,
  aoMudar,
  aoConcluir,
}: {
  ponte: PonteDoCorona;
  maletaId: string;
  leitura: LeituraDoFechamentoLida;
  concluiuAgora: boolean;
  aviso: AvisoDaTela | null;
  erro: ErroDaTela | null;
  aoAvisar: (aviso: AvisoDaTela | null, erro: ErroDaTela | null) => void;
  aoMudar: () => void;
  aoConcluir: () => void;
}) {
  const [confirmando, setConfirmando] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [escolhas, setEscolhas] = useState<Record<string, Escolha>>({});
  const [tentou, setTentou] = useState(false);
  const trava = useRef(false);

  const emAndamento = leitura.situacao === 'EM_ANDAMENTO';
  const comClientes = leitura.pecas.reduce((s, p) => s + p.comClientes, 0);
  // Falta e sobra separadas, peça por peça: −1 de uma peça e +1 de outra não se anulam (C4).
  const comFalta = leitura.pecas.filter((p) => p.diferenca < 0);
  const comSobra = leitura.pecas.filter((p) => p.diferenca > 0);
  const faltam = comFalta.reduce((s, p) => s - p.diferenca, 0);
  const sobram = comSobra.reduce((s, p) => s + p.diferenca, 0);
  // Maleta fechada: as peças que as clientes devolveram depois e ainda não foram lidas (C2).
  const paraLerDepois = leitura.pecas.reduce((s, p) => s + p.paraLerDepois, 0);

  const escolhaDe = (produtoId: string): Escolha =>
    escolhas[produtoId] ?? { id: '', decisao: '', motivo: '' };
  const semEscolha = comFalta.filter((p) => escolhaDe(p.produtoId).decisao === '');
  const semMotivo = comFalta.filter((p) => {
    const e = escolhaDe(p.produtoId);
    return e.decisao === 'PERDA_DA_LOJA' && e.motivo.trim() === '';
  });

  function escolher(produtoId: string, mudanca: Partial<Escolha>) {
    setEscolhas((atual) => {
      const antes = atual[produtoId] ?? { id: novoId(), decisao: '', motivo: '' };
      return { ...atual, [produtoId]: { ...antes, id: antes.id || novoId(), ...mudanca } };
    });
  }

  async function aoAchar(produto: Produto) {
    aoAvisar(null, null);
    const r = await chamar(ponte, emAndamento ? 'fechamento.leituras' : 'fechamento.voltas', {
      id: maletaId,
      leituras: [{ id: novoId(), produtoId: produto.id, quantidade: 1 }],
    });
    if (r.ok) {
      aoAvisar(
        {
          tipo: 'sucesso',
          texto: emAndamento ? T.lida(produto.nome) : T.depois.lida(produto.nome),
        },
        null,
      );
      aoMudar();
    } else {
      aoAvisar(null, r.erro);
    }
  }

  function pedirConclusao() {
    setTentou(true);
    if (semEscolha.length > 0 || semMotivo.length > 0) return;
    setConfirmando(true);
  }

  async function concluir() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    aoAvisar(null, null);
    const decisoes = comFalta.map((p) => {
      const e = escolhaDe(p.produtoId);
      return {
        id: e.id,
        produtoId: p.produtoId,
        decisao: e.decisao,
        ...(e.decisao === 'PERDA_DA_LOJA' ? { motivo: e.motivo.trim() } : {}),
      };
    });
    const r = await chamar(ponte, 'fechamento.concluir', { id: maletaId, decisoes });
    trava.current = false;
    setOcupado(false);
    setConfirmando(false);
    if (r.ok) {
      aoAvisar({ tipo: 'sucesso', texto: T.concluiu }, null);
      aoConcluir();
    } else {
      aoAvisar(null, r.erro);
    }
  }

  return (
    <section className="gestao-grupo" aria-label={T.titulo}>
      {emAndamento ? (
        <>
          <Aviso tipo="informacao">{T.emAndamento}</Aviso>
          <LeitorDaMaleta
            ponte={ponte}
            rotulos={T.leitor}
            aoAchar={aoAchar}
            aoNaoAchar={(codigo) => {
              aoAvisar({ tipo: 'atencao', texto: T.naoAchou(codigo) }, null);
            }}
          />
        </>
      ) : (
        <Aviso tipo={concluiuAgora ? 'sucesso' : 'informacao'}>
          {T.concluido(dataEHora(leitura.concluidoEm))}
        </Aviso>
      )}
      {!emAndamento && (paraLerDepois > 0 || comClientes > 0) ? (
        <>
          <Aviso tipo={paraLerDepois > 0 ? 'atencao' : 'informacao'}>
            <p>{T.depois.paraLer(paraLerDepois)}</p>
            <p>{T.depois.aindaComClientes(comClientes)}</p>
          </Aviso>
          <LeitorDaMaleta
            ponte={ponte}
            rotulos={T.depois}
            aoAchar={aoAchar}
            aoNaoAchar={(codigo) => {
              aoAvisar({ tipo: 'atencao', texto: T.naoAchou(codigo) }, null);
            }}
          />
        </>
      ) : null}
      {aviso ? <Aviso tipo={aviso.tipo}>{aviso.texto}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {emAndamento && comClientes > 0 ? (
        <Aviso tipo="informacao">{T.aindaComClientes(comClientes)}</Aviso>
      ) : null}
      {emAndamento && faltam > 0 ? (
        <Aviso tipo="atencao">
          <p>{T.faltam(faltam)}</p>
          <ul>
            {comFalta.map((p) => (
              <li key={p.produtoId}>{T.linhaDaDiferenca(p.nome, -p.diferenca)}</li>
            ))}
          </ul>
        </Aviso>
      ) : null}
      {emAndamento && sobram > 0 ? (
        <Aviso tipo="atencao">
          <p>{T.sobram(sobram)}</p>
          <ul>
            {comSobra.map((p) => (
              <li key={p.produtoId}>{T.linhaDaDiferenca(p.nome, p.diferenca)}</li>
            ))}
          </ul>
        </Aviso>
      ) : null}
      {emAndamento && faltam === 0 && sobram === 0 ? (
        <p className="gestao-texto">{T.semDiferenca}</p>
      ) : null}
      {emAndamento && comFalta.length > 0 ? (
        <div className="gestao-grupo">
          <h3 className="gestao-subtitulo">{T.decidir.titulo}</h3>
          <p className="gestao-texto">{T.decidir.ajuda}</p>
          {comFalta.map((p) => {
            const e = escolhaDe(p.produtoId);
            const faltaEscolha = tentou && e.decisao === '';
            const faltaMotivo = tentou && e.decisao === 'PERDA_DA_LOJA' && e.motivo.trim() === '';
            const nomeDoGrupo = `falta-${p.produtoId}`;
            return (
              <fieldset
                key={p.produtoId}
                className={`gestao-escolha${faltaEscolha || faltaMotivo ? ' gestao-escolha--erro' : ''}`}
              >
                <legend>{T.decidir.peca(p.nome, -p.diferenca)}</legend>
                <label className="gestao-escolha__opcao">
                  <input
                    type="radio"
                    name={nomeDoGrupo}
                    checked={e.decisao === 'COBRAR_DO_REPRESENTANTE'}
                    onChange={() => escolher(p.produtoId, { decisao: 'COBRAR_DO_REPRESENTANTE' })}
                  />{' '}
                  {T.decidir.cobrar}
                </label>
                <label className="gestao-escolha__opcao">
                  <input
                    type="radio"
                    name={nomeDoGrupo}
                    checked={e.decisao === 'PERDA_DA_LOJA'}
                    onChange={() => escolher(p.produtoId, { decisao: 'PERDA_DA_LOJA' })}
                  />{' '}
                  {T.decidir.perda}
                </label>
                {e.decisao === 'PERDA_DA_LOJA' ? (
                  <div className="cs-campo">
                    <label htmlFor={`motivo-${p.produtoId}`}>{T.decidir.motivo}</label>
                    <input
                      id={`motivo-${p.produtoId}`}
                      type="text"
                      maxLength={MAXIMO_DO_MOTIVO}
                      value={e.motivo}
                      aria-invalid={faltaMotivo ? true : undefined}
                      onChange={(ev) => escolher(p.produtoId, { motivo: ev.target.value })}
                    />
                    <span className="cs-campo__ajuda">{T.decidir.motivoAjuda}</span>
                  </div>
                ) : null}
                {faltaEscolha ? (
                  <span role="alert" className="cs-campo__erro">
                    {T.decidir.falta}
                  </span>
                ) : null}
                {faltaMotivo ? (
                  <span role="alert" className="cs-campo__erro">
                    {T.decidir.faltaMotivo}
                  </span>
                ) : null}
              </fieldset>
            );
          })}
        </div>
      ) : null}
      {leitura.pendencias.length > 0 ? (
        <div className="gestao-grupo">
          <h3 className="gestao-subtitulo">{T.registradas.titulo}</h3>
          <ul>
            {leitura.pendencias.map((p) => (
              <li key={p.id}>
                {p.decisao === 'PERDA_DA_LOJA'
                  ? T.registradas.perda(p.nome, p.quantidade, p.motivo)
                  : T.registradas.cobrar(
                      p.nome,
                      p.quantidade,
                      formatarReais(centavosDeTexto(p.valor) ?? 0n),
                    )}
              </li>
            ))}
          </ul>
          {centavosDeTexto(leitura.cobranca.nestaMaleta) ? (
            <p className="gestao-texto">
              <strong>
                {T.registradas.totalNestaMaleta(
                  leitura.cobranca.representante,
                  formatarReais(centavosDeTexto(leitura.cobranca.nestaMaleta) ?? 0n),
                )}
              </strong>{' '}
              {T.registradas.totalDasMaletas(
                formatarReais(centavosDeTexto(leitura.cobranca.todasAsMaletas) ?? 0n),
              )}
            </p>
          ) : null}
        </div>
      ) : null}
      {emAndamento ? (
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" disabled={ocupado} onClick={pedirConclusao}>
            {ocupado ? T.concluindo : T.concluir}
          </Botao>
        </div>
      ) : null}
      {confirmando ? (
        <JanelaDeConfirmacao
          titulo={T.confirmar.titulo}
          rotuloConfirmar={T.confirmar.concluir}
          rotuloCancelar={T.confirmar.voltar}
          perigosa
          aoCancelar={() => setConfirmando(false)}
          aoConfirmar={() => void concluir()}
        >
          {T.confirmar.texto}
        </JanelaDeConfirmacao>
      ) : null}
    </section>
  );
}
