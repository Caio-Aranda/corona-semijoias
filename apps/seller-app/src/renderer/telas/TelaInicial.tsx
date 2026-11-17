import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { AutorizacoesDoVendedor, AvisoDeAutorizacao } from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar } from '../chamadas';
import { IndicadorDeEnvio } from '../IndicadorDeEnvio';
import { TEXTOS } from '../textos';
import { diaEMes } from './devolucao/apoio';

export type Destino = 'vender' | 'maleta' | 'clientes' | 'numeros' | 'devolucao';

const BOTOES: readonly { destino: Destino; nome: string }[] = [
  { destino: 'vender', nome: 'Vender' },
  { destino: 'maleta', nome: 'Minha maleta' },
  { destino: 'clientes', nome: 'Clientes' },
  { destino: 'numeros', nome: 'Meus números' },
  // O quinto botão (tarefa 6.8; §19.7, linha de 13/11/2026).
  { destino: 'devolucao', nome: TEXTOS.devolucao.botaoInicial },
];

export interface TelaInicialProps {
  /** Para o indicador de envio; sem ela (testes de tela), o indicador não aparece. */
  ponte?: PonteDoCorona;
  /** Nome da pessoa, quando já se sabe. */
  nome: string | null;
  aoSair: () => void | Promise<void>;
  /** Abre uma das funções. Quem ainda não existe fica fora de `prontos` e mostra "Em breve". */
  aoEscolher?: (destino: Destino) => void;
  prontos?: readonly Destino[];
}

/**
 * Tela inicial do vendedor (§19.7): cinco botões grandes desde a tarefa 6.8 e os avisos das autorizações que a dona
 * respondeu. O que ainda não existe mostra "Em breve".
 */
export function TelaInicial({ ponte, nome, aoSair, aoEscolher, prontos = [] }: TelaInicialProps) {
  const [emBreve, setEmBreve] = useState<string | null>(null);
  const [avisos, setAvisos] = useState<AvisoDeAutorizacao[]>([]);

  useEffect(() => {
    if (!ponte) return;
    let ativo = true;
    void chamar<AutorizacoesDoVendedor>(ponte, { operacao: 'autorizacoes.listar' }).then((r) => {
      if (ativo && r.ok && Array.isArray(r.dados?.avisos)) setAvisos(r.dados.avisos);
    });
    return () => {
      ativo = false;
    };
  }, [ponte]);

  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Vendas"
        acoes={
          <>
            {nome ? <span>{nome} </span> : null}
            <Botao onClick={() => void aoSair()}>{TEXTOS.botoes.sair}</Botao>
          </>
        }
      />
      <main className="vendas-conteudo">
        <h1 className="vendas-titulo">{nome ? `Olá, ${nome}.` : 'Olá.'}</h1>
        {ponte ? <IndicadorDeEnvio ponte={ponte} /> : null}
        {avisos.map((a) => (
          <Aviso key={a.id} tipo={a.tipo === 'APROVADA' ? 'sucesso' : 'atencao'}>
            {a.tipo === 'APROVADA'
              ? TEXTOS.devolucao.avisoAprovada(a.clienteNome, diaEMes(a.validaAte ?? ''))
              : TEXTOS.devolucao.avisoNegada(a.clienteNome)}
          </Aviso>
        ))}
        {emBreve ? (
          <Aviso tipo="informacao" titulo="Em breve.">
            {`${emBreve} ainda está sendo preparado. Logo você poderá usar por aqui.`}
          </Aviso>
        ) : null}
        <nav className="vendas-botoes" aria-label="O que você quer fazer?">
          {BOTOES.map(({ destino, nome: nomeDoBotao }) => (
            <Botao
              key={destino}
              variante="primario"
              grande
              onClick={() => {
                if (aoEscolher && prontos.includes(destino)) aoEscolher(destino);
                else setEmBreve(nomeDoBotao);
              }}
            >
              {nomeDoBotao}
            </Botao>
          ))}
        </nav>
      </main>
    </div>
  );
}
