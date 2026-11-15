import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import { SITUACOES_DA_PENDENCIA } from '../../../shared/acertos';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { CampoDeEscolha, useVendedores } from '../devolucoes/Apoio';
import { ResolverPendencia } from './ResolverPendencia';
import { ValorDoAcerto } from './Valor';
import { lerPendencias, type PendenciaDoAcerto } from './pendencias';

const T = TEXTOS_DE_ACERTOS.pendencias;
const F = TEXTOS_DE_ACERTOS.fechada.pendencias;

/** Acerto → Pendências: o que ficou em aberto nos acertos; só a pendência em aberto pode ser resolvida aqui. */
export function TelaPendencias({ ponte }: { ponte: PonteDoCorona }) {
  const [vendedorId, setVendedorId] = useState('');
  const [status, setStatus] = useState('');
  const [volta, setVolta] = useState(0);
  const [resolvendo, setResolvendo] = useState<PendenciaDoAcerto | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () =>
      chamar(ponte, 'pendencias.listar', {
        ...(vendedorId ? { vendedorId } : {}),
        ...(status ? { status } : {}),
      }),
    `pendencias-${volta}-${vendedorId}-${status}`,
  );
  const pendencias = busca.dados ? lerPendencias(busca.dados) : [];
  const nomeDe = (p: PendenciaDoAcerto) => p.produtoNome || F.tipos[p.tipo] || F.semPeca;

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <p className="gestao-texto">{T.cobrar}</p>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="filtro-pendencia-vendedor"
          rotulo={T.filtros.vendedor}
          valor={vendedorId}
          aoMudar={setVendedorId}
          opcoes={vendedores.opcoes}
          vazio={T.filtros.todosVendedores}
        />
        <CampoDeEscolha
          id="filtro-pendencia-situacao"
          rotulo={T.filtros.situacao}
          valor={status}
          aoMudar={setStatus}
          opcoes={SITUACOES_DA_PENDENCIA.map((s) => ({ valor: s, texto: F.situacoes[s] ?? s }))}
          vazio={T.filtros.todasSituacoes}
        />
      </div>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      <Tabela
        legenda={T.legenda}
        textoVazio={T.vazia}
        colunas={[
          { chave: 'vendedor', titulo: T.colunas.vendedor },
          { chave: 'peca', titulo: T.colunas.peca },
          { chave: 'tipo', titulo: T.colunas.tipo },
          { chave: 'quantidade', titulo: T.colunas.quantidade, numerica: true },
          { chave: 'valor', titulo: T.colunas.valor, numerica: true },
          { chave: 'situacao', titulo: T.colunas.situacao },
          { chave: 'acoes', titulo: T.colunas.acoes },
        ]}
        linhas={pendencias.map((p) => ({
          vendedor: p.vendedorNome,
          peca: p.produtoNome || F.semPeca,
          tipo: F.tipos[p.tipo] ?? p.tipo,
          quantidade: p.quantidade,
          valor: <ValorDoAcerto valor={p.valor} />,
          situacao: F.situacoes[p.status] ?? p.status,
          acoes:
            p.status === 'ABERTA' ? (
              <Botao
                aria-label={T.resolverDe(nomeDe(p))}
                onClick={() => {
                  setAviso(null);
                  setResolvendo(p);
                }}
              >
                {T.resolver}
              </Botao>
            ) : (
              ''
            ),
        }))}
      />
      {resolvendo ? (
        <ResolverPendencia
          key={resolvendo.id}
          ponte={ponte}
          pendenciaId={resolvendo.id}
          ehFalta={resolvendo.tipo === 'FALTA'}
          aoCancelar={() => setResolvendo(null)}
          aoFeito={() => {
            setResolvendo(null);
            setAviso(T.feito);
            setVolta((v) => v + 1);
          }}
        />
      ) : null}
    </div>
  );
}
