import { Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import { SITUACOES_DO_ACERTO } from '../../../shared/acertos';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { CampoDeEscolha, useVendedores } from '../devolucoes/Apoio';
import { dataEHora } from '../sincronizacao/dados';
import { lerListaDeAcertos } from './dados';

const T = TEXTOS_DE_ACERTOS;
const L = T.lista;

export interface ListaDeAcertosProps {
  ponte: PonteDoCorona;
  /** Muda quando a lista precisa ser buscada de novo (ao voltar de um acerto). */
  chave: string;
  aoAbrir: (id: string) => void;
  aoComecar: () => void;
}

/** Acertos → Acertos: lista com filtro de vendedor e situação e o botão Começar acerto. */
export function ListaDeAcertos({ ponte, chave, aoAbrir, aoComecar }: ListaDeAcertosProps) {
  const [vendedorId, setVendedorId] = useState('');
  const [situacao, setSituacao] = useState('');
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () =>
      chamar(ponte, 'acertos.listar', {
        ...(vendedorId ? { vendedorId } : {}),
        ...(situacao ? { situacao } : {}),
      }),
    `acertos-${chave}-${vendedorId}-${situacao}`,
  );
  const acertos = busca.dados ? lerListaDeAcertos(busca.dados) : [];
  return (
    <section className="gestao-grupo" aria-labelledby="titulo-lista-de-acertos">
      <h2 id="titulo-lista-de-acertos" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <div className="gestao-acoes-da-linha">
        <Botao variante="primario" onClick={aoComecar}>
          {L.comecar}
        </Botao>
      </div>
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="filtro-acerto-vendedor"
          rotulo={L.filtros.vendedor}
          valor={vendedorId}
          aoMudar={setVendedorId}
          opcoes={vendedores.opcoes}
          vazio={L.filtros.todosVendedores}
        />
        <CampoDeEscolha
          id="filtro-acerto-situacao"
          rotulo={L.filtros.situacao}
          valor={situacao}
          aoMudar={setSituacao}
          opcoes={SITUACOES_DO_ACERTO.map((s) => ({ valor: s, texto: T.situacoes[s] ?? s }))}
          vazio={L.filtros.todasSituacoes}
        />
      </div>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={L.legenda}
        textoVazio={L.vazia}
        colunas={[
          { chave: 'vendedor', titulo: L.colunas.vendedor },
          { chave: 'semana', titulo: L.colunas.semana },
          { chave: 'maletas', titulo: L.colunas.maletas, numerica: true },
          { chave: 'comecou', titulo: L.colunas.comecou },
          { chave: 'situacao', titulo: L.colunas.situacao },
          { chave: 'acoes', titulo: L.colunas.acoes },
        ]}
        linhas={acertos.map((a) => ({
          vendedor: a.vendedorNome,
          semana: String(a.semanaNumero),
          maletas: a.maletas,
          comecou: dataEHora(a.iniciadoEm),
          situacao: T.situacoes[a.status] ?? a.status,
          acoes: (
            <Botao
              aria-label={L.abrirDe(a.vendedorNome, a.semanaNumero)}
              onClick={() => aoAbrir(a.id)}
            >
              {L.abrir}
            </Botao>
          ),
        }))}
      />
    </section>
  );
}
