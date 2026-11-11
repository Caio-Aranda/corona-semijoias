import { Aviso, Botao, Campo, EstadoDaLista, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { Venda } from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, cotacaoDaVenda, Dinheiro, novoId } from '../chamadas';
import { dataParaTela } from '../moedas';
import { TEXTOS } from '../textos';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaMinhasVendasProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
}

/** Minhas vendas: a lista, o detalhe de cada venda e o estorno (a venda nunca é editada nem apagada). */
export function TelaMinhasVendas({ ponte, aoVoltar }: TelaMinhasVendasProps) {
  const T = TEXTOS.vendas;
  const [vendas, setVendas] = useState<Venda[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [aberta, setAberta] = useState<Venda | null>(null);
  const [estornando, setEstornando] = useState(false);
  const [motivo, setMotivo] = useState('');
  const [erroDoMotivo, setErroDoMotivo] = useState<string | undefined>();
  const [problema, setProblema] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [aviso, setAviso] = useState<string | null>(null);
  // O mesmo estorno reenviado (queda de internet) tem o mesmo id: o servidor não estorna duas vezes.
  const idDoEstorno = useRef(novoId());

  const carregar = useCallback(async () => {
    setErro(null);
    const r = await chamar<Venda[]>(ponte, { operacao: 'vendas.listar' });
    if (r.ok) setVendas(r.dados);
    else setErro(r.mensagem);
  }, [ponte]);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  async function estornar() {
    if (!aberta) return;
    if (!motivo.trim()) {
      setErroDoMotivo(T.faltaMotivo);
      return;
    }
    setErroDoMotivo(undefined);
    setEnviando(true);
    const r = await chamar<Venda>(ponte, {
      operacao: 'vendas.estornar',
      id: aberta.id,
      estornoId: idDoEstorno.current,
      motivo: motivo.trim(),
    });
    setEnviando(false);
    if (!r.ok) {
      setProblema(r.mensagem);
      setEstornando(false);
      return;
    }
    setProblema(null);
    setEstornando(false);
    setMotivo('');
    idDoEstorno.current = novoId();
    setAberta(r.dados);
    setAviso(T.estornada);
    void carregar();
  }

  if (aberta) {
    // Os valores da venda, pela cotação do dia dela (ADR-0006), nunca pela de hoje.
    const cotacao = cotacaoDaVenda(aberta);
    return (
      <PaginaDoVendedor
        titulo={`${T.detalhe}: ${aberta.clienteNome}`}
        aoVoltar={() => setAberta(null)}
      >
        {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
        {problema ? <Aviso tipo="erro">{problema}</Aviso> : null}
        <p className="vendas-texto">
          {dataParaTela(aberta.vendidaEm)} · {T.situacao[aberta.status] ?? aberta.status}
        </p>
        <Tabela
          legenda="Peças da venda"
          colunas={[
            { chave: 'peca', titulo: TEXTOS.venda.peca },
            { chave: 'qtd', titulo: TEXTOS.venda.qtd, numerica: true },
            { chave: 'preco', titulo: TEXTOS.venda.preco, numerica: true },
            { chave: 'desconto', titulo: TEXTOS.venda.desconto, numerica: true },
            { chave: 'total', titulo: TEXTOS.venda.total, numerica: true },
          ]}
          linhas={aberta.itens.map((i) => ({
            peca: i.produto.nome,
            qtd: i.quantidade,
            preco: <Dinheiro reais={i.precoUnitario} cotacao={cotacao} />,
            desconto: <Dinheiro reais={i.desconto} cotacao={cotacao} />,
            total: <Dinheiro reais={i.total} cotacao={cotacao} />,
          }))}
        />
        <p className="vendas-total">
          {TEXTOS.venda.total}: <Dinheiro reais={aberta.total} cotacao={cotacao} />
        </p>
        <p className="vendas-texto">
          {T.pago}: <Dinheiro reais={aberta.pago} cotacao={cotacao} />
          {' · '}
          {T.aReceber}: <Dinheiro reais={aberta.aReceber} cotacao={cotacao} />
        </p>
        {aberta.pagamentos.length > 0 ? (
          <Tabela
            legenda={T.pagamentos}
            colunas={[
              { chave: 'forma', titulo: T.pagamentos },
              { chave: 'valor', titulo: TEXTOS.venda.total, numerica: true },
            ]}
            linhas={aberta.pagamentos.map((p) => ({
              forma: `${p.tipo === 'REEMBOLSO' ? `${T.reembolso}: ` : ''}${TEXTOS.venda.formas[p.forma] ?? p.forma}`,
              valor: <Dinheiro reais={p.valor} cotacao={cotacao} />,
            }))}
          />
        ) : null}
        {aberta.status === 'ESTORNADA' && aberta.motivoDoEstorno ? (
          <p className="vendas-texto">
            {T.motivoDoEstorno}: {aberta.motivoDoEstorno}
          </p>
        ) : null}
        {aberta.status === 'CONCLUIDA' ? (
          <Botao variante="perigoso" grande onClick={() => setEstornando(true)}>
            {T.estornar}
          </Botao>
        ) : null}
        {estornando ? (
          <JanelaDeConfirmacao
            titulo={T.estornoTitulo}
            rotuloConfirmar={T.estornar}
            perigosa
            ocupado={enviando}
            aoConfirmar={() => void estornar()}
            aoCancelar={() => setEstornando(false)}
          >
            <p>{T.estornoTexto}</p>
            <Campo
              rotulo={T.motivo}
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
              {...(erroDoMotivo ? { erro: erroDoMotivo } : {})}
              autoComplete="off"
              autoFocus
            />
          </JanelaDeConfirmacao>
        ) : null}
      </PaginaDoVendedor>
    );
  }

  return (
    <PaginaDoVendedor titulo={T.titulo} aoVoltar={aoVoltar}>
      <EstadoDaLista
        carregando={vendas === null && erro === null}
        erro={erro}
        textoCarregando={T.textoCarregando}
        aoTentarDeNovo={() => void carregar()}
        rotuloTentarDeNovo={TEXTOS.tentarDeNovo}
      >
        {vendas !== null && vendas.length === 0 ? <p className="vendas-texto">{T.vazia}</p> : null}
        {vendas !== null && vendas.length > 0 ? (
          <Tabela
            legenda={T.legenda}
            colunas={[
              { chave: 'data', titulo: T.colunas.data },
              { chave: 'cliente', titulo: T.colunas.cliente },
              { chave: 'total', titulo: T.colunas.total, numerica: true },
              { chave: 'situacao', titulo: T.colunas.situacao },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={vendas.map((v) => ({
              data: dataParaTela(v.vendidaEm),
              cliente: v.clienteNome,
              total: <Dinheiro reais={v.total} cotacao={cotacaoDaVenda(v)} />,
              situacao: T.situacao[v.status] ?? v.status,
              acoes: (
                <Botao
                  aria-label={`${T.ver}: ${v.clienteNome}`}
                  onClick={() => {
                    setAviso(null);
                    setProblema(null);
                    setAberta(v);
                  }}
                >
                  {T.ver}
                </Botao>
              ),
            }))}
          />
        ) : null}
      </EstadoDaLista>
    </PaginaDoVendedor>
  );
}
