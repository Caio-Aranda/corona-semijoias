import { useCallback, useEffect, useState } from 'react';
import type { AvisoDeSessao, EstadoDaSessao, PonteDoCorona } from '../shared/ponte';
import { useIdioma } from './idioma';
import { FaixaDeAtualizacao } from './FaixaDeAtualizacao';
import { TelaAppDesatualizado } from './telas/TelaAppDesatualizado';
import { TelaDesligado } from './telas/TelaDesligado';
import { TelaEntrar } from './telas/TelaEntrar';
import { TelaSemConexao } from './telas/TelaSemConexao';
import { TelaTrocarSenha } from './telas/TelaTrocarSenha';
import { TelaClientes } from './telas/consignado/TelaClientes';
import { TelaCobranca } from './telas/consignado/TelaCobranca';
import { TelaMaletas } from './telas/consignado/TelaMaletas';
import { TelaNovoPedido } from './telas/consignado/TelaNovoPedido';
import { TelaPesquisa } from './telas/consignado/TelaPesquisa';
import { TelaReposicao } from './telas/consignado/TelaReposicao';
import { TelaSemanal } from './telas/consignado/TelaSemanal';
import { TelaTroca } from './telas/consignado/TelaTroca';

type Tela = EstadoDaSessao | { estado: 'carregando' };

/** Onde a pessoa está depois de entrar: a tela principal é o Gerenciamento Semanal (§23.4). */
type Rota =
  | { tela: 'semanal' }
  | { tela: 'pedido' }
  | { tela: 'cobranca'; notaId: string | null }
  | { tela: 'reposicao' }
  | { tela: 'troca' }
  | { tela: 'clientes'; cliente: string | null }
  | { tela: 'maletas' }
  | { tela: 'pesquisa'; texto: string };

export interface AppProps {
  ponte: PonteDoCorona;
}

/** Decide qual tela mostrar a partir do estado da sessão, que quem sabe é o processo principal. */
export function App({ ponte }: AppProps) {
  // Assina o idioma: trocar de idioma desenha todas as telas de novo, sem perder onde a pessoa estava.
  useIdioma();
  const [tela, setTela] = useState<Tela>({ estado: 'carregando' });
  const [sessaoTerminou, setSessaoTerminou] = useState(false);
  const [rota, setRota] = useState<Rota>({ tela: 'semanal' });

  const conferir = useCallback(async () => {
    try {
      setTela(await ponte.estouLogado());
    } catch {
      setTela({ estado: 'fora' });
    }
  }, [ponte]);

  useEffect(() => {
    void conferir();
  }, [conferir]);

  useEffect(() => {
    return ponte.aoMudarASessao((aviso: AvisoDeSessao) => {
      if (aviso.estado === 'app-desatualizado') {
        setTela({ estado: 'app-desatualizado', mensagem: aviso.mensagem ?? '' });
        return;
      }
      if (aviso.estado === 'desligando' || aviso.estado === 'desligado') {
        setTela({ estado: aviso.estado });
        return;
      }
      setSessaoTerminou(true);
      setTela({ estado: 'fora' });
    });
  }, [ponte]);

  return (
    <>
      <FaixaDeAtualizacao ponte={ponte} />
      {telaAtual()}
    </>
  );

  function telaAtual() {
    switch (tela.estado) {
      case 'carregando':
        return <main className="vendas-pagina" aria-busy="true" />;
      case 'fora':
        return (
          <TelaEntrar
            ponte={ponte}
            sessaoTerminou={sessaoTerminou}
            aoMudar={() => {
              setSessaoTerminou(false);
              void conferir();
            }}
          />
        );
      case 'entrar-sem-internet':
        return <TelaEntrar ponte={ponte} semInternet aoMudar={() => void conferir()} />;
      case 'reabrir':
        return (
          <TelaEntrar
            key="reabrir"
            ponte={ponte}
            reabrindo
            usuarioFixo={tela.usuario}
            aoMudar={() => void conferir()}
          />
        );
      case 'desligando':
        return <TelaDesligado apagado={false} />;
      case 'desligado':
        return <TelaDesligado apagado />;
      case 'trocar-senha':
        return <TelaTrocarSenha ponte={ponte} aoMudar={() => void conferir()} />;
      case 'sem-conexao':
        return <TelaSemConexao aoTentarDeNovo={() => void conferir()} />;
      case 'app-desatualizado':
        return <TelaAppDesatualizado mensagem={tela.mensagem} />;
      case 'dentro': {
        const inicio = () => setRota({ tela: 'semanal' });
        const abrirPedido = (notaId: string) => setRota({ tela: 'cobranca', notaId });
        switch (rota.tela) {
          case 'pedido':
            return <TelaNovoPedido ponte={ponte} aoVoltar={inicio} aoAbrirPedido={abrirPedido} />;
          case 'cobranca':
            return <TelaCobranca ponte={ponte} notaId={rota.notaId} aoVoltar={inicio} />;
          case 'reposicao':
            return <TelaReposicao ponte={ponte} aoVoltar={inicio} aoAbrirPedido={abrirPedido} />;
          case 'troca':
            return <TelaTroca ponte={ponte} aoVoltar={inicio} />;
          case 'clientes':
            return (
              <TelaClientes
                ponte={ponte}
                clienteInicial={rota.cliente}
                aoVoltar={inicio}
                aoAbrirPedido={abrirPedido}
              />
            );
          case 'maletas':
            return <TelaMaletas ponte={ponte} aoVoltar={inicio} />;
          case 'pesquisa':
            return (
              <TelaPesquisa
                ponte={ponte}
                texto={rota.texto}
                aoVoltar={inicio}
                aoAbrirPedido={abrirPedido}
                aoAbrirCliente={(id) => setRota({ tela: 'clientes', cliente: id })}
              />
            );
          case 'semanal':
            return (
              <TelaSemanal
                ponte={ponte}
                nome={tela.usuario?.nome ?? null}
                aoIr={(destino) =>
                  setRota(
                    destino === 'cobranca'
                      ? { tela: 'cobranca', notaId: null }
                      : destino === 'clientes'
                        ? { tela: 'clientes', cliente: null }
                        : { tela: destino },
                  )
                }
                aoAbrirPedido={abrirPedido}
                aoPesquisar={(texto) => setRota({ tela: 'pesquisa', texto })}
                aoSair={async () => {
                  await ponte.sair();
                  setSessaoTerminou(false);
                  setRota({ tela: 'semanal' });
                  await conferir();
                }}
              />
            );
        }
      }
    }
  }
}
