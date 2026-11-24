import { Botao, Campo, EstadoDaLista, Paginacao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { SITUACOES_DA_COMPRA } from '../../../shared/compras';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca } from '../../nucleo/chamadas';
import { dataParaTela, tresMoedas } from '../../nucleo/moedas';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { lerOpcoes } from '../produtos/dados';
import { DetalheDaCompra } from './DetalheDaCompra';
import { lerPaginaDeCompras } from './dados';

const T = TEXTOS_DE_COMPRAS.lista;
const TAMANHO = 20;
const reais = (valor: string): string => tresMoedas(valor, null)?.real ?? valor;

export interface ListaDeComprasProps {
  ponte: PonteDoCorona;
  aoReceber: () => void;
}

/** Compras: filtros (período, fornecedor, situação), páginas e a compra aberta com as fotos. */
export function ListaDeCompras({ ponte, aoReceber }: ListaDeComprasProps) {
  const [de, setDe] = useState('');
  const [ate, setAte] = useState('');
  const [fornecedor, setFornecedor] = useState('');
  const [situacao, setSituacao] = useState('');
  const [pagina, setPagina] = useState(0);
  const [aberta, setAberta] = useState<string | null>(null);

  const fornecedores = useBusca(
    () => chamarTodasAsPaginas(ponte, 'fornecedores.listar', { situacao: 'TODOS' }),
    'fornecedores-do-filtro-de-compras',
  );
  const listaDeFornecedores = fornecedores.dados ? lerOpcoes(fornecedores.dados) : [];
  const periodoInvertido = de !== '' && ate !== '' && ate < de;

  const lista = useBusca(
    () =>
      periodoInvertido
        ? Promise.resolve({
            ok: true as const,
            dados: { itens: [], pagina: 0, tamanho: TAMANHO, total: 0 },
          })
        : chamar(ponte, 'compras.listar', {
            pagina,
            tamanho: TAMANHO,
            ...(de ? { de } : {}),
            ...(ate ? { ate } : {}),
            ...(fornecedor ? { fornecedorId: fornecedor } : {}),
            ...(situacao ? { situacao } : {}),
          }),
    `${de}|${ate}|${fornecedor}|${situacao}|${pagina}|${periodoInvertido}`,
  );
  const dados = lista.dados ? lerPaginaDeCompras(lista.dados) : null;
  const filtrando = de !== '' || ate !== '' || fornecedor !== '' || situacao !== '';

  if (aberta) {
    return (
      <DetalheDaCompra
        key={aberta}
        ponte={ponte}
        compraId={aberta}
        aoVoltar={() => setAberta(null)}
        aoMudar={lista.recarregar}
      />
    );
  }

  const linhas = (dados?.itens ?? []).map((c) => ({
    data: dataParaTela(c.dataCompra),
    fornecedor: c.fornecedorNome,
    numero: c.numero || T.semNota,
    pecas: c.pecas,
    total: reais(c.total),
    situacao: T.situacoes[c.situacao],
    acoes: <Botao onClick={() => setAberta(c.id)}>{T.abrir}</Botao>,
  }));

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao variante="primario" onClick={aoReceber}>
          {T.nova}
        </Botao>
      </div>
      <div className="gestao-filtros">
        <Campo
          rotulo={T.filtros.de}
          type="date"
          value={de}
          onChange={(e) => {
            setDe(e.target.value);
            setPagina(0);
          }}
        />
        <Campo
          rotulo={T.filtros.ate}
          type="date"
          value={ate}
          onChange={(e) => {
            setAte(e.target.value);
            setPagina(0);
          }}
          {...(periodoInvertido ? { erro: T.filtros.dataInvertida } : {})}
        />
        <div className="cs-campo">
          <label htmlFor="filtro-fornecedor-compras">{T.filtros.fornecedor}</label>
          <select
            id="filtro-fornecedor-compras"
            value={fornecedor}
            onChange={(e) => {
              setFornecedor(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{T.filtros.todos}</option>
            {listaDeFornecedores.map((f) => (
              <option key={f.id} value={f.id}>
                {f.nome}
              </option>
            ))}
          </select>
        </div>
        <div className="cs-campo">
          <label htmlFor="filtro-situacao-compras">{T.filtros.situacao}</label>
          <select
            id="filtro-situacao-compras"
            value={situacao}
            onChange={(e) => {
              setSituacao(e.target.value);
              setPagina(0);
            }}
          >
            <option value="">{T.filtros.todas}</option>
            {SITUACOES_DA_COMPRA.map((s) => (
              <option key={s} value={s}>
                {T.situacoes[s]}
              </option>
            ))}
          </select>
        </div>
        {filtrando ? (
          <Botao
            onClick={() => {
              setDe('');
              setAte('');
              setFornecedor('');
              setSituacao('');
              setPagina(0);
            }}
          >
            {T.filtros.limpar}
          </Botao>
        ) : null}
      </div>

      <EstadoDaLista
        carregando={lista.carregando && dados === null}
        erro={lista.erro && dados === null ? lista.erro.mensagem : null}
        textoCarregando={T.buscando}
        aoTentarDeNovo={lista.recarregar}
      >
        {lista.erro && dados !== null ? (
          <AvisoDeErro erro={lista.erro} aoTentarDeNovo={lista.recarregar} />
        ) : null}
        <Tabela
          legenda={T.legenda}
          textoVazio={filtrando ? T.semResultado : T.vazio}
          colunas={[
            { chave: 'data', titulo: T.colunas.data },
            { chave: 'fornecedor', titulo: T.colunas.fornecedor },
            { chave: 'numero', titulo: T.colunas.numero },
            { chave: 'pecas', titulo: T.colunas.pecas, numerica: true },
            { chave: 'total', titulo: T.colunas.total, numerica: true },
            { chave: 'situacao', titulo: T.colunas.situacao },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={linhas}
        />
        {dados ? (
          <Paginacao
            pagina={dados.pagina}
            tamanho={dados.tamanho}
            total={dados.total}
            nomeDoItem={T.itens}
            aoMudar={setPagina}
          />
        ) : null}
      </EstadoDaLista>
    </div>
  );
}
