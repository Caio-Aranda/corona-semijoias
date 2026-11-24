import { Aviso, Botao, Campo, Paginacao, Tabela } from '@corona/ui';
import { useState } from 'react';
import { SITUACOES_DA_CONTA_A_PAGAR } from '../../../shared/financeiro';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, chamarTodasAsPaginas, useBusca } from '../../nucleo/chamadas';
import { RodapeDaCotacao } from '../../nucleo/cotacao';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { lerOpcoes } from '../produtos/dados';
import { PagamentosDaConta } from './PagamentosDaConta';
import { PagarConta } from './PagarConta';
import { ValorDoLancamentoNaTela } from '../devolucoes/Apoio';
import { ValorDoDia } from './Valor';
import { lerPaginaDeContas, podePagar, type ContaAPagar } from './contas-a-pagar';

const T = TEXTOS_DE_FINANCEIRO.contasAPagar;
const TAMANHO = 20;

type Passo =
  { passo: 'lista' } | { passo: 'pagar'; conta: ContaAPagar } | { passo: 'ver'; id: string };

/** Financeiro → Contas a pagar: as parcelas das compras, o pagamento de cada uma e os pagamentos já feitos. */
export function TelaContasAPagar({ ponte }: { ponte: PonteDoCorona }) {
  const [passo, setPasso] = useState<Passo>({ passo: 'lista' });
  const [fornecedorId, setFornecedorId] = useState('');
  const [situacao, setSituacao] = useState('');
  const [de, setDe] = useState('');
  const [ate, setAte] = useState('');
  const [pagina, setPagina] = useState(0);
  const [volta, setVolta] = useState(0);
  const [aviso, setAviso] = useState<string | null>(null);

  const fornecedores = useBusca(
    () => chamarTodasAsPaginas(ponte, 'fornecedores.listar', { situacao: 'TODOS' }),
    'fornecedores-das-contas-a-pagar',
  );
  const busca = useBusca(
    () =>
      chamar(ponte, 'contasAPagar.listar', {
        pagina,
        tamanho: TAMANHO,
        ...(fornecedorId ? { fornecedorId } : {}),
        ...(situacao ? { situacao } : {}),
        ...(de ? { vencimentoDe: de } : {}),
        ...(ate ? { vencimentoAte: ate } : {}),
      }),
    `contas-a-pagar-${volta}-${pagina}-${fornecedorId}-${situacao}-${de}-${ate}`,
  );
  const dados = busca.dados ? lerPaginaDeContas(busca.dados) : null;
  const opcoesDeFornecedor = (fornecedores.dados ? lerOpcoes(fornecedores.dados) : []).map((f) => ({
    valor: f.id,
    texto: f.nome,
  }));

  const nomeDaParcela = (c: ContaAPagar) => T.parcela(c.parcela, c.totalParcelas);
  const mudar = (fazer: () => void) => {
    fazer();
    setPagina(0);
  };

  if (passo.passo === 'pagar') {
    return (
      <div className="gestao-tela">
        <div className="gestao-tela__topo">
          <h1 className="gestao-titulo">{T.titulo}</h1>
        </div>
        <PagarConta
          ponte={ponte}
          conta={passo.conta}
          aoCancelar={() => setPasso({ passo: 'lista' })}
          aoFeito={() => {
            setPasso({ passo: 'lista' });
            setAviso(T.pagamento.feito);
            setVolta((v) => v + 1);
          }}
        />
      </div>
    );
  }
  if (passo.passo === 'ver') {
    return (
      <div className="gestao-tela">
        <div className="gestao-tela__topo">
          <h1 className="gestao-titulo">{T.titulo}</h1>
        </div>
        <PagamentosDaConta
          ponte={ponte}
          contaId={passo.id}
          aoVoltar={() => {
            setPasso({ passo: 'lista' });
            setVolta((v) => v + 1);
          }}
        />
      </div>
    );
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="filtro-conta-fornecedor"
          rotulo={T.filtros.fornecedor}
          valor={fornecedorId}
          aoMudar={(v) => mudar(() => setFornecedorId(v))}
          opcoes={opcoesDeFornecedor}
          vazio={T.filtros.todosFornecedores}
        />
        <CampoDeEscolha
          id="filtro-conta-situacao"
          rotulo={T.filtros.situacao}
          valor={situacao}
          aoMudar={(v) => mudar(() => setSituacao(v))}
          opcoes={SITUACOES_DA_CONTA_A_PAGAR.map((s) => ({ valor: s, texto: T.situacoes[s] ?? s }))}
          vazio={T.filtros.todasSituacoes}
        />
        <Campo
          type="date"
          rotulo={T.filtros.vencimentoDe}
          value={de}
          onChange={(e) => mudar(() => setDe(e.target.value))}
        />
        <Campo
          type="date"
          rotulo={T.filtros.vencimentoAte}
          value={ate}
          onChange={(e) => mudar(() => setAte(e.target.value))}
        />
      </div>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {busca.dados && !dados ? <Aviso tipo="erro">{T.naoLeu}</Aviso> : null}
      {dados ? (
        <>
          <Tabela
            legenda={T.legenda}
            textoVazio={T.vazia}
            colunas={[
              { chave: 'fornecedor', titulo: T.colunas.fornecedor },
              { chave: 'compra', titulo: T.colunas.compra },
              { chave: 'parcela', titulo: T.colunas.parcela },
              { chave: 'vencimento', titulo: T.colunas.vencimento },
              { chave: 'valor', titulo: T.colunas.valor, numerica: true },
              { chave: 'jaPago', titulo: T.colunas.jaPago, numerica: true },
              { chave: 'falta', titulo: T.colunas.falta, numerica: true },
              { chave: 'situacao', titulo: T.colunas.situacao },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={dados.itens.map((c) => ({
              fornecedor: c.fornecedorNome,
              compra: c.numeroDaCompra,
              parcela: nomeDaParcela(c),
              vencimento: dataParaTela(c.vencimento),
              valor: <ValorDoLancamentoNaTela valor={c.valorNaTela} />,
              jaPago: <ValorDoLancamentoNaTela valor={c.pagoNaTela} />,
              falta: <ValorDoDia valor={c.saldo} />,
              situacao: T.situacoes[c.situacao] ?? c.situacao,
              acoes: (
                <span className="gestao-acoes-da-linha">
                  {podePagar(c) ? (
                    <Botao
                      aria-label={T.pagarDe(c.fornecedorNome, nomeDaParcela(c))}
                      onClick={() => {
                        setAviso(null);
                        setPasso({ passo: 'pagar', conta: c });
                      }}
                    >
                      {T.pagar}
                    </Botao>
                  ) : null}
                  <Botao
                    aria-label={T.verDe(c.fornecedorNome, nomeDaParcela(c))}
                    onClick={() => {
                      setAviso(null);
                      setPasso({ passo: 'ver', id: c.id });
                    }}
                  >
                    {T.ver}
                  </Botao>
                </span>
              ),
            }))}
          />
          <Paginacao
            pagina={dados.pagina}
            tamanho={dados.tamanho}
            total={dados.total}
            aoMudar={setPagina}
            nomeDoItem={['conta', 'contas']}
          />
          <RodapeDaCotacao />
        </>
      ) : null}
    </div>
  );
}
