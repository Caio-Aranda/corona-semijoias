import { Aviso, Botao, Campo, EstadoDaLista, Tabela } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import type { TipoDeFotoDoCliente } from '../../../shared/consignado';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { dataNaTela } from '../cadastros/definicoes';
import { lerPagina } from '../cadastros/definicoes';
import { reais } from '../devolucoes/Apoio';
import { lerFichaDoCliente, type FichaDoCliente } from './dados';
import { situacaoDaNota } from './TelaNotasDoConsignado';

const T = TEXTOS_DE_CONSIGNADO.clientes;
const F = T.ficha;

/** Consignado → Clientes dos representantes: a lista e a ficha de cada cliente (só leitura). */
export function TelaClientesDoConsignado({
  ponte,
  aoAbrirNota,
}: {
  ponte: PonteDoCorona;
  aoAbrirNota: (notaId: string) => void;
}) {
  const [aberto, setAberto] = useState<string | null>(null);
  const [texto, setTexto] = useState('');
  const [busca, setBusca] = useState('');

  // A busca espera a pessoa parar de digitar.
  useEffect(() => {
    const relogio = setTimeout(() => setBusca(texto.trim()), 300);
    return () => clearTimeout(relogio);
  }, [texto]);

  const lista = useBusca(
    () =>
      chamar(ponte, 'clientes.listar', {
        pagina: 0,
        tamanho: 50,
        ...(busca ? { busca } : {}),
        situacao: 'ATIVOS',
      }),
    `consignado-clientes-${busca}`,
  );

  if (aberto) {
    return (
      <FichaDoClienteNaTela
        ponte={ponte}
        id={aberto}
        aoVoltar={() => setAberto(null)}
        aoAbrirNota={aoAbrirNota}
      />
    );
  }
  const clientes = lista.dados ? lerPagina(lista.dados).itens : [];
  const nome = (r: Record<string, unknown>) => (typeof r.nome === 'string' ? r.nome : '');

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <Campo
          id="clientes-consignado-busca"
          rotulo={T.buscar}
          value={texto}
          autoComplete="off"
          onChange={(e) => setTexto(e.target.value)}
        />
      </div>
      <EstadoDaLista
        carregando={lista.carregando && !lista.dados}
        erro={lista.erro?.mensagem ?? null}
        textoCarregando={T.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        <Tabela
          legenda={T.legenda}
          colunas={[
            { chave: 'nome', titulo: T.colunas.nome },
            { chave: 'telefone', titulo: T.colunas.telefone },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={clientes.map((c) => ({
            nome: nome(c),
            telefone: typeof c.telefone === 'string' ? c.telefone : '',
            acoes: (
              <Botao aria-label={T.abrirDe(nome(c))} onClick={() => setAberto(c.id)}>
                {T.abrir}
              </Botao>
            ),
          }))}
          textoVazio={T.vazio}
        />
      </EstadoDaLista>
    </div>
  );
}

function FichaDoClienteNaTela({
  ponte,
  id,
  aoVoltar,
  aoAbrirNota,
}: {
  ponte: PonteDoCorona;
  id: string;
  aoVoltar: () => void;
  aoAbrirNota: (notaId: string) => void;
}) {
  const busca = useBusca(
    () => chamar(ponte, 'consignado.cliente', { id }),
    `consignado-cliente-${id}`,
  );
  const ficha: FichaDoCliente | null = busca.dados ? lerFichaDoCliente(busca.dados) : null;
  return (
    <div className="gestao-tela">
      <div>
        <Botao onClick={aoVoltar}>{T.voltar}</Botao>
      </div>
      <EstadoDaLista
        carregando={busca.carregando && !busca.dados}
        erro={busca.erro?.mensagem ?? null}
        textoCarregando={T.carregando}
        aoTentarDeNovo={busca.recarregar}
      >
        {ficha ? <Ficha ponte={ponte} ficha={ficha} aoAbrirNota={aoAbrirNota} /> : null}
      </EstadoDaLista>
    </div>
  );
}

function Ficha({
  ponte,
  ficha,
  aoAbrirNota,
}: {
  ponte: PonteDoCorona;
  ficha: FichaDoCliente;
  aoAbrirNota: (notaId: string) => void;
}) {
  const c = ficha.cadastro;
  const semInfo = (v: string) => v || F.naoInformado;
  return (
    <>
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{F.titulo(ficha.nome)}</h1>
      </div>

      <h2 className="gestao-subtitulo">{F.dados}</h2>
      <dl className="gestao-ficha">
        <dt>{F.representante}</dt>
        <dd>{semInfo(c.representante)}</dd>
        <dt>{F.telefone}</dt>
        <dd>{semInfo(c.telefone)}</dd>
        <dt>{F.whatsapp}</dt>
        <dd>{semInfo(c.whatsapp)}</dd>
        <dt>{F.endereco}</dt>
        <dd>{semInfo(c.endereco)}</dd>
        <dt>{F.documento}</dt>
        <dd>{c.documento ? F.documentoDe(c.documentoTipo, c.documento) : F.naoInformado}</dd>
        <dt>{F.indicadoPor}</dt>
        <dd>{semInfo(c.indicadoPor)}</dd>
        <dt>{F.comoConheceu}</dt>
        <dd>{semInfo(c.comoConheceu)}</dd>
        <dt>{F.comissao}</dt>
        <dd>{F.comissaoValor(c.comissaoPercentual)}</dd>
        <dt>{F.pendencia}</dt>
        <dd>{Number(ficha.pendencia) > 0 ? reais(ficha.pendencia) : F.semPendencia}</dd>
      </dl>

      <h2 className="gestao-subtitulo">{F.fotos}</h2>
      <p className="gestao-texto">{F.fotosAjuda}</p>
      <div className="gestao-fotos-do-cliente">
        <FotoDoCliente
          ponte={ponte}
          id={ficha.clienteId}
          tipo="cliente"
          nome={F.foto}
          tem={c.temFoto}
        />
        <FotoDoCliente
          ponte={ponte}
          id={ficha.clienteId}
          tipo="documento-frente"
          nome={F.documentoFrente}
          tem={c.temFotoDoDocumentoFrente}
        />
        <FotoDoCliente
          ponte={ponte}
          id={ficha.clienteId}
          tipo="documento-verso"
          nome={F.documentoVerso}
          tem={c.temFotoDoDocumentoVerso}
        />
      </div>

      <h2 className="gestao-subtitulo">{F.pecasComEle}</h2>
      <Tabela
        legenda={F.pecasComEle}
        colunas={[
          { chave: 'peca', titulo: F.colunasPecas.peca },
          { chave: 'quantidade', titulo: F.colunasPecas.quantidade, numerica: true },
        ]}
        linhas={ficha.pecasComEle.map((p) => ({ peca: p.produto, quantidade: p.quantidade }))}
        textoVazio={F.semPecas}
      />

      <h2 className="gestao-subtitulo">{F.notas}</h2>
      <Tabela
        legenda={F.notas}
        colunas={[
          { chave: 'nota', titulo: TEXTOS_DE_CONSIGNADO.notas.colunas.nota },
          { chave: 'cobranca', titulo: TEXTOS_DE_CONSIGNADO.notas.colunas.cobranca },
          { chave: 'valor', titulo: TEXTOS_DE_CONSIGNADO.notas.colunas.valor, numerica: true },
          { chave: 'situacao', titulo: TEXTOS_DE_CONSIGNADO.notas.colunas.situacao },
          { chave: 'acoes', titulo: TEXTOS_DE_CONSIGNADO.notas.colunas.acoes },
        ]}
        linhas={ficha.notas.map((n) => ({
          nota: n.numero,
          cobranca: n.dataCobranca ? dataNaTela(n.dataCobranca) : '',
          valor: reais(n.valorDx),
          situacao: situacaoDaNota(n.situacao),
          acoes: (
            <Botao
              aria-label={TEXTOS_DE_CONSIGNADO.notas.verDaNota(n.numero)}
              onClick={() => aoAbrirNota(n.id)}
            >
              {TEXTOS_DE_CONSIGNADO.notas.ver}
            </Botao>
          ),
        }))}
        textoVazio={F.semNotas}
      />
    </>
  );
}

/**
 * Uma das fotos do cliente. É dado pessoal: só é buscada quando a pessoa pede, vem pela rota da dona e não fica guardada
 * em disco. A falta de foto nunca atrapalha (o representante pode ainda não ter enviado).
 */
function FotoDoCliente({
  ponte,
  id,
  tipo,
  nome,
  tem,
}: {
  ponte: PonteDoCorona;
  id: string;
  tipo: TipoDeFotoDoCliente;
  nome: string;
  tem: boolean;
}) {
  const [estado, setEstado] = useState<
    | { passo: 'fechada' }
    | { passo: 'carregando' }
    | { passo: 'erro' }
    | { passo: 'aberta'; url: string }
  >({ passo: 'fechada' });

  useEffect(() => {
    return () => {
      if (estado.passo === 'aberta') URL.revokeObjectURL(estado.url);
    };
  }, [estado]);

  async function abrir() {
    setEstado({ passo: 'carregando' });
    const r = await chamar<{ tipo?: string; bytes?: Uint8Array }>(
      ponte,
      'consignado.fotoDoCliente',
      {
        id,
        tipo,
      },
    );
    if (r.ok && r.dados.bytes instanceof Uint8Array) {
      setEstado({
        passo: 'aberta',
        url: URL.createObjectURL(
          new Blob([r.dados.bytes as BlobPart], { type: r.dados.tipo ?? 'image/jpeg' }),
        ),
      });
    } else {
      setEstado({ passo: 'erro' });
    }
  }

  return (
    <figure className="gestao-foto-do-cliente">
      <figcaption>{nome}</figcaption>
      {!tem ? <p className="gestao-texto">{F.semFoto}</p> : null}
      {tem && estado.passo === 'fechada' ? (
        <Botao aria-label={F.verFoto(nome)} onClick={() => void abrir()}>
          {F.ver}
        </Botao>
      ) : null}
      {estado.passo === 'carregando' ? <p role="status">{F.carregandoFoto}</p> : null}
      {estado.passo === 'erro' ? (
        <>
          <Aviso tipo="atencao">{F.fotoNaoCarregou}</Aviso>
          <Botao aria-label={F.verFoto(nome)} onClick={() => void abrir()}>
            {F.ver}
          </Botao>
        </>
      ) : null}
      {estado.passo === 'aberta' ? (
        <>
          <img src={estado.url} alt={nome} className="gestao-foto-do-cliente__imagem" />
          <Botao onClick={() => setEstado({ passo: 'fechada' })}>{F.esconder}</Botao>
        </>
      ) : null}
    </figure>
  );
}
