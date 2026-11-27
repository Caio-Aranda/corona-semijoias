import { Campo } from '@corona/ui';
import { hojeEmBrasilia } from '../../nucleo/datas';
import type { PonteDoCorona } from '../../../shared/ponte';
import { FaltaDeCadastro } from '../../componentes/FaltaDeCadastro';
import { chamarTodasAsPaginas, useBusca } from '../../nucleo/chamadas';
import { TEXTOS } from '../../textos';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { lerOpcoes } from '../produtos/dados';

const T = TEXTOS_DE_COMPRAS.receber;

export interface DadosDoCabecalho {
  fornecedorId: string;
  /** "2026-11-10". */
  data: string;
  numero: string;
  observacoes: string;
}

export interface CabecalhoDaCompraProps {
  ponte: PonteDoCorona;
  valores: DadosDoCabecalho;
  aoMudar: (parcial: Partial<DadosDoCabecalho>) => void;
  /** Já tentou confirmar: campos que faltam ganham aviso. */
  tentou: boolean;
  /** Erros que o servidor devolveu, pelo nome do campo na API. */
  erros?: Record<string, string>;
  desativado?: boolean;
  /** Início dos identificadores dos campos (a tela pode ter dois cabeçalhos montados). */
  idBase?: string;
}

/** Hoje, em Brasília, no formato da data da API (em UTC, depois das 21h já seria amanhã). */
export function hojeEmTexto(): string {
  return hojeEmBrasilia();
}

/** Fornecedor, data, número da nota e observações: iguais na compra pela leitura e pela planilha. */
export function CabecalhoDaCompra({
  ponte,
  valores,
  aoMudar,
  tentou,
  erros = {},
  desativado,
  idBase = 'compra',
}: CabecalhoDaCompraProps) {
  const fornecedores = useBusca(
    () => chamarTodasAsPaginas(ponte, 'fornecedores.listar', { situacao: 'ATIVOS' }),
    'fornecedores-da-compra',
  );
  const lista = fornecedores.dados ? lerOpcoes(fornecedores.dados) : [];
  const erroDoFornecedor =
    tentou && !valores.fornecedorId ? T.escolhaFornecedorAviso : erros.fornecedorId;
  const erroDaData = tentou && !valores.data ? T.dataAviso : erros.dataCompra;
  return (
    <section className="gestao-grupo" aria-labelledby={`titulo-${idBase}`}>
      <h2 id={`titulo-${idBase}`} className="gestao-subtitulo">
        {T.cabecalho}
      </h2>
      {fornecedores.dados !== null && lista.length === 0 && !desativado ? (
        <FaltaDeCadastro
          mensagem={TEXTOS.faltaCadastro.semFornecedor}
          rotuloDoBotao={TEXTOS.faltaCadastro.cadastrarFornecedor}
          rota="cadastros/fornecedores"
        />
      ) : null}
      <div className="gestao-formulario gestao-formulario--larga">
        <div className={`cs-campo${erroDoFornecedor ? ' cs-campo--erro' : ''}`}>
          <label htmlFor={`${idBase}-fornecedor`}>{T.fornecedor}</label>
          <select
            id={`${idBase}-fornecedor`}
            value={valores.fornecedorId}
            disabled={desativado}
            onChange={(e) => aoMudar({ fornecedorId: e.target.value })}
          >
            <option value="">{T.escolhaFornecedor}</option>
            {lista.map((f) => (
              <option key={f.id} value={f.id}>
                {f.nome}
              </option>
            ))}
          </select>
          {erroDoFornecedor ? (
            <span role="alert" className="cs-campo__erro">
              {erroDoFornecedor}
            </span>
          ) : null}
        </div>
        <Campo
          id={`${idBase}-data`}
          rotulo={T.data}
          type="date"
          value={valores.data}
          disabled={desativado}
          onChange={(e) => aoMudar({ data: e.target.value })}
          {...(erroDaData ? { erro: erroDaData } : {})}
        />
        <Campo
          id={`${idBase}-numero`}
          rotulo={T.numero}
          value={valores.numero}
          maxLength={60}
          autoComplete="off"
          disabled={desativado}
          onChange={(e) => aoMudar({ numero: e.target.value })}
          {...(erros.numero ? { erro: erros.numero } : {})}
        />
        <Campo
          id={`${idBase}-observacoes`}
          rotulo={T.observacoes}
          value={valores.observacoes}
          maxLength={1000}
          autoComplete="off"
          disabled={desativado}
          onChange={(e) => aoMudar({ observacoes: e.target.value })}
          {...(erros.observacoes ? { erro: erros.observacoes } : {})}
        />
      </div>
    </section>
  );
}
