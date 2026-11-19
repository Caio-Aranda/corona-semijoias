import { Botao, EstadoDaLista, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { reais } from '../devolucoes/Apoio';
import { dataEHora } from '../sincronizacao/dados';
import { lerRepresentantes, lerSemanas } from './dados';

const T = TEXTOS_DE_CONSIGNADO.semanas;

/** Consignado → Semanas dos representantes: as semanas de cada representante, com os totais (só leitura). */
export function TelaSemanasDosRepresentantes({
  ponte,
  aoVerNotas,
}: {
  ponte: PonteDoCorona;
  aoVerNotas: (vendedorId: string, semanaId: string) => void;
}) {
  const [vendedorId, setVendedorId] = useState('');
  const representantes = useBusca(
    () => chamar(ponte, 'vendedores.escolha'),
    'consignado-representantes',
  );
  const semanas = useBusca(
    () =>
      vendedorId
        ? chamar(ponte, 'consignado.semanas', { id: vendedorId })
        : Promise.resolve({ ok: true as const, dados: [] }),
    `consignado-semanas-${vendedorId}`,
  );
  const nomes = lerRepresentantes(representantes.dados);
  const lidas = semanas.dados ? lerSemanas(semanas.dados) : [];

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-filtros">
        <div className="cs-campo">
          <label htmlFor="semanas-representante">{T.representante}</label>
          <select
            id="semanas-representante"
            value={vendedorId}
            onChange={(e) => setVendedorId(e.target.value)}
          >
            <option value="">{T.escolha}</option>
            {nomes.map((v) => (
              <option key={v.id} value={v.id}>
                {v.nome}
              </option>
            ))}
          </select>
        </div>
      </div>
      {vendedorId ? (
        <EstadoDaLista
          carregando={semanas.carregando && !semanas.dados}
          erro={semanas.erro?.mensagem ?? null}
          textoCarregando={T.carregando}
          aoTentarDeNovo={semanas.recarregar}
        >
          <Tabela
            legenda={T.legenda}
            colunas={[
              { chave: 'semana', titulo: T.colunas.semana, numerica: true },
              { chave: 'situacao', titulo: T.colunas.situacao },
              { chave: 'notas', titulo: T.colunas.notas, numerica: true },
              { chave: 'dx', titulo: T.colunas.dx, numerica: true },
              { chave: 'vd', titulo: T.colunas.vd, numerica: true },
              { chave: 'comissao', titulo: T.colunas.comissao, numerica: true },
              { chave: 'recebido', titulo: T.colunas.recebido, numerica: true },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={lidas.map((s) => ({
              semana: s.numero,
              situacao: `${T.situacoes[s.situacao] ?? s.situacao}${
                s.abertaEm ? `. ${T.abertaEm(dataEHora(s.abertaEm).split(' ')[0] ?? '')}` : ''
              }`,
              notas: s.notas,
              dx: reais(s.valorDx),
              vd: reais(s.valorVd),
              comissao: reais(s.comissao),
              recebido: reais(s.recebido),
              acoes: (
                <Botao
                  aria-label={T.verNotasDa(s.numero)}
                  onClick={() => aoVerNotas(vendedorId, s.id)}
                >
                  {T.verNotas}
                </Botao>
              ),
            }))}
            textoVazio={T.vazio}
          />
        </EstadoDaLista>
      ) : null}
    </div>
  );
}
