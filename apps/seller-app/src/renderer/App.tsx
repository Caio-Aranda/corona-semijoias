import { useCallback, useEffect, useState } from 'react';
import type { AvisoDeSessao, EstadoDaSessao, PonteDoCorona } from '../shared/ponte';
import { FaixaDeAtualizacao } from './FaixaDeAtualizacao';
import { TelaAppDesatualizado } from './telas/TelaAppDesatualizado';
import { TelaDesligado } from './telas/TelaDesligado';
import { TelaConferirMaleta } from './telas/TelaConferirMaleta';
import { TelaEntrar } from './telas/TelaEntrar';
import { TelaInicial, type Destino } from './telas/TelaInicial';
import { TelaMeusClientes } from './telas/TelaMeusClientes';
import { TelaMeusNumeros } from './telas/TelaMeusNumeros';
import { TelaMinhaMaleta } from './telas/TelaMinhaMaleta';
import { TelaMinhasVendas } from './telas/TelaMinhasVendas';
import { TelaSemConexao } from './telas/TelaSemConexao';
import { TelaTrocarSenha } from './telas/TelaTrocarSenha';
import { TelaVender } from './telas/TelaVender';
import type { Maleta } from '../shared/dados';

type Tela = EstadoDaSessao | { estado: 'carregando' };

/** Onde a pessoa está depois de entrar. */
type Rota =
  | { tela: 'inicio' }
  | { tela: 'maleta' }
  | { tela: 'conferir'; maleta: Maleta }
  | { tela: 'vender' }
  | { tela: 'vendas'; voltarPara: 'vender' | 'numeros' }
  | { tela: 'clientes' }
  | { tela: 'numeros' };

const PRONTOS: readonly Destino[] = ['vender', 'maleta', 'clientes', 'numeros'];

export interface AppProps {
  ponte: PonteDoCorona;
}

/** Decide qual tela mostrar a partir do estado da sessão, que quem sabe é o processo principal. */
export function App({ ponte }: AppProps) {
  const [tela, setTela] = useState<Tela>({ estado: 'carregando' });
  const [sessaoTerminou, setSessaoTerminou] = useState(false);
  const [rota, setRota] = useState<Rota>({ tela: 'inicio' });

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
        const inicio = () => setRota({ tela: 'inicio' });
        switch (rota.tela) {
          case 'maleta':
            return (
              <TelaMinhaMaleta
                ponte={ponte}
                aoVoltar={inicio}
                aoConferir={(maleta) => setRota({ tela: 'conferir', maleta })}
              />
            );
          case 'conferir':
            return (
              <TelaConferirMaleta
                ponte={ponte}
                maleta={rota.maleta}
                aoVoltar={() => setRota({ tela: 'maleta' })}
              />
            );
          case 'vender':
            return (
              <TelaVender
                ponte={ponte}
                aoVoltar={inicio}
                aoVerVendas={() => setRota({ tela: 'vendas', voltarPara: 'vender' })}
              />
            );
          case 'vendas':
            return (
              <TelaMinhasVendas ponte={ponte} aoVoltar={() => setRota({ tela: rota.voltarPara })} />
            );
          case 'clientes':
            return <TelaMeusClientes ponte={ponte} aoVoltar={inicio} />;
          case 'numeros':
            return (
              <TelaMeusNumeros
                ponte={ponte}
                aoVoltar={inicio}
                aoVerVendas={() => setRota({ tela: 'vendas', voltarPara: 'numeros' })}
              />
            );
          case 'inicio':
            return (
              <TelaInicial
                ponte={ponte}
                nome={tela.usuario?.nome ?? null}
                prontos={PRONTOS}
                aoEscolher={(destino) => setRota({ tela: destino })}
                aoSair={async () => {
                  await ponte.sair();
                  setSessaoTerminou(false);
                  setRota({ tela: 'inicio' });
                  await conferir();
                }}
              />
            );
        }
      }
    }
  }
}
