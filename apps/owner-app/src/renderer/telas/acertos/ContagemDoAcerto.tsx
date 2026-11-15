import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import { MAXIMO_DA_QUANTIDADE_NA_LEITURA } from '../../../shared/acertos';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { LeitorDaMaleta } from '../maletas/LeitorDaMaleta';
import type { Produto } from '../produtos/dados';
import { dataEHora } from '../sincronizacao/dados';
import { erroDoAcerto, lerFicha, type FichaDoAcerto } from './dados';

const T = TEXTOS_DE_ACERTOS.contagem;

export interface ContagemDoAcertoProps {
  ponte: PonteDoCorona;
  ficha: FichaDoAcerto;
  /** A ficha nova que o servidor devolveu a cada leitura: a contagem mostrada é sempre a dele. */
  aoMudar: (ficha: FichaDoAcerto) => void;
  /** O acerto deixou de estar em andamento: a tela busca a ficha de novo. */
  aoPerder: () => void;
}

type Estado = 'BOA' | 'DEFEITO';

/** Contagem das peças que voltaram: o leitor soma e o servidor devolve a contagem. A tela nunca soma. */
export function ContagemDoAcerto({ ponte, ficha, aoMudar, aoPerder }: ContagemDoAcertoProps) {
  const [estado, setEstado] = useState<Estado>('BOA');
  const [quantidade, setQuantidade] = useState('1');
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  // O mesmo id vale até o servidor responder: ler de novo a mesma peça depois de uma falha de rede não conta duas vezes.
  const pendente = useRef<{ chave: string; id: string } | null>(null);
  const trava = useRef(false);

  const quantidadeLida = Number(quantidade);
  const quantidadeValida =
    Number.isInteger(quantidadeLida) &&
    quantidadeLida >= 1 &&
    quantidadeLida <= MAXIMO_DA_QUANTIDADE_NA_LEITURA;

  async function registrar(produtoId: string, estadoPeca: Estado, qtd: number) {
    if (trava.current) return;
    trava.current = true;
    const chave = `${produtoId}|${estadoPeca}|${qtd}`;
    const id = pendente.current?.chave === chave ? pendente.current.id : crypto.randomUUID();
    pendente.current = { chave, id };
    setErro(null);
    const r = await chamar(ponte, 'acertos.ler', {
      id: ficha.id,
      leituraId: id,
      produtoId,
      estadoPeca,
      quantidade: qtd,
    });
    trava.current = false;
    if (!r.ok) {
      setErro(erroDoAcerto(r.erro));
      if (r.erro.codigo === 'ACERTO_NAO_ESTA_EM_ANDAMENTO') aoPerder();
      return;
    }
    pendente.current = null;
    const nova = lerFicha(r.dados);
    if (nova) aoMudar(nova);
  }

  async function aoAchar(produto: Produto) {
    if (!quantidadeValida) {
      setErro({ tipo: 'outro', mensagem: T.quantidadeAviso, campos: {} });
      return;
    }
    await registrar(produto.id, estado, quantidadeLida);
  }

  const nomeDaPeca = (id: string) =>
    ficha.contagem.find((c) => c.produto.id === id)?.produto.nome ?? 'Peça';
  const ultimasLeituras = [...ficha.leituras]
    .sort((a, b) => b.lidaEm.localeCompare(a.lidaEm))
    .slice(0, 8);

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-contagem-do-acerto">
      <h2 id="titulo-contagem-do-acerto" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      {ficha.vendasQueChegaramDepoisDoInicio > 0 ? (
        <Aviso tipo="atencao">{T.vendaNova(ficha.vendasQueChegaramDepoisDoInicio)}</Aviso>
      ) : null}
      {ficha.enviosAguardando > 0 ? (
        <Aviso tipo="atencao">{T.envios(ficha.enviosAguardando)}</Aviso>
      ) : null}
      <fieldset className="gestao-filtros">
        <legend>{T.estado}</legend>
        {(['BOA', 'DEFEITO'] as const).map((e) => (
          <label key={e} className="gestao-opcao">
            <input
              type="radio"
              name="estado-da-peca-no-acerto"
              checked={estado === e}
              onChange={() => setEstado(e)}
            />{' '}
            {e === 'BOA' ? T.boa : T.defeito}
          </label>
        ))}
        <Campo
          rotulo={T.quantidade}
          inputMode="numeric"
          value={quantidade}
          autoComplete="off"
          onChange={(e) => setQuantidade(e.target.value.replace(/\D/g, ''))}
          {...(quantidadeValida ? {} : { erro: T.quantidadeAviso })}
        />
      </fieldset>
      <LeitorDaMaleta
        ponte={ponte}
        rotulos={T.leitor}
        aoAchar={aoAchar}
        aoNaoAchar={(codigo) =>
          setErro({
            tipo: 'outro',
            mensagem: TEXTOS_DE_MALETAS.leitor.naoAchouTexto(codigo),
            campos: {},
          })
        }
      />
      {erro ? <AvisoDeErro erro={erro} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazia}
        colunas={[
          { chave: 'peca', titulo: T.colunas.peca },
          { chave: 'estado', titulo: T.colunas.estado },
          { chave: 'quantidade', titulo: T.colunas.quantidade, numerica: true },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={ficha.contagem.map((c) => ({
          peca: (
            <span className="gestao-opcao-de-peca">
              <ImagemDoProduto
                ponte={ponte}
                hash={c.produto.fotoHash}
                tamanho="miniatura"
                nome={c.produto.nome}
              />
              <span>
                {c.produto.nome} ({c.produto.codigo})
              </span>
            </span>
          ),
          estado: c.estadoPeca === 'DEFEITO' ? T.defeito : T.boa,
          quantidade: (
            <span data-testid={`contagem-${c.estadoPeca}-${c.produto.id}`}>{c.quantidade}</span>
          ),
          acoes: (
            <Botao
              aria-label={T.desfazerDe(c.produto.nome)}
              onClick={() =>
                void registrar(c.produto.id, c.estadoPeca === 'DEFEITO' ? 'DEFEITO' : 'BOA', -1)
              }
            >
              {T.desfazer}
            </Botao>
          ),
        }))}
      />
      {ultimasLeituras.length > 0 ? (
        <div>
          <h3 className="gestao-subtitulo">{T.leituras}</h3>
          <ul>
            {ultimasLeituras.map((l) => (
              <li key={l.id}>
                {T.leitura(
                  nomeDaPeca(l.produtoId),
                  l.quantidade,
                  l.estadoPeca === 'DEFEITO' ? T.defeito : T.boa,
                  l.lidaPorNome,
                  l.peloVendedor ? T.noComputadorDoVendedor : T.noComputadorDaLoja,
                  dataEHora(l.lidaEm),
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
