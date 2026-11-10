import { Aviso, Botao, Campo } from '@corona/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { normalizarCodigo } from '../../nucleo/codigos';
import { TEXTOS } from '../../textos';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { codigoPrincipal, lerPaginaDeProdutos, lerProduto, type Produto } from '../produtos/dados';

const T = TEXTOS_DE_COMPRAS.receber.leitor;
export const ID_DO_CAMPO_DO_LEITOR = 'compra-leitor';

export interface LeitorDaCompraProps {
  ponte: PonteDoCorona;
  /** A peça achada entra na compra; devolve a frase que a tela mostra ("agora são 3 peças..."). */
  aoAchar: (produto: Produto) => string;
  /** Código lido que não existe: a tela abre o cadastro rápido. */
  aoNaoAchar: (codigo: string) => void;
  desativado?: boolean;
}

/**
 * Campo do leitor. O leitor de código age como teclado e termina com Enter; digitar o código ou o
 * nome e apertar Enter funciona igual. Depois de cada leitura o foco volta ao campo, para a
 * próxima peça já poder ser lida.
 */
export function LeitorDaCompra({ ponte, aoAchar, aoNaoAchar, desativado }: LeitorDaCompraProps) {
  const [texto, setTexto] = useState('');
  const [procurando, setProcurando] = useState(false);
  const [mensagem, setMensagem] = useState<string | null>(null);
  const [naoAchou, setNaoAchou] = useState<string | null>(null);
  const [opcoes, setOpcoes] = useState<Produto[] | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  // O foco volta para o campo depois de cada leitura.
  useEffect(() => {
    if (!procurando && !desativado && !opcoes) {
      document.getElementById(ID_DO_CAMPO_DO_LEITOR)?.focus();
    }
  }, [procurando, desativado, opcoes, mensagem, naoAchou]);

  function escolher(produto: Produto) {
    setOpcoes(null);
    setNaoAchou(null);
    setMensagem(aoAchar(produto));
  }

  async function ler(e: FormEvent) {
    e.preventDefault();
    const lido = normalizarCodigo(texto);
    if (lido === '' || trava.current) return;
    trava.current = true;
    setProcurando(true);
    setErro(null);
    setMensagem(null);
    setNaoAchou(null);
    setOpcoes(null);
    const porCodigo = await chamar(ponte, 'produtos.porCodigo', { codigo: lido });
    let sair = true;
    if (porCodigo.ok) {
      const produto = lerProduto(porCodigo.dados);
      if (produto) setMensagem(aoAchar(produto));
      else setErro({ tipo: 'outro', mensagem: TEXTOS.algoDeuErrado, campos: {} });
    } else if (porCodigo.erro.status === 404) {
      // Não é um código conhecido: tenta como nome.
      const porNome = await chamar(ponte, 'produtos.listar', {
        busca: lido,
        ativo: true,
        pagina: 0,
        tamanho: 8,
      });
      if (porNome.ok) {
        const achados = lerPaginaDeProdutos(porNome.dados).itens;
        if (achados.length === 1 && achados[0]) setMensagem(aoAchar(achados[0]));
        else if (achados.length > 1) {
          setOpcoes(achados);
          sair = false;
        } else setNaoAchou(lido);
      } else setErro(porNome.erro);
    } else {
      setErro(porCodigo.erro);
    }
    setTexto(sair ? '' : lido);
    setProcurando(false);
    trava.current = false;
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-leitor">
      <h3 id="titulo-leitor" className="gestao-subtitulo">
        {T.titulo}
      </h3>
      <form className="gestao-filtros" onSubmit={(e) => void ler(e)}>
        <Campo
          id={ID_DO_CAMPO_DO_LEITOR}
          rotulo={T.campo}
          ajuda={T.ajuda}
          value={texto}
          autoComplete="off"
          spellCheck={false}
          autoFocus
          disabled={desativado}
          onChange={(e) => setTexto(e.target.value)}
        />
        <Botao type="submit" variante="primario" disabled={procurando || desativado}>
          {T.adicionar}
        </Botao>
      </form>
      {procurando ? <p role="status">{T.procurando}</p> : null}
      {mensagem ? (
        <p className="gestao-texto" role="status">
          {mensagem}
        </p>
      ) : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {naoAchou ? (
        <div>
          <Aviso tipo="atencao">{T.naoAchou(naoAchou)}</Aviso>
          <Botao onClick={() => aoNaoAchar(naoAchou)}>{T.cadastrar}</Botao>
        </div>
      ) : null}
      {opcoes ? (
        <div>
          <p className="gestao-texto">{T.varios(opcoes.length)}</p>
          <ul className="gestao-lista-de-opcoes">
            {opcoes.map((p) => (
              <li key={p.id} className="gestao-opcao-de-peca">
                <ImagemDoProduto
                  ponte={ponte}
                  hash={p.fotoPrincipal?.hash}
                  tamanho="miniatura"
                  nome={p.nome}
                />
                <span>
                  {p.nome} ({codigoPrincipal(p)})
                </span>
                <Botao aria-label={`${T.escolher} ${p.nome}`} onClick={() => escolher(p)}>
                  {T.escolher}
                </Botao>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
