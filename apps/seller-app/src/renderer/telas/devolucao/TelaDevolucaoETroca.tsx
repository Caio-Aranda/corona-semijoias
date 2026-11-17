import { Aviso, Botao } from '@corona/ui';
import { Campo } from '../../componentes-do-vendedor';
import { useState, type FormEvent } from 'react';
import type { VendaComAPeca } from '../../../shared/dados';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../chamadas';
import { dataParaTela } from '../../moedas';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { diaEMes, FotoPequena } from './apoio';
import { FormularioDeDevolucao } from './FormularioDeDevolucao';
import { FormularioDeTroca } from './FormularioDeTroca';
import { ListaDeDevolucoesETrocas } from './ListaDeDevolucoesETrocas';

/** Por onde a pessoa entrou: o botão da tela inicial, ou Devolver/Trocar na ficha da venda (Minhas vendas). */
export type InicioDaDevolucao =
  { modo: 'buscar' } | { modo: 'devolver' | 'trocar'; vendaId: string } | { modo: 'lista' };

export interface TelaDevolucaoETrocaProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
  inicio?: InicioDaDevolucao;
}

/**
 * Devolução e troca (tarefa 6.8): ler o código da peça que o cliente trouxe, ver as vendas com ela e escolher Devolver
 * ou Trocar. Tudo funciona igual com e sem internet: o computador guarda e envia depois.
 */
export function TelaDevolucaoETroca({
  ponte,
  aoVoltar,
  inicio = { modo: 'buscar' },
}: TelaDevolucaoETrocaProps) {
  const T = TEXTOS.devolucao;
  const [onde, setOnde] = useState<InicioDaDevolucao>(inicio);
  const [codigo, setCodigo] = useState('');
  const [buscando, setBuscando] = useState(false);
  const [vendas, setVendas] = useState<VendaComAPeca[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  // Quem entrou pela ficha da venda volta para ela; quem entrou pela busca volta para a busca.
  const voltarDoFormulario = () =>
    inicio.modo === 'buscar' ? setOnde({ modo: 'buscar' }) : aoVoltar();

  async function buscar(e: FormEvent) {
    e.preventDefault();
    const lido = codigo.trim();
    if (!lido || buscando) return;
    setBuscando(true);
    const r = await chamar<VendaComAPeca[]>(ponte, {
      operacao: 'devolucoes.vendasComAPeca',
      codigo: lido,
    });
    setBuscando(false);
    if (!r.ok) {
      setErro(r.mensagem);
      return;
    }
    setErro(null);
    setVendas(r.dados);
  }

  if (onde.modo === 'devolver')
    return (
      <FormularioDeDevolucao
        ponte={ponte}
        vendaId={onde.vendaId}
        aoVoltar={voltarDoFormulario}
        aoVerLista={() => setOnde({ modo: 'lista' })}
      />
    );
  if (onde.modo === 'trocar')
    return (
      <FormularioDeTroca
        ponte={ponte}
        vendaId={onde.vendaId}
        aoVoltar={voltarDoFormulario}
        aoVerLista={() => setOnde({ modo: 'lista' })}
      />
    );
  if (onde.modo === 'lista')
    return (
      <ListaDeDevolucoesETrocas
        ponte={ponte}
        aoVoltar={() => (inicio.modo === 'lista' ? aoVoltar() : setOnde({ modo: 'buscar' }))}
      />
    );

  const peca = vendas?.[0]?.peca ?? null;
  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <p className="vendas-texto">{T.ajuda}</p>
      <form className="vendas-leitor" onSubmit={(e) => void buscar(e)}>
        <Campo
          rotulo={T.codigo}
          value={codigo}
          onChange={(e) => setCodigo(e.target.value)}
          autoFocus
          autoComplete="off"
          spellCheck={false}
        />
        <Botao type="submit" variante="primario" disabled={buscando || !codigo.trim()}>
          {T.buscar}
        </Botao>
      </form>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
      {buscando ? (
        <p className="vendas-texto" aria-busy="true">
          {T.buscando}
        </p>
      ) : null}
      {vendas !== null && vendas.length === 0 ? (
        <Aviso tipo="informacao">{T.nenhumaVenda}</Aviso>
      ) : null}
      {peca ? (
        <p className="vendas-busca-item">
          <FotoPequena ponte={ponte} produto={peca} />
          <strong>{peca.nome}</strong>
        </p>
      ) : null}
      {vendas !== null && vendas.length > 0 ? (
        <table className="cs-tabela">
          <caption>{T.vendasComAPeca}</caption>
          <thead>
            <tr>
              <th scope="col">{T.colunas.vendidaEm}</th>
              <th scope="col">{T.colunas.cliente}</th>
              <th scope="col">{T.colunas.vendidas}</th>
              <th scope="col">{T.colunas.jaDevolvidas}</th>
              <th scope="col">{T.colunas.podemVoltar}</th>
              <th scope="col">{T.colunas.garantia}</th>
              <th scope="col">{T.colunas.acoes}</th>
            </tr>
          </thead>
          <tbody>
            {vendas.map((v) => {
              const dia = dataParaTela(v.vendidaEm);
              return (
                <tr key={v.vendaId}>
                  <td>{dia}</td>
                  <td>{v.clienteNome}</td>
                  <td>{v.vendidas}</td>
                  <td>{v.jaDevolvidas}</td>
                  <td>{v.podemVoltar}</td>
                  <td>{T.garantia(diaEMes(v.desistenciaAte), diaEMes(v.defeitoAte))}</td>
                  <td>
                    {v.bloqueio ? (
                      <small>{v.bloqueio}</small>
                    ) : v.podemVoltar > 0 ? (
                      <span className="vendas-quantidade">
                        <Botao
                          aria-label={T.devolverDe(v.clienteNome, dia)}
                          onClick={() => setOnde({ modo: 'devolver', vendaId: v.vendaId })}
                        >
                          {T.devolver}
                        </Botao>
                        <Botao
                          aria-label={T.trocarDe(v.clienteNome, dia)}
                          onClick={() => setOnde({ modo: 'trocar', vendaId: v.vendaId })}
                        >
                          {T.trocar}
                        </Botao>
                      </span>
                    ) : null}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : null}
      <Botao onClick={() => setOnde({ modo: 'lista' })}>{T.minhas}</Botao>
    </PaginaDoVendedor>
  );
}
