import { Aviso, Botao, ValorTresMoedas } from '@corona/ui';
import { type ReactNode, useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import type {
  NotaDeSaida,
  PecasVendidasPorCliente,
  PedidoDeRelatorio,
  RelatorioDoAcerto,
  RelatorioDoConsignado,
  ResultadoDeSaidaDoRelatorio,
  SituacaoDaMaleta,
  SituacaoDoCliente,
  TotaisDaSemanaNoRelatorio,
  ValorNasTresMoedas,
} from '../../../shared/relatorios-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../chamadas';
import { centavosDeTexto, formatarDolar, formatarGuarani, formatarReais } from '../../moedas';
import { TEXTOS } from '../../textos';
import { diaEmBrasilia } from '../../../shared/garantia';
import { dia, porCento, reais } from './apoio';

/**
 * Os relatórios do consignado ao cliente (tarefa 9.3; §23.11). Cada um abre numa folha branca, de texto escuro, que o
 * computador monta do banco local (sem internet) e que a pessoa imprime, salva em PDF ou prepara para o WhatsApp. A folha é
 * a única parte que sai no papel (veja `.relatorio-raiz` em estilos.css). Valores em Real, com Dólar e Guarani abaixo, pela
 * cotação do dia de cada lançamento; sem cotação, só o Real e o texto "Sem cotação".
 */

/** Um valor nas três moedas; sem Dólar e Guarani, só o Real e "Sem cotação". */
export function ValorDoRelatorio({ v }: { v: ValorNasTresMoedas }) {
  const T = TEXTOS.consignado.relatorios;
  const real = centavosDeTexto(v.real);
  if (real === null) return <span>{v.real}</span>;
  const dolar = v.dolar ? centavosDeTexto(v.dolar) : null;
  const guarani = v.guarani && /^-?\d+$/.test(v.guarani) ? BigInt(v.guarani) : null;
  if (dolar === null || guarani === null) {
    return (
      <span className="cs-valor">
        <strong className="cs-valor__real">{formatarReais(real)}</strong>
        <span className="cs-valor__outras">{T.semCotacao}</span>
      </span>
    );
  }
  return (
    <ValorTresMoedas
      real={formatarReais(real)}
      dolar={formatarDolar(dolar)}
      guarani={formatarGuarani(guarani)}
    />
  );
}

/**
 * O relatório do consignado como fonte dos valores de uma tela: cada lançamento já vem convertido pela cotação do DIA DELE
 * (ADR-0006), não pela de hoje. Enquanto não chega, ou se não puder ser montado, a tela usa a cotação de hoje.
 */
export function useRelatorioDaTela<T extends RelatorioDoConsignado>(
  ponte: PonteDoCorona,
  pedido: PedidoDeRelatorio | null,
  dependencias: readonly unknown[] = [],
): T | null {
  const [relatorio, setRelatorio] = useState<T | null>(null);
  const chave = JSON.stringify(pedido);
  useEffect(() => {
    let valida = true;
    if (!pedido) {
      setRelatorio(null);
      return undefined;
    }
    void chamar<RelatorioDoConsignado>(ponte, {
      operacao: 'consignado.relatorio',
      relatorio: pedido,
    })
      .then((r) => {
        if (valida) setRelatorio(r.ok && r.dados?.tipo === pedido.tipo ? (r.dados as T) : null);
      })
      .catch(() => {
        if (valida) setRelatorio(null);
      });
    return () => {
      valida = false;
    };
  }, [ponte, chave, ...dependencias]);
  return relatorio;
}

function Linha({ rotulo, children }: { rotulo: string; children: ReactNode }) {
  return (
    <>
      <dt>{rotulo}</dt>
      <dd>{children}</dd>
    </>
  );
}

/** O nome do arquivo e a mensagem do WhatsApp de cada relatório, no idioma da pessoa. */
export function textosDeSaida(r: RelatorioDoConsignado): {
  nomeDoArquivo: string;
  mensagem: string;
  telefone: string | null;
  semNumero: boolean;
} {
  const T = TEXTOS.consignado.relatorios;
  switch (r.tipo) {
    case 'NOTA_DE_SAIDA':
      return {
        nomeDoArquivo: T.nomeDoArquivo.NOTA_DE_SAIDA(r.numero),
        mensagem: T.mensagem.NOTA_DE_SAIDA(r.cliente.nome, r.numero),
        telefone: r.cliente.telefone,
        semNumero: false,
      };
    case 'ACERTO':
      return {
        nomeDoArquivo: T.nomeDoArquivo.ACERTO(r.numero),
        mensagem: T.mensagem.ACERTO(r.cliente.nome, r.numero),
        telefone: r.cliente.telefone,
        semNumero: false,
      };
    case 'SITUACAO_DO_CLIENTE':
      return {
        nomeDoArquivo: T.nomeDoArquivo.SITUACAO_DO_CLIENTE(r.cliente.nome),
        mensagem: T.mensagem.SITUACAO_DO_CLIENTE(r.cliente.nome),
        telefone: r.cliente.telefone,
        semNumero: false,
      };
    case 'PECAS_VENDIDAS':
      return {
        nomeDoArquivo: T.nomeDoArquivo.PECAS_VENDIDAS(r.cliente.nome),
        mensagem: T.mensagem.PECAS_VENDIDAS(r.cliente.nome),
        telefone: r.cliente.telefone,
        semNumero: false,
      };
    case 'TOTAIS_DA_SEMANA':
      return {
        nomeDoArquivo: T.nomeDoArquivo.TOTAIS_DA_SEMANA(r.semana),
        mensagem: T.mensagem.TOTAIS_DA_SEMANA(r.semana),
        telefone: null,
        semNumero: true,
      };
    case 'SITUACAO_DA_MALETA':
      return {
        nomeDoArquivo: T.nomeDoArquivo.SITUACAO_DA_MALETA(r.maleta),
        mensagem: T.mensagem.SITUACAO_DA_MALETA(r.maleta),
        telefone: null,
        semNumero: true,
      };
  }
}

function Topo({ r, subtitulo }: { r: RelatorioDoConsignado; subtitulo?: string }) {
  const T = TEXTOS.consignado.relatorios;
  const c = r.cabecalho;
  return (
    <header className="relatorio-topo">
      <p className="relatorio-loja">{T.loja}</p>
      <h1 className="relatorio-titulo">{T.titulos[r.tipo] ?? r.tipo}</h1>
      {subtitulo ? <p className="relatorio-subtitulo">{subtitulo}</p> : null}
      <p className="relatorio-linha">
        {c.representante ? (
          <span>
            <strong>{T.representante}:</strong> {c.representante}
          </span>
        ) : null}
        {c.praca ? (
          <span>
            <strong>{T.praca}:</strong> {c.praca}
          </span>
        ) : null}
        <span>{T.geradoEm(dia(c.geradoEm))}</span>
      </p>
    </header>
  );
}

function DadosDoCliente({ cliente }: { cliente: NotaDeSaida['cliente'] }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <dl className="relatorio-dados">
      <Linha rotulo={T.cliente}>{cliente.nome}</Linha>
      {cliente.documento ? <Linha rotulo={T.documento}>{cliente.documento}</Linha> : null}
      {cliente.telefone ? <Linha rotulo={T.telefone}>{cliente.telefone}</Linha> : null}
      {cliente.endereco ? <Linha rotulo={T.endereco}>{cliente.endereco}</Linha> : null}
    </dl>
  );
}

/** DX, DV e VD em valores e em itens, no mesmo desenho em todos os relatórios. */
function DxDvVd({
  itensDx,
  itensDv,
  itensVd,
  valorDx,
  valorDv,
  valorVd,
}: {
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: ValorNasTresMoedas;
  valorDv: ValorNasTresMoedas;
  valorVd: ValorNasTresMoedas;
}) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <dl className="relatorio-conta">
      <Linha rotulo={T.valorDx}>
        <ValorDoRelatorio v={valorDx} />
      </Linha>
      <Linha rotulo={T.valorDv}>
        <ValorDoRelatorio v={valorDv} />
      </Linha>
      <Linha rotulo={T.valorVd}>
        <ValorDoRelatorio v={valorVd} />
      </Linha>
      <Linha rotulo={T.itens(itensDx, itensDv, itensVd)}>{''}</Linha>
    </dl>
  );
}

function FolhaDaNotaDeSaida({ r }: { r: NotaDeSaida }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <>
      <Topo r={r} />
      <dl className="relatorio-dados">
        <Linha rotulo={T.nota}>{r.numero}</Linha>
        <Linha rotulo={T.dataDeSaida}>{dia(r.saidaEm)}</Linha>
        <Linha rotulo={T.retorno}>{dia(r.dataCobranca)}</Linha>
        <Linha rotulo={T.maletas}>{r.maletas.join(', ')}</Linha>
      </dl>
      <DadosDoCliente cliente={r.cliente} />
      <table className="relatorio-tabela">
        <thead>
          <tr>
            <th scope="col">{T.colunas.ref}</th>
            <th scope="col">{T.colunas.produto}</th>
            <th scope="col">{T.colunas.maleta}</th>
            <th scope="col" className="cs-numero">
              {T.colunas.qtd}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.preco}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.subTotal}
            </th>
          </tr>
        </thead>
        <tbody>
          {r.linhas.map((l, i) => (
            <tr key={`${l.codigo}-${l.maleta}-${String(i)}`}>
              <td>{l.codigo}</td>
              <td>{l.produto}</td>
              <td>{l.maleta}</td>
              <td className="cs-numero">{l.quantidade}</td>
              <td className="cs-numero">{reais(l.preco)}</td>
              <td className="cs-numero">
                <ValorDoRelatorio v={l.subTotal} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <dl className="relatorio-conta">
        <Linha rotulo={T.itensDx(r.itensDx)}>{''}</Linha>
        <Linha rotulo={T.totalDx}>
          <ValorDoRelatorio v={r.totalDx} />
        </Linha>
        <Linha rotulo={T.pendenciaAnterior}>
          <ValorDoRelatorio v={r.pendenciaAnterior} />
        </Linha>
      </dl>
      <div className="relatorio-assinaturas">
        <p>
          <span className="relatorio-assinatura-linha" />
          {T.assinaturaCliente}
          <br />
          {r.cliente.nome}
        </p>
        <p>
          <span className="relatorio-assinatura-linha" />
          {T.assinaturaRepresentante}
          <br />
          {r.cabecalho.representante ?? ''}
        </p>
      </div>
    </>
  );
}

function FolhaDoAcerto({ r }: { r: RelatorioDoAcerto }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <>
      <Topo r={r} subtitulo={`${T.nota} ${r.numero}`} />
      <dl className="relatorio-dados">
        <Linha rotulo={T.dataDeSaida}>{dia(r.saidaEm)}</Linha>
        <Linha rotulo={T.acertoEm}>{dia(r.acertadoEm)}</Linha>
      </dl>
      <DadosDoCliente cliente={r.cliente} />
      <h2 className="relatorio-secao">{T.pecasVendidasNoAcerto}</h2>
      <table className="relatorio-tabela">
        <thead>
          <tr>
            <th scope="col">{T.colunas.ref}</th>
            <th scope="col">{T.colunas.produto}</th>
            <th scope="col">{T.colunas.maleta}</th>
            <th scope="col" className="cs-numero">
              {T.colunas.qtd}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.preco}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.valor}
            </th>
          </tr>
        </thead>
        <tbody>
          {r.vendidas.map((l, i) => (
            <tr key={`${l.codigo}-${l.maleta}-${String(i)}`}>
              <td>{l.codigo}</td>
              <td>{l.produto}</td>
              <td>{l.maleta}</td>
              <td className="cs-numero">{l.quantidade}</td>
              <td className="cs-numero">{reais(l.preco)}</td>
              <td className="cs-numero">
                <ValorDoRelatorio v={l.valor} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <DxDvVd {...r} />
      <dl className="relatorio-conta">
        <Linha rotulo={T.vendaPercentual}>{porCento(r.vendaPercentual)}</Linha>
        <Linha rotulo={T.comissaoPercentual}>{porCento(r.comissaoPercentual)}</Linha>
        <Linha rotulo={T.comissaoValor}>
          <ValorDoRelatorio v={r.comissao} />
        </Linha>
        <Linha rotulo={T.valorLiquido}>
          <ValorDoRelatorio v={r.valorLiquido} />
        </Linha>
        <Linha rotulo={T.pendenciasAnteriores}>
          <ValorDoRelatorio v={r.totalPendenciasAnteriores} />
        </Linha>
        <Linha rotulo={T.totalAReceber}>
          <ValorDoRelatorio v={r.totalAReceber} />
        </Linha>
        {r.pago.map((f) => (
          <Linha
            key={f.forma}
            rotulo={`${T.pagoPorForma}: ${TEXTOS.consignado.formas[f.forma] ?? f.forma}`}
          >
            <ValorDoRelatorio v={f.valor} />
          </Linha>
        ))}
        <Linha rotulo={T.totalPago}>
          <ValorDoRelatorio v={r.totalPago} />
        </Linha>
        <Linha rotulo={T.ficouDevendo}>
          <ValorDoRelatorio v={r.ficouDevendo} />
        </Linha>
      </dl>
      {r.recibo ? <p className="relatorio-linha">{T.recibo(r.recibo)}</p> : null}
    </>
  );
}

function FolhaDaSituacaoDoCliente({ r }: { r: SituacaoDoCliente }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <>
      <Topo r={r} />
      <DadosDoCliente cliente={r.cliente} />
      <h2 className="relatorio-secao">{T.jaVendeu}</h2>
      {r.jaVendeu.length === 0 ? (
        <p className="relatorio-linha">{T.nadaVendido}</p>
      ) : (
        <table className="relatorio-tabela">
          <thead>
            <tr>
              <th scope="col">{T.colunas.nota}</th>
              <th scope="col">{T.colunas.situacao}</th>
              <th scope="col" className="cs-numero">
                {T.colunas.itensDx}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.itensDv}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.itensVd}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.dx}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.dv}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.vd}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.comissao}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.liquido}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.pago}
              </th>
            </tr>
          </thead>
          <tbody>
            {r.jaVendeu.map((n) => (
              <tr key={n.numero}>
                <td>{n.numero}</td>
                <td>{TEXTOS.consignado.situacoes[n.situacao] ?? n.situacao}</td>
                <td className="cs-numero">{n.itensDx}</td>
                <td className="cs-numero">{n.itensDv}</td>
                <td className="cs-numero">{n.itensVd}</td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.valorDx} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.valorDv} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.valorVd} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.comissao} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.valorLiquido} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.pago} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <DxDvVd {...r} />
      <dl className="relatorio-conta">
        <Linha rotulo={T.comissaoTotal}>
          <ValorDoRelatorio v={r.comissao} />
        </Linha>
        <Linha rotulo={T.liquidoTotal}>
          <ValorDoRelatorio v={r.valorLiquido} />
        </Linha>
        <Linha rotulo={T.pagoTotal}>
          <ValorDoRelatorio v={r.pago} />
        </Linha>
      </dl>
      <h2 className="relatorio-secao">{T.aFalta}</h2>
      {r.pendentes.length > 0 ? (
        <table className="relatorio-tabela">
          <caption>{T.notasPendentes}</caption>
          <thead>
            <tr>
              <th scope="col">{T.colunas.nota}</th>
              <th scope="col" className="cs-numero">
                {T.colunas.dx}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.vd}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.aindaDeve}
              </th>
            </tr>
          </thead>
          <tbody>
            {r.pendentes.map((n) => (
              <tr key={n.numero}>
                <td>{n.numero}</td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.valorDx} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.valorVd} />
                </td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={n.ficouDevendo} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {r.comOCliente.length === 0 ? (
        <p className="relatorio-linha">{T.nadaComOCliente}</p>
      ) : (
        <table className="relatorio-tabela">
          <thead>
            <tr>
              <th scope="col">{T.colunas.nota}</th>
              <th scope="col">{T.colunas.maleta}</th>
              <th scope="col">{T.colunas.ref}</th>
              <th scope="col">{T.colunas.produto}</th>
              <th scope="col" className="cs-numero">
                {T.colunas.qtd}
              </th>
              <th scope="col">{T.colunas.cobranca}</th>
            </tr>
          </thead>
          <tbody>
            {r.comOCliente.map((p, i) => (
              <tr key={`${p.nota}-${p.codigo}-${p.maleta}-${String(i)}`}>
                <td>{p.nota}</td>
                <td>{p.maleta}</td>
                <td>{p.codigo}</td>
                <td>{p.produto}</td>
                <td className="cs-numero">{p.quantidade}</td>
                <td>{dia(p.dataCobranca)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <dl className="relatorio-conta">
        <Linha rotulo={T.itensComOCliente(r.itensComOCliente)}>{''}</Linha>
        <Linha rotulo={T.valorComOCliente}>
          <ValorDoRelatorio v={r.valorComOCliente} />
        </Linha>
        <Linha rotulo={T.quantoDeve}>
          <ValorDoRelatorio v={r.deve} />
        </Linha>
      </dl>
    </>
  );
}

function FolhaDasPecasVendidas({ r }: { r: PecasVendidasPorCliente }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <>
      <Topo r={r} subtitulo={T.periodo(dia(r.de), dia(r.ate))} />
      <DadosDoCliente cliente={r.cliente} />
      {r.linhas.length === 0 ? (
        <p className="relatorio-linha">{T.semPecas}</p>
      ) : (
        <table className="relatorio-tabela">
          <thead>
            <tr>
              <th scope="col">{T.colunas.data}</th>
              <th scope="col">{T.colunas.nota}</th>
              <th scope="col">{T.colunas.ref}</th>
              <th scope="col">{T.colunas.produto}</th>
              <th scope="col">{T.colunas.maleta}</th>
              <th scope="col" className="cs-numero">
                {T.colunas.qtd}
              </th>
              <th scope="col" className="cs-numero">
                {T.colunas.valor}
              </th>
            </tr>
          </thead>
          <tbody>
            {r.linhas.map((l, i) => (
              <tr key={`${l.nota}-${l.codigo}-${l.maleta}-${String(i)}`}>
                <td>{dia(l.data)}</td>
                <td>{l.nota}</td>
                <td>{l.codigo}</td>
                <td>{l.produto}</td>
                <td>{l.maleta}</td>
                <td className="cs-numero">{l.quantidade}</td>
                <td className="cs-numero">
                  <ValorDoRelatorio v={l.valor} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <dl className="relatorio-conta">
        <Linha rotulo={T.colunas.itensVd}>{r.itensVd}</Linha>
        <Linha rotulo={T.totalVendido}>
          <ValorDoRelatorio v={r.valorVd} />
        </Linha>
      </dl>
    </>
  );
}

function FolhaDosTotais({ r }: { r: TotaisDaSemanaNoRelatorio }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <>
      <Topo r={r} subtitulo={`${T.semana} ${String(r.semana).padStart(7, '0')}`} />
      <dl className="relatorio-dados">
        <Linha rotulo={T.situacaoDaSemana}>
          {r.situacaoDaSemana === 'EM_ANDAMENTO' ? T.emAndamento : T.fechada}
        </Linha>
        <Linha rotulo={T.pedidos(r.pedidos)}>{''}</Linha>
      </dl>
      <DxDvVd {...r} />
      <dl className="relatorio-conta">
        <Linha rotulo={T.comissaoDosClientes}>
          <ValorDoRelatorio v={r.comissao} />
        </Linha>
        <Linha rotulo={T.liquidoTotal}>
          <ValorDoRelatorio v={r.valorLiquido} />
        </Linha>
        <Linha rotulo={T.recebido}>
          <ValorDoRelatorio v={r.recebido} />
        </Linha>
        {r.recebidoPorForma.map((f) => (
          <Linha key={f.forma} rotulo={TEXTOS.consignado.formas[f.forma] ?? f.forma}>
            <ValorDoRelatorio v={f.valor} />
          </Linha>
        ))}
        <Linha rotulo={T.pendente}>
          <ValorDoRelatorio v={r.pendente} />
        </Linha>
      </dl>
    </>
  );
}

function FolhaDaMaleta({ r }: { r: SituacaoDaMaleta }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <>
      <Topo r={r} subtitulo={`${T.maleta} ${r.maleta}`} />
      <dl className="relatorio-dados">
        <Linha rotulo={T.enviadaEm(dia(r.enviadaEm))}>{''}</Linha>
        <Linha
          rotulo={T.resumoDaMaleta(
            r.totalEnviada,
            r.totalNaMaleta,
            r.totalComClientes,
            r.totalVendida,
          )}
        >
          {''}
        </Linha>
      </dl>
      <table className="relatorio-tabela">
        <thead>
          <tr>
            <th scope="col">{T.colunas.ref}</th>
            <th scope="col">{T.colunas.produto}</th>
            <th scope="col" className="cs-numero">
              {T.colunas.enviada}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.naMaleta}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.comClientes}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.vendida}
            </th>
            <th scope="col" className="cs-numero">
              {T.colunas.loja}
            </th>
            <th scope="col">{T.colunas.comQuem}</th>
          </tr>
        </thead>
        <tbody>
          {r.pecas.map((p, i) => (
            <tr key={`${p.codigo}-${String(i)}`}>
              <td>{p.codigo}</td>
              <td>{p.produto}</td>
              <td className="cs-numero">{p.enviada}</td>
              <td className="cs-numero">{p.naMaleta}</td>
              <td className="cs-numero">{p.comClientes}</td>
              <td className="cs-numero">{p.vendida}</td>
              <td className="cs-numero">{p.devolvidaALoja}</td>
              <td>{p.comQuem.join('; ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <h2 className="relatorio-secao">{T.dxDvVdDaMaleta}</h2>
      <DxDvVd {...r} />
      <dl className="relatorio-conta">
        <Linha rotulo={T.valorNaMaleta}>
          <ValorDoRelatorio v={r.valorNaMaleta} />
        </Linha>
        <Linha rotulo={T.valorComClientes}>
          <ValorDoRelatorio v={r.valorComClientes} />
        </Linha>
      </dl>
    </>
  );
}

/** A folha do relatório (a mesma na tela, no papel e no PDF). */
export function FolhaDoRelatorio({ relatorio: r }: { relatorio: RelatorioDoConsignado }) {
  const T = TEXTOS.consignado.relatorios;
  return (
    <article className="relatorio-folha" aria-label={T.titulos[r.tipo] ?? r.tipo}>
      {r.tipo === 'NOTA_DE_SAIDA' ? <FolhaDaNotaDeSaida r={r} /> : null}
      {r.tipo === 'ACERTO' ? <FolhaDoAcerto r={r} /> : null}
      {r.tipo === 'SITUACAO_DO_CLIENTE' ? <FolhaDaSituacaoDoCliente r={r} /> : null}
      {r.tipo === 'PECAS_VENDIDAS' ? <FolhaDasPecasVendidas r={r} /> : null}
      {r.tipo === 'TOTAIS_DA_SEMANA' ? <FolhaDosTotais r={r} /> : null}
      {r.tipo === 'SITUACAO_DA_MALETA' ? <FolhaDaMaleta r={r} /> : null}
      <p className="relatorio-aviso">{T.aviso}</p>
    </article>
  );
}

/**
 * O relatório aberto sobre a tela, com os botões Imprimir, Salvar em PDF e Preparar para o WhatsApp. Monta do banco do
 * computador na hora (sem internet). Nada é enviado sozinho: o WhatsApp só abre a conversa com a mensagem pronta.
 */
export function RelatorioAberto({
  ponte,
  pedido,
  aoFechar,
}: {
  ponte: PonteDoCorona;
  pedido: PedidoDeRelatorio;
  aoFechar: () => void;
}) {
  const T = TEXTOS.consignado.relatorios;
  const [relatorio, setRelatorio] = useState<RelatorioDoConsignado | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [aviso, setAviso] = useState<{
    tipo: 'sucesso' | 'atencao' | 'erro';
    texto: string;
  } | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const chave = JSON.stringify(pedido);

  useEffect(() => {
    let ativo = true;
    void chamar<RelatorioDoConsignado>(ponte, {
      operacao: 'consignado.relatorio',
      relatorio: pedido,
    }).then((r) => {
      if (!ativo) return;
      if (r.ok) setRelatorio(r.dados);
      else setErro(r.mensagem);
    });
    return () => {
      ativo = false;
    };
  }, [ponte, chave]);

  function contar(r: ResultadoDeSaidaDoRelatorio, quando: 'imprimir' | 'pdf' | 'whatsapp'): void {
    if (r.ok) {
      if (quando === 'pdf' && r.arquivo)
        setAviso({ tipo: 'sucesso', texto: T.saida.salvouPdf(r.arquivo) });
      else if (quando === 'whatsapp')
        setAviso({ tipo: 'sucesso', texto: T.saida.whatsappPronto(r.pasta ?? '') });
      else setAviso(null);
      return;
    }
    switch (r.motivo) {
      case 'cancelado':
        setAviso({ tipo: 'atencao', texto: T.saida.cancelado });
        break;
      case 'sem-telefone':
        setAviso({ tipo: 'atencao', texto: T.saida.semTelefone });
        break;
      case 'sem-internet':
        setAviso({ tipo: 'atencao', texto: T.saida.semInternet(r.pasta ?? '') });
        break;
      default:
        setAviso({ tipo: 'erro', texto: T.saida.erro });
    }
  }

  async function agir(quando: 'imprimir' | 'pdf' | 'whatsapp'): Promise<void> {
    if (!relatorio) return;
    const s = textosDeSaida(relatorio);
    setOcupado(true);
    try {
      const resultado =
        quando === 'imprimir'
          ? await ponte.imprimirRelatorio()
          : quando === 'pdf'
            ? await ponte.salvarRelatorioEmPdf({ nome: s.nomeDoArquivo })
            : await ponte.prepararRelatorioNoWhatsapp({
                telefone: s.telefone,
                mensagem: s.mensagem,
                nome: s.nomeDoArquivo,
                semNumero: s.semNumero,
              });
      contar(resultado, quando);
    } catch {
      setAviso({ tipo: 'erro', texto: T.saida.erro });
    } finally {
      setOcupado(false);
    }
  }

  return createPortal(
    <div
      className="relatorio-raiz"
      role="dialog"
      aria-modal="true"
      aria-label={T.titulos[pedido.tipo] ?? pedido.tipo}
    >
      <div className="relatorio-acoes" role="group" aria-label={T.acoes}>
        <Botao
          variante="primario"
          disabled={!relatorio || ocupado}
          onClick={() => void agir('imprimir')}
        >
          {T.imprimir}
        </Botao>
        <Botao disabled={!relatorio || ocupado} onClick={() => void agir('pdf')}>
          {T.salvarPdf}
        </Botao>
        <Botao disabled={!relatorio || ocupado} onClick={() => void agir('whatsapp')}>
          {T.whatsapp}
        </Botao>
        <Botao onClick={aoFechar}>{T.fechar}</Botao>
      </div>
      {aviso ? (
        <div className="relatorio-aviso-de-saida">
          <Aviso tipo={aviso.tipo}>{aviso.texto}</Aviso>
        </div>
      ) : null}
      {erro ? (
        <div className="relatorio-aviso-de-saida">
          <Aviso tipo="erro">{erro}</Aviso>
        </div>
      ) : null}
      {!relatorio && !erro ? <p className="vendas-texto">{T.carregando}</p> : null}
      {relatorio ? <FolhaDoRelatorio relatorio={relatorio} /> : null}
    </div>,
    document.body,
  );
}

/** O botão que abre um relatório (cada tela põe o seu). */
export function BotaoDeRelatorio({
  ponte,
  pedido,
  rotulo,
  variante,
}: {
  ponte: PonteDoCorona;
  pedido: PedidoDeRelatorio;
  rotulo: string;
  variante?: 'primario' | 'secundario';
}) {
  const [aberto, setAberto] = useState(false);
  return (
    <>
      <Botao {...(variante ? { variante } : {})} onClick={() => setAberto(true)}>
        {rotulo}
      </Botao>
      {aberto ? (
        <RelatorioAberto ponte={ponte} pedido={pedido} aoFechar={() => setAberto(false)} />
      ) : null}
    </>
  );
}

/**
 * Os relatórios de um cliente (na ficha dele): a Situação do cliente e as Peças vendidas por cliente, com o período
 * escolhido (começa nos últimos 30 dias).
 */
export function RelatoriosDoCliente({
  ponte,
  clienteId,
}: {
  ponte: PonteDoCorona;
  clienteId: string;
}) {
  const T = TEXTOS.consignado.relatorios;
  const hoje = diaEmBrasilia(new Date());
  const [de, setDe] = useState(diaEmBrasilia(new Date(Date.now() - 30 * 86_400_000)));
  const [ate, setAte] = useState(hoje);
  const periodoOk = /^\d{4}-\d{2}-\d{2}$/.test(de) && /^\d{4}-\d{2}-\d{2}$/.test(ate) && de <= ate;
  return (
    <section className="consignado-relatorios-do-cliente" aria-label={T.doCliente}>
      <h2 className="vendas-subtitulo">{T.doCliente}</h2>
      <div className="consignado-botoes">
        <BotaoDeRelatorio
          ponte={ponte}
          pedido={{ tipo: 'SITUACAO_DO_CLIENTE', clienteId }}
          rotulo={T.abrir.situacaoDoCliente}
        />
      </div>
      <div className="consignado-botoes">
        <div className="cs-campo">
          <label htmlFor="relatorio-de">{T.de}</label>
          <input id="relatorio-de" type="date" value={de} onChange={(e) => setDe(e.target.value)} />
        </div>
        <div className="cs-campo">
          <label htmlFor="relatorio-ate">{T.ate}</label>
          <input
            id="relatorio-ate"
            type="date"
            value={ate}
            onChange={(e) => setAte(e.target.value)}
          />
        </div>
        {periodoOk ? (
          <BotaoDeRelatorio
            ponte={ponte}
            pedido={{ tipo: 'PECAS_VENDIDAS', clienteId, de, ate }}
            rotulo={T.abrir.pecasVendidas}
          />
        ) : (
          <p className="vendas-texto vendas-texto--pequeno">{T.periodoInvalido}</p>
        )}
      </div>
    </section>
  );
}
