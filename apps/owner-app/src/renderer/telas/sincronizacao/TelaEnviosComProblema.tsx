import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_SINCRONIZACAO } from '../../textos-sincronizacao';
import { PedirMotivo } from '../maletas/PedirMotivo';
import { dataEHora, ehCpfRepetido, lerEnvios, type EnvioComProblema } from './dados';

const T = TEXTOS_DE_SINCRONIZACAO.envios;

function textoDe(dados: Record<string, unknown> | null, campo: string): string {
  const v = dados?.[campo];
  return typeof v === 'string' ? v : '';
}

/** Envios com problema: Tentar de novo e Descartar; os de CPF repetido ficam numa lista à parte. */
export function TelaEnviosComProblema({ ponte }: { ponte: PonteDoCorona }) {
  const busca = useBusca(() => chamar(ponte, 'sincronizacao.envios'), 'envios-com-problema');
  const [descartando, setDescartando] = useState<EnvioComProblema | null>(null);
  const [tentando, setTentando] = useState<string | null>(null);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [feito, setFeito] = useState<string | null>(null);
  const todos = busca.dados ? lerEnvios(busca.dados) : [];
  const repetidos = todos.filter(ehCpfRepetido);
  const outros = todos.filter((e) => !ehCpfRepetido(e));

  async function tentarDeNovo(e: EnvioComProblema) {
    if (tentando) return;
    setTentando(e.id);
    setErro(null);
    setFeito(null);
    const r = await chamar(ponte, 'sincronizacao.tentarDeNovo', { id: e.id });
    setTentando(null);
    if (!r.ok) setErro(r.erro);
    else setFeito(T.tentouEDeuCerto);
    busca.recarregar();
  }

  const acoes = (e: EnvioComProblema, repetido: boolean) => {
    const quem = e.vendedorNome;
    return (
      <div className="gestao-acoes-da-linha">
        {!repetido && e.resultado === 'RECUSADO' ? (
          <Botao
            aria-label={T.tentarDe(quem)}
            disabled={tentando !== null}
            onClick={() => void tentarDeNovo(e)}
          >
            {tentando === e.id ? T.tentando : T.tentar}
          </Botao>
        ) : null}
        <Botao aria-label={T.descartarDe(quem)} onClick={() => setDescartando(e)}>
          {T.descartar}
        </Botao>
      </div>
    );
  };

  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.titulo}</h1>
      <p className="gestao-texto">{T.ajuda}</p>
      {feito ? <Aviso tipo="sucesso">{feito}</Aviso> : null}
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {erro ? <AvisoDeErro erro={erro} aoAtualizar={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazio}
        colunas={[
          { chave: 'oQue', titulo: T.colunas.oQue },
          { chave: 'vendedor', titulo: T.colunas.vendedor },
          { chave: 'quando', titulo: T.colunas.quando },
          { chave: 'motivo', titulo: T.colunas.motivo },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={outros.map((e) => ({
          oQue: T.tipos[e.tipo] ?? e.tipo,
          vendedor: e.vendedorNome,
          quando: dataEHora(e.recebidoEm),
          motivo: e.mensagem,
          acoes: acoes(e, false),
        }))}
      />
      <section className="gestao-grupo" aria-labelledby="titulo-cpf-repetido">
        <h2 id="titulo-cpf-repetido" className="gestao-subtitulo">
          {T.cpf.titulo}
        </h2>
        <p className="gestao-texto">{T.cpf.ajuda}</p>
        <Tabela
          legenda={T.cpf.legenda}
          textoVazio={T.cpf.vazio}
          colunas={[
            { chave: 'nome', titulo: T.cpf.colunas.nome },
            { chave: 'cpf', titulo: T.cpf.colunas.cpf },
            { chave: 'vendedor', titulo: T.colunas.vendedor },
            { chave: 'quando', titulo: T.colunas.quando },
            { chave: 'acoes', titulo: T.colunas.acoes },
          ]}
          linhas={repetidos.map((e) => ({
            nome: textoDe(e.dados, 'nome'),
            cpf: textoDe(e.dados, 'cpf'),
            vendedor: e.vendedorNome,
            quando: dataEHora(e.recebidoEm),
            acoes: acoes(e, true),
          }))}
        />
      </section>
      {descartando ? (
        <PedirMotivo
          titulo={T.confirmarDescarte.titulo}
          texto={T.confirmarDescarte.texto}
          rotuloDoMotivo={T.confirmarDescarte.rotuloDoMotivo}
          avisoDoMotivo={T.confirmarDescarte.avisoDoMotivo}
          rotuloConfirmar={T.confirmarDescarte.descartar}
          rotuloCancelar={T.confirmarDescarte.cancelar}
          executar={(motivo) =>
            chamar(ponte, 'sincronizacao.descartarEnvio', { id: descartando.id, motivo })
          }
          aoFeito={() => {
            setDescartando(null);
            busca.recarregar();
          }}
          aoCancelar={() => setDescartando(null)}
        />
      ) : null}
    </div>
  );
}
