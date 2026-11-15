import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import type {
  RelatorioAReceber,
  RelatorioDeCreditos,
  RelatorioDeDevolucoes,
  RelatorioDeVendas,
  TotaisDeRelatorio,
} from '../../shared/dados';
import type { AgrupamentoDeVendas } from '../../shared/operacoes';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, DinheiroConvertido, useCotacao } from '../chamadas';
import { AvisoDosDados } from '../IndicadorDeEnvio';
import { TEXTOS } from '../textos';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaMeusNumerosProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoVerVendas: () => void;
  /** "Meu acerto e minha comissão" e "A receber dos clientes" (tarefa 7.11). */
  aoVerAcerto?: () => void;
  aoVerAReceber?: () => void;
}

interface Numeros {
  vendas: RelatorioDeVendas;
  recebimentos: RelatorioDeVendas;
  aReceber: RelatorioAReceber;
  devolucoes: RelatorioDeDevolucoes;
  creditos: RelatorioDeCreditos;
}

/**
 * Meus números (§12, "App do vendedor"): vendas por cliente, peça ou dia, recebimentos por forma e o que os clientes
 * ainda devem. Só dados dele, sem custo; vendas estornadas não contam. Os valores aparecem em Real, Dólar e Guarani,
 * cada venda e cada pagamento pela cotação do seu dia, já somados pelo servidor (ADR-0006, item 10; o banco guarda Real).
 */
export function TelaMeusNumeros({
  ponte,
  aoVoltar,
  aoVerVendas,
  aoVerAcerto,
  aoVerAReceber,
}: TelaMeusNumerosProps) {
  const T = TEXTOS.numeros;
  const { curta: cotacaoCurta } = useCotacao(ponte);
  const [de, setDe] = useState('');
  const [ate, setAte] = useState('');
  const [agrupar, setAgrupar] = useState<AgrupamentoDeVendas>('cliente');
  const [agrupadoPor, setAgrupadoPor] = useState<AgrupamentoDeVendas>('cliente');
  const [numeros, setNumeros] = useState<Numeros | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [avisoDoPeriodo, setAvisoDoPeriodo] = useState<string | null>(null);

  const carregar = useCallback(
    async (periodo: { de: string; ate: string }, como: AgrupamentoDeVendas) => {
      setErro(null);
      const filtro = {
        ...(periodo.de ? { de: periodo.de } : {}),
        ...(periodo.ate ? { ate: periodo.ate } : {}),
      };
      const [vendas, recebimentos, aReceber, devolucoes, creditos] = await Promise.all([
        chamar<RelatorioDeVendas>(ponte, {
          operacao: 'relatorios.vendas',
          agrupar: como,
          ...filtro,
        }),
        chamar<RelatorioDeVendas>(ponte, { operacao: 'relatorios.recebimentos', ...filtro }),
        chamar<RelatorioAReceber>(ponte, { operacao: 'relatorios.aReceber' }),
        chamar<RelatorioDeDevolucoes>(ponte, { operacao: 'relatorios.devolucoes', ...filtro }),
        chamar<RelatorioDeCreditos>(ponte, { operacao: 'relatorios.creditos' }),
      ]);
      if (!vendas.ok) return setErro(vendas.mensagem);
      if (!recebimentos.ok) return setErro(recebimentos.mensagem);
      if (!aReceber.ok) return setErro(aReceber.mensagem);
      if (!devolucoes.ok) return setErro(devolucoes.mensagem);
      if (!creditos.ok) return setErro(creditos.mensagem);
      setAgrupadoPor(como);
      setNumeros({
        vendas: vendas.dados,
        recebimentos: recebimentos.dados,
        aReceber: aReceber.dados,
        devolucoes: devolucoes.dados,
        creditos: creditos.dados,
      });
    },
    [ponte],
  );

  useEffect(() => {
    void carregar({ de: '', ate: '' }, 'cliente');
  }, [carregar]);

  function aplicar(evento: FormEvent) {
    evento.preventDefault();
    if (de && ate && de > ate) {
      setAvisoDoPeriodo(T.periodoInvertido);
      return;
    }
    setAvisoDoPeriodo(null);
    void carregar({ de, ate }, agrupar);
  }

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      <AvisoDosDados ponte={ponte} cotacao={cotacaoCurta} />
      {aoVerAcerto || aoVerAReceber ? (
        <div className="vendas-botoes">
          {aoVerAcerto ? (
            <Botao variante="primario" onClick={aoVerAcerto}>
              {TEXTOS.acerto.botaoNosNumeros}
            </Botao>
          ) : null}
          {aoVerAReceber ? (
            <Botao variante="primario" onClick={aoVerAReceber}>
              {TEXTOS.aReceber.botaoNosNumeros}
            </Botao>
          ) : null}
        </div>
      ) : null}
      <form className="vendas-leitor" onSubmit={aplicar}>
        <Campo rotulo={T.de} type="date" value={de} onChange={(e) => setDe(e.target.value)} />
        <Campo rotulo={T.ate} type="date" value={ate} onChange={(e) => setAte(e.target.value)} />
        <div className="cs-campo">
          <label htmlFor="agrupar-vendas">{T.agrupar}</label>
          <select
            id="agrupar-vendas"
            value={agrupar}
            onChange={(e) => setAgrupar(e.target.value as AgrupamentoDeVendas)}
          >
            {Object.entries(T.agrupamentos).map(([valor, nome]) => (
              <option key={valor} value={valor}>
                {nome}
              </option>
            ))}
          </select>
        </div>
        <Botao type="submit" variante="primario">
          {T.aplicar}
        </Botao>
      </form>
      {avisoDoPeriodo ? <Aviso tipo="erro">{avisoDoPeriodo}</Aviso> : null}
      {numeros === null && erro === null ? (
        <p className="vendas-texto" aria-busy="true">
          {T.textoCarregando}
        </p>
      ) : null}
      {numeros ? (
        <>
          <p className="vendas-texto vendas-texto--pequeno">
            {T.cotacaoDeCadaLancamento} {T.vendasEstornadasNaoContam}
          </p>

          <section aria-label={T.vendasTitulo}>
            <h2 className="vendas-subtitulo">{T.vendasTitulo}</h2>
            <Tabela
              legenda={`${T.vendasTitulo} (${T.agrupamentos[agrupadoPor] ?? ''})`}
              textoVazio={T.semVendas}
              colunas={[
                { chave: 'chave', titulo: T.agrupamentos[agrupadoPor] ?? T.colunasVendas.chave },
                { chave: 'quantidade', titulo: T.colunasVendas.quantidade, numerica: true },
                { chave: 'total', titulo: T.colunasVendas.total, numerica: true },
                { chave: 'desconto', titulo: T.colunasVendas.desconto, numerica: true },
              ]}
              linhas={numeros.vendas.linhas.map((l) => ({
                chave: l.nome,
                quantidade: l.quantidade,
                total: (
                  <DinheiroConvertido
                    reais={l.total}
                    dolar={l.totalEmDolar}
                    guarani={l.totalEmGuarani}
                  />
                ),
                desconto: (
                  <DinheiroConvertido
                    reais={l.desconto}
                    dolar={l.descontoEmDolar}
                    guarani={l.descontoEmGuarani}
                  />
                ),
              }))}
            />
            <p className="vendas-total">
              {T.totalVendido}: <Totais totais={numeros.vendas.totais} />
            </p>
            <Botao onClick={aoVerVendas}>{T.minhasVendas}</Botao>
          </section>

          <section aria-label={T.recebimentosTitulo}>
            <h2 className="vendas-subtitulo">{T.recebimentosTitulo}</h2>
            <Tabela
              legenda={T.recebimentosTitulo}
              textoVazio={T.semRecebimentos}
              colunas={[
                { chave: 'forma', titulo: T.recebimentosColunas.forma },
                { chave: 'pagamentos', titulo: T.recebimentosColunas.pagamentos, numerica: true },
                { chave: 'total', titulo: T.recebimentosColunas.total, numerica: true },
              ]}
              linhas={numeros.recebimentos.linhas.map((l) => ({
                forma: TEXTOS.venda.formas[l.nome] ?? l.nome,
                pagamentos: l.quantidade,
                total: (
                  <DinheiroConvertido
                    reais={l.total}
                    dolar={l.totalEmDolar}
                    guarani={l.totalEmGuarani}
                  />
                ),
              }))}
            />
            <p className="vendas-total">
              {T.totalRecebido}: <Totais totais={numeros.recebimentos.totais} />
            </p>
            <p className="vendas-texto vendas-texto--pequeno">{T.recebimentosSemCredito}</p>
          </section>

          <section aria-label={T.devolucoesTitulo}>
            <h2 className="vendas-subtitulo">{T.devolucoesTitulo}</h2>
            <Tabela
              legenda={T.devolucoesTitulo}
              textoVazio={T.semDevolucoes}
              colunas={[
                { chave: 'motivo', titulo: T.devolucoesColunas.motivo },
                { chave: 'devolucoes', titulo: T.devolucoesColunas.devolucoes, numerica: true },
                { chave: 'valor', titulo: T.devolucoesColunas.valor, numerica: true },
              ]}
              linhas={numeros.devolucoes.linhas.map((l) => ({
                motivo: T.motivos[l.motivo] ?? l.motivo,
                devolucoes: l.devolucoes,
                valor: (
                  <DinheiroConvertido
                    reais={l.valor}
                    dolar={l.valorEmDolar}
                    guarani={l.valorEmGuarani}
                  />
                ),
              }))}
            />
            <p className="vendas-total">
              {T.devolucoesTotais.devolucoes}: {numeros.devolucoes.totais.devolucoes}
              {' · '}
              {T.devolucoesTotais.trocas}: {numeros.devolucoes.totais.trocas}
            </p>
            <p className="vendas-total">
              {T.devolucoesTotais.valorDevolvido}:{' '}
              <DinheiroConvertido
                reais={numeros.devolucoes.totais.valorDevolvido}
                dolar={numeros.devolucoes.totais.valorDevolvidoEmDolar}
                guarani={numeros.devolucoes.totais.valorDevolvidoEmGuarani}
              />
            </p>
            <p className="vendas-total">
              {T.devolucoesTotais.emDinheiro}:{' '}
              <DinheiroConvertido
                reais={numeros.devolucoes.totais.emDinheiro}
                dolar={numeros.devolucoes.totais.emDinheiroEmDolar}
                guarani={numeros.devolucoes.totais.emDinheiroEmGuarani}
              />
            </p>
            <p className="vendas-total">
              {T.devolucoesTotais.emCredito}:{' '}
              <DinheiroConvertido
                reais={numeros.devolucoes.totais.emCredito}
                dolar={numeros.devolucoes.totais.emCreditoEmDolar}
                guarani={numeros.devolucoes.totais.emCreditoEmGuarani}
              />
            </p>
          </section>

          <section aria-label={T.creditosTitulo}>
            <h2 className="vendas-subtitulo">{T.creditosTitulo}</h2>
            <Tabela
              legenda={T.creditosTitulo}
              textoVazio={T.semCreditos}
              colunas={[
                { chave: 'cliente', titulo: T.creditosColunas.cliente },
                { chave: 'gerado', titulo: T.creditosColunas.gerado, numerica: true },
                { chave: 'usado', titulo: T.creditosColunas.usado, numerica: true },
                { chave: 'emAberto', titulo: T.creditosColunas.emAberto, numerica: true },
              ]}
              linhas={numeros.creditos.clientes.map((c) => ({
                cliente: c.clienteNome,
                gerado: (
                  <DinheiroConvertido
                    reais={c.gerado}
                    dolar={c.geradoEmDolar}
                    guarani={c.geradoEmGuarani}
                  />
                ),
                usado: (
                  <DinheiroConvertido
                    reais={c.usado}
                    dolar={c.usadoEmDolar}
                    guarani={c.usadoEmGuarani}
                  />
                ),
                emAberto: (
                  <DinheiroConvertido
                    reais={c.emAberto}
                    dolar={c.emAbertoEmDolar}
                    guarani={c.emAbertoEmGuarani}
                  />
                ),
              }))}
            />
            <p className="vendas-total">
              {T.totalCreditoEmAberto}:{' '}
              <DinheiroConvertido
                reais={numeros.creditos.totais.emAberto}
                dolar={numeros.creditos.totais.emAbertoEmDolar}
                guarani={numeros.creditos.totais.emAbertoEmGuarani}
              />
            </p>
          </section>

          <section aria-label={T.aReceberTitulo}>
            <h2 className="vendas-subtitulo">{T.aReceberTitulo}</h2>
            <Tabela
              legenda={T.aReceberTitulo}
              textoVazio={T.ninguemDeve}
              colunas={[
                { chave: 'cliente', titulo: T.aReceberColunas.cliente },
                { chave: 'vendido', titulo: T.aReceberColunas.vendido, numerica: true },
                { chave: 'pago', titulo: T.aReceberColunas.pago, numerica: true },
                { chave: 'aReceber', titulo: T.aReceberColunas.aReceber, numerica: true },
              ]}
              linhas={numeros.aReceber.clientes.map((c) => ({
                cliente: c.clienteNome,
                vendido: (
                  <DinheiroConvertido
                    reais={c.vendido}
                    dolar={c.vendidoEmDolar}
                    guarani={c.vendidoEmGuarani}
                  />
                ),
                pago: (
                  <DinheiroConvertido
                    reais={c.pago}
                    dolar={c.pagoEmDolar}
                    guarani={c.pagoEmGuarani}
                  />
                ),
                aReceber: (
                  <DinheiroConvertido
                    reais={c.aReceber}
                    dolar={c.aReceberEmDolar}
                    guarani={c.aReceberEmGuarani}
                  />
                ),
              }))}
            />
            <p className="vendas-total">
              {T.totalAReceber}: <Totais totais={numeros.aReceber.totais} />
            </p>
          </section>
        </>
      ) : null}
    </PaginaDoVendedor>
  );
}

function Totais({ totais }: { totais: TotaisDeRelatorio }) {
  return (
    <DinheiroConvertido
      reais={totais.total}
      dolar={totais.totalEmDolar}
      guarani={totais.totalEmGuarani}
    />
  );
}
