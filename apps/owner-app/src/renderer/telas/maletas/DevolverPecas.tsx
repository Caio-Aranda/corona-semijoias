import { Aviso, Botao, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import { MAXIMO_DA_QUANTIDADE_NA_MALETA } from '../../../shared/maletas';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import type { Produto } from '../produtos/dados';
import { LeitorDaMaleta } from './LeitorDaMaleta';
import type { Maleta, SaldoDaMaleta } from './dados';

const T = TEXTOS_DE_MALETAS;
const D = T.devolucao;

export interface DevolverPecasProps {
  ponte: PonteDoCorona;
  maleta: Maleta;
  /** O que o vendedor tem agora, peça a peça (o servidor confere de novo). */
  saldo: SaldoDaMaleta;
  /** Volta para a ficha, que se atualiza (depois de devolver ou ao desistir). */
  aoVoltar: () => void;
}

interface Linha {
  /** Identificador da linha, gerado aqui: reenviar a mesma linha nunca devolve duas vezes. */
  id: string;
  quantidade: number;
}

const ehQuantidade = (texto: string, saldo: number): number | null => {
  if (!/^\d{1,6}$/.test(texto.trim())) return null;
  const n = Number(texto.trim());
  return n >= 1 && n <= Math.min(saldo, MAXIMO_DA_QUANTIDADE_NA_MALETA) ? n : null;
};

function semChave<T>(objeto: Record<string, T>, chave: string): Record<string, T> {
  return Object.fromEntries(Object.entries(objeto).filter(([k]) => k !== chave));
}

/** Devolução à dona: lê (ou digita) as peças que voltam, nunca acima do saldo, e confirma o lote. */
export function DevolverPecas({ ponte, maleta, saldo, aoVoltar }: DevolverPecasProps) {
  const [linhas, setLinhas] = useState<Record<string, Linha>>({});
  const [digitadas, setDigitadas] = useState<Record<string, string>>({});
  const [ruim, setRuim] = useState<string | null>(null);
  const [lida, setLida] = useState<{
    produto: Produto;
    tipo: 'sucesso' | 'erro';
    texto: string;
  } | null>(null);
  const [confirmando, setConfirmando] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [semPecas, setSemPecas] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [resultado, setResultado] = useState<'feita' | 'jaEstava' | null>(null);
  const trava = useRef(false);

  const saldoDe = (produtoId: string) =>
    saldo.linhas.find((l) => l.produtoId === produtoId) ?? null;
  const voltando = saldo.linhas.filter((l) => linhas[l.produtoId]);
  const totalDePecas = voltando.reduce((s, l) => s + (linhas[l.produtoId]?.quantidade ?? 0), 0);

  function mudar(produtoId: string, quantidade: number) {
    // Quantidade nova = linha nova (id novo), para o servidor não ver o mesmo id com outro valor.
    setLinhas((atual) => ({ ...atual, [produtoId]: { id: crypto.randomUUID(), quantidade } }));
    setDigitadas((d) => semChave(d, produtoId));
    setRuim(null);
    setSemPecas(false);
  }

  function aoAchar(produto: Produto): Promise<void> {
    const linha = saldoDe(produto.id);
    const aviso = (texto: string) => {
      setLida({ produto, tipo: 'erro', texto });
      return Promise.resolve();
    };
    if (!linha) return aviso(D.naoEstaNaMaleta(produto.nome));
    const atual = linhas[produto.id]?.quantidade ?? 0;
    if (linha.saldo <= 0) return aviso(D.semSaldo(produto.nome));
    if (atual + 1 > linha.saldo) return aviso(D.passaDoSaldo(produto.nome, linha.saldo));
    mudar(produto.id, atual + 1);
    setLida({ produto, tipo: 'sucesso', texto: D.entrou(produto.nome, atual + 1) });
    return Promise.resolve();
  }

  function aoNaoAchar(codigo: string) {
    setLida(null);
    setErro({
      tipo: 'outro',
      mensagem: T.leitor.naoAchouTexto(codigo),
      campos: {},
    });
  }

  function atualizarDigitada(produtoId: string, texto: string) {
    setDigitadas((d) => ({ ...d, [produtoId]: texto }));
    const linha = saldoDe(produtoId);
    const n = linha ? ehQuantidade(texto, linha.saldo) : null;
    if (n === null) {
      setRuim(produtoId);
      return;
    }
    setRuim(null);
    setLinhas((atual) => ({ ...atual, [produtoId]: { id: crypto.randomUUID(), quantidade: n } }));
  }

  function tirar(produtoId: string) {
    setLinhas((atual) => semChave(atual, produtoId));
    setDigitadas((d) => semChave(d, produtoId));
    setRuim((r) => (r === produtoId ? null : r));
  }

  function pedirConfirmacao() {
    if (voltando.length === 0) {
      setSemPecas(true);
      return;
    }
    if (ruim) return;
    setConfirmando(true);
  }

  async function devolver() {
    if (trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'maletas.devolver', {
      id: maleta.id,
      itens: voltando.map((l) => ({
        id: (linhas[l.produtoId] as Linha).id,
        produtoId: l.produtoId,
        quantidade: (linhas[l.produtoId] as Linha).quantidade,
      })),
    });
    trava.current = false;
    setOcupado(false);
    setConfirmando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    setResultado(r.jaRegistrado ? 'jaEstava' : 'feita');
    setLinhas({});
  }

  if (resultado) {
    return (
      <section className="gestao-grupo" aria-labelledby="titulo-devolucao">
        <h2 id="titulo-devolucao" className="gestao-subtitulo">
          {D.titulo}
        </h2>
        <Aviso tipo={resultado === 'feita' ? 'sucesso' : 'atencao'}>
          {resultado === 'feita' ? D.feita : D.jaEstava}
        </Aviso>
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" onClick={aoVoltar}>
            {D.voltar}
          </Botao>
        </div>
      </section>
    );
  }

  return (
    <div>
      <section className="gestao-grupo" aria-labelledby="titulo-devolucao">
        <h2 id="titulo-devolucao" className="gestao-subtitulo">
          {D.titulo}
        </h2>
        <p className="gestao-texto">{D.deQuem(maleta.vendedorNome, maleta.semanaNumero)}</p>
        <p className="gestao-texto">{D.ajuda}</p>
      </section>
      <LeitorDaMaleta
        ponte={ponte}
        aoAchar={aoAchar}
        aoNaoAchar={aoNaoAchar}
        desativado={ocupado}
      />
      {lida ? (
        <div className="gestao-peca-lida">
          <ImagemDoProduto
            ponte={ponte}
            hash={lida.produto.fotoPrincipal?.hash}
            tamanho="grande"
            nome={lida.produto.nome}
          />
          <Aviso tipo={lida.tipo === 'sucesso' ? 'sucesso' : 'erro'}>{lida.texto}</Aviso>
        </div>
      ) : null}
      <section className="gestao-grupo" aria-label={D.legenda}>
        <Tabela
          legenda={D.legenda}
          textoVazio={D.vazia}
          colunas={[
            { chave: 'foto', titulo: D.colunas.foto },
            { chave: 'nome', titulo: D.colunas.nome },
            { chave: 'codigo', titulo: D.colunas.codigo },
            { chave: 'saldo', titulo: D.colunas.saldo, numerica: true },
            { chave: 'voltando', titulo: D.colunas.voltando, numerica: true },
            { chave: 'acoes', titulo: D.colunas.acoes },
          ]}
          linhas={voltando.map((l) => ({
            foto: (
              <ImagemDoProduto ponte={ponte} hash={l.fotoHash} tamanho="miniatura" nome={l.nome} />
            ),
            nome: l.nome,
            codigo: l.codigo,
            saldo: l.saldo,
            voltando: (
              <div>
                <input
                  type="text"
                  inputMode="numeric"
                  aria-label={D.quantidadeDe(l.nome)}
                  aria-invalid={ruim === l.produtoId}
                  value={digitadas[l.produtoId] ?? String(linhas[l.produtoId]?.quantidade ?? '')}
                  onChange={(e) => atualizarDigitada(l.produtoId, e.target.value)}
                  size={6}
                />
                {ruim === l.produtoId ? (
                  <span role="alert" className="cs-campo__erro">
                    {D.quantidadeInvalida(l.saldo)}
                  </span>
                ) : null}
              </div>
            ),
            acoes: (
              <Botao aria-label={D.tirarDe(l.nome)} onClick={() => tirar(l.produtoId)}>
                {D.tirar}
              </Botao>
            ),
          }))}
        />
        {semPecas ? <Aviso tipo="atencao">{D.vazioAviso}</Aviso> : null}
        {erro ? <AvisoDeErro erro={erro} aoTentarDeNovo={() => void devolver()} /> : null}
        <div className="gestao-acoes-da-linha">
          <Botao variante="primario" onClick={pedirConfirmacao} disabled={ocupado}>
            {D.confirmar}
          </Botao>
          <Botao onClick={aoVoltar} disabled={ocupado}>
            {T.gerir.voltarAMaletas}
          </Botao>
        </div>
      </section>
      {confirmando ? (
        <JanelaDeConfirmacao
          titulo={D.confirmacao.titulo}
          rotuloConfirmar={ocupado ? D.confirmando : D.confirmar}
          rotuloCancelar={D.confirmacao.voltar}
          ocupado={ocupado}
          aoCancelar={() => setConfirmando(false)}
          aoConfirmar={() => void devolver()}
        >
          {D.confirmacao.texto(T.pecas(totalDePecas), maleta.vendedorNome)}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
