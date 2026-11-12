import { Aviso, Botao, JanelaDeConfirmacao, Tabela } from '@corona/ui';
import { useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_SINCRONIZACAO } from '../../textos-sincronizacao';
import { lerVendedoresParaEscolher } from '../maletas/dados';
import { dataEHora, lerComputadores, type Computador } from './dados';

const T = TEXTOS_DE_SINCRONIZACAO.computadores;

type Acao = { tipo: 'trocar' | 'bloquear'; computador: Computador };

/** Computadores do vendedor (§19.5): situação, último contato, o que falta enviar, trocar e bloquear com confirmação. */
export function TelaComputadores({ ponte }: { ponte: PonteDoCorona }) {
  const [vendedorId, setVendedorId] = useState('');
  const [acao, setAcao] = useState<Acao | null>(null);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [feito, setFeito] = useState<string | null>(null);
  const trava = useRef(false);
  const vendedores = useBusca(() => chamar(ponte, 'vendedores.escolha'), 'computadores-vendedores');
  const busca = useBusca(
    () =>
      vendedorId
        ? chamar(ponte, 'sincronizacao.aparelhos', { vendedorId })
        : Promise.resolve({ ok: true as const, dados: [] as unknown }),
    `computadores-${vendedorId}`,
  );
  const opcoes = vendedores.dados ? lerVendedoresParaEscolher(vendedores.dados) : [];
  const nomeDoVendedor = opcoes.find((o) => o.id === vendedorId)?.nome ?? '';
  const computadores = busca.dados ? lerComputadores(busca.dados) : [];

  async function confirmar() {
    if (!acao || trava.current) return;
    trava.current = true;
    setOcupado(true);
    setErro(null);
    const r = await chamar(
      ponte,
      acao.tipo === 'trocar' ? 'sincronizacao.liberarAparelho' : 'sincronizacao.bloquearAparelho',
      { id: acao.computador.id },
    );
    trava.current = false;
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      setAcao(null);
      return;
    }
    setFeito(acao.tipo === 'trocar' ? T.trocado : T.bloqueado);
    setAcao(null);
    busca.recarregar();
  }

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <div className="cs-campo">
          <label htmlFor="computadores-vendedor">{T.vendedor}</label>
          <select
            id="computadores-vendedor"
            value={vendedorId}
            onChange={(e) => {
              setVendedorId(e.target.value);
              setFeito(null);
              setErro(null);
            }}
          >
            <option value="">{T.escolhaVendedor}</option>
            {opcoes.map((o) => (
              <option key={o.id} value={o.id}>
                {o.nome}
              </option>
            ))}
          </select>
        </div>
      </div>
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {vendedores.erro ? (
        <AvisoDeErro erro={vendedores.erro} aoTentarDeNovo={vendedores.recarregar} />
      ) : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {erro ? <AvisoDeErro erro={erro} aoAtualizar={busca.recarregar} /> : null}
      {vendedorId ? (
        <Tabela
          legenda={T.legenda}
          textoVazio={T.vazio}
          colunas={[
            { chave: 'situacao', titulo: T.colunas.situacao },
            { chave: 'contato', titulo: T.colunas.ultimoContato },
            { chave: 'porEnviar', titulo: T.colunas.porEnviar, numerica: true },
            { chave: 'ultimoEnvio', titulo: T.colunas.ultimoEnvio },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={computadores.map((c) => ({
            situacao: TEXTOS_DE_SINCRONIZACAO.situacoes[c.situacao] ?? c.situacao,
            contato: dataEHora(c.ultimoContatoEm) || T.semContato,
            porEnviar: c.aguardandoEnvio ?? 0,
            ultimoEnvio: dataEHora(c.ultimoEnvioCompletoEm) || T.nuncaEnviouTudo,
            acoes: (
              <div className="gestao-acoes-da-linha">
                {c.situacao === 'AGUARDANDO_LIBERACAO' ? (
                  <Botao
                    aria-label={T.trocarDe(nomeDoVendedor)}
                    onClick={() => setAcao({ tipo: 'trocar', computador: c })}
                  >
                    {T.trocar}
                  </Botao>
                ) : null}
                {c.situacao !== 'BLOQUEADO' ? (
                  <Botao
                    aria-label={T.bloquearDe(nomeDoVendedor)}
                    onClick={() => setAcao({ tipo: 'bloquear', computador: c })}
                  >
                    {T.bloquear}
                  </Botao>
                ) : null}
              </div>
            ),
          }))}
        />
      ) : null}
      {acao ? (
        <JanelaDeConfirmacao
          titulo={
            acao.tipo === 'trocar'
              ? T.confirmarTroca.titulo(nomeDoVendedor)
              : T.confirmarBloqueio.titulo
          }
          rotuloConfirmar={
            acao.tipo === 'trocar'
              ? ocupado
                ? T.trocando
                : T.trocar
              : ocupado
                ? T.bloqueando
                : T.bloquear
          }
          rotuloCancelar={T.cancelar}
          perigosa={acao.tipo === 'bloquear'}
          ocupado={ocupado}
          aoCancelar={() => setAcao(null)}
          aoConfirmar={() => void confirmar()}
        >
          {acao.tipo === 'trocar' ? T.confirmarTroca.texto : T.confirmarBloqueio.texto}
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}
