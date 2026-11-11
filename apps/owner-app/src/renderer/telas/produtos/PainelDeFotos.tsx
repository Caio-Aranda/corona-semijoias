import { Aviso, Botao, JanelaDeConfirmacao } from '@corona/ui';
import { useCallback, useEffect, useRef, useState, type DragEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { lerBytes } from '../../nucleo/arquivos';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { conferirArquivoDeFoto, nomeParaEnviar } from '../../nucleo/fotos';
import { TEXTOS } from '../../textos';
import { lerFotos, type FotoDoProduto } from './dados';

const T = TEXTOS.produtos.fotos;

export interface PainelDeFotosProps {
  ponte: PonteDoCorona;
  produtoId: string;
  nome: string;
  /** Trocar a foto muda a versão do produto: a ficha lê o produto de novo. */
  aoMudar: () => void;
}

/** Foto do produto (uma só por peça): escolher arquivo, colar (Ctrl+V) ou arrastar; trocar e remover. */
export function PainelDeFotos({ ponte, produtoId, nome, aoMudar }: PainelDeFotosProps) {
  const busca = useBusca(
    () => chamar(ponte, 'produtos.fotos.listar', { id: produtoId }),
    `fotos-${produtoId}`,
  );
  const fotos = busca.dados ? lerFotos(busca.dados) : [];
  const [mensagem, setMensagem] = useState<{ tipo: 'ok' | 'erro'; texto: string } | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [aRemover, setARemover] = useState<FotoDoProduto | null>(null);
  const [vendo, setVendo] = useState<FotoDoProduto | null>(null);
  const [arrastando, setArrastando] = useState(false);
  const entrada = useRef<HTMLInputElement>(null);
  const ocupado = useRef(false);

  const enviar = useCallback(
    async (arquivo: File) => {
      if (ocupado.current) return;
      setErro(null);
      const problema = conferirArquivoDeFoto(arquivo);
      if (problema) {
        setMensagem({ tipo: 'erro', texto: problema });
        return;
      }
      ocupado.current = true;
      setEnviando(true);
      setMensagem(null);
      try {
        const bytes = await lerBytes(arquivo);
        const r = await chamar(ponte, 'produtos.fotos.enviar', {
          id: produtoId,
          arquivo: { nome: nomeParaEnviar(arquivo), bytes },
        });
        if (r.ok) {
          setMensagem({ tipo: 'ok', texto: fotos.length > 0 ? T.trocada : T.enviada });
          busca.recarregar();
          aoMudar();
        } else setErro(r.erro);
      } catch {
        setMensagem({ tipo: 'erro', texto: T.erroAoLer });
      } finally {
        ocupado.current = false;
        setEnviando(false);
      }
    },
    [ponte, produtoId, fotos.length, busca, aoMudar],
  );

  // Ctrl+V com uma imagem na área de transferência.
  useEffect(() => {
    const aoColar = (e: ClipboardEvent) => {
      const alvo = e.target as HTMLElement | null;
      if (
        alvo &&
        /^(INPUT|TEXTAREA|SELECT)$/.test(alvo.tagName) &&
        (alvo as HTMLInputElement).type !== 'file'
      ) {
        return;
      }
      const arquivo = Array.from(e.clipboardData?.files ?? []).find((f) =>
        f.type.startsWith('image/'),
      );
      if (!arquivo) return;
      e.preventDefault();
      void enviar(arquivo);
    };
    window.addEventListener('paste', aoColar);
    return () => window.removeEventListener('paste', aoColar);
  }, [enviar]);

  const aoSoltar = (e: DragEvent) => {
    e.preventDefault();
    setArrastando(false);
    const arquivo = e.dataTransfer.files[0];
    if (arquivo) void enviar(arquivo);
  };

  const acao = async (operacao: 'produtos.fotos.excluir', foto: FotoDoProduto, ok?: string) => {
    setErro(null);
    setMensagem(null);
    const r = await chamar(ponte, operacao, { id: produtoId, fotoId: foto.id });
    if (r.ok) {
      if (ok) setMensagem({ tipo: 'ok', texto: ok });
      busca.recarregar();
      aoMudar();
    } else setErro(r.erro);
  };

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-fotos">
      <h2 id="titulo-fotos" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      <div
        className={`gestao-zona-de-foto${arrastando ? ' gestao-zona-de-foto--sobre' : ''}`}
        role="group"
        aria-label={T.zonaRotulo}
        onDragOver={(e) => {
          e.preventDefault();
          setArrastando(true);
        }}
        onDragLeave={() => setArrastando(false)}
        onDrop={aoSoltar}
      >
        <p>{T.zona}</p>
        <input
          ref={entrada}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          hidden
          aria-label={T.escolher}
          data-testid="escolher-foto"
          onChange={(e) => {
            const arquivo = e.target.files?.[0];
            e.target.value = '';
            if (arquivo) void enviar(arquivo);
          }}
        />
        <Botao disabled={enviando} onClick={() => entrada.current?.click()}>
          {fotos.length > 0 ? T.trocar : T.escolher}
        </Botao>
      </div>
      {enviando ? <p role="status">{T.enviando}</p> : null}
      {mensagem ? (
        <Aviso tipo={mensagem.tipo === 'ok' ? 'sucesso' : 'erro'}>{mensagem.texto}</Aviso>
      ) : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {busca.erro && !busca.dados ? (
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
      ) : null}

      {busca.dados && fotos.length === 0 ? <p className="gestao-texto">{T.nenhuma}</p> : null}
      <ul className="gestao-fotos">
        {fotos.map((f) => (
          <li key={f.id} className="gestao-fotos__item">
            <ImagemDoProduto ponte={ponte} hash={f.hash} tamanho="media" nome={nome} />
            <div className="gestao-acoes-da-linha">
              <Botao onClick={() => setVendo(f)}>{T.ver}</Botao>
              <Botao variante="perigoso" onClick={() => setARemover(f)}>
                {T.remover}
              </Botao>
            </div>
          </li>
        ))}
      </ul>

      {vendo ? (
        <JanelaDeConfirmacao
          titulo={nome}
          rotuloConfirmar={T.fechar}
          rotuloCancelar={T.removerConfirma.voltar}
          aoCancelar={() => setVendo(null)}
          aoConfirmar={() => setVendo(null)}
        >
          <ImagemDoProduto ponte={ponte} hash={vendo.hash} tamanho="grande" nome={nome} />
        </JanelaDeConfirmacao>
      ) : null}
      {aRemover ? (
        <JanelaDeConfirmacao
          titulo={T.removerConfirma.titulo}
          rotuloConfirmar={T.remover}
          rotuloCancelar={T.removerConfirma.voltar}
          perigosa
          aoCancelar={() => setARemover(null)}
          aoConfirmar={() => {
            const f = aRemover;
            setARemover(null);
            void acao('produtos.fotos.excluir', f, T.removida);
          }}
        >
          {T.removerConfirma.texto}
        </JanelaDeConfirmacao>
      ) : null}
    </section>
  );
}
