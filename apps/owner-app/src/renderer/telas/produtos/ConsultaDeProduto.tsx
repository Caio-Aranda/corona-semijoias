import { Aviso, Botao, Campo, ValorTresMoedas } from '@corona/ui';
import { useEffect, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { normalizarCodigo } from '../../nucleo/codigos';
import { useCotacao } from '../../nucleo/cotacao';
import { tresMoedas } from '../../nucleo/moedas';
import { TEXTOS } from '../../textos';
import { codigoPrincipal, lerProduto, type Produto } from './dados';

const T = TEXTOS.produtos.consulta;

export interface ConsultaDeProdutoProps {
  ponte: PonteDoCorona;
  aoAbrir: (id: string) => void;
}

/** Consulta: lê o código (leitor ou teclado) e mostra preço, foto e saldo. */
export function ConsultaDeProduto({ ponte, aoAbrir }: ConsultaDeProdutoProps) {
  const { taxas } = useCotacao();
  const [codigo, setCodigo] = useState('');
  const [produto, setProduto] = useState<Produto | null>(null);
  const [naoAchou, setNaoAchou] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [procurando, setProcurando] = useState(false);

  // O foco volta para o campo depois de cada leitura: a próxima peça já pode ser lida.
  useEffect(() => {
    if (!procurando) document.getElementById('consulta-codigo')?.focus();
  }, [procurando]);

  async function consultar(e: FormEvent) {
    e.preventDefault();
    const lido = normalizarCodigo(codigo);
    if (lido === '') return;
    setProcurando(true);
    setErro(null);
    setNaoAchou(null);
    const r = await chamar(ponte, 'produtos.porCodigo', { codigo: lido });
    setProcurando(false);
    setCodigo('');
    if (r.ok) {
      setProduto(lerProduto(r.dados));
    } else if (r.erro.status === 404) {
      setProduto(null);
      setNaoAchou(T.naoAchou(lido));
    } else {
      setProduto(null);
      setErro(r.erro);
    }
  }

  const preco = produto ? tresMoedas(produto.precoVenda, taxas) : null;

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-consulta">
      <h2 id="titulo-consulta" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      <form className="gestao-filtros" onSubmit={(e) => void consultar(e)}>
        <Campo
          id="consulta-codigo"
          rotulo={T.campo}
          value={codigo}
          autoComplete="off"
          spellCheck={false}
          autoFocus
          onChange={(e) => setCodigo(e.target.value)}
        />
        <Botao type="submit" variante="primario" disabled={procurando}>
          {T.consultar}
        </Botao>
      </form>
      {procurando ? <p role="status">{T.consultando}</p> : null}
      {naoAchou ? <Aviso tipo="atencao">{naoAchou}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {produto ? (
        <div className="gestao-consulta" aria-live="polite">
          <ImagemDoProduto
            ponte={ponte}
            hash={produto.fotoPrincipal?.hash}
            tamanho="media"
            nome={produto.nome}
          />
          <div>
            <h3 className="gestao-subtitulo">{produto.nome}</h3>
            {!produto.ativo ? <Aviso tipo="atencao">{T.desativado}</Aviso> : null}
            <p className="gestao-texto">
              {T.codigo}: {codigoPrincipal(produto)}
            </p>
            {preco ? (
              <p className="gestao-texto">
                {T.preco}:{' '}
                <ValorTresMoedas
                  real={preco.real}
                  {...(preco.dolar ? { dolar: preco.dolar } : {})}
                  {...(preco.guarani ? { guarani: preco.guarani } : {})}
                />
              </p>
            ) : null}
            <p className="gestao-texto">
              {T.saldo}: {produto.estoqueAtual}
            </p>
            <Botao onClick={() => aoAbrir(produto.id)}>{T.abrir}</Botao>
          </div>
        </div>
      ) : null}
    </section>
  );
}
