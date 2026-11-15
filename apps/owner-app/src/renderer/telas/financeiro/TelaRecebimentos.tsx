import { Aviso, Botao, Campo, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { RodapeDaCotacao } from '../../nucleo/cotacao';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { CampoDeEscolha, useVendedores } from '../devolucoes/Apoio';
import { dataEHora } from '../sincronizacao/dados';
import { EstornarComMotivo } from './EstornarComMotivo';
import { RegistrarRecebimento } from './RegistrarRecebimento';
import { ValorDoDia } from './Valor';
import {
  lerRecebimentos,
  recebimentoPodeSerEstornado,
  type RecebimentoDoVendedor,
} from './recebimentos';

const T = TEXTOS_DE_FINANCEIRO.recebimentos;
const FORMAS = TEXTOS_DE_FINANCEIRO.formas;

/** A janela de estorno: o novo vencimento só se pergunta quando o recebimento pagou um acerto. */
function EstornarRecebimento({
  ponte,
  recebimento,
  aoFeito,
  aoCancelar,
}: {
  ponte: PonteDoCorona;
  recebimento: RecebimentoDoVendedor;
  aoFeito: () => void;
  aoCancelar: () => void;
}) {
  const [vencimento, setVencimento] = useState('');
  const pagouAcerto = recebimento.aplicadoEm === 'ACERTO';
  return (
    <EstornarComMotivo
      ponte={ponte}
      operacao="recebimentos.estornar"
      alvo={{ id: recebimento.id }}
      textos={T.estornoDialogo}
      {...(pagouAcerto
        ? {
            extra: (
              <Campo
                type="date"
                rotulo={T.estornoDialogo.vencimento}
                ajuda={T.estornoDialogo.vencimentoAjuda}
                value={vencimento}
                onChange={(e) => setVencimento(e.target.value)}
              />
            ),
            parametrosExtras: (): Record<string, string> => (vencimento ? { vencimento } : {}),
          }
        : {})}
      aoFeito={aoFeito}
      aoCancelar={aoCancelar}
    />
  );
}

/** Financeiro → Recebimentos do vendedor: o que o vendedor pagou, o recebimento avulso e o estorno. */
export function TelaRecebimentos({ ponte }: { ponte: PonteDoCorona }) {
  const [vendedorId, setVendedorId] = useState('');
  const [volta, setVolta] = useState(0);
  const [registrando, setRegistrando] = useState(false);
  const [estornando, setEstornando] = useState<RecebimentoDoVendedor | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const vendedores = useVendedores(ponte);
  const busca = useBusca(
    () => chamar(ponte, 'recebimentos.listar', vendedorId ? { vendedorId } : {}),
    `recebimentos-${volta}-${vendedorId}`,
  );
  const recebimentos = busca.dados ? lerRecebimentos(busca.dados) : null;

  if (registrando) {
    return (
      <div className="gestao-tela">
        <div className="gestao-tela__topo">
          <h1 className="gestao-titulo">{T.titulo}</h1>
        </div>
        <RegistrarRecebimento
          ponte={ponte}
          aoCancelar={() => setRegistrando(false)}
          aoFeito={(jaRegistrado) => {
            setRegistrando(false);
            setAviso(jaRegistrado ? T.novo.jaFeito : T.novo.feito);
            setVolta((v) => v + 1);
          }}
        />
      </div>
    );
  }

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
        <Botao
          variante="primario"
          onClick={() => {
            setAviso(null);
            setRegistrando(true);
          }}
        >
          {T.registrar}
        </Botao>
      </div>
      <p className="gestao-texto">{T.ajuda}</p>
      {aviso ? <Aviso tipo="sucesso">{aviso}</Aviso> : null}
      <div className="gestao-filtros">
        <CampoDeEscolha
          id="filtro-recebimento-vendedor"
          rotulo={T.filtros.vendedor}
          valor={vendedorId}
          aoMudar={setVendedorId}
          opcoes={vendedores.opcoes}
          vazio={T.filtros.todosVendedores}
        />
      </div>
      {busca.erro ? <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} /> : null}
      {busca.dados && !recebimentos ? <Aviso tipo="erro">{T.naoLeu}</Aviso> : null}
      {recebimentos ? (
        <>
          <Tabela
            legenda={T.legenda}
            textoVazio={T.vazia}
            colunas={[
              { chave: 'data', titulo: T.colunas.data },
              { chave: 'vendedor', titulo: T.colunas.vendedor },
              { chave: 'forma', titulo: T.colunas.forma },
              { chave: 'valor', titulo: T.colunas.valor, numerica: true },
              { chave: 'de', titulo: T.colunas.de },
              { chave: 'situacao', titulo: T.colunas.situacao },
              { chave: 'acoes', titulo: T.colunas.acoes },
            ]}
            linhas={recebimentos.map((r) => ({
              data: dataEHora(r.recebidoEm),
              vendedor: r.vendedorNome,
              forma: FORMAS[r.forma] ?? r.forma,
              valor: <ValorDoDia valor={r.valor} />,
              de: T.momentos[r.momento] ?? r.momento,
              situacao: r.estornaId ? T.ehEstorno : r.estornado ? T.desfeito : T.valido,
              acoes: recebimentoPodeSerEstornado(r) ? (
                <Botao
                  aria-label={T.estornarDe(r.vendedorNome)}
                  onClick={() => {
                    setAviso(null);
                    setEstornando(r);
                  }}
                >
                  {T.estornar}
                </Botao>
              ) : (
                (r.motivo ?? '')
              ),
            }))}
          />
          <RodapeDaCotacao />
        </>
      ) : null}
      {estornando ? (
        <EstornarRecebimento
          key={estornando.id}
          ponte={ponte}
          recebimento={estornando}
          aoCancelar={() => setEstornando(null)}
          aoFeito={() => {
            setEstornando(null);
            setAviso(T.estornoDialogo.feito);
            setVolta((v) => v + 1);
          }}
        />
      ) : null}
    </div>
  );
}
