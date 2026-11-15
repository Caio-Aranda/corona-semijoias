import { Aviso, Botao, Campo } from '@corona/ui';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { normalizarCodigo } from '../../nucleo/codigos';
import { TEXTOS } from '../../textos';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { codigoPrincipal, lerPaginaDeProdutos, lerProduto, type Produto } from '../produtos/dados';

const T = TEXTOS_DE_MALETAS.leitor;
export const ID_DO_CAMPO_DO_LEITOR_DA_MALETA = 'maleta-leitor';

export interface LeitorDaMaletaProps {
  ponte: PonteDoCorona;
  /** A peça achada: a tela decide se entra e devolve a frase do resultado (ou nada, se mostrou aviso). */
  aoAchar: (produto: Produto) => Promise<void>;
  /** Código que não existe: a tela mostra "Peça não encontrada" (não cadastra, ADR-0010 item 16). */
  aoNaoAchar: (codigo: string) => void;
  desativado?: boolean;
  /** Outros textos para o mesmo leitor (ex.: a contagem do acerto). */
  rotulos?: { titulo: string; ajuda: string; adicionar: string };
}

/**
 * Campo do leitor da maleta. O leitor de código age como teclado e termina com Enter; digitar o
 * código ou o nome e apertar Enter funciona igual. O foco volta ao campo depois de cada leitura.
 */
export function LeitorDaMaleta({
  ponte,
  aoAchar,
  aoNaoAchar,
  desativado,
  rotulos,
}: LeitorDaMaletaProps) {
  const R = rotulos ?? T;
  const [texto, setTexto] = useState('');
  const [procurando, setProcurando] = useState(false);
  const [opcoes, setOpcoes] = useState<Produto[] | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const trava = useRef(false);

  useEffect(() => {
    if (!procurando && !desativado && !opcoes) {
      document.getElementById(ID_DO_CAMPO_DO_LEITOR_DA_MALETA)?.focus();
    }
  }, [procurando, desativado, opcoes]);

  async function escolher(produto: Produto) {
    setOpcoes(null);
    await aoAchar(produto);
  }

  async function ler(e: FormEvent) {
    e.preventDefault();
    const lido = normalizarCodigo(texto);
    if (lido === '' || trava.current) return;
    trava.current = true;
    setProcurando(true);
    setErro(null);
    setOpcoes(null);
    const porCodigo = await chamar(ponte, 'produtos.porCodigo', { codigo: lido });
    let sair = true;
    if (porCodigo.ok) {
      const produto = lerProduto(porCodigo.dados);
      if (produto) await aoAchar(produto);
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
        if (achados.length === 1 && achados[0]) await aoAchar(achados[0]);
        else if (achados.length > 1) {
          setOpcoes(achados);
          sair = false;
        } else aoNaoAchar(lido);
      } else setErro(porNome.erro);
    } else {
      setErro(porCodigo.erro);
    }
    setTexto(sair ? '' : lido);
    setProcurando(false);
    trava.current = false;
  }

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-leitor-maleta">
      <h2 id="titulo-leitor-maleta" className="gestao-subtitulo">
        {R.titulo}
      </h2>
      <form className="gestao-filtros" onSubmit={(e) => void ler(e)}>
        <Campo
          id={ID_DO_CAMPO_DO_LEITOR_DA_MALETA}
          rotulo={T.campo}
          ajuda={R.ajuda}
          value={texto}
          autoComplete="off"
          spellCheck={false}
          autoFocus
          disabled={desativado}
          onChange={(e) => setTexto(e.target.value)}
        />
        <Botao type="submit" variante="primario" disabled={procurando || desativado}>
          {R.adicionar}
        </Botao>
      </form>
      {procurando ? <p role="status">{T.procurando}</p> : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {opcoes ? (
        <div>
          <Aviso tipo="informacao">{T.varios(opcoes.length)}</Aviso>
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
                <Botao aria-label={`${T.escolher} ${p.nome}`} onClick={() => void escolher(p)}>
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
