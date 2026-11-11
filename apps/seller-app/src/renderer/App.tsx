import { useCallback, useEffect, useState } from 'react';
import type { AvisoDeSessao, EstadoDaSessao, PonteDoCorona } from '../shared/ponte';
import { FaixaDeAtualizacao } from './FaixaDeAtualizacao';
import { TelaAppDesatualizado } from './telas/TelaAppDesatualizado';
import { TelaEntrar } from './telas/TelaEntrar';
import { TelaInicial } from './telas/TelaInicial';
import { TelaSemConexao } from './telas/TelaSemConexao';
import { TelaTrocarSenha } from './telas/TelaTrocarSenha';

type Tela = EstadoDaSessao | { estado: 'carregando' };

export interface AppProps {
  ponte: PonteDoCorona;
}

/** Decide qual tela mostrar a partir do estado da sessão, que quem sabe é o processo principal. */
export function App({ ponte }: AppProps) {
  const [tela, setTela] = useState<Tela>({ estado: 'carregando' });
  const [sessaoTerminou, setSessaoTerminou] = useState(false);

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
      case 'trocar-senha':
        return <TelaTrocarSenha ponte={ponte} aoMudar={() => void conferir()} />;
      case 'sem-conexao':
        return <TelaSemConexao aoTentarDeNovo={() => void conferir()} />;
      case 'app-desatualizado':
        return <TelaAppDesatualizado mensagem={tela.mensagem} />;
      case 'dentro':
        return (
          <TelaInicial
            nome={tela.usuario?.nome ?? null}
            aoSair={async () => {
              await ponte.sair();
              setSessaoTerminou(false);
              await conferir();
            }}
          />
        );
    }
  }
}
