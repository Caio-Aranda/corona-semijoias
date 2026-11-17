import { Aviso, Botao } from '@corona/ui';
import { Campo, EstadoDaLista, Tabela } from '../componentes-do-vendedor';
import { useCallback, useEffect, useState } from 'react';
import type {
  Cliente,
  CreditosDoCliente,
  Devedor,
  RelatorioAReceber,
  Venda,
} from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, cotacaoDaVenda, Dinheiro, DinheiroConvertido } from '../chamadas';
import { AvisoDosDados } from '../IndicadorDeEnvio';
import { dataParaTela } from '../moedas';
import { TEXTOS } from '../textos';
import { reais } from './devolucao/apoio';
import { FormularioDeCliente } from './FormularioDeCliente';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaMeusClientesProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
}

/** Clientes da loja: busca no diretório, cadastro e a ficha de cada um (o que ele deve e as compras feitas com este vendedor). */
export function TelaMeusClientes({ ponte, aoVoltar }: TelaMeusClientesProps) {
  const T = TEXTOS.clientes;
  const [busca, setBusca] = useState('');
  const [clientes, setClientes] = useState<Cliente[] | null>(null);
  const [devedores, setDevedores] = useState<Record<string, Devedor>>({});
  const [erro, setErro] = useState<string | null>(null);
  const [cadastrando, setCadastrando] = useState(false);
  const [editando, setEditando] = useState(false);
  const [aberto, setAberto] = useState<Cliente | null>(null);
  const [vendas, setVendas] = useState<Venda[] | null>(null);
  const [credito, setCredito] = useState<string | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  const carregar = useCallback(async () => {
    setErro(null);
    const r = await chamar<Cliente[]>(ponte, {
      operacao: 'clientes.listar',
      ...(busca.trim() ? { busca: busca.trim() } : {}),
    });
    if (r.ok) setClientes(r.dados);
    else setErro(r.mensagem);
  }, [ponte, busca]);

  // A busca espera a pessoa parar de digitar.
  useEffect(() => {
    const espera = setTimeout(() => void carregar(), 250);
    return () => clearTimeout(espera);
  }, [carregar]);

  useEffect(() => {
    let ativo = true;
    void chamar<RelatorioAReceber>(ponte, { operacao: 'relatorios.aReceber' }).then((r) => {
      if (!ativo || !r.ok || !Array.isArray(r.dados.clientes)) return;
      setDevedores(Object.fromEntries(r.dados.clientes.map((d) => [d.clienteId, d])));
    });
    return () => {
      ativo = false;
    };
  }, [ponte]);

  useEffect(() => {
    if (!aberto) return;
    let ativo = true;
    setVendas(null);
    void chamar<Venda[]>(ponte, { operacao: 'vendas.listar', clienteId: aberto.id }).then((r) => {
      if (ativo && r.ok) setVendas(r.dados);
    });
    // O crédito do cliente com este vendedor (tarefa 6.8): só os em aberto.
    setCredito(null);
    void chamar<CreditosDoCliente>(ponte, {
      operacao: 'creditos.doCliente',
      clienteId: aberto.id,
    }).then((r) => {
      if (ativo && r.ok && typeof r.dados?.total === 'string' && r.dados.total !== '0.00')
        setCredito(r.dados.total);
    });
    return () => {
      ativo = false;
    };
  }, [ponte, aberto]);

  if (cadastrando) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={() => setCadastrando(false)}>
        <FormularioDeCliente
          ponte={ponte}
          aoCancelar={() => setCadastrando(false)}
          aoSalvar={(novo) => {
            setCadastrando(false);
            setAviso(T.salvo);
            setAberto(novo);
            void carregar();
          }}
        />
      </PaginaDoVendedor>
    );
  }

  if (aberto && editando) {
    return (
      <PaginaDoVendedor
        ponte={ponte}
        titulo={`${T.editar}: ${aberto.nome}`}
        aoVoltar={() => setEditando(false)}
      >
        <FormularioDeCliente
          ponte={ponte}
          editar={aberto}
          aoCancelar={() => setEditando(false)}
          aoSalvar={(atualizado) => {
            setEditando(false);
            setAviso(T.atualizado);
            setAberto(atualizado);
            void carregar();
          }}
        />
      </PaginaDoVendedor>
    );
  }

  if (aberto) {
    const deve = devedores[aberto.id];
    return (
      <PaginaDoVendedor
        ponte={ponte}
        titulo={`${T.detalhe}: ${aberto.nome}`}
        aoVoltar={() => setAberto(null)}
      >
        {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
        <ul className="vendas-lista vendas-lista--dados">
          {aberto.telefone ? (
            <li>
              {T.contato}: {aberto.telefone}
            </li>
          ) : null}
          {aberto.whatsapp ? <li>WhatsApp: {aberto.whatsapp}</li> : null}
          {aberto.cpf ? (
            <li>
              {T.cpf}: {aberto.cpf}
            </li>
          ) : null}
          {aberto.dataNascimento ? (
            <li>
              {T.nascimento}: {dataParaTela(aberto.dataNascimento)}
            </li>
          ) : null}
          {aberto.observacoes ? (
            <li>
              {T.observacoes}: {aberto.observacoes}
            </li>
          ) : null}
        </ul>
        <p className="vendas-total">
          {TEXTOS.vendas.aReceber}: <AReceber devedor={deve} />
        </p>
        {credito ? <p className="vendas-texto">{T.creditoEmAberto(reais(credito))}</p> : null}
        {aberto.meu ? (
          <Botao
            onClick={() => {
              setAviso(null);
              setEditando(true);
            }}
          >
            {T.editar}
          </Botao>
        ) : (
          <p className="vendas-texto vendas-texto--pequeno">{T.soQuemAtende}</p>
        )}
        <h2 className="vendas-subtitulo">{T.vendasDoCliente}</h2>
        {vendas === null ? (
          <p className="vendas-texto" aria-busy="true">
            {TEXTOS.carregando}
          </p>
        ) : (
          <Tabela
            legenda={T.vendasDoCliente}
            textoVazio={T.semVendas}
            colunas={[
              { chave: 'data', titulo: TEXTOS.vendas.colunas.data },
              { chave: 'total', titulo: TEXTOS.vendas.colunas.total, numerica: true },
              { chave: 'situacao', titulo: TEXTOS.vendas.colunas.situacao },
            ]}
            linhas={vendas.map((v) => ({
              data: dataParaTela(v.vendidaEm),
              total: <Dinheiro reais={v.total} cotacao={cotacaoDaVenda(v)} />,
              situacao: TEXTOS.vendas.situacao[v.status] ?? v.status,
            }))}
          />
        )}
      </PaginaDoVendedor>
    );
  }

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <AvisoDosDados ponte={ponte} />
      <Campo
        rotulo={T.buscar}
        value={busca}
        onChange={(e) => setBusca(e.target.value)}
        autoComplete="off"
        autoFocus
      />
      <EstadoDaLista
        carregando={clientes === null && erro === null}
        erro={erro}
        textoCarregando={T.textoCarregando}
        aoTentarDeNovo={() => void carregar()}
        rotuloTentarDeNovo={TEXTOS.tentarDeNovo}
      >
        {clientes !== null && clientes.length === 0 ? (
          <p className="vendas-texto">{busca.trim() ? T.nenhum : T.vazio}</p>
        ) : null}
        {clientes !== null && clientes.length > 0 ? (
          <Tabela
            legenda={T.legenda}
            colunas={[
              { chave: 'nome', titulo: T.colunas.nome },
              { chave: 'telefone', titulo: T.colunas.telefone },
              { chave: 'cidade', titulo: T.colunas.cidade },
              { chave: 'aReceber', titulo: T.colunas.aReceber, numerica: true },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={clientes.map((c) => ({
              nome: c.nome,
              telefone: c.telefone ?? c.whatsapp ?? '',
              cidade: c.cidade ?? '',
              aReceber: devedores[c.id] ? <AReceber devedor={devedores[c.id]} /> : '',
              acoes: (
                <Botao
                  aria-label={`${T.ver}: ${c.nome}`}
                  onClick={() => {
                    setAviso(null);
                    setAberto(c);
                  }}
                >
                  {T.ver}
                </Botao>
              ),
            }))}
          />
        ) : null}
      </EstadoDaLista>
      <Botao variante="primario" grande onClick={() => setCadastrando(true)}>
        {T.cadastrar}
      </Botao>
    </PaginaDoVendedor>
  );
}

/**
 * O que o cliente ainda deve, como o servidor somou: cada venda pela cotação do dia dela e cada pagamento pela do dia em
 * que foi pago (ADR-0006, item 10). Quem não deve nada aparece com zero.
 */
function AReceber({ devedor }: { devedor: Devedor | undefined }) {
  if (!devedor) return <DinheiroConvertido reais="0.00" dolar="0.00" guarani="0" />;
  return (
    <DinheiroConvertido
      reais={devedor.aReceber}
      dolar={devedor.aReceberEmDolar}
      guarani={devedor.aReceberEmGuarani}
    />
  );
}
