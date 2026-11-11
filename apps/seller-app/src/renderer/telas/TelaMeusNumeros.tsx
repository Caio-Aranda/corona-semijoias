import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import type { RelatorioAReceber, RelatorioDeVendas, TotaisDeRelatorio } from '../../shared/dados';
import type { AgrupamentoDeVendas } from '../../shared/operacoes';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, DinheiroConvertido } from '../chamadas';
import { TEXTOS } from '../textos';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaMeusNumerosProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  aoVerVendas: () => void;
}

interface Numeros {
  vendas: RelatorioDeVendas;
  recebimentos: RelatorioDeVendas;
  aReceber: RelatorioAReceber;
}

/**
 * Meus números (§12, "App do vendedor"): vendas por cliente, peça ou dia, recebimentos por forma e o que os clientes
 * ainda devem. Só dados dele, sem custo; vendas estornadas não contam. Os valores aparecem em Real, Dólar e Guarani,
 * cada venda e cada pagamento pela cotação do seu dia, já somados pelo servidor (ADR-0006, item 10; o banco guarda Real).
 */
export function TelaMeusNumeros({ ponte, aoVoltar, aoVerVendas }: TelaMeusNumerosProps) {
  const T = TEXTOS.numeros;
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
      const [vendas, recebimentos, aReceber] = await Promise.all([
        chamar<RelatorioDeVendas>(ponte, {
          operacao: 'relatorios.vendas',
          agrupar: como,
          ...filtro,
        }),
        chamar<RelatorioDeVendas>(ponte, { operacao: 'relatorios.recebimentos', ...filtro }),
        chamar<RelatorioAReceber>(ponte, { operacao: 'relatorios.aReceber' }),
      ]);
      if (!vendas.ok) return setErro(vendas.mensagem);
      if (!recebimentos.ok) return setErro(recebimentos.mensagem);
      if (!aReceber.ok) return setErro(aReceber.mensagem);
      setAgrupadoPor(como);
      setNumeros({
        vendas: vendas.dados,
        recebimentos: recebimentos.dados,
        aReceber: aReceber.dados,
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
    <PaginaDoVendedor titulo={T.titulo} aoVoltar={aoVoltar}>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
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
