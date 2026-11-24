import { Aviso, Botao, Campo, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState, type FormEvent } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import {
  TAMANHO_MAXIMO_DO_DOCUMENTO,
  extensaoAceita,
  type ArquivoBaixado,
} from '../../../shared/vendedores';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { entregarArquivo, lerBytes, tamanhoNaTela } from '../../nucleo/arquivos';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { dataDeBrasilia } from '../usuarios/dados';
import { TEXTOS } from '../../textos';
import { lerLista, texto, type Registro } from './dados';

const T = TEXTOS.vendedores.documentos;

export interface AbaDocumentosProps {
  ponte: PonteDoCorona;
  vendedorId: string;
}

/** Quem dá a conferida na tela antes de enviar (o servidor confere de novo, pelo conteúdo do arquivo). */
export function conferirArquivoEscolhido(arquivo: { name: string; size: number }): string | null {
  if (!extensaoAceita(arquivo.name)) return T.tipoNaoAceito;
  if (arquivo.size === 0) return T.arquivoVazio;
  if (arquivo.size > TAMANHO_MAXIMO_DO_DOCUMENTO) return T.grandeDemais;
  return null;
}

/**
 * Aba "Documentos": enviar, baixar e excluir fotos e PDFs. O arquivo vai e vem como bytes pela
 * ponte; fica guardado em arquivo no servidor, nunca dentro do banco de dados.
 */
export function AbaDocumentos({ ponte, vendedorId }: AbaDocumentosProps) {
  const lista = useBusca(
    () => chamar(ponte, 'vendedores.documentos.listar', { id: vendedorId }),
    `doc|${vendedorId}`,
  );
  const [escolhido, setEscolhido] = useState<File | null>(null);
  const [descricao, setDescricao] = useState('');
  const [erroLocal, setErroLocal] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [feito, setFeito] = useState<string | null>(null);
  const [excluindo, setExcluindo] = useState<Registro | null>(null);
  const [executando, setExecutando] = useState(false);
  const entrada = useRef<HTMLInputElement>(null);

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    if (enviando) return;
    if (!escolhido) {
      setErroLocal(T.escolhaOArquivo);
      return;
    }
    const problema = conferirArquivoEscolhido(escolhido);
    if (problema) {
      setErroLocal(problema);
      return;
    }
    setErroLocal(null);
    setErro(null);
    setFeito(null);
    setEnviando(true);
    let bytes: Uint8Array;
    try {
      bytes = await lerBytes(escolhido);
    } catch {
      setEnviando(false);
      setErroLocal(T.naoLeu);
      return;
    }
    const r = await chamar(ponte, 'vendedores.documentos.enviar', {
      id: vendedorId,
      ...(descricao.trim() ? { descricao: descricao.trim() } : {}),
      arquivo: { nome: escolhido.name, bytes },
    });
    setEnviando(false);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    setFeito(T.feito.enviado);
    setEscolhido(null);
    setDescricao('');
    if (entrada.current) entrada.current.value = '';
    lista.recarregar();
  }

  async function baixar(doc: Registro) {
    if (!doc.id) return;
    setErro(null);
    setFeito(null);
    const r = await chamar<ArquivoBaixado>(ponte, 'vendedores.documentos.baixar', {
      id: vendedorId,
      documentoId: doc.id,
    });
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    entregarArquivo(r.dados.nome, r.dados.tipo, r.dados.bytes);
  }

  async function excluir() {
    if (!excluindo?.id || executando) return;
    setExecutando(true);
    const r = await chamar(ponte, 'vendedores.documentos.excluir', {
      id: vendedorId,
      documentoId: excluindo.id,
    });
    setExecutando(false);
    setExcluindo(null);
    if (!r.ok) {
      setErro(r.erro);
      return;
    }
    setErro(null);
    setFeito(T.feito.excluido);
    lista.recarregar();
  }

  const itens = lista.dados ? lerLista(lista.dados) : null;
  const linhas = (itens ?? []).map((d) => ({
    descricao: texto(d, 'descricao'),
    arquivo: texto(d, 'nomeOriginal'),
    tamanho: typeof d.tamanhoBytes === 'number' ? tamanhoNaTela(d.tamanhoBytes) : '',
    data: (typeof d.enviadoEm === 'string' && dataDeBrasilia(d.enviadoEm)) || '',
    acoes: (
      <span className="gestao-acoes-da-linha">
        <Botao onClick={() => void baixar(d)}>{T.baixar}</Botao>
        <Botao
          onClick={() => {
            setFeito(null);
            setExcluindo(d);
          }}
        >
          {T.excluir}
        </Botao>
      </span>
    ),
  }));

  return (
    <div className="gestao-aba">
      <h2 className="gestao-subtitulo">{T.titulo}</h2>
      <form
        className="gestao-formulario gestao-formulario--larga"
        onSubmit={(e) => void enviar(e)}
        noValidate
      >
        <p className="gestao-texto">{T.ajuda}</p>
        <div className={`cs-campo${erroLocal ? ' cs-campo--erro' : ''}`}>
          <label htmlFor="doc-arquivo">{T.arquivo}</label>
          <input
            id="doc-arquivo"
            ref={entrada}
            type="file"
            accept=".pdf,.jpg,.jpeg,.png,.webp,application/pdf,image/jpeg,image/png,image/webp"
            onChange={(e) => {
              setEscolhido(e.target.files?.[0] ?? null);
              setErroLocal(null);
            }}
          />
          {erroLocal ? (
            <span role="alert" className="cs-campo__erro">
              {erroLocal}
            </span>
          ) : null}
        </div>
        <Campo
          id="doc-descricao"
          rotulo={T.descricao}
          ajuda={T.descricaoAjuda}
          value={descricao}
          maxLength={200}
          autoComplete="off"
          onChange={(e) => setDescricao(e.target.value)}
          {...(erro?.campos.descricao ? { erro: erro.campos.descricao } : {})}
        />
        <div className="gestao-rodape-do-formulario">
          <Botao type="submit" variante="primario" disabled={enviando}>
            {enviando ? T.enviando : T.enviar}
          </Botao>
        </div>
      </form>

      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {erro ? <AvisoDeErro erro={erro} camposJaMostrados={['descricao']} /> : null}
      {lista.erro ? <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} /> : null}
      {itens === null && !lista.erro ? (
        <p className="gestao-texto">{TEXTOS.vendedores.ficha.carregando}</p>
      ) : null}
      {itens !== null ? (
        <Tabela
          legenda={T.legenda}
          colunas={[
            { chave: 'descricao', titulo: T.colunas.descricao },
            { chave: 'arquivo', titulo: T.colunas.arquivo },
            { chave: 'tamanho', titulo: T.colunas.tamanho },
            { chave: 'data', titulo: T.colunas.data },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={linhas}
          textoVazio={T.vazio}
        />
      ) : null}
      {excluindo ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarTitulo(
            texto(excluindo, 'descricao') || texto(excluindo, 'nomeOriginal'),
          )}
          rotuloConfirmar={T.excluir}
          rotuloCancelar={TEXTOS.vendedores.confirmar.voltar}
          perigosa
          ocupado={executando}
          aoCancelar={() => setExcluindo(null)}
          aoConfirmar={() => void excluir()}
        >
          {T.confirmarTexto}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
