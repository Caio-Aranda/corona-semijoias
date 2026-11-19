import { Aviso, Botao, CabecalhoDeMarca, RodapeDeAcoes } from '@corona/ui';
import { useState } from 'react';
import type { PainelDaSemana } from '../../../shared/dados-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao } from '../../chamadas';
import { Campo, JanelaDeConfirmacao } from '../../componentes-do-vendedor';
import { IndicadorDeEnvio } from '../../IndicadorDeEnvio';
import { SeletorDeIdioma } from '../../SeletorDeIdioma';
import { TEXTOS } from '../../textos';
import { classeDaSituacao, dia, porCento, reais, situacaoNaTela, useConsulta } from './apoio';

export type DestinoDoConsignado =
  | 'pedido'
  | 'cobranca'
  | 'reposicao'
  | 'troca'
  | 'clientes'
  | 'maletas';

export interface TelaSemanalProps {
  ponte: PonteDoCorona;
  nome: string | null;
  aoIr: (destino: DestinoDoConsignado) => void;
  aoAbrirPedido: (notaId: string) => void;
  aoPesquisar: (texto: string) => void;
  aoSair: () => void | Promise<void>;
}

const BOTOES: readonly {
  destino: DestinoDoConsignado;
  chave: 'novoPedido' | 'cobranca' | 'reposicao' | 'troca' | 'clientes' | 'maletas';
}[] = [
  { destino: 'pedido', chave: 'novoPedido' },
  { destino: 'cobranca', chave: 'cobranca' },
  { destino: 'reposicao', chave: 'reposicao' },
  { destino: 'troca', chave: 'troca' },
  { destino: 'clientes', chave: 'clientes' },
  { destino: 'maletas', chave: 'maletas' },
];

/**
 * Gerenciamento Semanal (§23.4): a tela principal do Corona Vendas. Botões no alto, cabeçalho da semana com a lista de semanas
 * anteriores, Nova semana e as abas Pedidos (linhas coloridas pela situação) e Totais. Tudo vem do banco do computador, então
 * funciona sem internet.
 */
export function TelaSemanal({ ponte, nome, aoIr, aoAbrirPedido, aoPesquisar, aoSair }: TelaSemanalProps) {
  const T = TEXTOS.consignado.semanal;
  const [semanaId, setSemanaId] = useState<string | undefined>(undefined);
  const [aba, setAba] = useState<'pedidos' | 'totais'>('pedidos');
  const [texto, setTexto] = useState('');
  const [confirmandoNova, setConfirmandoNova] = useState(false);
  const [numeroInicial, setNumeroInicial] = useState('1');
  const [erro, setErro] = useState<string | null>(null);
  const cotacao = useCotacao(ponte);
  const { dados: painel, erro: erroDaConsulta, recarregar } = useConsulta<PainelDaSemana>(
    ponte,
    { operacao: 'consignado.painel', ...(semanaId ? { semanaId } : {}) },
    [],
  );

  async function abrirSemana(numero?: number): Promise<void> {
    setErro(null);
    const r = await chamar<unknown>(ponte, {
      operacao: 'consignado.semanaNova',
      id: novoId(),
      ...(numero ? { numero } : {}),
    });
    if (!r.ok) setErro(r.mensagem);
    setConfirmandoNova(false);
    setSemanaId(undefined);
    await recarregar();
  }

  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Vendas"
        acoes={
          <>
            {nome ? <span className="vendas-nome-do-vendedor">{nome}</span> : null}
            <SeletorDeIdioma />
            <Botao onClick={() => void aoSair()}>{TEXTOS.botoes.sair}</Botao>
          </>
        }
      />
      <main className="vendas-conteudo consignado-largo">
        <h1 className="vendas-titulo">{T.titulo}</h1>
        <IndicadorDeEnvio ponte={ponte} />
        {erro ?? erroDaConsulta ? <Aviso tipo="erro">{erro ?? erroDaConsulta}</Aviso> : null}

        <nav className="consignado-botoes" aria-label={T.acoes}>
          {BOTOES.map(({ destino, chave }) => (
            <Botao
              key={destino}
              variante={destino === 'pedido' ? 'primario' : 'secundario'}
              grande
              disabled={destino === 'pedido' && painel !== null && !painel.podeNovoPedido}
              title={destino === 'pedido' && painel && !painel.podeNovoPedido ? T.novoPedidoSoNaAtual : undefined}
              onClick={() => aoIr(destino)}
            >
              {T.botoes[chave]}
            </Botao>
          ))}
          <form
            className="consignado-pesquisa"
            onSubmit={(e) => {
              e.preventDefault();
              if (texto.trim()) aoPesquisar(texto.trim());
            }}
          >
            <Campo
              rotulo={T.botoes.pesquisar}
              placeholder={T.pesquisarCampo}
              value={texto}
              onChange={(e) => setTexto(e.target.value)}
            />
          </form>
        </nav>

        {painel?.semSemana ? (
          <div className="vendas-cartao vendas-cartao--larga" role="group" aria-label={T.primeiraTitulo}>
            <h2 className="vendas-subtitulo">{T.primeiraTitulo}</h2>
            <p className="vendas-texto">{T.primeiraTexto}</p>
            <Campo
              rotulo={T.primeiraCampo}
              inputMode="numeric"
              value={numeroInicial}
              onChange={(e) => setNumeroInicial(e.target.value.replace(/\D/g, ''))}
            />
            <RodapeDeAcoes
              principal={
                <Botao
                  variante="primario"
                  disabled={!/^[1-9]\d{0,6}$/.test(numeroInicial)}
                  onClick={() => void abrirSemana(Number(numeroInicial))}
                >
                  {T.primeiraBotao}
                </Botao>
              }
            />
          </div>
        ) : null}

        {painel?.semana ? (
          <>
            <section className="consignado-cabecalho" aria-label={T.semana}>
              <p>
                <strong>{T.semana}:</strong> {String(painel.semana.numero).padStart(7, '0')}
              </p>
              <p>
                <strong>{T.representante}:</strong> {painel.representante ?? nome ?? ''}
              </p>
              <p>
                <strong>{T.situacao}:</strong>{' '}
                {painel.semana.situacao === 'EM_ANDAMENTO' ? T.emAndamento : T.fechada}
              </p>
              <label className="consignado-seletor">
                {T.escolherSemana}
                <select
                  value={painel.semana.id}
                  onChange={(e) => setSemanaId(e.target.value)}
                  aria-label={T.escolherSemana}
                >
                  {painel.semanas.map((s) => {
                    const numero = String(s.numero).padStart(7, '0');
                    return (
                      <option key={s.id} value={s.id}>
                        {s.atual ? T.semanaAtual(numero) : numero}
                      </option>
                    );
                  })}
                </select>
              </label>
              <Botao onClick={() => setConfirmandoNova(true)}>{T.novaSemana}</Botao>
            </section>

            <div className="consignado-abas" role="tablist">
              {(['pedidos', 'totais'] as const).map((a) => (
                <button
                  key={a}
                  type="button"
                  role="tab"
                  aria-selected={aba === a}
                  className={`consignado-aba${aba === a ? ' consignado-aba--ativa' : ''}`}
                  onClick={() => setAba(a)}
                >
                  {T.abas[a]}
                </button>
              ))}
            </div>

            {aba === 'pedidos' ? (
              <>
                <table className="cs-tabela consignado-tabela">
                  <caption>{T.pedidosLegenda}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{T.colunas.nota}</th>
                      <th scope="col">{T.colunas.cliente}</th>
                      <th scope="col">{T.colunas.cobranca}</th>
                      <th scope="col" className="cs-numero">{T.colunas.dx}</th>
                      <th scope="col" className="cs-numero">{T.colunas.dv}</th>
                      <th scope="col" className="cs-numero">{T.colunas.vd}</th>
                      <th scope="col" className="cs-numero">{T.colunas.percentual}</th>
                      <th scope="col" className="cs-numero">{T.colunas.comissao}</th>
                      <th scope="col" className="cs-numero">{T.colunas.liquido}</th>
                      <th scope="col">{T.colunas.situacao}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {painel.pedidos.length === 0 ? (
                      <tr>
                        <td colSpan={10}>{T.semPedidos}</td>
                      </tr>
                    ) : (
                      painel.pedidos.map((p) => (
                        <tr
                          key={p.notaId}
                          className={classeDaSituacao(p.situacao)}
                          onDoubleClick={() => aoAbrirPedido(p.notaId)}
                          tabIndex={0}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') aoAbrirPedido(p.notaId);
                          }}
                        >
                          <td>{p.numero}</td>
                          <td>{p.cliente}</td>
                          <td>{dia(p.dataCobranca)}</td>
                          <td className="cs-numero">{reais(p.valorDx)}</td>
                          <td className="cs-numero">{reais(p.valorDv)}</td>
                          <td className="cs-numero">{reais(p.valorVd)}</td>
                          <td className="cs-numero">{porCento(p.comissaoPercentual)}</td>
                          <td className="cs-numero">{reais(p.comissao)}</td>
                          <td className="cs-numero">{reais(p.valorLiquido)}</td>
                          <td>{situacaoNaTela(p.situacao)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
                <p className="vendas-texto vendas-texto--pequeno">{T.dica}</p>
              </>
            ) : (
              <section className="consignado-totais" aria-label={T.totaisLegenda}>
                <dl>
                  <dt>{T.totais.levado}</dt>
                  <dd><Dinheiro reais={painel.totais.valorDx} cotacao={cotacao.cotacao} /></dd>
                  <dt>{T.totais.devolvido}</dt>
                  <dd><Dinheiro reais={painel.totais.valorDv} cotacao={cotacao.cotacao} /></dd>
                  <dt>{T.totais.vendido}</dt>
                  <dd><Dinheiro reais={painel.totais.valorVd} cotacao={cotacao.cotacao} /></dd>
                  <dt>{T.totais.itens(painel.totais.itensDx, painel.totais.itensDv, painel.totais.itensVd)}</dt>
                  <dd />
                  <dt>{T.totais.comissao}</dt>
                  <dd><Dinheiro reais={painel.totais.comissao} cotacao={cotacao.cotacao} /></dd>
                  <dt>{T.totais.liquido}</dt>
                  <dd><Dinheiro reais={painel.totais.liquido} cotacao={cotacao.cotacao} /></dd>
                  <dt>{T.totais.recebido}</dt>
                  <dd><Dinheiro reais={painel.totais.recebido} cotacao={cotacao.cotacao} /></dd>
                  {(['DINHEIRO', 'PIX', 'DEBITO', 'CREDITO'] as const).map((f) => (
                    <div key={f} className="consignado-totais__forma">
                      <dt>{TEXTOS.consignado.formas[f]}</dt>
                      <dd>{reais(painel.totais.recebidoPorForma[f])}</dd>
                    </div>
                  ))}
                  <dt>{T.totais.pendente}</dt>
                  <dd><Dinheiro reais={painel.totais.pendente} cotacao={cotacao.cotacao} /></dd>
                </dl>
                {cotacao.aviso ? <p className="vendas-texto vendas-texto--pequeno">{cotacao.aviso}</p> : null}
              </section>
            )}
          </>
        ) : null}
      </main>

      {confirmandoNova && painel?.semana ? (
        <JanelaDeConfirmacao
          titulo={T.novaSemanaTitulo}
          rotuloConfirmar={T.novaSemanaConfirmar}
          aoConfirmar={() => void abrirSemana()}
          aoCancelar={() => setConfirmandoNova(false)}
        >
          {T.novaSemanaTexto(String(painel.semanas[0]?.numero ?? painel.semana.numero).padStart(7, '0'))}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
